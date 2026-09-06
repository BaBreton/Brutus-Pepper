import json
import tempfile
import threading
import unittest
from pathlib import Path

from brain.settings import DEFAULTS, SECRET_KEYS, SettingsStore


class SettingsStoreTest(unittest.TestCase):
    def test_returns_defaults_when_no_file_exists(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = SettingsStore(Path(tmp))
            settings = store.load()
            self.assertEqual(settings["stt"]["active"], "whisper-local")
            self.assertEqual(settings["stt"]["model"], "base")
            self.assertEqual(settings["llm"]["active"], "")

    def test_secrets_are_encrypted_at_rest(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = SettingsStore(Path(tmp))
            store.update_section("llm", {"credentials": {"anthropic": {"api_key": "sk-secret-value"}}})
            raw = (Path(tmp) / "settings.enc").read_bytes()
            self.assertNotIn(b"sk-secret-value", raw)

    def test_public_view_masks_each_secret_field_individually(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = SettingsStore(Path(tmp))
            store.update_section("llm", {
                "active": "anthropic",
                "model": "claude-haiku-4-5",
                "credentials": {"anthropic": {"api_key": "sk-ant-abcd1234"}},
            })
            public = store.public()
            self.assertEqual(public["llm"]["active"], "anthropic")
            secret = public["llm"]["credentials"]["anthropic"]["api_key"]
            self.assertTrue(secret["configured"])
            self.assertEqual(secret["masked"], "…1234")
            self.assertNotIn("sk-ant-abcd1234", json.dumps(public))

    def test_public_view_preserves_non_secret_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = SettingsStore(Path(tmp))
            store.update_section("llm", {
                "credentials": {"bedrock": {
                    "region": "eu-west-3",
                    "access_key": "AKIA1234",
                    "secret_key": "shh12345",
                }},
            })
            public = store.public()
            bedrock = public["llm"]["credentials"]["bedrock"]
            # La région n'est pas un secret : la webapp doit pouvoir la réafficher,
            # is_configured() de Bedrock en dépend.
            self.assertEqual(bedrock["region"], "eu-west-3")
            self.assertTrue(bedrock["access_key"]["configured"])
            self.assertTrue(bedrock["secret_key"]["configured"])

    def test_public_never_leaks_a_secret_planted_in_any_section_or_field(self):
        """Propriété centrale du produit : aucun champ nommé comme SECRET_KEYS ne doit
        jamais atteindre json.dumps(public()), quelle que soit la section où il vit."""
        with tempfile.TemporaryDirectory() as tmp:
            store = SettingsStore(Path(tmp))
            sentinels = []
            for section in DEFAULTS:
                if "credentials" not in DEFAULTS[section]:
                    continue
                for index, field in enumerate(SECRET_KEYS):
                    sentinel = "leak-%s-%s" % (section, field)
                    sentinels.append(sentinel)
                    store.update_section(section, {
                        "credentials": {"connector-%d" % index: {field: sentinel}},
                    })
            dumped = json.dumps(store.public())
            for sentinel in sentinels:
                self.assertNotIn(sentinel, dumped)

    def test_blank_value_preserves_existing_secret(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = SettingsStore(Path(tmp))
            store.update_section("llm", {"credentials": {"anthropic": {"api_key": "sk-first"}}})
            store.update_section("llm", {"model": "claude-sonnet-5",
                                         "credentials": {"anthropic": {"api_key": ""}}})
            settings = store.load()
            self.assertEqual(settings["llm"]["credentials"]["anthropic"]["api_key"], "sk-first")
            self.assertEqual(settings["llm"]["model"], "claude-sonnet-5")

    def test_none_secret_value_also_preserves_existing_secret(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = SettingsStore(Path(tmp))
            store.update_section("llm", {"credentials": {"anthropic": {"api_key": "sk-first"}}})
            store.update_section("llm", {"credentials": {"anthropic": {"api_key": None}}})
            settings = store.load()
            self.assertEqual(settings["llm"]["credentials"]["anthropic"]["api_key"], "sk-first")

    def test_unknown_section_survives_a_save_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = SettingsStore(Path(tmp))
            settings = store.load()
            settings["future_feature"] = {"enabled": True}
            store.save(settings)
            reloaded = store.load()
            self.assertEqual(reloaded["future_feature"], {"enabled": True})

    def test_delete_credential_removes_a_connector(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = SettingsStore(Path(tmp))
            store.update_section("llm", {"credentials": {"bedrock": {"access_key": "AKIA1"}}})
            store.delete_credential("llm", "bedrock")
            settings = store.load()
            self.assertNotIn("bedrock", settings["llm"]["credentials"])

    def test_concurrent_updates_do_not_lose_writes(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = SettingsStore(Path(tmp))
            connectors = ["connector-%d" % i for i in range(20)]

            def write(name):
                store.update_section("llm", {"credentials": {name: {"api_key": "key-%s" % name}}})

            threads = [threading.Thread(target=write, args=(name,)) for name in connectors]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()

            settings = store.load()
            for name in connectors:
                self.assertEqual(settings["llm"]["credentials"][name]["api_key"], "key-%s" % name)
