"""Accroche d'accueil du mode hospitalité.

L'opérateur écrit sa consigne en s'adressant à Pepper — « accueille les clients et
envoie-les vers la cuisine ». Ce n'est pas une phrase à dire : il faut la retourner en
phrase d'accueil. C'est le rôle de ce module.

Elle est composée une fois, à l'enregistrement des réglages, et rangée telle quelle.
Pas au moment où quelqu'un entre : un appel au fournisseur coûte une à deux secondes,
et laisser un visiteur devant un robot muet le temps de rédiger serait absurde. Ça a
aussi le mérite de rendre l'accroche prévisible, et de la montrer à l'opérateur avant
qu'un visiteur ne l'entende.
"""
import logging
import re

from brain.connectors import llm
from brain.prompt import clean_places

# Deux phrases lues à voix haute. Au-delà, on coupe : un modèle bavard ne doit pas
# transformer l'accueil en monologue.
MAX_CHARS = 240

INSTRUCTION = (
    "Tu rédiges la phrase d'accueil d'un robot placé à l'entrée. Elle est prononcée "
    "à voix haute dès qu'il voit quelqu'un arriver, avant tout échange.\n"
    "Contraintes : commence par une salutation de bienvenue ; UNE ou DEUX phrases, "
    "pas plus ; ton chaleureux et naturel ; aucune mise en forme, aucun guillemet ; "
    "si un lieu est indiqué ci-dessous, donne son orientation mot pour mot.\n"
    "Ne suppose pas l’identité ni l’entreprise de la personne qui arrive. "
    "Ne récite pas de notes privées ni l’objectif du rendez-vous.\n"
    "Réponds UNIQUEMENT par la phrase, sans commentaire."
)


def fallback(hospitality: dict) -> str:
    """Accroche composée sans modèle.

    Sert quand le fournisseur est injoignable au moment où l'opérateur enregistre :
    l'hospitalité doit pouvoir s'activer quand même, quitte à sonner plus plate.
    """
    company = str(hospitality.get("company", "")).strip()
    places = clean_places(hospitality.get("places"))

    welcome = "Bienvenue chez %s !" % company if company else "Bienvenue !"
    lines = ["%s Je suis Pepper." % welcome]
    if places:
        # Le premier lieu seulement : réciter tout l'annuaire à quelqu'un qui vient
        # d'entrer ne sert personne.
        first = places[0]
        lines.append("Pour %s, c'est %s." % (first["name"], first["directions"]))
    lines.append("Comment puis-je vous aider ?")
    return " ".join(lines)


def generate(hospitality: dict, connector, credentials: dict, model: str) -> str:
    """Fait rédiger l'accroche par le modèle, avec repli sur `fallback`."""
    try:
        raw = connector.complete(
            credentials=credentials,
            model=model,
            system=INSTRUCTION,
            messages=[llm.Message("user", _brief(hospitality))],
        )
    except llm.LlmError as error:
        logging.warning("accroche d'hospitalité non rédigée par le modèle : %s", error)
        return fallback(hospitality)
    except Exception:
        # Même filet que dans les routes : on journalise, on ne propage jamais
        # l'exception d'origine, qui peut porter la clé API dans ses en-têtes.
        logging.exception("erreur inattendue en rédigeant l'accroche d'hospitalité")
        return fallback(hospitality)

    text = _tidy(raw)
    return text or fallback(hospitality)


VARIANTS_INSTRUCTION = (
    "Tu proposes plusieurs phrases d'accueil pour un robot placé à l'entrée.\n"
    "Contraintes pour CHACUNE : une salutation de bienvenue ; UNE ou DEUX phrases ; "
    "ton chaleureux et naturel ; aucune mise en forme, aucun guillemet, aucune "
    "numérotation ; si un lieu est indiqué, reprends son orientation mot pour mot.\n"
    "Ne suppose ni l'identité ni l'entreprise de la personne qui arrive. "
    "Ne récite pas de notes privées ni l'objectif du rendez-vous.\n"
    "Réponds UNIQUEMENT par les phrases, une par ligne, sans rien d'autre."
)

MAX_VARIANTS = 6


def variants(hospitality: dict, connector, credentials: dict, model: str,
             seed: str = "", count: int = 4) -> list[str]:
    """Propose plusieurs accroches, à ranger dans la réserve de l'opérateur.

    Il choisit ensuite celle qui lui plaît plutôt que de subir la seule qu'un modèle
    aurait rendue. `seed` permet de demander des variations autour d'une phrase déjà
    écrite, ce qui est le cas courant : on tient presque la bonne formulation.

    Un échec ne renvoie jamais d'exception : sans modèle joignable, l'opérateur garde
    l'accroche composée sans lui, et la webapp le lui dit.
    """
    count = max(1, min(int(count), MAX_VARIANTS))
    demande = _brief(hospitality)
    if str(seed or "").strip():
        demande += "\nPhrase de départ à faire varier, sans la recopier telle quelle :\n%s" % seed.strip()
    demande += "\nPropose %d phrases différentes, une par ligne." % count
    try:
        raw = connector.complete(
            credentials=credentials, model=model,
            system=VARIANTS_INSTRUCTION, messages=[llm.Message("user", demande)],
        )
    except llm.LlmError as error:
        logging.warning("variantes d'accroche non rédigées par le modèle : %s", error)
        return []
    except Exception:
        logging.exception("erreur inattendue en proposant des accroches")
        return []

    propositions = []
    for ligne in str(raw or "").splitlines():
        # Le modèle numérote ou puce parfois malgré la consigne.
        texte = _tidy(re.sub(r"^\s*(?:[-*\u2022]|\d+[.)])\s*", "", ligne))
        if texte and texte not in propositions:
            propositions.append(texte)
    return propositions[:count]


def _brief(hospitality: dict) -> str:
    """Ce que le modèle doit savoir pour rédiger. Sans la consigne ni les lieux,
    l'accroche ne peut pas les mentionner."""
    company = str(hospitality.get("company", "")).strip()
    mission = str(hospitality.get("mission", "")).strip()
    places = clean_places(hospitality.get("places"))

    lines = []
    if company:
        lines.append("Société qui accueille : %s" % company)
    if mission:
        lines.append("Consigne de l'opérateur : %s" % mission)
    if places:
        lines.append("Lieux et leur orientation exacte :")
        lines.extend("- %s : %s" % (p["name"], p["directions"]) for p in places)
    if not lines:
        lines.append("Aucune consigne particulière : accueille simplement le visiteur.")
    return "\n".join(lines)


def _tidy(raw) -> str:
    text = str(raw or "").strip()
    # Les modèles rendent volontiers leur phrase entre guillemets ; Pepper les lirait.
    for pair in ('""', "''", "«»", "“”"):
        if len(text) >= 2 and text[0] == pair[0] and text[-1] == pair[1]:
            text = text[1:-1].strip()
    if len(text) > MAX_CHARS:
        text = text[:MAX_CHARS].rsplit(" ", 1)[0].rstrip(" ,;:") + "…"
    return text
