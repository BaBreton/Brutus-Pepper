"""Endpoints consommés par la tablette Pepper."""
import asyncio
import logging

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, Field, field_validator

from brain import config, hospitality, prompt
from brain.api.deps import AppState, require_robot
from brain.connectors import llm, stt, websearch
from brain.media import imagesearch

router = APIRouter(prefix="/api/robot")

MAX_HISTORY = 20
MAX_MESSAGE_CHARS = 4000
MAX_AUDIO_BYTES = 20 * 1024 * 1024
# Recherche sans clé ; les licences Commons restent applicables.
DEFAULT_IMAGE_PROVIDER = "wikimedia"
# Nombre de candidats tentés avant d'abandonner. Au-delà, l'attente devient plus
# gênante pour le visiteur que l'absence d'image.
MAX_IMAGE_CANDIDATES = 4

# Chaque erreur de connecteur porte un message rédigé pour l'installateur ; on ne
# relaie que ce message, jamais l'exception d'origine.
STATUS_FOR = {
    llm.LlmNotConfigured: 503,
    llm.LlmAuthError: 502,
    llm.LlmTimeout: 504,
    llm.LlmProviderUnavailable: 502,
    llm.LlmError: 502,
}


class ChatMessage(BaseModel):
    role: str
    text: str = Field(min_length=1, max_length=MAX_MESSAGE_CHARS)

    @field_validator("role")
    @classmethod
    def role_is_known(cls, value: str) -> str:
        if value not in ("user", "assistant"):
            raise ValueError("rôle invalide")
        return value

    @field_validator("text")
    @classmethod
    def text_is_not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("texte vide")
        return value


class ChatRequest(BaseModel):
    messages: list[ChatMessage] = Field(min_length=1, max_length=MAX_HISTORY)


@router.get("/hello")
def hello(current: AppState = Depends(require_robot)) -> dict:
    """Handshake : ce que le cerveau sait faire, et avec quoi."""
    settings = current.settings.load()
    return {
        "name": "Pepper Brain",
        "llm": {
            "active": settings["llm"]["active"],
            "model": settings["llm"]["model"],
            "available": llm.catalog(settings["llm"]["credentials"]),
        },
        "stt": {
            "active": settings["stt"]["active"],
            "model": settings["stt"]["model"],
            "available": stt.catalog(settings["stt"]["credentials"]),
        },
        "media_count": len(current.media.list()),
    }


@router.get("/greeting")
def greeting(current: AppState = Depends(require_robot)) -> dict:
    """Accroche à prononcer quand Pepper voit quelqu'un arriver.

    Appelée à chaque engagement, donc elle doit répondre tout de suite : la phrase a
    été rédigée à l'enregistrement des réglages, on ne fait que la relire. `active` à
    faux signifie « reprends ton accueil habituel », et non « je n'ai rien à dire ».
    """
    card = hospitality.active_card(current.settings.load()["hospitality"])
    if not card.get("active"):
        return {"active": False, "speech": "", "actions": []}
    # Ce que l'hôte a écrit dans la zone de texte, mot pour mot. Le serveur ne rédige
    # plus au dernier moment : il relit.
    speech = str(card.get("greeting", "")).strip()
    # Un côté choisi explicitement l'emporte : l'opérateur qui écrit « l'accueil est
    # sur votre gauche » sans nommer un lieu déclaré doit pouvoir obtenir le geste.
    # Sinon on déduit du lieu que l'accroche nomme — la phrase réellement prononcée,
    # imposée ou rédigée, pas la fiche.
    direction = str(card.get("greeting_point", "")).strip().lower()
    if direction not in ("left", "right"):
        place = hospitality.place_named_in(speech, card.get("places"))
        direction = str(place["point_direction"]).strip().lower() if place else ""
    actions = [{"name": "point_" + direction, "arguments": {}}] if direction else []
    return {"active": True, "speech": speech, "actions": actions}


@router.get("/idle-image")
def idle_image(request: Request, current: AppState = Depends(require_robot)) -> dict:
    """L'image que la tablette affiche en boucle tant que personne ne parle à Pepper.

    Interrogée régulièrement par la tablette : changer l'image — ou couper
    l'hospitalité — depuis la webapp doit se voir sans redémarrer le robot. Une URL
    vide veut dire « rends la tablette à son interface », pas « garde la précédente ».
    """
    card = hospitality.active_card(current.settings.load()["hospitality"])
    item = hospitality.idle_image(current.media.list(), card.get("idle_media", "")) \
        if card.get("active") else None
    if item is None:
        return {"id": "", "url": "", "name": ""}
    base = str(request.base_url).rstrip("/")
    return {"id": item["id"], "url": base + item["url"], "name": item["name"]}


