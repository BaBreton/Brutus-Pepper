"""Dépendances FastAPI partagées : état de l'application et contrôle des jetons."""
from dataclasses import dataclass
from pathlib import Path

from fastapi import Header, HTTPException, Request

from brain.auth import TokenStore
from brain.media.imagecache import ImageCache
from brain.media.library import MediaLibrary
from brain.settings import SettingsStore


@dataclass
class AppState:
    settings: SettingsStore
    tokens: TokenStore
    media: MediaLibrary
    images: ImageCache


def build_state(root: Path) -> AppState:
    """Une seule instance de chaque store par application : le verrou d'écriture de
    SettingsStore est un verrou d'instance, il ne protège rien s'il y en a plusieurs."""
    settings = SettingsStore(root)
    return AppState(
        settings=settings,
        tokens=TokenStore(root),
        media=MediaLibrary(root),
        # Le contact Wikimedia est modifiable depuis la webapp. On le relit à chaque
        # téléchargement afin qu'une nouvelle valeur soit active sans redémarrage.
        images=ImageCache(root, contact_provider=lambda: settings.load()["image_search"].get("contact", "")),
    )


def state(request: Request) -> AppState:
    return request.app.state.brain


def require_robot(request: Request, authorization: str | None = Header(default=None)) -> AppState:
    current = state(request)
    if not current.tokens.accepts_robot(authorization):
        raise HTTPException(status_code=401, detail="token d'appairage invalide")
    return current


def require_admin(request: Request, authorization: str | None = Header(default=None)) -> AppState:
    current = state(request)
    if not current.tokens.accepts_admin(authorization):
        raise HTTPException(status_code=401, detail="token admin invalide")
    return current
