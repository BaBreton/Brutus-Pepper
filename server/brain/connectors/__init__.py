"""Importer les connecteurs enregistre leur instance dans le registre.
Retirer une ligne retire le connecteur du produit."""
from brain.connectors import llm_anthropic  # noqa: F401
from brain.connectors import llm_bedrock  # noqa: F401
from brain.connectors import llm_openai  # noqa: F401
from brain.connectors import stt_whisper_local  # noqa: F401
from brain.connectors import stt_cloud  # noqa: F401
