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
from brain.pricing import OPENAI_GPT_56_LUNA, OPENAI_GPT_56_TERRA

# Devant un robot d'accueil, tout ce qui dépasse une dizaine de secondes est
# déjà un échec produit : on coupe court plutôt que de laisser le SDK attendre
# 10 minutes et retenter deux fois par défaut.
DEFAULT_TIMEOUT = 15.0
DEFAULT_MAX_RETRIES = 1

# Le plafond de jetons a changé de nom : les modèles GPT-5 et suivants refusent
# `max_tokens` sur Chat Completions et exigent `max_completion_tokens`, tandis que
# les GPT-4.x ne connaissent que l'ancien nom. Le modèle étant saisi dans la webapp,
# on tranche sur son identifiant — et on rattrape en dernier recours (voir complete).
NEWER_TOKEN_LIMIT_PREFIXES = ("gpt-5", "gpt-6", "o1", "o3", "o4")


def token_limit_field(model: str) -> str:
    """Le nom du plafond de jetons attendu par ce modèle."""
    name = str(model or "").strip().lower()
    return "max_completion_tokens" if name.startswith(NEWER_TOKEN_LIMIT_PREFIXES) else "max_tokens"


def rejects_token_limit_name(error: Exception) -> bool:
    """Vrai quand le fournisseur refuse l'orthographe employée pour le plafond.

    On lit le message plutôt que le type d'exception : le nom de la classe change
    d'une version du SDK à l'autre, alors que ce message est celui que l'API
    renvoie. Un faux positif ne coûte qu'un second appel.
    """
    text = str(error).lower()
    return "unsupported parameter" in text and (
        "max_tokens" in text or "max_completion_tokens" in text)


class OpenAiConnector(LlmConnector):
    id = "openai"
    label = "OpenAI"
    credential_fields = ("api_key",)

    def models(self, credentials: dict | None = None) -> list[ModelInfo]:
        return [
            ModelInfo("gpt-5.6-luna", "GPT-5.6 Luna — le plus rapide",
                      pricing=OPENAI_GPT_56_LUNA),
            ModelInfo("gpt-5.6-terra", "GPT-5.6 Terra — équilibré",
                      pricing=OPENAI_GPT_56_TERRA),
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

        field = token_limit_field(model)
        try:
            response = self._create(active, model, payload, field, max_tokens)
        except Exception as exc:
            # OpenAI renomme ses modèles souvent, et le modèle est libre dans la
            # webapp : un identifiant que la liste de préfixes ne connaît pas ne doit
            # pas laisser un visiteur sans réponse. On réessaie une fois avec l'autre
            # orthographe, celle que le fournisseur vient précisément de réclamer.
            if not rejects_token_limit_name(exc):
                raise self._translate_error(exc) from None
            other = "max_tokens" if field == "max_completion_tokens" else "max_completion_tokens"
            try:
                response = self._create(active, model, payload, other, max_tokens)
            except Exception as retry_error:
                raise self._translate_error(retry_error) from None

        text = (response.choices[0].message.content or "").strip()
        if not text:
            raise LlmProviderUnavailable("réponse OpenAI vide")
        return text

    @staticmethod
    def _create(client, model: str, payload: list, token_limit_field: str, max_tokens: int):
        return client.chat.completions.create(
            model=model, messages=payload, **{token_limit_field: max_tokens}
        )

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
