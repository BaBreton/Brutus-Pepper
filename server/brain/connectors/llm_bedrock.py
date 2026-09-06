"""Connecteur AWS Bedrock (API Converse). Retirable avant livraison client :
supprimer son import de connectors/__init__.py suffit."""
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
from brain.pricing import BEDROCK_VARIABLE

# Devant un robot d'accueil, tout ce qui dépasse une dizaine de secondes est
# déjà un échec produit : on coupe court plutôt que de laisser le SDK attendre
# les délais par défaut de botocore.
DEFAULT_TIMEOUT = 15.0
DEFAULT_MAX_RETRIES = 1

# Préfixe du profil d'inférence Bedrock selon la région : coder "eu." en dur
# ferait échouer les trois modèles dès qu'un client déploie hors zone eu-*.
_REGION_PREFIXES = {"eu": "eu.", "us": "us.", "ap": "apac."}


def _profile_prefix(credentials: dict | None) -> str:
    region = str((credentials or {}).get("region", "")).strip().lower()
    zone = region.split("-", 1)[0]
    return _REGION_PREFIXES.get(zone, "us.")


class BedrockConnector(LlmConnector):
    id = "bedrock"
    label = "AWS Bedrock"
    credential_fields = ("access_key", "secret_key", "region")

    def models(self, credentials: dict | None = None) -> list[ModelInfo]:
        prefix = _profile_prefix(credentials)
        return [
            ModelInfo(f"{prefix}anthropic.claude-haiku-4-5-20251001-v1:0", "Claude Haiku 4.5 (Bedrock)", pricing=BEDROCK_VARIABLE),
            ModelInfo(f"{prefix}amazon.nova-lite-v1:0", "Nova Lite (Bedrock)", pricing=BEDROCK_VARIABLE),
            ModelInfo(f"{prefix}amazon.nova-pro-v1:0", "Nova Pro (Bedrock)", pricing=BEDROCK_VARIABLE),
        ]

    def is_configured(self, credentials: dict) -> bool:
        return bool(
            str(credentials.get("access_key", "")).strip()
            and str(credentials.get("secret_key", "")).strip()
            and str(credentials.get("region", "")).strip()
        )

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
            raise LlmNotConfigured("identifiants AWS Bedrock absents ou incomplets")
        active = client or self._client(credentials, timeout=timeout, max_retries=max_retries)

        try:
            response = active.converse(
                modelId=model,
                system=[{"text": system}],
                messages=[{"role": m.role, "content": [{"text": m.text}]} for m in messages],
                inferenceConfig={"maxTokens": max_tokens},
            )
        except Exception as exc:
            raise self._translate_error(exc) from None

        blocks = response.get("output", {}).get("message", {}).get("content", [])
        text = "".join(
            block["text"] for block in blocks if isinstance(block, dict) and "text" in block
        ).strip()
        if not text:
            raise LlmProviderUnavailable("réponse Bedrock vide")
        return text

    @staticmethod
    def _client(credentials: dict, timeout: float = DEFAULT_TIMEOUT, max_retries: int = DEFAULT_MAX_RETRIES):
        import boto3
        from botocore.config import Config

        return boto3.client(
            "bedrock-runtime",
            region_name=credentials["region"],
            aws_access_key_id=credentials["access_key"],
            aws_secret_access_key=credentials["secret_key"],
            config=Config(
                connect_timeout=timeout,
                read_timeout=timeout,
                retries={"max_attempts": max_retries, "mode": "standard"},
            ),
        )

    @staticmethod
    def _translate_error(exc: Exception) -> Exception:
        """Traduit une erreur botocore en `LlmError` métier. Ne jamais reporter
        l'exception d'origine : elle peut porter la requête AWS signée."""
        import botocore.exceptions as boto_errors

        if isinstance(exc, (boto_errors.ConnectTimeoutError, boto_errors.ReadTimeoutError)):
            return LlmTimeout("le fournisseur AWS Bedrock n'a pas répondu à temps")
        if isinstance(exc, boto_errors.ClientError):
            code = exc.response.get("Error", {}).get("Code", "")
            if code in (
                "UnrecognizedClientException",
                "AccessDeniedException",
                "InvalidSignatureException",
                "ExpiredTokenException",
            ):
                return LlmAuthError("identifiants AWS Bedrock refusés")
            return LlmProviderUnavailable("le fournisseur AWS Bedrock est indisponible")
        if isinstance(exc, boto_errors.BotoCoreError):
            return LlmProviderUnavailable("le fournisseur AWS Bedrock est indisponible")
        return exc


register(BedrockConnector())
