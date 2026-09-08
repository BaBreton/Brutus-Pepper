"""Endpoints consommés par la webapp d'administration. Les secrets entrent, ne sortent jamais."""
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, Field, ValidationError
from typing import Annotated, Literal

from brain import greeting, hospitality
from brain.api.deps import AppState, require_admin
from brain.connectors import llm, stt
from brain.connectors import websearch
from brain.media import imagesearch
from brain.media.library import MAX_UPLOAD_BYTES

router = APIRouter(prefix="/api/admin")

# Sortis du corps de Profile : le champ « greeting » y masque le module du même nom,
# et toute ligne suivante lirait un FieldInfo au lieu des constantes.
GREETING_MAX_CHARS = greeting.MAX_CHARS
GREETING_MAX_VARIANTS = greeting.MAX_VARIANTS


class SectionUpdate(BaseModel):
    active: str | None = None
    model: str | None = None
    provider: str | None = None
    # Contact facultatif affiché dans le User-Agent Wikimedia. Cela peut être une
    # adresse e-mail ou l'URL du site de l'hôte ; il ne s'agit pas d'un secret.
    contact: str | None = Field(default=None, max_length=300)
    credentials: dict[str, dict[str, str]] | None = None


class ConnectorsUpdate(BaseModel):
    llm: SectionUpdate | None = None
    stt: SectionUpdate | None = None
    image_search: SectionUpdate | None = None


class Place(BaseModel):
    """Un lieu que Pepper peut indiquer : « la cuisine, au fond à votre droite »."""

    name: str = Field(default="", max_length=80)
    directions: str = Field(default="", max_length=300)
    # Côté du geste, distinct de la phrase libre que Pepper prononce.
    point_direction: Literal["", "left", "right"] = ""


class Profile(BaseModel):
    """Une fiche d'accueil : tout ce que Pepper doit savoir pour recevoir chez un hôte.

    L'hôte en prépare une par entreprise ou par lieu, et bascule de l'une à l'autre
    sans avoir à ressaisir ses consignes.
    """

    id: str = Field(default="", max_length=64)
    label: str = Field(default="", max_length=120)
    company: str = Field(default="", max_length=160)
    mission: str = Field(default="", max_length=500)
    visitors: list[Annotated[str, Field(max_length=120)]] = Field(default_factory=list, max_length=30)
    notes: str = Field(default="", max_length=6000)
    # Plafonné : tout ce qui entre ici part dans le prompt système à chaque réponse.
    places: list[Place] = Field(default_factory=list, max_length=40)
    idle_media: str = Field(default="", max_length=64)
    # LA phrase prononcée. Plus de duplication entre « imposée » et « composée » :
    # ce que l'hôte lit dans la zone de texte est ce que Pepper dit.
    greeting: str = Field(default="", max_length=GREETING_MAX_CHARS)
    # Réserve de propositions, pour choisir plutôt que subir.
    greeting_pool: list[Annotated[str, Field(max_length=GREETING_MAX_CHARS)]] = Field(
        default_factory=list, max_length=GREETING_MAX_VARIANTS)
    greeting_point: Literal["", "left", "right"] = ""
    active_visit: hospitality.VisitProfile | None = None


class HospitalityUpdate(BaseModel):
    active: bool = False
    active_profile: str = Field(default="", max_length=64)
    profiles: list[Profile] = Field(default_factory=list, max_length=30)


class GreetingRequest(BaseModel):
    """Demande de propositions d'accroche pour une fiche en cours d'édition."""

    profile: Profile = Field(default_factory=Profile)
    seed: str = Field(default="", max_length=GREETING_MAX_CHARS)
    count: int = Field(default=4, ge=1, le=GREETING_MAX_VARIANTS)


