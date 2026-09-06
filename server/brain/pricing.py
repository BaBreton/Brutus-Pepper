"""Métadonnées publiques des fournisseurs et de leurs tarifs indicatifs.

Les montants sont exprimés en dollars US, tels qu'affichés par les pages tarifaires
officielles vérifiées le 6 septembre 2026. Ils servent à orienter le choix dans la
webapp, pas à remplacer la facture du fournisseur : AWS varie selon la région et
les plans, et les tarifs peuvent évoluer.
"""
from dataclasses import dataclass


PRICING_CHECKED = "2026-09-06"


@dataclass(frozen=True)
class Pricing:
    """Tarif public sérialisable sans secret."""

    unit: str
    input_usd_per_million: float | None = None
    output_usd_per_million: float | None = None
    usd_per_minute: float | None = None
    note: str = ""
    source_url: str = ""

    def public(self) -> dict:
        result = {
            "unit": self.unit,
            "checked_at": PRICING_CHECKED,
            "note": self.note,
            "source_url": self.source_url,
        }
        if self.input_usd_per_million is not None:
            result["input_usd_per_million"] = self.input_usd_per_million
        if self.output_usd_per_million is not None:
            result["output_usd_per_million"] = self.output_usd_per_million
        if self.usd_per_minute is not None:
            result["usd_per_minute"] = self.usd_per_minute
        return result


OPENAI_GPT_41_MINI = Pricing(
    "1M tokens",
    input_usd_per_million=0.40,
    output_usd_per_million=1.60,
    source_url="https://developers.openai.com/api/docs/models/gpt-4.1-mini",
)
OPENAI_GPT_41 = Pricing(
    "1M tokens",
    input_usd_per_million=2.00,
    output_usd_per_million=8.00,
    source_url="https://developers.openai.com/api/docs/models/gpt-4.1",
)

ANTHROPIC_HAIKU_45 = Pricing(
    "1M tokens",
    input_usd_per_million=1.00,
    output_usd_per_million=5.00,
    source_url="https://platform.claude.com/docs/en/about-claude/pricing",
)
ANTHROPIC_SONNET_5 = Pricing(
    "1M tokens",
    input_usd_per_million=2.00,
    output_usd_per_million=10.00,
    source_url="https://platform.claude.com/docs/en/about-claude/pricing",
)
ANTHROPIC_OPUS_5 = Pricing(
    "1M tokens",
    input_usd_per_million=5.00,
    output_usd_per_million=25.00,
    source_url="https://platform.claude.com/docs/en/about-claude/pricing",
)

OPENAI_LIVE_TRANSCRIBE = Pricing(
    "minute",
    usd_per_minute=0.017,
    note="Tarif audio temps réel affiché par OpenAI.",
    source_url="https://developers.openai.com/api/docs/models/gpt-live-transcribe",
)
OPENAI_MINI_TRANSCRIBE = Pricing(
    "1M audio tokens",
    input_usd_per_million=1.25,
    output_usd_per_million=5.00,
    note="Le fournisseur facture des tokens audio ; il ne faut pas convertir arbitrairement en minutes.",
    source_url="https://developers.openai.com/api/docs/models/gpt-4o-mini-transcribe",
)
OPENAI_TRANSCRIBE = Pricing(
    "1M audio tokens",
    input_usd_per_million=2.50,
    output_usd_per_million=10.00,
    note="Le fournisseur facture des tokens audio ; il ne faut pas convertir arbitrairement en minutes.",
    source_url="https://developers.openai.com/api/docs/models/gpt-4o-transcribe",
)
OPENAI_WHISPER = Pricing(
    "minute",
    usd_per_minute=0.006,
    source_url="https://developers.openai.com/api/docs/models/whisper-1",
)
AWS_TRANSCRIBE_STREAMING = Pricing(
    "minute",
    usd_per_minute=0.010,
    note="Exemple de tarif Standard Streaming en us-east-1 ; AWS applique le tarif de la région et du palier du compte.",
    source_url="https://aws.amazon.com/transcribe/pricing/",
)

LOCAL_WHISPER = Pricing(
    "local",
    note="Pas de coût d'API. Prévoir seulement la machine, l'électricité et le téléchargement initial du modèle.",
    source_url="https://github.com/SYSTRAN/faster-whisper",
)
WIKIMEDIA = Pricing(
    "free",
    note="Pas de clé ni de coût d'API dans ce produit ; respecter les licences, quotas et conditions de réutilisation.",
    source_url="https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia",
)
BRAVE = Pricing(
    "request",
    note="$5 / 1 000 recherches ; $5 de crédits gratuits chaque mois selon l'offre Search actuelle.",
    source_url="https://brave.com/search/api/",
)
SERPER = Pricing(
    "request",
    note="$0,001 / recherche au pack Starter : $50 pour 50 000 crédits, valables 6 mois. "
         "2 500 recherches offertes à l'inscription, sans carte. Hors taxes ; vérifier l'offre du compte.",
    source_url="https://serper.dev/",
)
BEDROCK_VARIABLE = Pricing(
    "1M tokens",
    note="Tarif variable selon le modèle, la région, le type d'endpoint et le niveau de service AWS. Vérifier le modèle choisi dans AWS.",
    source_url="https://aws.amazon.com/bedrock/pricing/",
)


_CREDENTIAL_LABELS = {
    "language": ("Langue des résultats", False, "fr (par défaut), en…"),
    "country": ("Pays des résultats", False, "fr (par défaut), be, ch…"),
    "api_key": ("Clé API", True, "Collez la clé API"),
    "access_key": ("Access key AWS", True, "AKIA…"),
    "secret_key": ("Secret key AWS", True, "Clé secrète AWS"),
    "region": ("Région AWS", False, "ex. eu-west-3"),
}


def credential_fields(names: tuple[str, ...] | list[str]) -> list[dict]:
    """Décrit les champs que la webapp doit afficher pour un fournisseur."""
    result = []
    for name in names:
        label, secret, placeholder = _CREDENTIAL_LABELS.get(
            name, (name, False, "")
        )
        result.append(
            {
                "id": name,
                "label": label,
                "secret": secret,
                "placeholder": placeholder,
            }
        )
    return result
