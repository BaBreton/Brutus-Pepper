import unittest

from brain import greeting
from brain.connectors import llm


class FallbackTest(unittest.TestCase):
    """L'accroche de repli, composée sans modèle. Elle sert quand le fournisseur est
    injoignable au moment où l'opérateur enregistre — l'hospitalité doit marcher
    quand même, quitte à être moins bien tournée."""

    def test_names_the_company_and_the_place_to_send_people_to(self):
        text = greeting.fallback({
            "company": "Recepta",
            "mission": "accueille les clients et envoie-les vers la cuisine",
            "places": [{"name": "cuisine", "directions": "au fond à votre droite"}],
        })
        self.assertIn("Recepta", text)
        self.assertIn("cuisine", text)
        self.assertIn("au fond à votre droite", text)

    def test_still_greets_when_the_operator_filled_almost_nothing(self):
        text = greeting.fallback({"active": True})
        self.assertTrue(text.strip())
        self.assertIn("Bienvenue", text)

    def test_uses_only_the_first_place(self):
        # Réciter tout l'annuaire à quelqu'un qui vient d'entrer ne sert personne.
        text = greeting.fallback({"places": [
            {"name": "cuisine", "directions": "à droite"},
            {"name": "salle Ariane", "directions": "au premier"},
        ]})
        self.assertIn("cuisine", text)
        self.assertNotIn("Ariane", text)

    def test_ignores_a_place_that_has_no_directions(self):
        text = greeting.fallback({"places": [{"name": "cuisine", "directions": "  "}]})
        self.assertNotIn("cuisine", text)


class FakeConnector:
    def __init__(self, reply="Bienvenue chez Recepta ! La cuisine est au fond à droite."):
        self.reply = reply
        self.calls = []

    def complete(self, credentials, model, system, messages):
        self.calls.append({"system": system, "messages": messages, "model": model})
        if isinstance(self.reply, Exception):
            raise self.reply
        return self.reply


class GenerateTest(unittest.TestCase):
    HOSPITALITY = {
        "active": True,
        "company": "Recepta",
        "mission": "accueille les clients et envoie-les vers la cuisine",
        "places": [{"name": "cuisine", "directions": "au fond à votre droite"}],
    }

    def test_asks_the_model_for_the_line_and_returns_it(self):
        connector = FakeConnector()
        text = greeting.generate(self.HOSPITALITY, connector, credentials={}, model="m")
        self.assertEqual(text, "Bienvenue chez Recepta ! La cuisine est au fond à droite.")
        # La consigne de l'opérateur et les lieux doivent parvenir au modèle, sinon
        # l'accroche ne peut pas les mentionner.
        sent = connector.calls[0]["system"] + connector.calls[0]["messages"][0].text
        self.assertIn("envoie-les vers la cuisine", sent)
        self.assertIn("au fond à votre droite", sent)

    def test_falls_back_when_the_provider_fails(self):
        # L'opérateur enregistre ses réglages ; un fournisseur en panne ne doit pas
        # l'empêcher d'activer l'hospitalité.
        connector = FakeConnector(llm.LlmTimeout("trop lent"))
        text = greeting.generate(self.HOSPITALITY, connector, credentials={}, model="m")
        self.assertEqual(text, greeting.fallback(self.HOSPITALITY))

    def test_falls_back_on_an_unexpected_error_too(self):
        connector = FakeConnector(RuntimeError("boum"))
        self.assertEqual(greeting.generate(self.HOSPITALITY, connector, {}, "m"),
                         greeting.fallback(self.HOSPITALITY))

    def test_falls_back_when_the_model_answers_nothing(self):
        self.assertEqual(greeting.generate(self.HOSPITALITY, FakeConnector("   "), {}, "m"),
                         greeting.fallback(self.HOSPITALITY))

    def test_strips_quotes_the_model_wraps_around_its_answer(self):
        # Les modèles rendent volontiers « "Bienvenue…" » ; Pepper lirait les guillemets.
        connector = FakeConnector('"Bienvenue chez Recepta."')
        self.assertEqual(greeting.generate(self.HOSPITALITY, connector, {}, "m"),
                         "Bienvenue chez Recepta.")

    def test_keeps_the_line_short_enough_to_be_spoken(self):
        connector = FakeConnector("phrase. " * 200)
        text = greeting.generate(self.HOSPITALITY, connector, {}, "m")
        self.assertLessEqual(len(text), greeting.MAX_CHARS)