@router.post("/chat")
async def chat(body: ChatRequest, current: AppState = Depends(require_robot)) -> dict:
    settings = current.settings.load()
    active = settings["llm"]["active"]
    if not active:
        raise HTTPException(status_code=503, detail="aucun connecteur LLM configuré")
    try:
        connector = llm.get(active)
    except KeyError:
        raise HTTPException(
            status_code=503, detail="connecteur LLM « %s » inconnu" % active) from None

    searcher, search_credentials = _web_search(settings)
    card = hospitality.active_card(settings["hospitality"])
    system = prompt.build_system_prompt(
        catalog=current.media.list(),
        hospitality=card,
        can_search=searcher is not None,
    )
    credentials = settings["llm"]["credentials"].get(active) or {}
    model = settings["llm"]["model"]
    messages = [llm.Message(m.role, m.text.strip()) for m in body.messages]

    async def ask(history: list) -> str:
        # Même raison que pour la transcription : un appel fournisseur de plusieurs
        # secondes ne doit pas figer la boucle d'événements.
        return await asyncio.to_thread(
            connector.complete,
            credentials=credentials, model=model, system=system, messages=history,
        )

    try:
        text = await ask(messages)
        query = prompt.wanted_search(text) if searcher is not None else ""
        if query:
            # Un seul tour de recherche, jamais deux : le visiteur attend devant le
            # robot, et un modèle qui redemanderait à chercher boucherait l'échange.
            text = await _answer_with_search(
                searcher, search_credentials, query, messages, ask)
    except llm.LlmError as error:
        logging.warning("conversation refusée par %s : %s", active, error)
        status = STATUS_FOR.get(type(error), 502)
        raise HTTPException(status_code=status, detail=str(error)) from None
    except Exception:
        # Filet de sécurité : on journalise la trace côté serveur mais on ne renvoie
        # JAMAIS l'exception d'origine — la clé API vit dans e.request.headers et
        # httpx ne masque pas l'en-tête x-api-key.
        logging.exception("erreur inattendue du connecteur LLM %s", active)
        raise HTTPException(status_code=502, detail="le fournisseur LLM a échoué") from None
    latest_user_text = next(
        (message.text for message in reversed(body.messages) if message.role == "user"),
        "",
    )
    text = hospitality.add_pointing_action(
        text, latest_user_text, card.get("places") or [],
        active=bool(card.get("active")),
    )
    return {"response": text}


def _web_search(settings: dict):
    """Le fournisseur de recherche configuré, ou (None, {}) s'il n'y en a pas.

    Serper ou Brave sert aux images et au web avec la même clé, saisie une fois.
    """
    return websearch.configured(settings)


async def _answer_with_search(searcher, credentials, query, messages, ask) -> str:
    """Cherche, puis fait répondre le modèle avec ce qu'on a trouvé.

    Un échec de recherche n'est pas une panne de conversation : on le journalise et on
    laisse le modèle répondre sans, quitte à dire qu'il ne sait pas.
    """
    try:
        results = await asyncio.to_thread(searcher.search, credentials, query)
    except websearch.WebSearchError as error:
        logging.warning("recherche web refusée : %s", error)
        results = []
    except Exception:
        logging.exception("erreur inattendue de la recherche web")
        results = []

    rendered = websearch.render(results)
    # Les résultats entrent comme un message utilisateur, pas comme une consigne : une
    # page web ne doit pas pouvoir dicter au robot ce qu'il doit dire.
    found = ("Résultats de recherche pour « %s » :\n%s" % (query, rendered)) if rendered \
        else "La recherche pour « %s » n'a rien donné." % query
    return await ask(messages + [llm.Message("assistant", "Je recherche les informations demandées."),
                                 llm.Message("user", found)])