@router.get("/connectors")
def read_connectors(current: AppState = Depends(require_admin)) -> dict:
    settings = current.settings.load()
    return {
        "settings": current.settings.public(),
        "llm_available": llm.catalog(settings["llm"]["credentials"]),
        "stt_available": stt.catalog(settings["stt"]["credentials"]),
        # Le catalogue commun permet de choisir le même moteur pour le web et les
        # images, tout en conservant les installations historiques sans clé.
        "image_search_available": imagesearch.catalog(
            settings["image_search"]["credentials"]),
        "web_search_available": websearch.catalog(
            settings["image_search"]["credentials"]),
    }


@router.put("/connectors")
def write_connectors(body: ConnectorsUpdate, current: AppState = Depends(require_admin)) -> dict:
    for section in ("llm", "stt", "image_search"):
        update = getattr(body, section)
        if update is None:
            continue
        changes = {k: v for k, v in update.model_dump().items() if v is not None}
        if changes:
            current.settings.update_section(section, changes)
    return read_connectors(current)


@router.get("/hospitality")
def read_hospitality(current: AppState = Depends(require_admin)) -> dict:
    # Toujours passer par public() : aucune route ne renvoie load() brut, pour qu'un
    # champ secret ajouté ici un jour soit masqué sans qu'on ait à y repenser.
    return hospitality.normalise(current.settings.public()["hospitality"])


class SearchPreview(BaseModel):
    query: str = Field(min_length=1, max_length=200)


@router.post("/search/preview")
def search_preview(body: SearchPreview, request: Request,
                   current: AppState = Depends(require_admin)) -> dict:
    from brain.api.routes_robot import resolve_image

    result = resolve_image(request, body.query, current)
    # Same pipeline as Pepper, but an admin-only media URL and no pairing token.
    path = result["url"].split("/api/robot/", 1)[1]
    if result["source"] == "library":
        identifier = path.split("/")[1]
        result["url"] = "/api/admin/media/" + identifier + "/content"
    else:
        result["url"] = "/api/admin/" + path
    return result


@router.get("/image/{identifier}")
def preview_image_content(identifier: str, current: AppState = Depends(require_admin)) -> Response:
    try:
        data, mime = current.images.read(identifier)
    except LookupError:
        raise HTTPException(status_code=404, detail="Image absente de l’aperçu.") from None
    return Response(content=data, media_type=mime)


@router.put("/hospitality")
def write_hospitality(body: HospitalityUpdate, current: AppState = Depends(require_admin)) -> dict:
    """Enregistre les fiches. Ce qui est enregistré est ce qui sert : il n'y a pas
    d'état brouillon, et donc rien à valider en plus du bouton d'enregistrement."""
    card = body.model_dump()
    catalogue = current.media.list()
    seen: set[str] = set()
    for profile in card["profiles"]:
        # Un identifiant est attribué ici, pas côté navigateur : deux onglets ouverts
        # ne doivent pas pouvoir créer deux fiches qui se réclament la même.
        if not profile["id"] or profile["id"] in seen:
            profile["id"] = uuid.uuid4().hex[:12]
        seen.add(profile["id"])
        if not profile["label"].strip():
            profile["label"] = profile["company"].strip() or "Fiche sans nom"
        # Une image supprimée de la médiathèque laisserait un identifiant mort : la
        # tablette n'afficherait rien et personne ne saurait pourquoi.
        if profile["idle_media"] and hospitality.idle_image(catalogue, profile["idle_media"]) is None:
            raise HTTPException(status_code=422,
                                detail="L’image de la fiche « %s » n’est plus dans la médiathèque." % profile["label"])
    known = {profile["id"] for profile in card["profiles"]}
    if card["active_profile"] not in known:
        card["active_profile"] = next(iter(profile["id"] for profile in card["profiles"]), "")
    if card["active"] and not card["active_profile"]:
        raise HTTPException(status_code=422, detail="Créez une fiche avant d’activer l’accueil.")
    current.settings.update_section("hospitality", card)
    return current.settings.public()["hospitality"]


