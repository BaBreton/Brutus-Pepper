import tempfile
import unittest
from pathlib import Path

from brain import preload
from brain.settings import SettingsStore


class PreloadTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.settings = SettingsStore(self.root)

    def tearDown(self):
        self._tmp.cleanup()

    def test_loads_the_configured_stt_model(self):
        loaded: list[str] = []
        preload.warm_stt(self.settings, loader=loaded.append)
        self.assertEqual(loaded, ["base"])

    def test_uses_the_model_chosen_by_the_client(self):
        self.settings.update_section("stt", {"model": "base"})
        loaded: list[str] = []
        preload.warm_stt(self.settings, loader=loaded.append)
        self.assertEqual(loaded, ["base"])

    def test_a_failure_never_prevents_the_server_from_starting(self):
        # Un serveur client sans accès internet doit quand même démarrer : la webapp
        # doit rester joignable pour qu'on puisse choisir un autre modèle.
        def exploding(_name):
            raise RuntimeError("pas de réseau")

        preload.warm_stt(self.settings, loader=exploding)  # ne doit pas lever

    def test_does_nothing_for_a_connector_that_needs_no_model(self):
        self.settings.update_section("stt", {"active": "inconnu"})
        loaded: list[str] = []
        preload.warm_stt(self.settings, loader=loaded.append)
        self.assertEqual(loaded, [])
