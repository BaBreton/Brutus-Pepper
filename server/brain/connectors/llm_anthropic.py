"""Connecteur Anthropic (API Messages), via le SDK officiel.

Nécessite anthropic >= 0.120.0 : c'est la première version où l'API Messages
expose `thinking` et `output_config` (vérifié par inspection de signature dans
les tests, pas seulement par le numéro de version épinglé)."""
from brain.connectors.llm import (
    LlmAuthError,
    LlmConnector,
    LlmNotConfigured,
    LlmProviderUnavailable,
    LlmTimeout,
    Message,
    ModelInfo,
    register,
    validate_conversation,
)
from brain.pricing import ANTHROPIC_HAIKU_45, ANTHROPIC_OPUS_5, ANTHROPIC_SONNET_5

# Devant un robot d'accueil, tout ce qui dépasse une dizaine de secondes est
# déjà un échec produit : on coupe court plutôt que de laisser le SDK attendre
# 10 minutes et retenter deux fois par défaut.
DEFAULT_TIMEOUT = 15.0
DEFAULT_MAX_RETRIES = 1


class AnthropicConnector(LlmConnector):
    id = "anthropic"
    label = "Anthropic (Claude)"
    credential_fields = ("api_key",)

    def models(self, credentials: dict | None = None) -> list[ModelInfo]:
        return [
            # Génération 4.5 : ne supporte pas thinking/output_config.
            ModelInfo(
                "claude-haiku-4-5",
                "Claude Haiku 4.5 — le plus rapide",
                supports_effort=False,
                pricing=ANTHROPIC_HAIKU_45,
            ),
            ModelInfo("claude-sonnet-5", "Claude Sonnet 5 — équilibré", pricing=ANTHROPIC_SONNET_5),
            ModelInfo("claude-opus-5", "Claude Opus 5 — le plus capable", pricing=ANTHROPIC_OPUS_5),
        ]

    def is_configured(self, credentials: dict) -> bool:
        return bool(str(credentials.get("api_key", "")).strip())

    def complete(
        self,
        credentials: dict,
        model: str,
        system: str,
        messages: list[Message],
        max_tokens: int = 300,
        client=None,
        timeout: float = DEFAULT_TIMEOUT,
        max_retries: int = DEFAULT_MAX_RETRIES,
    ) -> str:
        validate_conversation(system, messages)
        if client is None and not self.is_configured(credentials):
            raise LlmNotConfigured("clé API Anthropic absente ou vide")
        active = client or self._client(credentials, timeout=timeout, max_retries=max_retries)

        extra: dict = {}
        model_info = next((m for m in self.models() if m.id == model), None)
        if model_info is None or model_info.supports_effort:
            # Un tour de parole doit répondre vite : pas de réflexion, effort bas.
            # Sur Claude Opus 5, désactiver la réflexion n'est permis qu'à effort <= "high".
            extra["thinking"] = {"type": "disabled"}
            extra["output_config"] = {"effort": "low"}

        try:
            response = active.messages.create(
                model=model,
                max_tokens=max_tokens,
                system=system,
                messages=[{"role": m.role, "content": m.text} for m in messages],
                **extra,
            )
        except Exception as exc:
            raise self._translate_error(exc) from None

        text = "".join(
            block.text for block in response.content if getattr(block, "type", "") == "text"
        ).strip()
        if not text:
            raise LlmProviderUnavailable("réponse Anthropic vide")
        return text

    @staticmethod
    def _client(credentials: dict, timeout: float = DEFAULT_TIMEOUT, max_retries: int = DEFAULT_MAX_RETRIES):
        import anthropic

        return anthropic.Anthropic(
            api_key=credentials["api_key"], timeout=timeout, max_retries=max_retries
        )

    @staticmethod
    def _translate_error(exc: Exception) -> Exception:
        """Traduit une erreur du SDK en `LlmError` métier. Ne jamais reporter
        l'exception d'origine : elle peut porter la requête HTTP, et donc la
        clé API dans ses en-têtes (`x-api-key`, non masqué par httpx)."""
        import anthropic

        if isinstance(exc, anthropic.APITimeoutError):
            return LlmTimeout("le fournisseur Anthropic n'a pas répondu à temps")
        if isinstance(exc, anthropic.AuthenticationError):
            return LlmAuthError("clé API Anthropic refusée")
        if isinstance(exc, anthropic.APIError):
            return LlmProviderUnavailable("le fournisseur Anthropic est indisponible")
        return exc


register(AnthropicConnector())
