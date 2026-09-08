import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from brain.auth import TokenStore
from brain.connectors import llm
from brain.main import create_app
from brain.settings import SettingsStore


class RobotApiTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.settings = SettingsStore(root)
        self.tokens = TokenStore(root)
        self.client = TestClient(create_app(root=root, preload_stt=False), raise_server_exceptions=False)
        self.headers = {"Authorization": "Bearer " + self.tokens.pairing_token}

    def tearDown(self):
        self._tmp.cleanup()

    def _configure_anthropic(self):
        self.settings.update_section("llm", {
            "active": "anthropic",
            "model": "claude-haiku-4-5",
            "credentials": {"anthropic": {"api_key": "sk-ant-x"}},
        })

    def test_health_is_public(self):
        self.assertEqual(self.client.get("/api/health").status_code, 200)

    def test_robot_endpoints_reject_a_missing_or_wrong_token(self):
        self.assertEqual(self.client.get("/api/robot/hello").status_code, 401)
        self.assertEqual(
            self.client.get("/api/robot/hello", headers={"Authorization": "Bearer nope"}).status_code,
            401,
        )

    def test_robot_endpoints_reject_the_admin_token(self):
        # Le jeton d'appairage vit sur une tablette accessible au public : les deux
        # rôles restent disjoints.
        response = self.client.get(
            "/api/robot/hello", headers={"Authorization": "Bearer " + self.tokens.admin_token})
        self.assertEqual(response.status_code, 401)

    def test_greeting_is_silent_until_hospitality_is_switched_on(self):
        body = self.client.get("/api/robot/greeting", headers=self.headers).json()
        self.assertFalse(body["active"])
        self.assertEqual(body["speech"], "")

    def test_greeting_returns_the_line_composed_when_saving(self):
        self.settings.update_section("hospitality", {
            "active": True, "greeting": "Bienvenue chez Recepta ! La cuisine est à droite."})
        body = self.client.get("/api/robot/greeting", headers=self.headers).json()
        self.assertTrue(body["active"])
        self.assertEqual(body["speech"], "Bienvenue chez Recepta ! La cuisine est à droite.")

    def test_greeting_stays_a_robot_route(self):
        self.assertEqual(self.client.get("/api/robot/greeting").status_code, 401)

    def test_greeting_reports_hospitality_on_even_without_a_composed_line(self):
        # Réglages écrits à la main, ou accroche vidée : le mode reste actif et la
        # tablette retombe sur son accueil habituel plutôt que de rester muette.
        self.settings.update_section("hospitality", {"active": True, "greeting": "   "})
        body = self.client.get("/api/robot/greeting", headers=self.headers).json()
        self.assertTrue(body["active"])
        self.assertEqual(body["speech"], "")

    def test_hello_reports_the_active_connectors(self):
        self._configure_anthropic()
        response = self.client.get("/api/robot/hello", headers=self.headers)
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["llm"]["active"], "anthropic")
        self.assertEqual(body["llm"]["model"], "claude-haiku-4-5")
        self.assertEqual(body["stt"]["active"], "whisper-local")
        self.assertEqual(body["media_count"], 0)

    def test_hello_never_leaks_a_secret(self):
        self._configure_anthropic()
        body = self.client.get("/api/robot/hello", headers=self.headers).text
        self.assertNotIn("sk-ant-x", body)

    def test_chat_returns_a_clear_error_when_no_llm_is_configured(self):
        response = self.client.post(
            "/api/robot/chat",
            headers=self.headers,
            json={"messages": [{"role": "user", "text": "Bonjour"}]},
        )
        self.assertEqual(response.status_code, 503)
        self.assertIn("configuré", response.json()["detail"])

    def test_chat_calls_the_active_connector_and_returns_its_text(self):
        self._configure_anthropic()
        seen: dict = {}

        def fake_complete(credentials, model, system, messages, **kwargs):
            seen["system"] = system
            seen["messages"] = messages
            return "Bonjour, je suis Pepper."

        connector = llm.get("anthropic")
        original = connector.complete
        connector.complete = fake_complete
        try:
            response = self.client.post(
                "/api/robot/chat",
                headers=self.headers,
                json={"messages": [{"role": "user", "text": "Bonjour"}]},
            )
        finally:
            connector.complete = original

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["response"], "Bonjour, je suis Pepper.")
        self.assertIn("Tu es Pepper", seen["system"])
        self.assertEqual(seen["messages"][0].text, "Bonjour")

    def test_chat_maps_connector_errors_to_status_codes(self):
        self._configure_anthropic()
        connector = llm.get("anthropic")
        original = connector.complete
        cases = [
            (llm.LlmTimeout("le fournisseur n'a pas répondu à temps"), 504),
            (llm.LlmAuthError("clé API Anthropic refusée"), 502),
            (llm.LlmNotConfigured("clé API Anthropic absente ou vide"), 503),
        ]
        try:
            for error, expected in cases:
                def fake(credentials, model, system, messages, _e=error, **kwargs):
                    raise _e
                connector.complete = fake
                response = self.client.post(
                    "/api/robot/chat",
                    headers=self.headers,
                    json={"messages": [{"role": "user", "text": "Bonjour"}]},
                )
                self.assertEqual(response.status_code, expected, error)
                self.assertEqual(response.json()["detail"], str(error))
        finally:
            connector.complete = original

    def test_chat_never_relays_an_unexpected_exception(self):
        # La clé API vit dans e.request.headers et httpx ne masque pas x-api-key :
        # une exception inattendue ne doit jamais atteindre la tablette.
        self._configure_anthropic()
        connector = llm.get("anthropic")
        original = connector.complete

        def exploding(credentials, model, system, messages, **kwargs):
            raise RuntimeError("trace interne avec sk-ant-SECRET dedans")

        connector.complete = exploding
        try:
            response = self.client.post(
                "/api/robot/chat",
                headers=self.headers,
                json={"messages": [{"role": "user", "text": "Bonjour"}]},
            )
        finally:
            connector.complete = original

        self.assertEqual(response.status_code, 502)
        self.assertNotIn("sk-ant-SECRET", response.text)

    def test_chat_rejects_an_invalid_history(self):
        self._configure_anthropic()
        for payload in ({"messages": []},
                        {"messages": [{"role": "system", "text": "x"}]},
                        {"messages": [{"role": "user", "text": "   "}]}):
            response = self.client.post("/api/robot/chat", headers=self.headers, json=payload)
            self.assertEqual(response.status_code, 422, payload)

    def test_transcribe_rejects_an_empty_body(self):
        response = self.client.post("/api/robot/transcribe", headers=self.headers, content=b"")
        self.assertEqual(response.status_code, 413)

    def test_transcribe_rejects_audio_too_short_with_422(self):
        response = self.client.post(
            "/api/robot/transcribe", headers=self.headers, content=b"\x00\x01" * 10)
        self.assertEqual(response.status_code, 422)

    def test_image_prefers_the_client_library_over_the_web(self):
        # Une image que le client a lui-même déposée doit l'emporter sur une recherche.
        from brain.media.library import MediaLibrary

        MediaLibrary(Path(self._tmp.name)).add_bytes(
            "Plan du site", "image/png", b"\x89PNG\r\n\x1a\n" + b"\x00" * 64)
        response = self.client.get("/api/robot/image?q=plan du site", headers=self.headers)
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["source"], "library")
        self.assertEqual(body["title"], "Plan du site")
        self.assertTrue(body["url"].startswith("http"))

    def _add_idle_image(self, name="Hall d'accueil"):
        from brain.media.library import MediaLibrary

        return MediaLibrary(Path(self._tmp.name)).add_bytes(
            name, "image/png", b"\x89PNG\r\n\x1a\n" + b"\x00" * 64)

    def test_idle_image_is_served_when_hospitality_is_on(self):
        item = self._add_idle_image()
        self.settings.update_section("hospitality", {"active": True, "idle_media": item["id"]})
        body = self.client.get("/api/robot/idle-image", headers=self.headers).json()
        self.assertEqual(body["id"], item["id"])
        self.assertEqual(body["name"], "Hall d'accueil")
        self.assertTrue(body["url"].endswith(item["url"]))
        self.assertTrue(body["url"].startswith("http"))

    def test_idle_image_disappears_when_hospitality_is_switched_off(self):
        # Couper l'hospitalité depuis la webapp doit rendre la tablette à son
        # interface sans qu'on touche au robot : l'URL vide dit exactement cela.
        item = self._add_idle_image()
        self.settings.update_section("hospitality", {"active": True, "idle_media": item["id"]})
        self.settings.update_section("hospitality", {"active": False})
        self.assertEqual(
            self.client.get("/api/robot/idle-image", headers=self.headers).json()["url"], "")

    def test_idle_image_survives_a_media_deleted_behind_its_back(self):
        from brain.media.library import MediaLibrary

        item = self._add_idle_image()
        self.settings.update_section("hospitality", {"active": True, "idle_media": item["id"]})
        MediaLibrary(Path(self._tmp.name)).delete(item["id"])
        self.assertEqual(
            self.client.get("/api/robot/idle-image", headers=self.headers).json()["url"], "")

    def test_idle_image_refuses_a_video(self):
        # Une boucle vidéo tiendrait le processeur de la tablette éveillé des heures
        # devant un hall vide ; seule une image tient ce rôle.
        self.settings.update_section(
            "hospitality", {"active": True, "idle_media": "inexistant"})
        self.assertEqual(
            self.client.get("/api/robot/idle-image", headers=self.headers).json()["url"], "")
        from brain import hospitality

        video = [{"id": "v1", "kind": "video", "name": "Clip"}]
        self.assertIsNone(hospitality.idle_image(video, "v1"))

    def test_idle_image_requires_the_pairing_token(self):
        self.assertEqual(self.client.get("/api/robot/idle-image").status_code, 401)

    def test_image_rejects_an_empty_query(self):
        response = self.client.get("/api/robot/image?q=%20%20", headers=self.headers)
        self.assertEqual(response.status_code, 422)

    def _fake_web_image(self, payload=b"\xff\xd8\xff" + b"\x00" * 64,
                        mime="image/jpeg", status=200):
        """Remplace la recherche et le téléchargement, sans toucher au réseau."""
        from brain.media import imagesearch

        provider = imagesearch.get("wikimedia")
        original_search = provider.candidates
        provider.candidates = lambda credentials, query, http=None: [imagesearch.FoundImage(
            url="https://upload.test/tour.jpg", title="Tour Eiffel", source="wikimedia",
            referer="https://commons.wikimedia.org/")]

        cache = self.client.app.state.brain.images
        original_fetch = cache.fetch

        class Response:
            status_code = status
            headers = {"content-type": mime}
            content = payload

        class Http:
            def get(self, url, **kwargs):
                Http.seen = kwargs
                return Response()

        http = Http()
        cache.fetch = lambda url, referer="", _h=http: original_fetch(
            url, referer=referer, http=_h)
        return provider, original_search, cache, original_fetch, Http

    def test_image_is_downloaded_by_the_brain_and_served_from_it(self):
        # La tablette tourne sous Android 6 : elle échoue sur les sites qui exigent
        # un User-Agent. C'est le cerveau qui télécharge, elle ne voit que lui.
        provider, orig_search, cache, orig_fetch, Http = self._fake_web_image()
        try:
            body = self.client.get("/api/robot/image?q=tour eiffel",
                                   headers=self.headers).json()
        finally:
            provider.candidates = orig_search
            cache.fetch = orig_fetch

        self.assertEqual(body["source"], "wikimedia")
        self.assertNotIn("upload.test", body["url"])
        self.assertIn("/api/robot/image/", body["url"])
        self.assertIn("PepperBrain", Http.seen["headers"]["User-Agent"])
        # Sans référent, Wikimedia traite la requête comme un lien pillé et répond 429.
        self.assertIn("wikimedia", Http.seen["headers"]["Referer"])

        served = self.client.get("/api/robot/image/" + body["url"].rsplit("/", 1)[-1],
                                 headers=self.headers)
        self.assertEqual(served.status_code, 200)
        self.assertEqual(served.headers["content-type"], "image/jpeg")

    def test_image_that_is_not_an_image_is_refused(self):
        # On n'affiche pas un fichier arbitraire sur la tablette d'un hall d'accueil.
        provider, orig_search, cache, orig_fetch, _ = self._fake_web_image(
            payload=b"<html>", mime="text/html")
        try:
            response = self.client.get("/api/robot/image?q=x", headers=self.headers)
        finally:
            provider.candidates = orig_search
            cache.fetch = orig_fetch
        self.assertEqual(response.status_code, 502)
        self.assertIn("illisible", response.json()["detail"])

    def test_unknown_cached_image_returns_404(self):
        response = self.client.get("/api/robot/image/inexistant", headers=self.headers)
        self.assertEqual(response.status_code, 404)

    def test_image_search_errors_stay_readable(self):
        from brain.media import imagesearch

        provider = imagesearch.get("wikimedia")
        original = provider.candidates

        def failing(credentials, query, http=None):
            raise imagesearch.ImageSearchUnavailable("Wikimedia Commons a répondu 503")

        provider.candidates = failing
        try:
            response = self.client.get("/api/robot/image?q=x", headers=self.headers)
        finally:
            provider.candidates = original
        self.assertEqual(response.status_code, 503)
        self.assertIn("Wikimedia", response.json()["detail"])

    def test_media_resolve_returns_404_for_an_unknown_name(self):
        response = self.client.get("/api/robot/media/resolve?q=inexistant", headers=self.headers)
        self.assertEqual(response.status_code, 404)


