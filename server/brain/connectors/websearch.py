"""Recherche web, pour les questions dont le modèle ne connaît pas la réponse.

Un modèle a une date d'arrêt de connaissances et ignore tout du local : les horaires
du musée d'à côté, la météo, l'actualité. Devant un visiteur, « je ne sais pas » est
une réponse acceptable mais pauvre quand la réponse tient en une recherche.

Même forme que les autres connecteurs : sans état, identifiants passés à l'appel,
messages d'erreur rédigés pour un installateur.
"""
from dataclasses import dataclass

from brain.pricing import BRAVE, SERPER, credential_fields
from brain.connectors import serper

TIMEOUT_SECONDS = 8.0
# Ce qui est réinjecté dans la conversation. Au-delà, on allonge le prompt sans
# améliorer la réponse : le modèle ne lira pas la dixième page pour dire une phrase.
MAX_RESULTS = 4
MAX_SNIPPET_CHARS = 320


class WebSearchError(Exception):
    """Base des erreurs de recherche web. Ne contient jamais d'identifiant."""


class WebSearchNotConfigured(WebSearchError):
    pass


class WebSearchAuthError(WebSearchError):
    pass


class WebSearchUnavailable(WebSearchError):
    pass


@dataclass(frozen=True)
class SearchResult:
    title: str
    snippet: str
    url: str


class WebSearchProvider:
    id = ""
    label = ""

    def is_configured(self, credentials: dict) -> bool:
        raise NotImplementedError

    def search(self, credentials: dict, query: str, http=None) -> list[SearchResult]:
        raise NotImplementedError


def _client(http):
    if http is not None:
        return http
    import httpx

    return httpx.Client(timeout=TIMEOUT_SECONDS, follow_redirects=True)


class BraveWebSearch(WebSearchProvider):
    """Brave Search. La même clé sert aux images et au web, d'où la carte unique
    « Recherche web et images » dans la webapp."""

    id = "brave"
    label = "Brave Search"
    credential_fields = ("api_key",)
    pricing = BRAVE
    ENDPOINT = "https://api.search.brave.com/res/v1/web/search"

    def is_configured(self, credentials: dict) -> bool:
        return bool(str(credentials.get("api_key", "")).strip())

    def search(self, credentials: dict, query: str, http=None) -> list[SearchResult]:
        if not self.is_configured(credentials):
            raise WebSearchNotConfigured("clé API Brave Search absente ou vide")
        response = _client(http).get(
            self.ENDPOINT,
            params={"q": query, "count": MAX_RESULTS, "safesearch": "strict",
                    "result_filter": "web"},
            # La clé voyage en en-tête : dans l'URL elle finirait dans les journaux
            # du serveur et de tout proxy sur le chemin.
            headers={"X-Subscription-Token": credentials["api_key"],
                     "Accept": "application/json"},
        )
        if response.status_code in (401, 403):
            raise WebSearchAuthError("clé API Brave Search refusée")
        if response.status_code >= 300:
            raise WebSearchUnavailable("Brave Search a répondu %s" % response.status_code)

        found = []
        for item in ((response.json().get("web") or {}).get("results") or []):
            title = str(item.get("title", "")).strip()
            snippet = str(item.get("description", "")).strip()[:MAX_SNIPPET_CHARS]
            url = str(item.get("url", "")).strip()
            if title and snippet:
                found.append(SearchResult(title=title, snippet=snippet, url=url))
        return found[:MAX_RESULTS]


REGISTRY: dict[str, WebSearchProvider] = {}


class SerperWebSearch(WebSearchProvider):
    id = "serper"
    label = "Google via Serper"
    credential_fields = ("api_key", "language", "country")
    pricing = SERPER

    def is_configured(self, credentials: dict) -> bool:
        return bool(str(credentials.get("api_key") or "").strip())

    def search(self, credentials: dict, query: str, http=None) -> list[SearchResult]:
        if not self.is_configured(credentials):
            raise WebSearchNotConfigured("Clé Serper absente. Configurez-la dans Voix et intelligence.")
        try:
            body = serper.query("search", credentials, query, http)
        except serper.SerperAuthError as error:
            raise WebSearchAuthError(str(error)) from None
        except serper.SerperError as error:
            raise WebSearchUnavailable(str(error)) from None
        results = body.get("organic")
        if not isinstance(results, list):
            raise WebSearchUnavailable("Réponse web Serper invalide.")
        found = []
        for item in results[:10]:
            if not isinstance(item, dict):
                continue
            title, snippet, url = (str(item.get(k) or "").strip() for k in ("title", "snippet", "link"))
            if title and snippet and url.startswith(("https://", "http://")):
                found.append(SearchResult(title[:300], snippet[:MAX_SNIPPET_CHARS], url))
        return found[:MAX_RESULTS]


def configured(settings: dict):
    """Share the selected engine with images; keep legacy Brave setups working."""
    section = settings.get("image_search") or {}
    selected = section.get("provider")
    # Wikimedia provides images only. An existing Brave key can still power web.
    provider_id = selected if selected in REGISTRY else "brave"
    provider = REGISTRY.get(provider_id)
    credentials = (section.get("credentials") or {}).get(provider_id) or {}
    return (provider, credentials) if provider and provider.is_configured(credentials) else (None, {})


def register(provider: WebSearchProvider) -> WebSearchProvider:
    REGISTRY[provider.id] = provider
    return provider


def get(provider_id: str) -> WebSearchProvider:
    if provider_id not in REGISTRY:
        raise KeyError("fournisseur de recherche web inconnu: %s" % provider_id)
    return REGISTRY[provider_id]


def catalog(credentials: dict) -> list[dict]:
    """Catalogue public de la recherche web, avec ses champs et son coût."""
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


def render(results: list[SearchResult]) -> str:
    """Met les résultats en forme pour être relus par le modèle.

    Volontairement sec : des titres et des extraits, sans consigne. La consigne est
    dans le prompt système, sa place n'est pas dans des données venues du web — c'est
    ce qui empêche une page de dicter au robot ce qu'il doit dire.
    """
    return "\n".join(
        "- %s : %s" % (result.title, result.snippet) for result in results
    )


register(BraveWebSearch())
register(SerperWebSearch())
