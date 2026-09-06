"""Google results via Serper. Fixed endpoints; API key only in a TLS header."""
import re

import httpx


class SerperError(Exception):
    pass


class SerperAuthError(SerperError):
    pass


def query(kind: str, credentials: dict, text: str, http=None) -> dict:
    if kind not in ("search", "images"):
        raise ValueError("type de recherche inconnu")
    key = str(credentials.get("api_key") or "").strip()
    if not key:
        raise SerperAuthError("Clé Serper absente. Configurez-la dans Voix et intelligence.")
    language = str(credentials.get("language") or "fr").strip().lower()
    country = str(credentials.get("country") or "fr").strip().lower()
    if not re.fullmatch(r"[a-z]{2}", language) or not re.fullmatch(r"[a-z]{2}", country):
        raise SerperError("Langue et pays Serper : utilisez deux lettres, par exemple fr.")
    payload = {"q": text[:400], "hl": language, "gl": country, "num": 10,
               "safe": "active"}
    owned = http is None
    client = http if http is not None else httpx.Client(timeout=8, follow_redirects=False)
    try:
        response = client.post("https://google.serper.dev/" + kind,
                               headers={"X-API-KEY": key}, json=payload)
        if response.status_code in (401, 403):
            raise SerperAuthError("Clé Serper refusée. Vérifiez votre compte Serper.")
        if response.status_code in (402, 429):
            raise SerperError("Crédits ou quota Serper indisponibles. Vérifiez votre compte et réessayez plus tard.")
        if response.status_code != 200:
            raise SerperError("Recherche Serper indisponible. Réessayez plus tard.")
        value = response.json()
        if not isinstance(value, dict) or value.get("error"):
            raise SerperError("Réponse Serper inexploitable.")
        return value
    except (httpx.HTTPError, ValueError):
        raise SerperError("Recherche Serper indisponible ou réponse invalide.") from None
    finally:
        if owned:
            client.close()
