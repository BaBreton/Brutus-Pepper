import unittest

from brain.media import imagesearch


class FakeResponse:
    def __init__(self, payload, status=200):
        self._payload = payload
        self.status_code = status
        self.text = str(payload)

    def json(self):
        return self._payload


class FakeHttp:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def get(self, url, **kwargs):
        self.calls.append({"url": url, **kwargs})
        return self.response


class RegistryTest(unittest.TestCase):
    def test_wikimedia_needs_no_key_and_brave_does(self):
        by_id = {entry["id"]: entry for entry in imagesearch.catalog({})}
        self.assertTrue(by_id["wikimedia"]["configured"])
        self.assertFalse(by_id["brave"]["configured"])

    def test_catalog_survives_a_null_credentials_entry(self):
        by_id = {entry["id"]: entry for entry in imagesearch.catalog({"brave": None})}
        self.assertFalse(by_id["brave"]["configured"])

    def test_get_raises_for_unknown_provider(self):
        with self.assertRaises(KeyError):
            imagesearch.get("nope")


class BraveTest(unittest.TestCase):
    def setUp(self):
        self.provider = imagesearch.get("brave")

    def test_sends_the_key_as_a_header_never_in_the_url(self):
        http = FakeHttp(FakeResponse({"results": [
            {"properties": {"url": "https://exemple.test/tour.jpg"}, "title": "Tour Eiffel"}]}))
        found = self.provider.search({"api_key": "cle-brave"}, "tour eiffel", http=http)
        self.assertEqual(found.url, "https://exemple.test/tour.jpg")
        self.assertEqual(found.title, "Tour Eiffel")
        call = http.calls[0]
        self.assertEqual(call["headers"]["X-Subscription-Token"], "cle-brave")
        # Une clé dans l'URL finirait dans les journaux du serveur et du proxy.
        self.assertNotIn("cle-brave", call["url"])

    def test_missing_key_is_reported_before_any_request(self):
        http = FakeHttp(FakeResponse({}))
        with self.assertRaises(imagesearch.ImageSearchNotConfigured):
            self.provider.search({}, "tour eiffel", http=http)
        self.assertEqual(http.calls, [])

    def test_no_result_raises_a_lookup_error(self):
        http = FakeHttp(FakeResponse({"results": []}))
        with self.assertRaises(LookupError):
            self.provider.search({"api_key": "x"}, "objet introuvable", http=http)

    def test_a_refused_key_says_so_plainly(self):
        http = FakeHttp(FakeResponse({}, status=401))
        with self.assertRaises(imagesearch.ImageSearchAuthError):
            self.provider.search({"api_key": "mauvaise"}, "tour eiffel", http=http)


class WikimediaTest(unittest.TestCase):
    def setUp(self):
        self.provider = imagesearch.get("wikimedia")

    def test_returns_the_thumbnail_url(self):
        http = FakeHttp(FakeResponse({"query": {"pages": {
            "42": {"title": "File:Tour Eiffel.jpg",
                   "imageinfo": [{"url": "https://upload.test/tour.jpg",
                                  "thumburl": "https://upload.test/thumb/800px-tour.jpg"}]}}}}))
        found = self.provider.search({}, "tour eiffel", http=http)
        self.assertEqual(found.url, "https://upload.test/thumb/800px-tour.jpg")
        self.assertEqual(found.title, "Tour Eiffel.jpg")

    def test_never_falls_back_to_the_original_file(self):
        # Wikimedia bride le lien direct vers les originaux par un 429, et ces
        # fichiers pèsent souvent plusieurs mégaoctets : inaffichables sur la tablette.
        http = FakeHttp(FakeResponse({"query": {"pages": {
            "1": {"title": "File:Sans vignette.jpg",
                  "imageinfo": [{"url": "https://upload.test/original.jpg"}]}}}}))
        with self.assertRaises(LookupError):
            self.provider.search({}, "x", http=http)

    def test_rejects_a_thumburl_that_is_actually_the_original(self):
        # Quand l'original est moins large que la vignette demandée, Wikimedia renvoie
        # l'original lui-même dans thumburl. C'est ce cas qui faisait échouer
        # « tour Eiffel » alors que « Mont Blanc » passait.
        original = "https://upload.wikimedia.org/wikipedia/commons/d/d7/Petite.jpg"
        http = FakeHttp(FakeResponse({"query": {"pages": {
            "1": {"title": "File:Petite.jpg",
                  "imageinfo": [{"url": original, "thumburl": original}]}}}}))
        with self.assertRaises(LookupError):
            self.provider.search({}, "x", http=http)

    def test_asks_for_a_width_narrow_enough_to_force_a_real_thumbnail(self):
        http = FakeHttp(FakeResponse({"query": {"pages": {}}}))
        with self.assertRaises(LookupError):
            self.provider.search({}, "x", http=http)
        self.assertEqual(http.calls[0]["params"]["iiurlwidth"], 800)

    def test_uses_the_configured_contact_in_the_search_user_agent(self):
        http = FakeHttp(FakeResponse({"query": {"pages": {}}}))
        with self.assertRaises(LookupError):
            self.provider.search({}, "x", http=http, contact="hote@example.fr")
        self.assertIn("hote@example.fr", http.calls[0]["headers"]["User-Agent"])

    def test_a_page_without_thumbnail_is_skipped_for_the_next(self):
        http = FakeHttp(FakeResponse({"query": {"pages": {
            "1": {"title": "File:Sans info.jpg"},
            "2": {"title": "File:Bonne.jpg",
                  "imageinfo": [{"url": "https://upload.test/o.jpg",
                                 "thumburl": "https://upload.test/thumb/bonne.jpg"}]}}}}))
        self.assertEqual(self.provider.search({}, "x", http=http).url,
                         "https://upload.test/thumb/bonne.jpg")

    def test_empty_result_raises_a_lookup_error(self):
        http = FakeHttp(FakeResponse({"query": {"pages": {}}}))
        with self.assertRaises(LookupError):
            self.provider.search({}, "objet introuvable", http=http)


