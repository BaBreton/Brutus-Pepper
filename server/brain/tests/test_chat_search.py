import json
import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from brain.auth import TokenStore
from brain.connectors import llm, websearch
from brain.main import create_app
from brain.prompt import SEARCH_PREFIX
from brain.settings import SettingsStore


class FakeLlm:
    """Connecteur scripté : rend les réponses dans l'ordre, en notant ce qu'il a reçu."""

    id = "fake"
    label = "Fake"

    def __init__(self):
        self.replies: list[str] = []
        self.calls: list[dict] = []

    def models(self, credentials):
        return []

    def is_configured(self, credentials):
        return True

    def complete(self, credentials, model, system, messages):
        self.calls.append({"system": system, "messages": list(messages)})
        return self.replies.pop(0) if self.replies else ""


class FakeSearch:
    id = "brave"
    label = "Brave Search"

    def __init__(self, results=None, error=None):
        self.results = results or []
        self.error = error
        self.queries: list[str] = []

    def is_configured(self, credentials):
        return bool(credentials.get("api_key"))

    def search(self, credentials, query, http=None):
        self.queries.append(query)
        if self.error:
            raise self.error
        return self.results


class ChatSearchTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.settings = SettingsStore(root)
        self.tokens = TokenStore(root)
        self.client = TestClient(create_app(root=root, preload_stt=False),
                                 raise_server_exceptions=False)
        self.headers = {"Authorization": "Bearer " + self.tokens.pairing_token}

        self.model = FakeLlm()
        self._llm_backup = dict(llm.REGISTRY)
        llm.REGISTRY["fake"] = self.model
        self.settings.update_section("llm", {"active": "fake", "model": "m"})

        self.search = FakeSearch()
        self._search_backup = dict(websearch.REGISTRY)
        websearch.REGISTRY["brave"] = self.search

    def tearDown(self):
        llm.REGISTRY.clear(); llm.REGISTRY.update(self._llm_backup)
        websearch.REGISTRY.clear(); websearch.REGISTRY.update(self._search_backup)
        self._tmp.cleanup()

    def _configure_brave(self):
        self.settings.update_section(
            "image_search", {"credentials": {"brave": {"api_key": "cle"}}})

    def _ask(self, question="Quels sont les horaires du Louvre ?"):
        return self.client.post("/api/robot/chat", headers=self.headers,
                                json={"messages": [{"role": "user", "text": question}]})

    def test_searches_then_answers_with_what_was_found(self):
        self._configure_brave()
        self.search.results = [
            websearch.SearchResult("Louvre", "Ouvert de 9 h à 18 h.", "https://louvre.fr")]
        self.model.replies = [SEARCH_PREFIX + " horaires du Louvre",
                              "Le Louvre est ouvert de 9 h à 18 h."]

        response = self._ask()
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["response"], "Le Louvre est ouvert de 9 h à 18 h.")
        self.assertEqual(self.search.queries, ["horaires du Louvre"])
        # Les résultats sont fournis au second tour, comme message utilisateur.
        second = self.model.calls[1]["messages"][-1]
        self.assertEqual(second.role, "user")
        self.assertIn("Ouvert de 9 h à 18 h.", second.text)

    def test_the_marker_never_reaches_the_visitor(self):
        # Sans le second tour, le robot prononcerait « RECHERCHE: horaires du Louvre ».
        self._configure_brave()
        self.model.replies = [SEARCH_PREFIX + " horaires du Louvre", "Je regarde…"]
        self.assertNotIn(SEARCH_PREFIX, self._ask().json()["response"])

    def test_only_one_round_of_search(self):
        # Le visiteur attend devant le robot : un modèle qui redemanderait à chercher
        # boucherait l'échange. La deuxième demande est rendue telle quelle, pas suivie.
        self._configure_brave()
        self.model.replies = [SEARCH_PREFIX + " a", SEARCH_PREFIX + " b"]
        self._ask()
        self.assertEqual(self.search.queries, ["a"])
        self.assertEqual(len(self.model.calls), 2)

    def test_an_empty_result_still_produces_an_answer(self):
        self._configure_brave()
        self.model.replies = [SEARCH_PREFIX + " truc introuvable",
                              "Je n'ai pas trouvé, désolé."]
        self.assertEqual(self._ask().json()["response"], "Je n'ai pas trouvé, désolé.")
        self.assertIn("n'a rien donné", self.model.calls[1]["messages"][-1].text)

    def test_a_search_outage_does_not_break_the_conversation(self):
        self._configure_brave()
        self.search.error = websearch.WebSearchUnavailable("panne")
        self.model.replies = [SEARCH_PREFIX + " x", "Je ne sais pas, désolé."]
        response = self._ask()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["response"], "Je ne sais pas, désolé.")

    def test_without_a_key_the_model_is_never_offered_the_option(self):
        # Proposer de chercher sans moyen de chercher ferait dire « RECHERCHE: … » à
        # voix haute devant un visiteur.
        self.model.replies = ["Je ne sais pas."]
        self._ask()
        self.assertNotIn(SEARCH_PREFIX, self.model.calls[0]["system"])

    def test_with_a_key_the_option_is_offered(self):
        self._configure_brave()
        self.model.replies = ["Bonjour !"]
        self._ask()
        self.assertIn(SEARCH_PREFIX, self.model.calls[0]["system"])

    def test_an_ordinary_answer_triggers_no_search(self):
        self._configure_brave()
        self.model.replies = ["Bonjour, je suis Pepper !"]
        self.assertEqual(self._ask("Bonjour").json()["response"], "Bonjour, je suis Pepper !")
        self.assertEqual(self.search.queries, [])

    def test_location_question_includes_configured_pointing_action(self):
        self.settings.update_section("hospitality", {
            "active": True,
            "active_profile": "fiche",
            "profiles": [{
                "id": "fiche",
                "label": "Hôte",
                "places": [{
                    "name": "Cuisine",
                    "directions": "au fond du couloir",
                    "point_direction": "right",
                }],
            }],
        })
        self.model.replies = ["La cuisine est au fond du couloir."]

        response = self._ask("Où est la cuisine ?")

        self.assertEqual(response.status_code, 200, response.text)
        payload = json.loads(response.json()["response"])
        self.assertEqual(payload["speech"], "La cuisine est au fond du couloir.")
        self.assertEqual(payload["actions"][0]["name"], "point_right")
