"""Connecteurs de transcription dans le nuage.

Alternative à Whisper embarqué quand la machine du client est trop juste : sur un
mini-PC quatre cœurs, le modèle `small` met environ autant de temps que la durée de
la phrase, et le modèle `base`, deux fois plus rapide, écorche les noms propres —
ce qui est rédhibitoire pour un robot d'accueil qui doit comprendre des noms de
visiteurs et de lieux.

Contrepartie à annoncer au client : l'audio sort de son réseau. Whisper embarqué
reste le défaut pour cette raison.
"""
import io
import wave

from brain.connectors.stt import (
    SttAudioTooShort,
    SttConnector,
    SttError,
    SttModelInfo,
    SttNotConfigured,
    register,
)
from brain.connectors.stt_whisper_local import MIN_PCM_BYTES
from brain.pricing import (
    AWS_TRANSCRIBE_STREAMING,
    OPENAI_LIVE_TRANSCRIBE,
    OPENAI_MINI_TRANSCRIBE,
    OPENAI_TRANSCRIBE,
    OPENAI_WHISPER,
)

TIMEOUT_SECONDS = 15.0


def pcm_to_wav(pcm16: bytes, sample_rate: int) -> bytes:
    """Les API cloud attendent un conteneur, pas du PCM brut."""
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as stream:
        stream.setnchannels(1)
        stream.setsampwidth(2)
        stream.setframerate(sample_rate)
        stream.writeframes(pcm16)
    return buffer.getvalue()


class OpenAiSttConnector(SttConnector):
    id = "openai"
    label = "OpenAI (l'audio sort du réseau)"
    credential_fields = ("api_key",)

    def models(self) -> list[SttModelInfo]:
        return [
            SttModelInfo("gpt-live-transcribe", "GPT Live Transcribe — en continu", pricing=OPENAI_LIVE_TRANSCRIBE),
            SttModelInfo("gpt-4o-mini-transcribe", "gpt-4o-mini-transcribe — rapide", pricing=OPENAI_MINI_TRANSCRIBE),
            SttModelInfo("gpt-4o-transcribe", "gpt-4o-transcribe — plus précis", pricing=OPENAI_TRANSCRIBE),
            SttModelInfo("whisper-1", "whisper-1 — historique", pricing=OPENAI_WHISPER),
        ]

    def is_configured(self, credentials: dict) -> bool:
        return bool(str(credentials.get("api_key", "")).strip())

    def transcribe(
        self,
        credentials: dict,
        pcm16: bytes,
        sample_rate: int = 16000,
        language: str = "fr",
        model: str | object = "gpt-4o-mini-transcribe",
        client=None,
    ) -> str:
        if not self.is_configured(credentials):
            raise SttNotConfigured("clé API OpenAI absente ou vide")
        if len(pcm16) < MIN_PCM_BYTES:
            raise SttAudioTooShort("extrait audio trop court pour contenir de la parole")

        active = client or self._client(credentials)
        audio = io.BytesIO(pcm_to_wav(pcm16, sample_rate))
        audio.name = "parole.wav"  # le SDK déduit le format du nom
        try:
            response = active.audio.transcriptions.create(
                model=model if isinstance(model, str) and model != "gpt-live-transcribe" else "gpt-4o-mini-transcribe",
                file=audio,
                language=language,
            )
        except Exception as error:
            raise self._translate(error) from None
        return (response.text or "").strip()

    @staticmethod
    def _client(credentials: dict):
        import openai

        return openai.OpenAI(api_key=credentials["api_key"], timeout=TIMEOUT_SECONDS,
                             max_retries=1)

    @staticmethod
    def _translate(error: Exception) -> SttError:
        name = type(error).__name__
        if "Authentication" in name or "PermissionDenied" in name:
            return SttNotConfigured("clé API OpenAI refusée")
        if "Timeout" in name:
            return SttError("OpenAI n'a pas répondu à temps")
        return SttError("la transcription OpenAI a échoué")


class AwsSttConnector(SttConnector):
    """AWS Transcribe en flux. Les identifiants sont les mêmes que pour Bedrock."""

    id = "aws"
    label = "AWS Transcribe (l'audio sort du réseau)"
    credential_fields = ("access_key", "secret_key", "region")

    def models(self) -> list[SttModelInfo]:
        return [SttModelInfo("streaming", "streaming temps réel", pricing=AWS_TRANSCRIBE_STREAMING)]

    def is_configured(self, credentials: dict) -> bool:
        return bool(
            str(credentials.get("access_key", "")).strip()
            and str(credentials.get("secret_key", "")).strip()
            and str(credentials.get("region", "")).strip()
        )

    def transcribe(
        self,
        credentials: dict,
        pcm16: bytes,
        sample_rate: int = 16000,
        language: str = "fr",
        model: str | object = "streaming",
        runner=None,
    ) -> str:
        if not self.is_configured(credentials):
            raise SttNotConfigured("identifiants AWS Transcribe absents ou incomplets")
        if len(pcm16) < MIN_PCM_BYTES:
            raise SttAudioTooShort("extrait audio trop court pour contenir de la parole")
        try:
            return (runner or self._stream)(credentials, pcm16, sample_rate, language)
        except SttError:
            raise
        except Exception as error:
            raise self._translate(error) from None

    @staticmethod
    def _stream(credentials: dict, pcm16: bytes, sample_rate: int, language: str) -> str:
        import asyncio

        from amazon_transcribe.auth import StaticCredentialResolver
        from amazon_transcribe.client import TranscribeStreamingClient
        from amazon_transcribe.handlers import TranscriptResultStreamHandler

        class Collector(TranscriptResultStreamHandler):
            def __init__(self, output_stream):
                super().__init__(output_stream)
                self.finals: list[str] = []
                self.partial = ""

            async def handle_transcript_event(self, event):
                for result in event.transcript.results:
                    for alternative in result.alternatives:
                        if not alternative.transcript:
                            continue
                        if result.is_partial:
                            self.partial = alternative.transcript
                        else:
                            self.finals.append(alternative.transcript)

        async def run() -> str:
            # Ne jamais injecter les identifiants dans os.environ : cela les rend
            # visibles aux autres requêtes et aux processus enfants. Le SDK de
            # streaming accepte un résolveur statique propre à cette session.
            client = TranscribeStreamingClient(
                region=credentials["region"],
                credential_resolver=StaticCredentialResolver(
                    credentials["access_key"], credentials["secret_key"]
                ),
            )
            stream = await client.start_stream_transcription(
                language_code="%s-%s" % (language, language.upper())
                if len(language) == 2 else language,
                media_sample_rate_hz=sample_rate,
                media_encoding="pcm",
            )

            async def send() -> None:
                chunk = 8 * 1024
                for offset in range(0, len(pcm16), chunk):
                    await stream.input_stream.send_audio_event(
                        audio_chunk=pcm16[offset:offset + chunk])
                    await asyncio.sleep(0.005)
                await stream.input_stream.end_stream()

            collector = Collector(stream.output_stream)
            await asyncio.gather(send(), collector.handle_events())
            return (" ".join(collector.finals) or collector.partial).strip()

        return asyncio.run(run())

    @staticmethod
    def _translate(error: Exception) -> SttError:
        text = str(error)
        if "security token" in text or "not authorized" in text.lower():
            return SttNotConfigured("identifiants AWS Transcribe refusés")
        return SttError("la transcription AWS Transcribe a échoué")


register(OpenAiSttConnector())
register(AwsSttConnector())