class ImageCacheTest(unittest.TestCase):
    """Le cerveau télécharge lui-même : la tablette Android 6 échoue sur les sites
    qui exigent un User-Agent, et ne doit parler qu'au cerveau."""

    JPEG = b"\xff\xd8\xff" + b"\x00" * 64

    def test_user_agent_is_safe_for_http_headers(self):
        from brain.media.imagecache import user_agent

        self.assertTrue(user_agent().isascii())
        self.assertEqual(user_agent("hôte@example.fr"),
                         "PepperBrain/1.0 (hote@example.fr)")

    def _cache(self):
        import tempfile
        from pathlib import Path

        from brain.media.imagecache import ImageCache

        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        return ImageCache(Path(self._tmp.name))

    class Http:
        def __init__(self, responses):
            self.responses = list(responses)
            self.calls = []

        def get(self, url, **kwargs):
            self.calls.append(kwargs)
            return self.responses.pop(0)

    class Resp:
        def __init__(self, status=200, mime="image/jpeg", content=b"", retry_after=None):
            self.status_code = status
            self.content = content
            self.headers = {"content-type": mime}
            if retry_after is not None:
                self.headers["retry-after"] = retry_after
            self.text = ""

    def test_downloads_once_and_serves_from_cache(self):
        cache = self._cache()
        http = self.Http([self.Resp(content=self.JPEG)])
        identifier, mime = cache.fetch("https://exemple.test/a.jpg", http=http)
        self.assertEqual(mime, "image/jpeg")
        self.assertEqual(cache.read(identifier)[0], self.JPEG)
        # Deuxième demande : aucun nouvel appel réseau. Sans cette garde, redemander
        # la même image faisait retélécharger et déclenchait le bridage.
        cache.fetch("https://exemple.test/a.jpg", http=http)
        self.assertEqual(len(http.calls), 1)

    def test_retries_once_when_throttled(self):
        cache = self._cache()
        http = self.Http([
            self.Resp(status=429, retry_after="0"),
            self.Resp(content=self.JPEG),
        ])
        identifier, _ = cache.fetch("https://exemple.test/b.jpg", http=http)
        self.assertEqual(len(http.calls), 2)
        self.assertEqual(cache.read(identifier)[0], self.JPEG)

    def test_gives_up_after_a_second_throttle_with_an_actionable_message(self):
        cache = self._cache()
        http = self.Http([self.Resp(status=429, retry_after="0")] * 2)
        with self.assertRaises(ValueError) as caught:
            cache.fetch("https://exemple.test/c.jpg", http=http)
        self.assertIn("Brave Search", str(caught.exception))

    def test_refuses_anything_that_is_not_an_image(self):
        cache = self._cache()
        http = self.Http([self.Resp(mime="text/html", content=b"<html>")])
        with self.assertRaises(ValueError):
            cache.fetch("https://exemple.test/d.html", http=http)

    def test_sends_an_identifiable_user_agent(self):
        cache = self._cache()
        http = self.Http([self.Resp(content=self.JPEG)])
        cache.fetch("https://exemple.test/e.jpg", http=http)
        self.assertIn("PepperBrain", http.calls[0]["headers"]["User-Agent"])
