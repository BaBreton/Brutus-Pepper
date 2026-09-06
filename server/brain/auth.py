"""Deux jetons distincts : la webapp administre, le robot consomme.

Les jetons sont relus depuis le disque à chaque vérification plutôt que mis en
cache sur l'instance : avec plusieurs workers uvicorn, rotate_pairing() doit
invalider l'ancien jeton pour tous les workers immédiatement, pas seulement pour
celui qui a effectué la rotation. Le fichier reste la seule source de vérité."""
import hmac
import secrets
from pathlib import Path

from brain.storage import atomic_write

PREFIX = "Bearer "


class TokenStore:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.admin_path = self.root / "admin.token"
        self.pairing_path = self.root / "pairing.token"
        self._ensure(self.admin_path)
        self._ensure(self.pairing_path)

    @property
    def admin_token(self) -> str:
        return self._read(self.admin_path)

    @property
    def pairing_token(self) -> str:
        return self._read(self.pairing_path)

    def rotate_pairing(self) -> str:
        """Régénère le token d'appairage : les robots existants devront être ré-appairés."""
        token = secrets.token_urlsafe(32)
        atomic_write(self.pairing_path, token.encode("utf-8"))
        return token

    def accepts_admin(self, authorization: str | None) -> bool:
        return self._matches(authorization, [self.admin_token])

    def accepts_robot(self, authorization: str | None) -> bool:
        """N'accepte que le jeton d'appairage. Le jeton admin ne doit jamais y donner
        accès : une tablette d'accueil est physiquement accessible au public, elle ne
        doit pas pouvoir emprunter les droits d'administration."""
        return self._matches(authorization, [self.pairing_token])

    @staticmethod
    def _matches(authorization: str | None, allowed: list[str]) -> bool:
        if not authorization or not authorization.startswith(PREFIX):
            return False
        candidate = authorization[len(PREFIX):]
        return any(hmac.compare_digest(candidate, token) for token in allowed)

    @staticmethod
    def _read(path: Path) -> str:
        return path.read_text("utf-8").strip()

    @classmethod
    def _ensure(cls, path: Path) -> None:
        if path.exists() and path.read_text("utf-8").strip():
            return
        atomic_write(path, secrets.token_urlsafe(32).encode("utf-8"))
