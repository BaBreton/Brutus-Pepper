import inspect
import unittest

from brain.connectors import stt


class Segment:
    def __init__(self, text):
        self.text = text


class FakeWhisperModel:
    """Faux modèle : enregistre les arguments reçus pour qu'on puisse les inspecter."""

    def __init__(self):
        self.calls: list[dict] = []

    def transcribe(self, path, **kwargs):
        self.calls.append({"path": path, **kwargs})
        return [Segment(" Bonjour"), Segment(" Pepper")], None


class WhisperLocalTest(unittest.TestCase):
    def setUp(self):
        from brain.connectors.stt_whisper_local import WhisperLocalConnector

        self.connector = WhisperLocalConnector()

    def test_is_always_configured_because_it_needs_no_key(self):
        self.assertTrue(self.connector.is_configured({}))

    def test_transcribe_joins_segments_and_passes_the_language(self):
        model = FakeWhisperModel()
        pcm = b"\x00\x01" * 16000  # 1 s de PCM 16 kHz mono
        text = self.connector.transcribe({}, pcm, sample_rate=16000, language="fr", model=model)
        self.assertEqual(text, "Bonjour Pepper")
        self.assertEqual(model.calls[0]["language"], "fr")
        self.assertEqual(model.calls[0]["beam_size"], 1)

    def test_transcribe_rejects_audio_that_is_too_short(self):
        with self.assertRaises(stt.SttAudioTooShort):
            self.connector.transcribe({}, b"\x00\x01", sample_rate=16000, language="fr",
                                      model=FakeWhisperModel())

    def test_transcribe_leaves_no_temporary_file_behind(self):
        import tempfile
        from pathlib import Path

        before = set(Path(tempfile.gettempdir()).glob("*.wav"))
        self.connector.transcribe({}, b"\x00\x01" * 16000, sample_rate=16000,
                                  language="fr", model=FakeWhisperModel())
        after = set(Path(tempfile.gettempdir()).glob("*.wav"))
        self.assertEqual(after - before, set())


class RealSdkSignatureTest(unittest.TestCase):
    """Le faux modèle accepte n'importe quel argument. Ces tests confrontent ce que le
    connecteur envoie à la vraie signature de faster-whisper, pour qu'un argument
    inexistant ne passe pas inaperçu — c'est exactement le défaut qui avait rendu le
    connecteur Anthropic inopérant à 100 % pendant que ses tests étaient au vert."""

    def test_transcribe_arguments_exist_in_the_real_sdk(self):
        from faster_whisper import WhisperModel

        sent = {"language", "beam_size"}
        real = set(inspect.signature(WhisperModel.transcribe).parameters)
        self.assertEqual(sent - real, set())

    def test_model_construction_arguments_exist_in_the_real_sdk(self):
        from faster_whisper import WhisperModel

        sent = {"device", "compute_type", "download_root"}
        real = set(inspect.signature(WhisperModel.__init__).parameters)
        self.assertEqual(sent - real, set())


class RegistryTest(unittest.TestCase):
    def test_catalog_lists_whisper_local_as_configured(self):
        by_id = {entry["id"]: entry for entry in stt.catalog({})}
        self.assertTrue(by_id["whisper-local"]["configured"])
        self.assertIn("small", [m["id"] for m in by_id["whisper-local"]["models"]])

    def test_catalog_survives_a_null_credentials_entry(self):
        # Un réglage JSON produit des null : champ effacé dans la webapp, migration
        # de schéma. Le catalogue ne doit pas tomber, sinon c'est toute la page de
        # réglages du client qui disparaît.
        by_id = {entry["id"]: entry for entry in stt.catalog({"whisper-local": None})}
        self.assertTrue(by_id["whisper-local"]["configured"])

    def test_get_raises_for_unknown_connector(self):
        with self.assertRaises(KeyError):
            stt.get("nope")


class CloudSttTest(unittest.TestCase):
    """Les connecteurs cloud sont l'alternative quand la machine du client est trop
    juste pour Whisper embarqué. Contrepartie : l'audio sort de son réseau."""

    def test_both_cloud_providers_are_registered(self):
        by_id = {entry["id"]: entry for entry in stt.catalog({})}
        self.assertIn("openai", by_id)
        self.assertIn("aws", by_id)
        # Sans clé, ils sont visibles mais pas sélectionnables.
        self.assertFalse(by_id["openai"]["configured"])
        self.assertFalse(by_id["aws"]["configured"])

    def test_openai_needs_a_key_before_any_request(self):
        provider = stt.get("openai")
        with self.assertRaises(stt.SttNotConfigured):
            provider.transcribe({}, b"\x00\x01" * 16000, client=object())

    def test_aws_needs_all_three_credentials(self):
        provider = stt.get("aws")
        self.assertFalse(provider.is_configured({"access_key": "a", "secret_key": "b"}))
        self.assertTrue(provider.is_configured(
            {"access_key": "a", "secret_key": "b", "region": "eu-central-1"}))

    def test_openai_sends_a_wav_container_not_raw_pcm(self):
        seen = {}

        class Transcriptions:
            def create(self, **kwargs):
                seen.update(kwargs)
                seen["header"] = kwargs["file"].getvalue()[:4]

                class R:
                    text = " Bonjour Pepper "

                return R()

        class Audio:
            transcriptions = Transcriptions()

        class Client:
            audio = Audio()

        text = stt.get("openai").transcribe(
            {"api_key": "sk-x"}, b"\x00\x01" * 16000, language="fr", client=Client())
        self.assertEqual(text, "Bonjour Pepper")
        self.assertEqual(seen["header"], b"RIFF")  # conteneur WAV, pas du PCM brut
        self.assertEqual(seen["language"], "fr")

    def test_cloud_providers_reject_audio_too_short(self):
        with self.assertRaises(stt.SttAudioTooShort):
            stt.get("openai").transcribe({"api_key": "sk-x"}, b"\x00\x01", client=object())
        with self.assertRaises(stt.SttAudioTooShort):
            stt.get("aws").transcribe(
                {"access_key": "a", "secret_key": "b", "region": "r"},
                b"\x00\x01", runner=lambda *a: "jamais appelé")

    def test_aws_passes_the_audio_to_the_stream_runner(self):
        captured = {}

        def fake_runner(credentials, pcm, rate, language):
            captured.update(pcm=len(pcm), rate=rate, language=language)
            return "Bonjour Pepper"

        text = stt.get("aws").transcribe(
            {"access_key": "a", "secret_key": "b", "region": "eu-central-1"},
            b"\x00\x01" * 16000, sample_rate=16000, language="fr", runner=fake_runner)
        self.assertEqual(text, "Bonjour Pepper")
        self.assertEqual(captured["rate"], 16000)
        self.assertEqual(captured["language"], "fr")
