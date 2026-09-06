"""Protocole des connecteurs de transcription, même forme que les connecteurs LLM.

Les connecteurs sont sans état : les identifiants leur sont passés à l'appel et ne sont
jamais stockés dans l'instance. Whisper embarqué n'en a d'ailleurs aucun.
"""
from dataclasses import dataclass

from brain.pricing import Pricing, credential_fields


class SttError(Exception):
    """Base des erreurs de transcription. Le message est rédigé pour un installateur,
    pas pour un développeur, et ne doit jamais contenir d'identifiant."""


class SttNotConfigured(SttError):
    """Le connecteur sélectionné n'a pas les identifiants nécessaires."""


class SttAudioTooShort(SttError):
    """L'extrait reçu est trop court pour contenir de la parole."""


class SttModelUnavailable(SttError):
    """Le modèle n'a pas pu être chargé : absent du volume et non téléchargeable."""


@dataclass(frozen=True)
class SttModelInfo:
    id: str
    label: str
    pricing: Pricing | None = None


class SttConnector:
    id = ""
    label = ""
    credential_fields: tuple[str, ...] = ()

    def models(self) -> list[SttModelInfo]:
        raise NotImplementedError

    def is_configured(self, credentials: dict) -> bool:
        raise NotImplementedError

    def transcribe(
        self,
        credentials: dict,
        pcm16: bytes,
        sample_rate: int = 16000,
        language: str = "fr",
        model: str | object = "base",
    ) -> str:
        """Transcrit du PCM 16 bits mono.

        `model` accepte soit un identifiant de modèle, soit un objet modèle déjà
        chargé — ce second cas est réservé aux tests, pour ne pas télécharger de
        modèle ni toucher le réseau.

        Lève une sous-classe de [SttError]. Ne laisse jamais remonter l'exception
        d'origine du fournisseur.
        """
        raise NotImplementedError


REGISTRY: dict[str, SttConnector] = {}


def register(connector: SttConnector) -> SttConnector:
    REGISTRY[connector.id] = connector
    return connector


def get(connector_id: str) -> SttConnector:
    if connector_id not in REGISTRY:
        raise KeyError("connecteur STT inconnu: %s" % connector_id)
    return REGISTRY[connector_id]


def catalog(credentials: dict) -> list[dict]:
    """Liste pour la webapp. `or {}` et non `get(id, {})` : un réglage JSON produit
    des null, et un catalogue qui plante fait disparaître toute la page de réglages."""
    return [
        {
            "id": connector.id,
            "label": connector.label,
            "configured": connector.is_configured(credentials.get(connector.id) or {}),
            "credential_fields": credential_fields(connector.credential_fields),
            "models": [
                {
                    "id": m.id,
                    "label": m.label,
                    "pricing": m.pricing.public() if m.pricing else None,
                }
                for m in connector.models()
            ],
        }
        for connector in REGISTRY.values()
    ]
