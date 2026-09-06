"""Protocole des connecteurs LLM. Les connecteurs sont sans état : les identifiants
sont passés à l'appel, jamais stockés dans l'instance.

Contrat partagé par `LlmConnector.complete()`, validé une fois par
`validate_conversation()` avant que chaque connecteur ne construise sa requête
spécifique :
  - `system` non vide ;
  - `messages` non vide ;
  - chaque rôle appartient à {"user", "assistant"} ;
  - les rôles alternent strictement en commençant par "user".

Les erreurs sont levées via la hiérarchie ci-dessous, dont les messages sont
rédigés pour l'installateur (pas de jargon SDK, jamais de secret ni d'objet de
requête d'origine — voir chaque connecteur pour le détail du mapping)."""
from dataclasses import dataclass

from brain.pricing import Pricing, credential_fields


class LlmError(Exception):
    """Erreur d'un connecteur LLM, avec un message destiné à l'installateur."""


class LlmNotConfigured(LlmError):
    """Identifiants absents ou vides pour ce connecteur."""


class LlmAuthError(LlmError):
    """Le fournisseur a rejeté les identifiants fournis."""


class LlmTimeout(LlmError):
    """Le fournisseur n'a pas répondu dans le délai imparti."""


class LlmProviderUnavailable(LlmError):
    """Le fournisseur est indisponible ou a renvoyé une réponse inexploitable."""


@dataclass(frozen=True)
class ModelInfo:
    id: str
    label: str
    # Génération 4.6+ seulement : réflexion désactivable + effort de sortie.
    # Un modèle de génération antérieure (ex. claude-haiku-4-5) rejette ces
    # paramètres — les connecteurs doivent les omettre pour lui.
    supports_effort: bool = True
    pricing: Pricing | None = None


@dataclass(frozen=True)
class Message:
    role: str  # "user" | "assistant"
    text: str


class LlmConnector:
    """Protocole commun aux connecteurs LLM.

    Sans état : tous les identifiants sont passés en argument à chaque appel,
    jamais conservés sur l'instance. Voir le contrat de `complete()` en tête de
    module — chaque implémentation doit appeler `validate_conversation()` avant
    de construire sa requête.
    """

    id = ""
    label = ""
    credential_fields: tuple[str, ...] = ()

    def models(self, credentials: dict | None = None) -> list[ModelInfo]:
        raise NotImplementedError

    def is_configured(self, credentials: dict) -> bool:
        raise NotImplementedError

    def complete(
        self,
        credentials: dict,
        model: str,
        system: str,
        messages: list[Message],
        max_tokens: int = 300,
        # Réservé aux tests : injection d'un faux client. Ne pas en faire un
        # point d'entrée pour du pooling de connexions en production.
        client=None,
    ) -> str:
        raise NotImplementedError


def validate_conversation(system: str, messages: list[Message]) -> None:
    """Valide une fois le contrat d'entrée commun aux trois connecteurs.

    Lève `LlmError` avec un message clair si `system` est vide, si `messages`
    est vide, si un rôle est inconnu, ou si les rôles n'alternent pas
    strictement en commençant par "user"."""
    if not str(system).strip():
        raise LlmError("message système vide")
    if not messages:
        raise LlmError("aucun message à envoyer")
    expected = "user"
    for message in messages:
        if message.role not in ("user", "assistant"):
            raise LlmError("rôle de message inconnu : %r" % (message.role,))
        if message.role != expected:
            raise LlmError(
                "les messages doivent alterner strictement en commençant par 'user'"
            )
        expected = "assistant" if expected == "user" else "user"


REGISTRY: dict[str, LlmConnector] = {}


def register(connector: LlmConnector) -> LlmConnector:
    REGISTRY[connector.id] = connector
    return connector


def get(connector_id: str) -> LlmConnector:
    if connector_id not in REGISTRY:
        raise KeyError("connecteur LLM inconnu: %s" % connector_id)
    return REGISTRY[connector_id]


def catalog(credentials: dict) -> list[dict]:
    """Liste pour la webapp : un connecteur non configuré est visible mais pas sélectionnable."""
    entries = []
    for connector in REGISTRY.values():
        # `.get(...)` seul ne couvre pas une valeur `null` explicite (réglage
        # effacé côté webapp, migration) : `or {}` couvre les deux cas.
        connector_credentials = credentials.get(connector.id) or {}
        entries.append(
            {
                "id": connector.id,
                "label": connector.label,
                "configured": connector.is_configured(connector_credentials),
                "credential_fields": credential_fields(connector.credential_fields),
                "models": [
                    {
                        "id": m.id,
                        "label": m.label,
                        "pricing": m.pricing.public() if m.pricing else None,
                    }
                    for m in connector.models(connector_credentials)
                ],
            }
        )
    return entries
