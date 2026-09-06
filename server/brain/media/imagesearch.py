"""Recherche d'images sur le web.

Utilisée seulement en second recours : la médiathèque du client est toujours
consultée d'abord, pour qu'un média qu'il a lui-même déposé l'emporte sur le web.

Même forme que les connecteurs LLM et STT : sans état, identifiants passés à
l'appel, hiérarchie d'erreurs aux messages rédigés pour un installateur.
"""
from dataclasses import dataclass

from brain.pricing import BRAVE, WIKIMEDIA, SERPER, credential_fields
from brain.connectors import serper
from urllib.parse import urlsplit

TIMEOUT_SECONDS = 8.0


class ImageSearchError(Exception):
    """Base des erreurs de recherche d'image. Ne contient jamais d'identifiant."""


class ImageSearchNotConfigured(ImageSearchError):
    pass


class ImageSearchAuthError(ImageSearchError):
    pass


class ImageSearchUnavailable(ImageSearchError):
    pass


@dataclass(frozen=True)
class FoundImage:
    url: str
    title: str
    source: str
    # Référent à présenter au téléchargement. Wikimedia bride le « hotlinking » :
    # une requête sans référent est traitée comme un lien pillé depuis un site tiers
    # et se voit répondre 429. Le fournisseur sait d'où vient l'image, pas le cache.
    referer: str = ""


class ImageSearchProvider:
    id = ""
    label = ""

    def is_configured(self, credentials: dict) -> bool:
        raise NotImplementedError

    def candidates(self, credentials: dict, query: str, http=None,
                   contact: str | None = None) -> list[FoundImage]:
        """Rend plusieurs images pertinentes, par ordre de préférence.

        Plusieurs et non une seule : un téléchargement peut échouer — bridage du
        fournisseur, format inattendu — et il vaut mieux passer à la suivante que
        laisser le robot sans rien à montrer.
        """
        raise NotImplementedError

    def search(self, credentials: dict, query: str, http=None,
               contact: str | None = None) -> FoundImage:
        """Première image pertinente. Lève LookupError si rien ne correspond."""
        found = self.candidates(credentials, query, http=http, contact=contact)
        if not found:
            raise LookupError("aucune image trouvée pour « %s »" % query)
        return found[0]


def _user_agent(contact: str | None = None) -> str:
    from brain.media.imagecache import user_agent

    return user_agent(contact)


def _client(http):
    if http is not None:
        return http
    import httpx

    return httpx.Client(timeout=TIMEOUT_SECONDS, follow_redirects=True)


class BraveImageSearch(ImageSearchProvider):
    id = "brave"
    label = "Brave Search (clé API)"
    credential_fields = ("api_key",)
    pricing = BRAVE
    ENDPOINT = "https://api.search.brave.com/res/v1/images/search"

    def is_configured(self, credentials: dict) -> bool:
        return bool(str(credentials.get("api_key", "")).strip())

    def candidates(self, credentials: dict, query: str, http=None,
                   contact: str | None = None) -> list[FoundImage]:
        if not self.is_configured(credentials):
            raise ImageSearchNotConfigured("clé API Brave Search absente ou vide")
        response = _client(http).get(
            self.ENDPOINT,
            params={"q": query, "count": 5, "safesearch": "strict"},
            # La clé voyage en en-tête : dans l'URL elle finirait dans les journaux
            # du serveur et de tout proxy sur le chemin.
            headers={"X-Subscription-Token": credentials["api_key"],
                     "Accept": "application/json"},
        )
        if response.status_code in (401, 403):
            raise ImageSearchAuthError("clé API Brave Search refusée")
        if response.status_code >= 300:
            raise ImageSearchUnavailable("Brave Search a répondu %s" % response.status_code)
        found = []
        for item in response.json().get("results", []):
            url = (item.get("properties") or {}).get("url") or item.get("url")
            if url:
                found.append(FoundImage(url=url, title=item.get("title", query),
                                        source=self.id,
                                        referer="https://search.brave.com/"))
        if not found:
            raise LookupError("aucune image trouvée pour « %s »" % query)
        return found


