import unittest

from brain.connectors import websearch


class FakeResponse:
    def __init__(self, payload, status=200):
        self._payload = payload
        self.status_code = status

    def json(self):
        return self._payload


class FakeHttp:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def get(self, url, **kwargs):
        self.calls.append({"url": url, **kwargs})
        return self.response


def page(*results):
    return {"web": {"results": list(results)}}


class BraveWebSearchTest(unittest.TestCase):
    def setUp(self):
        self.provider = websearch.get("brave")

    def test_sends_the_key_as_a_header_never_in_the_url(self):
        http = FakeHttp(FakeResponse(page(
            {"title": "Musée du Louvre", "description": "Ouvert de 9 h à 18 h.",
             "url": "https://louvre.fr"})))
        found = self.provider.search({"api_key": "cle-brave"}, "horaires louvre", http=http)
        self.assertEqual(found[0].title, "Musée du Louvre")
        self.assertEqual(found[0].snippet, "Ouvert de 9 h à 18 h.")
        call = http.calls[0]
        self.assertEqual(call["headers"]["X-Subscription-Token"], "cle-brave")
        # Une clé dans l'URL finirait dans les journaux du serveur et du proxy.
        self.assertNotIn("cle-brave", call["url"])

    def test_missing_key_is_reported_before_any_request(self):
        http = FakeHttp(FakeResponse({}))
        with self.assertRaises(websearch.WebSearchNotConfigured):
            self.provider.search({}, "quoi que ce soit", http=http)
        self.assertEqual(http.calls, [])

    def test_a_refused_key_says_so_plainly(self):
        http = FakeHttp(FakeResponse({}, status=401))
        with self.assertRaises(websearch.WebSearchAuthError):
            self.provider.search({"api_key": "mauvaise"}, "x", http=http)

    def test_an_outage_is_reported_as_such(self):
        http = FakeHttp(FakeResponse({}, status=503))
        with self.assertRaises(websearch.WebSearchUnavailable):
            self.provider.search({"api_key": "x"}, "x", http=http)

    def test_no_result_is_an_empty_list_not_an_error(self):
        # Ne rien trouver est une réponse, pas une panne : le modèle dira qu'il ne sait pas.
        self.assertEqual(self.provider.search({"api_key": "x"}, "x",
                                              http=FakeHttp(FakeResponse(page()))), [])

    def test_survives_a_malformed_payload(self):
        for payload in ({}, {"web": None}, {"web": {"results": None}}):
            self.assertEqual(
                self.provider.search({"api_key": "x"}, "x", http=FakeHttp(FakeResponse(payload))),
                [],
            )

    def test_drops_entries_without_a_usable_extract(self):
        http = FakeHttp(FakeResponse(page(
            {"title": "Sans description", "url": "https://a.test"},
            {"title": "", "description": "sans titre", "url": "https://b.test"},
            {"title": "Bonne", "description": "Un extrait.", "url": "https://c.test"})))
        found = self.provider.search({"api_key": "x"}, "x", http=http)
        self.assertEqual([r.title for r in found], ["Bonne"])

    def test_caps_the_number_of_results(self):
        many = [{"title": "T%d" % i, "description": "E%d" % i, "url": "https://x.test"}
                for i in range(20)]
        found = self.provider.search({"api_key": "x"}, "x", http=FakeHttp(FakeResponse(page(*many))))
        self.assertLessEqual(len(found), websearch.MAX_RESULTS)

    def test_caps_the_length_of_an_extract(self):
        http = FakeHttp(FakeResponse(page(
            {"title": "T", "description": "x" * 5_000, "url": "https://x.test"})))
        found = self.provider.search({"api_key": "x"}, "x", http=http)
        self.assertLessEqual(len(found[0].snippet), websearch.MAX_SNIPPET_CHARS)


class RenderTest(unittest.TestCase):
    def test_renders_titles_and_extracts(self):
        text = websearch.render([
            websearch.SearchResult("Louvre", "Ouvert de 9 h à 18 h.", "https://louvre.fr"),
            websearch.SearchResult("Orsay", "Fermé le lundi.", "https://orsay.fr"),
        ])
        self.assertIn("Louvre", text)
        self.assertIn("Fermé le lundi.", text)

    def test_carries_no_instruction_of_its_own(self):
        # Une page web ne doit pas pouvoir dicter au robot ce qu'il doit dire : on ne
        # met aucune consigne autour des résultats, elle reste dans le prompt système.
        text = websearch.render([websearch.SearchResult("T", "E", "https://x.test")])
        for word in ("réponds", "tu dois", "ignore", "consigne"):
            self.assertNotIn(word, text.lower())

    def test_empty_results_render_to_nothing(self):
        self.assertEqual(websearch.render([]), "")


class RegistryTest(unittest.TestCase):
    def test_get_raises_for_unknown_provider(self):
        with self.assertRaises(KeyError):
            websearch.get("nope")
