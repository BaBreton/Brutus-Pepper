"""Connecteur OpenAI (Chat Completions), via le SDK officiel."""
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
from brain.pricing import OPENAI_GPT_41, OPENAI_GPT_41_MINI

# Devant un robot d'accueil, tout ce qui dépasse une dizaine de secondes est
# déjà un échec produit : on coupe court plutôt que de laisser le SDK attendre
# 10 minutes et retenter deux fois par défaut.
DEFAULT_TIMEOUT = 15.0
DEFAULT_MAX_RETRIES = 1


class OpenAiConnector(LlmConnector):
    id = "openai"
    label = "OpenAI"
    credential_fields = ("api_key",)

    def models(self, credentials: dict | None = None) -> list[ModelInfo]:
        return [
            ModelInfo("gpt-4.1-mini", "GPT-4.1 mini — le plus rapide", pricing=OPENAI_GPT_41_MINI),
            ModelInfo("gpt-4.1", "GPT-4.1 — équilibré", pricing=OPENAI_GPT_41),
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
            raise LlmNotConfigured("clé API OpenAI absente ou vide")
        active = client or self._client(credentials, timeout=timeout, max_retries=max_retries)

        payload = [{"role": "system", "content": system}]
        payload += [{"role": m.role, "content": m.text} for m in messages]
        try:
            response = active.chat.completions.create(
                model=model, max_tokens=max_tokens, messages=payload
            )
        except Exception as exc:
            raise self._translate_error(exc) from None

        text = (response.choices[0].message.content or "").strip()
        if not text:
            raise LlmProviderUnavailable("réponse OpenAI vide")
        return text

    @staticmethod
    def _client(credentials: dict, timeout: float = DEFAULT_TIMEOUT, max_retries: int = DEFAULT_MAX_RETRIES):
        import openai

        return openai.OpenAI(
            api_key=credentials["api_key"], timeout=timeout, max_retries=max_retries
        )

    @staticmethod
    def _translate_error(exc: Exception) -> Exception:
        """Traduit une erreur du SDK en `LlmError` métier. Ne jamais reporter
        l'exception d'origine : elle peut porter la requête HTTP d'origine."""
        import openai

        if isinstance(exc, openai.APITimeoutError):
            return LlmTimeout("le fournisseur OpenAI n'a pas répondu à temps")
        if isinstance(exc, openai.AuthenticationError):
            return LlmAuthError("clé API OpenAI refusée")
        if isinstance(exc, openai.APIError):
            return LlmProviderUnavailable("le fournisseur OpenAI est indisponible")
        return exc


register(OpenAiConnector())
