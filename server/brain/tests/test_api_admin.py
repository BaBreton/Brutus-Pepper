import json
import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from brain.auth import TokenStore
from brain.main import create_app


class _ImageHttpForAdminTest:
    class Response:
        status_code = 200
        content = b"\xff\xd8\xff" + b"\x00" * 64
        headers = {"content-type": "image/jpeg"}

    def __init__(self):
        self.calls = []

    def get(self, url, **kwargs):
        self.calls.append({"url": url, **kwargs})
        return self.Response()


class AdminApiTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.tokens = TokenStore(root)
        self.client = TestClient(create_app(root=root, preload_stt=False), raise_server_exceptions=False)
        self.headers = {"Authorization": "Bearer " + self.tokens.admin_token}
        self.robot_headers = {"Authorization": "Bearer " + self.tokens.pairing_token}

    def tearDown(self):
        self._tmp.cleanup()

    def test_admin_endpoints_reject_the_robot_token(self):
        response = self.client.get("/api/admin/connectors", headers=self.robot_headers)
        self.assertEqual(response.status_code, 401)

    def test_connectors_never_return_a_secret(self):
        self.client.put(
            "/api/admin/connectors",
            headers=self.headers,
            json={"llm": {"active": "anthropic", "model": "claude-haiku-4-5",
                          "credentials": {"anthropic": {"api_key": "sk-ant-supersecret"}}}},
        )
        response = self.client.get("/api/admin/connectors", headers=self.headers)
        self.assertEqual(response.status_code, 200)
        self.assertNotIn("sk-ant-supersecret", json.dumps(response.json()))
        # Forme du masquage : chaque champ secret devient {configured, masked}.
        api_key = response.json()["settings"]["llm"]["credentials"]["anthropic"]["api_key"]
        self.assertTrue(api_key["configured"])
        self.assertEqual(api_key["masked"], "…cret")

    def test_connectors_list_what_is_available(self):
        body = self.client.get("/api/admin/connectors", headers=self.headers).json()
        self.assertEqual(
            sorted(entry["id"] for entry in body["llm_available"]),
            ["anthropic", "bedrock", "openai"],
        )
        self.assertEqual(
            sorted(entry["id"] for entry in body["stt_available"]),
            ["aws", "openai", "whisper-local"],
        )
        # Whisper embarqué est le seul utilisable sans clé : c'est le défaut, et la
        # seule option qui ne fait pas sortir l'audio du réseau du client.
        by_id = {entry["id"]: entry for entry in body["stt_available"]}
        self.assertTrue(by_id["whisper-local"]["configured"])
        self.assertFalse(by_id["openai"]["configured"])
        self.assertFalse(by_id["aws"]["configured"])

    def test_catalog_exposes_each_provider_fields_and_pricing_for_the_webapp(self):
        body = self.client.get("/api/admin/connectors", headers=self.headers).json()

        llm = {entry["id"]: entry for entry in body["llm_available"]}
        self.assertEqual([field["id"] for field in llm["bedrock"]["credential_fields"]],
                         ["access_key", "secret_key", "region"])
        self.assertEqual(llm["openai"]["models"][0]["pricing"]["input_usd_per_million"],
                         0.4)
        self.assertEqual(llm["anthropic"]["models"][0]["pricing"]["output_usd_per_million"],
                         5.0)

        stt = {entry["id"]: entry for entry in body["stt_available"]}
        self.assertEqual([field["id"] for field in stt["aws"]["credential_fields"]],
                         ["access_key", "secret_key", "region"])
        self.assertEqual(stt["openai"]["models"][0]["pricing"]["usd_per_minute"],
                         0.017)
        self.assertEqual(stt["whisper-local"]["models"][0]["pricing"]["unit"], "local")

        images = {entry["id"]: entry for entry in body["image_search_available"]}
        self.assertEqual(images["brave"]["credential_fields"][0]["id"], "api_key")
        self.assertEqual(images["wikimedia"]["pricing"]["unit"], "free")
        self.assertEqual(body["web_search_available"][0]["pricing"]["unit"], "request")

    def test_wikimedia_contact_is_configurable_without_being_a_secret(self):
        response = self.client.put(
            "/api/admin/connectors",
            headers=self.headers,
            json={"image_search": {"provider": "wikimedia", "contact": "hote@example.fr"}},
        )
        self.assertEqual(response.status_code, 200, response.text)
        public = response.json()["settings"]["image_search"]
        self.assertEqual(public["contact"], "hote@example.fr")

        http = _ImageHttpForAdminTest()
        self.client.app.state.brain.images.fetch(
            "https://exemple.test/contact.jpg", http=http)
        self.assertIn("hote@example.fr", http.calls[0]["headers"]["User-Agent"])

    def test_a_blank_secret_preserves_the_stored_one(self):
        put = lambda payload: self.client.put(
            "/api/admin/connectors", headers=self.headers, json=payload)
        put({"llm": {"credentials": {"anthropic": {"api_key": "sk-ant-first"}}}})
        put({"llm": {"model": "claude-sonnet-5",
                     "credentials": {"anthropic": {"api_key": ""}}}})
        body = self.client.get("/api/admin/connectors", headers=self.headers).json()
        self.assertEqual(body["settings"]["llm"]["model"], "claude-sonnet-5")
        self.assertTrue(
            body["settings"]["llm"]["credentials"]["anthropic"]["api_key"]["configured"])

    def test_pairing_can_be_read_and_rotated(self):
        first = self.client.get("/api/admin/pairing", headers=self.headers).json()["pairing_token"]
        self.assertEqual(first, self.tokens.pairing_token)
        second = self.client.post("/api/admin/pairing", headers=self.headers).json()["pairing_token"]
        self.assertNotEqual(first, second)
        stale = self.client.get("/api/robot/hello",
                                headers={"Authorization": "Bearer " + first})
        self.assertEqual(stale.status_code, 401)
        fresh = self.client.get("/api/robot/hello",
                                headers={"Authorization": "Bearer " + second})
        self.assertEqual(fresh.status_code, 200)

    def test_hospitality_round_trips(self):
        self.client.put(
            "/api/admin/hospitality",
            headers=self.headers,
            json={"company": "Recepta", "visitors": ["Jean Dupont"], "notes": "Réunion 15h"},
        )
        body = self.client.get("/api/admin/hospitality", headers=self.headers).json()
        self.assertEqual(body["company"], "Recepta")
        self.assertEqual(body["visitors"], ["Jean Dupont"])

    def test_media_upload_list_and_delete(self):
        png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
        created = self.client.put(
            "/api/admin/media?name=Accueil%20%C3%89t%C3%A9",
            headers={**self.headers, "Content-Type": "image/png"},
            content=png,
        )
        self.assertEqual(created.status_code, 201, created.text)
        media_id = created.json()["id"]

        listed = self.client.get("/api/admin/media", headers=self.headers).json()
        self.assertEqual([item["name"] for item in listed["items"]], ["Accueil Été"])

        deleted = self.client.delete("/api/admin/media/%s" % media_id, headers=self.headers)
        self.assertEqual(deleted.status_code, 200)
        self.assertEqual(self.client.get("/api/admin/media", headers=self.headers).json()["items"], [])

    def test_media_preview_is_served_to_the_admin_only(self):
        png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
        media_id = self.client.put(
            "/api/admin/media?name=Logo",
            headers={**self.headers, "Content-Type": "image/png"},
            content=png,
        ).json()["id"]
        served = self.client.get("/api/admin/media/%s/content" % media_id, headers=self.headers)
        self.assertEqual(served.status_code, 200)
        self.assertEqual(served.content, png)
        self.assertEqual(served.headers["content-type"], "image/png")
        refused = self.client.get("/api/admin/media/%s/content" % media_id,
                                  headers=self.robot_headers)
        self.assertEqual(refused.status_code, 401)

    def test_webapp_is_served_and_holds_no_secret(self):
        page = self.client.get("/")
        self.assertEqual(page.status_code, 200)
        self.assertIn("Pepper — Espace hôte", page.text)
        # Le jeton est saisi dans le navigateur : le serveur ne doit jamais l'inclure.
        self.assertNotIn(self.tokens.admin_token, page.text)
        self.assertNotIn(self.tokens.pairing_token, page.text)
        for asset in ("admin.js", "admin.css"):
            response = self.client.get("/static/" + asset)
            self.assertEqual(response.status_code, 200)
            self.assertNotIn(self.tokens.admin_token, response.text)
            self.assertNotIn(self.tokens.pairing_token, response.text)
        self.assertEqual(self.client.get("/static/../settings.py").status_code, 404)

    def test_media_upload_rejects_a_mismatched_content(self):
        response = self.client.put(
            "/api/admin/media?name=Faux",
            headers={**self.headers, "Content-Type": "image/png"},
            content=b"pas un png du tout",
        )
        self.assertEqual(response.status_code, 400)

    def test_media_routes_reject_the_robot_token(self):
        self.assertEqual(
            self.client.get("/api/admin/media", headers=self.robot_headers).status_code, 401)

    def test_uploaded_media_appears_in_the_system_prompt(self):
        # Un média déposé doit devenir nommable par la voix : c'est la boucle complète.
        png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
        self.client.put(
            "/api/admin/media?name=Plan%20du%20site",
            headers={**self.headers, "Content-Type": "image/png"},
            content=png,
        )
        from brain import prompt
        from brain.media.library import MediaLibrary

        built = prompt.build_system_prompt(
            catalog=MediaLibrary(Path(self._tmp.name)).list(), hospitality={})
        self.assertIn("Plan du site", built)

    def test_hospitality_reaches_the_system_prompt(self):
        # C'est tout l'intérêt du mode hospitalité : ce que l'opérateur saisit doit
        # arriver au modèle.
        self.client.put(
            "/api/admin/hospitality",
            headers=self.headers,
            json={"active": True, "company": "Recepta", "visitors": ["Jean Dupont"],
                  "notes": "", "mission": "envoie-les vers la cuisine",
                  "places": [{"name": "cuisine", "directions": "au fond à votre droite"}]},
        )
        built = self._built_prompt()
        self.assertIn("Recepta", built)
        self.assertIn("Jean Dupont", built)
        self.assertIn("envoie-les vers la cuisine", built)
        self.assertIn("au fond à votre droite", built)

    def _built_prompt(self) -> str:
        from brain import prompt
        from brain.settings import SettingsStore

        return prompt.build_system_prompt(
            catalog=[],
            hospitality=SettingsStore(Path(self._tmp.name)).load()["hospitality"],
        )

    def _put_hospitality(self, **fields) -> dict:
        body = {"active": True, "company": "Recepta",
                "mission": "accueille les clients et envoie-les vers la cuisine",
                "places": [{"name": "cuisine", "directions": "au fond à votre droite"}]}
        body.update(fields)
        response = self.client.put("/api/admin/hospitality", headers=self.headers, json=body)
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def test_switching_hospitality_off_keeps_what_was_typed(self):
        # L'opérateur coupe l'hospitalité entre deux évènements ; il ne doit pas avoir
        # à ressaisir ses lieux le lendemain.
        self._put_hospitality()
        off = self._put_hospitality(active=False)
        self.assertEqual(off["places"][0]["name"], "cuisine")
        self.assertNotIn("cuisine", self._built_prompt())
        # Et le lieu revient tel quel à la réactivation.
        self._put_hospitality(active=True)
        self.assertIn("au fond à votre droite", self._built_prompt())

    def test_the_greeting_is_composed_when_saving_not_when_a_visitor_arrives(self):
        # Sans LLM configuré dans ce test, c'est le repli qui s'applique — l'essentiel
        # est qu'une phrase existe et cite le lieu.
        body = self._put_hospitality()
        self.assertIn("Bienvenue", body["greeting"])
        self.assertIn("au fond à votre droite", body["greeting"])

    def test_the_greeting_is_dropped_when_hospitality_is_off(self):
        self._put_hospitality()
        self.assertEqual(self._put_hospitality(active=False)["greeting"], "")

    def test_the_greeting_follows_a_change_of_place(self):
        # Une accroche périmée enverrait les visiteurs à l'ancien endroit.
        self._put_hospitality()
        updated = self._put_hospitality(
            places=[{"name": "salle Ariane", "directions": "au premier étage"}])
        self.assertIn("au premier étage", updated["greeting"])
        self.assertNotIn("au fond à votre droite", updated["greeting"])

    def test_a_place_without_directions_is_refused_politely(self):
        # Pas une erreur : la webapp laisse des lignes en cours de saisie.
        body = self._put_hospitality(places=[{"name": "cuisine", "directions": ""}])
        # « cuisine » reste dans la mission, qui est du texte libre ; c'est la liste des
        # lieux qui ne doit pas apparaître, faute d'orientation à donner.
        self.assertNotIn("Lieux que tu peux indiquer", self._built_prompt())
        self.assertNotIn("cuisine", body["greeting"])