class ImageCandidateFallbackTest(unittest.TestCase):
    """Un téléchargement qui échoue ne doit pas laisser le robot sans rien à montrer
    alors qu'une autre image de la liste ferait l'affaire."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tokens = TokenStore(Path(self._tmp.name))
        self.client = TestClient(create_app(root=Path(self._tmp.name), preload_stt=False),
                                 raise_server_exceptions=False)
        self.headers = {"Authorization": "Bearer " + self.tokens.pairing_token}

    def tearDown(self):
        self._tmp.cleanup()

    def test_moves_on_to_the_next_candidate_when_one_fails(self):
        from brain.media import imagesearch

        provider = imagesearch.get("wikimedia")
        original = provider.candidates
        provider.candidates = lambda c, q, http=None: [
            imagesearch.FoundImage("https://exemple.test/bridee.jpg", "Bridée", "wikimedia"),
            imagesearch.FoundImage("https://exemple.test/bonne.jpg", "Bonne", "wikimedia"),
        ]
        cache = self.client.app.state.brain.images
        original_fetch = cache.fetch

        def fetch(url, referer="", http=None):
            if "bridee" in url:
                raise ValueError("le fournisseur d'images nous bride (429)")
            return "abc123", "image/jpeg"

        cache.fetch = fetch
        try:
            body = self.client.get("/api/robot/image?q=x", headers=self.headers).json()
        finally:
            provider.candidates = original
            cache.fetch = original_fetch
        self.assertEqual(body["title"], "Bonne")

    def test_reports_the_last_cause_when_every_candidate_fails(self):
        from brain.media import imagesearch

        provider = imagesearch.get("wikimedia")
        original = provider.candidates
        provider.candidates = lambda c, q, http=None: [
            imagesearch.FoundImage("https://exemple.test/%d.jpg" % i, str(i), "wikimedia")
            for i in range(3)
        ]
        cache = self.client.app.state.brain.images
        original_fetch = cache.fetch

        def fetch(url, referer="", http=None):
            raise ValueError("le fournisseur d'images nous bride (429)")

        cache.fetch = fetch
        try:
            response = self.client.get("/api/robot/image?q=x", headers=self.headers)
        finally:
            provider.candidates = original
            cache.fetch = original_fetch
        self.assertEqual(response.status_code, 502)
        self.assertIn("429", response.json()["detail"])
