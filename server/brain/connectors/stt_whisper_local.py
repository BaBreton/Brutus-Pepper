"""Whisper embarqué (faster-whisper, CPU int8).

Aucun audio ne sort du réseau du client et aucune clé n'est nécessaire. Cible
matérielle : mini-PC x86, Mac ou Raspberry Pi — d'où `base` par défaut pour la
réactivité et `small` disponible si les noms propres sont prioritaires.
"""
import io
import threading
import wave
from pathlib import Path

import numpy as np

from brain.connectors.stt import (
    SttAudioTooShort,
    SttConnector,
    SttModelInfo,
    SttModelUnavailable,
    register,
)
from brain.pricing import LOCAL_WHISPER

# 0.3 s à 16 kHz sur 16 bits : en dessous, il n'y a pas de parole à transcrire.
MIN_PCM_BYTES = 16000 * 2 * 3 // 10

_MODELS: dict[str, object] = {}
_LOCK = threading.Lock()
# Includes consumption of the lazy segment generator. HTTP fallback and WS
# speculation can overlap; serialize native inference across both entrypoints.
_INFERENCE_LOCK = threading.Lock()


class WhisperLocalConnector(SttConnector):
    id = "whisper-local"
    label = "Whisper embarqué (aucune donnée ne sort)"

    def models(self) -> list[SttModelInfo]:
        return [
            SttModelInfo("small", "small — plus précis, CPU rapide requis", pricing=LOCAL_WHISPER),
            SttModelInfo("base", "base — plus rapide, noms propres à vérifier", pricing=LOCAL_WHISPER),
            SttModelInfo("medium", "medium — plus précis, machine puissante", pricing=LOCAL_WHISPER),
        ]

    def is_configured(self, credentials: dict) -> bool:
        return True  # modèle local : aucune clé nécessaire

    def transcribe(
        self,
        credentials: dict,
        pcm16: bytes,
        sample_rate: int = 16000,
        language: str = "fr",
        model: str | object = "base",
    ) -> str:
        if len(pcm16) < MIN_PCM_BYTES:
            raise SttAudioTooShort("extrait audio trop court pour contenir de la parole")
        active = self.load(model) if isinstance(model, str) else model
        if sample_rate <= 0 or len(pcm16) % 2:
            raise ValueError("PCM 16 bits et fréquence positive requis")
        if sample_rate == 16000:
            # faster-whisper accepts float32 audio already sampled at 16 kHz.
            # No WAV/file descriptor, decoding subprocess or resampling needed.
            audio = np.frombuffer(pcm16, dtype="<i2").astype(np.float32) / 32768.0
        else:
            # Preserve callers using another sample rate: PyAV resamples a WAV
            # file-like object. No named temporary file (mkstemp leaked its fd).
            audio = io.BytesIO()
            with wave.open(audio, "wb") as stream:
                stream.setnchannels(1)
                stream.setsampwidth(2)
                stream.setframerate(sample_rate)
                stream.writeframes(pcm16)
            audio.seek(0)
        with _INFERENCE_LOCK:
            segments, _info = active.transcribe(audio, language=language, beam_size=1)
            return " ".join(segment.text.strip() for segment in segments).strip()

    @staticmethod
    def load(name: str):
        """Charge et met en cache le modèle.

        Le premier appel télécharge le modèle s'il est absent du volume — environ
        500 Mo pour `small`. Appeler cette méthode au démarrage du conteneur évite
        que ce téléchargement ne tombe sur le premier visiteur qui parle au robot.
        """
        with _LOCK:
            if name not in _MODELS:
                from faster_whisper import WhisperModel

                from brain import config

                try:
                    _MODELS[name] = WhisperModel(
                        name,
                        device="cpu",
                        compute_type="int8",
                        download_root=str(config.MODELS_ROOT),
                    )
                except Exception as error:
                    raise SttModelUnavailable(
                        "modèle Whisper « %s » indisponible : il est absent du volume "
                        "de modèles et n'a pas pu être téléchargé. Vérifiez l'accès "
                        "internet du serveur, ou choisissez un modèle déjà présent." % name
                    ) from None
        return _MODELS[name]

    @staticmethod
    def _write_wav(path: Path, pcm16: bytes, sample_rate: int) -> None:
        with wave.open(str(path), "wb") as stream:
            stream.setnchannels(1)
            stream.setsampwidth(2)
            stream.setframerate(sample_rate)
            stream.writeframes(pcm16)


register(WhisperLocalConnector())