@router.post("/hospitality/greetings")
def suggest_greetings(body: GreetingRequest, current: AppState = Depends(require_admin)) -> dict:
    """Propose des accroches à partir de la fiche en cours d'édition.

    Ne touche à rien : l'opérateur choisit dans la webapp, puis enregistre.
    """
    settings = current.settings.load()
    active = settings["llm"]["active"]
    card = body.profile.model_dump()
    try:
        connector = llm.get(active) if active else None
    except KeyError:
        connector = None
    if connector is None:
        # Sans modèle configuré, on rend au moins l'accroche composée sans lui : mieux
        # vaut une phrase plate à retoucher qu'une zone de texte vide.
        return {"greetings": [greeting.fallback(card)], "generated": False}
    propositions = greeting.variants(
        card, connector,
        credentials=settings["llm"]["credentials"].get(active) or {},
        model=settings["llm"]["model"], seed=body.seed, count=body.count)
    if not propositions:
        return {"greetings": [greeting.fallback(card)], "generated": False}
    return {"greetings": propositions, "generated": True}


@router.post("/hospitality/preview")
def preview_hospitality(body: GreetingRequest, current: AppState = Depends(require_admin)) -> dict:
    """Ce que Pepper dirait avec cette fiche, sans appeler le fournisseur."""
    card = body.profile.model_dump()
    return {"greeting": str(card.get("greeting") or "").strip() or greeting.fallback(card)}


@router.post("/hospitality/research")
def research_hospitality(body: hospitality.ResearchRequest,
                         current: AppState = Depends(require_admin)) -> dict:
    try:
        return hospitality.research(body, current.settings.load())
    except websearch.WebSearchNotConfigured as error:
        raise HTTPException(status_code=503, detail=str(error)) from None
    except websearch.WebSearchAuthError:
        raise HTTPException(status_code=502, detail="Clé du fournisseur de recherche refusée. Vérifiez-la dans Voix et intelligence.") from None
    except websearch.WebSearchError:
        raise HTTPException(status_code=502, detail="Recherche indisponible. Réessayez ou remplissez la fiche manuellement.") from None


@router.get("/media")
def list_media(current: AppState = Depends(require_admin)) -> dict:
    return {"items": current.media.list()}


@router.put("/media", status_code=201)
async def upload_media(request: Request, name: str = "",
                       current: AppState = Depends(require_admin)) -> dict:
    """Corps = octets bruts du média, type annoncé par l'en-tête Content-Type.
    Le contenu est vérifié contre le type annoncé : un client peut téléverser
    n'importe quoi en le déclarant image/png."""
    data = await request.body()
    if not data or len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="taille de fichier invalide")
    content_type = request.headers.get("content-type", "").split(";")[0].strip()
    try:
        return current.media.add_bytes(name, content_type, data)
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from None


@router.get("/media/{media_id}/content")
def media_content(media_id: str, current: AppState = Depends(require_admin)) -> Response:
    """Aperçu pour la webapp. Elle le récupère en fetch avec l'en-tête d'autorisation
    puis le convertit en blob : un <img src> ne peut pas porter d'en-tête, et mettre
    le jeton dans l'URL le ferait fuiter dans les journaux."""
    try:
        item, path = current.media.content(media_id)
    except LookupError as error:
        raise HTTPException(status_code=404, detail=str(error)) from None
    return Response(content=path.read_bytes(), media_type=item["mime"])


@router.delete("/media/{media_id}")
def delete_media(media_id: str, current: AppState = Depends(require_admin)) -> dict:
    try:
        current.media.delete(media_id)
    except LookupError as error:
        raise HTTPException(status_code=404, detail=str(error)) from None
    return {"deleted": True}


@router.get("/pairing")
def read_pairing(current: AppState = Depends(require_admin)) -> dict:
    return {"pairing_token": current.tokens.pairing_token}


@router.post("/pairing")
def rotate_pairing(current: AppState = Depends(require_admin)) -> dict:
    """Régénère le jeton : tous les robots devront être ré-appairés."""
    return {"pairing_token": current.tokens.rotate_pairing()}
