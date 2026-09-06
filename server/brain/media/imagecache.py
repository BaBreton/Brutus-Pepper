"""Cache des images trouvées sur le web.

Le cerveau télécharge l'image et la sert lui-même, plutôt que de renvoyer une URL
externe à la tablette. Trois raisons :

- la tablette Pepper tourne sous Android 6 ; elle échoue sur des sites qui exigent
  un en-tête User-Agent (Wikimedia répond 403 à celui de Java par défaut) et sur
  des chaînes TLS récentes ;
- le principe de l'architecture est que la tablette ne parle qu'au cerveau ;
- servir depuis un identifiant plutôt que depuis une URL arbitraire évite d'ouvrir
  un relais que n'importe qui pourrait détourner pour atteindre le réseau interne.
"""
import hashlib
import time
import unicodedata
from pathlib import Path

from brain.storage import atomic_write

# Les images d'illustration sont éphémères : on garde de quoi couvrir une visite,
# pas une bibliothèque.
MAX_ENTRIES = 40
MAX_IMAGE_BYTES = 12 * 1024 * 1024
TIMEOUT_SECONDS = 10.0
# Plafond de l'attente avant reprise : au-delà, mieux vaut échouer que laisser un
# visiteur devant un écran vide.
MAX_RETRY_WAIT_SECONDS = 2.5


def _retry_after(response) -> float:
    try:
        return max(0.0, float(response.headers.get("retry-after", "1")))
    except (TypeError, ValueError):
        return 1.0


def user_agent(contact: str | None = None) -> str:
    """Wikimedia exige un User-Agent identifiable, avec un moyen de contact ; sans
    lui, il bride les requêtes par un 429. Le réglage de la webapp est prioritaire ;
    BRAIN_IMAGE_CONTACT reste le repli utile pour une installation par environnement."""
    if contact is None:
        from brain import config

        contact = config.IMAGE_CONTACT

    contact = unicodedata.normalize("NFKD", str(contact or ""))
    contact = contact.encode("ascii", "ignore").decode("ascii").strip()
    return "PepperBrain/1.0 (%s)" % (contact or "robot d'accueil; contact non renseigne")

ALLOWED_MIMES = {
    "image/jpeg": ".jpg", "image/png": ".png",
    "image/webp": ".webp", "image/gif": ".gif",
}


class ImageCache:
    def __init__(self, root: Path, contact_provider=None):
        self.root = Path(root) / "image-cache"
        self.root.mkdir(parents=True, exist_ok=True)
        self.contact_provider = contact_provider

    def _user_agent(self) -> str:
        contact = self.contact_provider() if self.contact_provider is not None else None
        return user_agent(contact)

    def fetch(self, url: str, referer: str = "", http=None) -> tuple[str, str]:
        """Télécharge et met en cache. Rend (identifiant, type mime).

        Lève ValueError si la ressource n'est pas une image exploitable — on ne veut
        pas afficher un fichier arbitraire sur la tablette d'un hall d'accueil.
        """
        identifier = hashlib.sha256(url.encode("utf-8")).hexdigest()[:16]
        # Déjà téléchargée : on ne redemande pas. Sans cette garde, redemander la même
        # image faisait retélécharger à chaque fois, et Wikimedia finissait par nous
        # brider avec un 429.
        for mime, suffix in ALLOWED_MIMES.items():
            path = self._path(identifier, mime)
            if path.exists():
                path.touch()
                return identifier, mime

        client = http
        if client is None:
            import httpx

            client = httpx.Client(timeout=TIMEOUT_SECONDS, follow_redirects=True)

        headers = {"User-Agent": self._user_agent(), "Accept": "image/jpeg,image/png,image/*"}
        # Sans référent, Wikimedia traite la requête comme un lien pillé depuis un
        # site tiers et répond 429 — mesuré : le référent seul fait passer de 429 à 200.
        if referer:
            headers["Referer"] = referer

        response = client.get(url, headers=headers)
        if response.status_code == 429:
            delay = min(_retry_after(response), MAX_RETRY_WAIT_SECONDS)
            time.sleep(delay)
            response = client.get(url, headers=headers)

        if response.status_code == 429:
            raise ValueError(
                "le fournisseur d'images nous bride (429) même après une reprise. "
                "Passez sur Brave Search, ou déposez l'image dans la médiathèque"
            )
        if response.status_code >= 300:
            raise ValueError("image inaccessible (HTTP %s)" % response.status_code)

        mime = response.headers.get("content-type", "").split(";")[0].strip().lower()
        if mime not in ALLOWED_MIMES:
            raise ValueError("type d'image non supporté : %s" % (mime or "inconnu"))
        data = response.content
        if not data or len(data) > MAX_IMAGE_BYTES:
            raise ValueError("taille d'image invalide")

        atomic_write(self._path(identifier, mime), data, mode=0o600)
        self._evict()
        return identifier, mime

    def read(self, identifier: str) -> tuple[bytes, str]:
        if not identifier.isalnum():
            raise LookupError("identifiant d'image invalide")
        for mime, suffix in ALLOWED_MIMES.items():
            path = self._path(identifier, mime)
            if path.exists():
                path.touch()  # marque l'usage, pour l'éviction
                return path.read_bytes(), mime
        raise LookupError("image absente du cache")

    def _path(self, identifier: str, mime: str) -> Path:
        return self.root / (identifier + ALLOWED_MIMES[mime])

    def _evict(self) -> None:
        """Garde les plus récemment utilisées. Le cache vit dans le volume de données
        du client : il ne doit pas grossir indéfiniment."""
        entries = sorted(self.root.iterdir(), key=lambda p: p.stat().st_mtime, reverse=True)
        for stale in entries[MAX_ENTRIES:]:
            stale.unlink(missing_ok=True)

    def purge_older_than(self, seconds: int) -> int:
        removed = 0
        limit = time.time() - seconds
        for entry in self.root.iterdir():
            if entry.stat().st_mtime < limit:
                entry.unlink(missing_ok=True)
                removed += 1
        return removed