class SerperImageSearch(ImageSearchProvider):
    id = "serper"
    label = "Google Images via Serper — recommandé"
    credential_fields = ("api_key", "language", "country")
    pricing = SERPER

    def is_configured(self, credentials: dict) -> bool:
        return bool(str(credentials.get("api_key") or "").strip())

    def candidates(self, credentials: dict, query: str, http=None,
                   contact: str | None = None) -> list[FoundImage]:
        if not self.is_configured(credentials):
            raise ImageSearchNotConfigured("Clé Serper absente. Configurez-la dans Voix et intelligence.")
        try:
            body = serper.query("images", credentials, query, http)
        except serper.SerperAuthError as error:
            raise ImageSearchAuthError(str(error)) from None
        except serper.SerperError as error:
            raise ImageSearchUnavailable(str(error)) from None
        results = body.get("images")
        if not isinstance(results, list):
            raise ImageSearchUnavailable("Réponse images Serper invalide.")
        found, seen = [], set()
        for item in results[:10]:
            if not isinstance(item, dict):
                continue
            # Full-size images first. Thumbnails are kept after the originals so
            # a blocked publisher does not leave the tablet without any picture.
            for field in ("imageUrl", "thumbnailUrl"):
                url = item.get(field)
                try:
                    parsed = urlsplit(url) if isinstance(url, str) else None
                    valid = parsed and parsed.scheme in ("http", "https") and parsed.hostname and not parsed.username
                except ValueError:
                    valid = False
                if not valid or url in seen:
                    continue
                seen.add(url)
                found.append((field, FoundImage(url, str(item.get("title") or query)[:300], self.id)))
        ordered = [image for field, image in found if field == "imageUrl"]
        thumbs = [image for field, image in found if field == "thumbnailUrl"]
        # The route attempts four downloads: three originals and one thumbnail.
        selected = ordered[:3] + thumbs[:1] if thumbs else ordered[:4]
        if not selected:
            raise LookupError("Aucune image trouvée. Précisez le nom ou le lieu.")
        return selected


class WikimediaImageSearch(ImageSearchProvider):
    """Recherche sans clé. Les licences et l’adéquation du contenu restent à vérifier."""

    id = "wikimedia"
    label = "Wikimedia Commons (sans clé)"
    credential_fields = ()
    pricing = WIKIMEDIA
    ENDPOINT = "https://commons.wikimedia.org/w/api.php"

    def is_configured(self, credentials: dict) -> bool:
        return True

    def candidates(self, credentials: dict, query: str, http=None,
                   contact: str | None = None) -> list[FoundImage]:
        response = _client(http).get(
            self.ENDPOINT,
            params={
                "action": "query", "format": "json", "generator": "search",
                # 20 candidats et non 5 : beaucoup d'images de Commons sont plus étroites
                # que la vignette demandée, et Wikimedia renvoie alors l'original — que
                # l'on refuse. Il faut donc de la marge pour en trouver une exploitable.
                "gsrnamespace": 6, "gsrsearch": query, "gsrlimit": 20,
                # 800 px : largement assez pour l'écran de la tablette (1280×800), et
                # surtout étroit devant la plupart des originaux, ce qui garantit que
                # Wikimedia génère une vraie vignette. Demander plus large ferait
                # renvoyer l'original lui-même, qui est bridé au lien direct.
                "prop": "imageinfo", "iiprop": "url", "iiurlwidth": 800,
            },
            headers={"User-Agent": _user_agent(contact)},
        )
        if response.status_code >= 300:
            raise ImageSearchUnavailable(
                "Wikimedia Commons a répondu %s" % response.status_code)
        pages = (response.json().get("query") or {}).get("pages") or {}
        found = []
        for page in pages.values():
            info = (page.get("imageinfo") or [{}])[0]
            # UNIQUEMENT la vignette. Wikimedia sert les vignettes sans broncher mais
            # bride le lien direct vers les fichiers originaux par un 429 — et une
            # image de plusieurs mégaoctets serait de toute façon trop lourde pour la
            # tablette. Un résultat sans vignette est ignoré, pas rabattu sur l'original.
            url = info.get("thumburl") or ""
            # Le champ thumburl contient parfois l'original lui-même, quand celui-ci
            # est moins large que la vignette demandée. On exige donc explicitement un
            # chemin /thumb/ : le lien direct vers un original se voit répondre 429.
            if "/thumb/" in url:
                title = page.get("title", query).removeprefix("File:")
                found.append(FoundImage(url=url, title=title, source=self.id,
                                        referer="https://commons.wikimedia.org/"))
        if not found:
            raise LookupError("aucune image affichable trouvée pour « %s »" % query)
        return found


REGISTRY: dict[str, ImageSearchProvider] = {}


def register(provider: ImageSearchProvider) -> ImageSearchProvider:
    REGISTRY[provider.id] = provider
    return provider


def get(provider_id: str) -> ImageSearchProvider:
    if provider_id not in REGISTRY:
        raise KeyError("fournisseur de recherche d'image inconnu: %s" % provider_id)
    return REGISTRY[provider_id]


def catalog(credentials: dict) -> list[dict]:
    return [
        {
            "id": provider.id,
            "label": provider.label,
            "configured": provider.is_configured(credentials.get(provider.id) or {}),
            "credential_fields": credential_fields(provider.credential_fields),
            "pricing": provider.pricing.public() if getattr(provider, "pricing", None) else None,
        }
        for provider in REGISTRY.values()
    ]


register(SerperImageSearch())
register(WikimediaImageSearch())
register(BraveImageSearch())