@router.post("/transcribe")
async def transcribe(request: Request, current: AppState = Depends(require_robot)) -> dict:
    """Corps = PCM 16 bits mono brut, 16 kHz. Pas de WAV : la tablette envoie ses samples."""
    pcm = await request.body()
    if not pcm or len(pcm) > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=413, detail="taille audio invalide")

    settings = current.settings.load()
    active = settings["stt"]["active"]
    try:
        connector = stt.get(active)
    except KeyError:
        raise HTTPException(
            status_code=503, detail="connecteur STT « %s » inconnu" % active) from None

    try:
        # Dans un fil séparé, pour deux raisons : l'inférence Whisper occupe le
        # processeur plusieurs secondes et figerait toute la boucle d'événements —
        # webapp comprise — et les connecteurs cloud appellent asyncio.run(), ce qui
        # est interdit depuis une boucle déjà en cours.
        text = await asyncio.to_thread(
            connector.transcribe,
            credentials=settings["stt"]["credentials"].get(active) or {},
            pcm16=pcm,
            sample_rate=16000,
            language=config.LANGUAGE,
            model=settings["stt"]["model"],
        )
    except stt.SttAudioTooShort as error:
        raise HTTPException(status_code=422, detail=str(error)) from None
    except stt.SttError as error:
        # Journalisé même si l'erreur est « attendue » : sans trace côté serveur,
        # l'installateur n'a aucun moyen de diagnostiquer un identifiant refusé.
        logging.warning("transcription refusée par %s : %s", active, error)
        raise HTTPException(status_code=503, detail=str(error)) from None
    except Exception:
        logging.exception("erreur inattendue du connecteur STT %s", active)
        raise HTTPException(status_code=502, detail="la transcription a échoué") from None
    return {"text": text}


@router.get("/image")
def resolve_image(request: Request, q: str = "",
                  current: AppState = Depends(require_robot)) -> dict:
    """Résout « montre-moi X ». La médiathèque du client passe toujours avant le web :
    une image qu'il a lui-même déposée doit l'emporter sur un résultat de recherche."""
    query = q.strip()
    if not query:
        raise HTTPException(status_code=422, detail="requête d'image vide")

    try:
        item = current.media.resolve(query)
        base = str(request.base_url).rstrip("/")
        return {"url": base + item["url"], "title": item["name"], "source": "library"}
    except LookupError:
        pass  # rien dans la bibliothèque : on tente le web

    settings = current.settings.load()["image_search"]
    provider_id = settings.get("provider") or DEFAULT_IMAGE_PROVIDER
    try:
        provider = imagesearch.get(provider_id)
    except KeyError:
        raise HTTPException(
            status_code=503,
            detail="fournisseur de recherche d'image « %s » inconnu" % provider_id) from None

    try:
        contact = str(settings.get("contact") or "").strip()
        search_options = {"contact": contact} if contact else {}
        found_all = provider.candidates(
            settings.get("credentials", {}).get(provider_id) or {}, query,
            **search_options)
    except LookupError as error:
        raise HTTPException(status_code=404, detail=str(error)) from None
    except imagesearch.ImageSearchError as error:
        logging.warning("recherche d'image refusée par %s : %s", provider_id, error)
        raise HTTPException(status_code=503, detail=str(error)) from None
    except Exception:
        logging.exception("erreur inattendue de la recherche d'image %s", provider_id)
        raise HTTPException(status_code=502, detail="la recherche d'image a échoué") from None

    # Le cerveau télécharge lui-même : la tablette tourne sous Android 6 et échoue
    # sur les sites qui exigent un User-Agent — Wikimedia répond 403 à celui de Java.
    # On essaie les candidats dans l'ordre : un téléchargement qui échoue ne doit pas
    # laisser le robot sans rien à montrer alors qu'une autre image ferait l'affaire.
    base = str(request.base_url).rstrip("/")
    last_error = "aucun candidat exploitable"
    for candidate in found_all[:MAX_IMAGE_CANDIDATES]:
        try:
            identifier, _mime = current.images.fetch(candidate.url, referer=candidate.referer)
        except Exception as error:
            last_error = str(error)
            logging.warning("candidat « %s » écarté : %s", candidate.url, error)
            continue
        return {"url": "%s/api/robot/image/%s" % (base, identifier),
                "title": candidate.title, "source": candidate.source}

    logging.warning("aucun des %d candidats n'a pu être téléchargé pour « %s »",
                    len(found_all[:MAX_IMAGE_CANDIDATES]), query)
    raise HTTPException(status_code=502, detail="image trouvée mais illisible : %s" % last_error)


@router.get("/image/{identifier}")
def image_content(identifier: str, current: AppState = Depends(require_robot)) -> Response:
    try:
        data, mime = current.images.read(identifier)
    except LookupError as error:
        raise HTTPException(status_code=404, detail=str(error)) from None
    return Response(content=data, media_type=mime)


@router.get("/media/resolve")
def resolve_media(q: str = "", current: AppState = Depends(require_robot)) -> dict:
    try:
        return current.media.resolve(q)
    except LookupError as error:
        raise HTTPException(status_code=404, detail=str(error)) from None


@router.get("/media/{media_id}/content")
def media_content(media_id: str, current: AppState = Depends(require_robot)) -> Response:
    try:
        item, path = current.media.content(media_id)
    except LookupError as error:
        raise HTTPException(status_code=404, detail=str(error)) from None
    return Response(content=path.read_bytes(), media_type=item["mime"])
