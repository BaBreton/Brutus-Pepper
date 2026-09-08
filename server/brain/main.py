from pathlib import Path
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles

import hashlib

import brain.connectors  # noqa: F401  — l'import remplit les registres de connecteurs
from brain import config, preload
from brain.api import routes_admin, routes_robot
from brain.api.deps import build_state
from brain.api import routes_audio

STATIC_INDEX = Path(__file__).parent / "static" / "index.html"


def create_app(root: Path | None = None, preload_stt: bool | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application):
        yield
        runtime = getattr(application.state, "audio_runtime", None)
        if runtime is not None:
            runtime.close(wait=False)

    app = FastAPI(title="Pepper Brain", docs_url=None, redoc_url=None, lifespan=lifespan)
    app.state.brain = build_state(root or config.DATA_ROOT)

    # Le préchargement tourne en tâche de fond : il ne retarde pas la webapp, mais
    # évite que le téléchargement du modèle ne tombe sur le premier visiteur.
    should_preload = config.PRELOAD_STT if preload_stt is None else preload_stt
    if should_preload:
        preload.warm_stt_in_background(app.state.brain.settings)

    @app.get("/api/health")
    def health() -> dict:
        return {"status": "ok"}

    @app.get("/", response_class=HTMLResponse)
    def webapp() -> HTMLResponse:
        """La page est publique ; elle ne montre rien sans le jeton administrateur,
        qui est saisi dans le navigateur et n'est jamais servi par le serveur.

        Le marqueur __ASSETS__ est remplacé par une empreinte du script et de la
        feuille de style. Après une mise à jour du serveur, le navigateur de l'hôte
        demande donc des URL qu'il n'a jamais vues et ne peut pas servir d'ancienne
        version depuis son cache. C'est la panne la plus pénible à diagnostiquer chez
        un client : la page paraît à jour, mais un champ ajouté n'est jamais envoyé.
        """
        html = STATIC_INDEX.read_text("utf-8").replace("__ASSETS__", _assets_version())
        # La page elle-même ne doit pas être stockée, sinon elle continuerait de
        # pointer vers l'ancienne empreinte.
        return HTMLResponse(html, headers={"Cache-Control": "no-store"})

    def _assets_version() -> str:
        digest = hashlib.sha256()
        for name in ("admin.js", "admin.css"):
            path = STATIC_INDEX.parent / name
            if path.exists():
                digest.update(path.read_bytes())
        return digest.hexdigest()[:12]

    app.include_router(routes_robot.router)
    app.include_router(routes_admin.router)
    app.include_router(routes_audio.router)
    class RevalidatedStatic(StaticFiles):
        """Sert les fichiers statiques en exigeant une revalidation.

        Sans cela, le navigateur de l'hôte garde son ancien admin.js après une mise à
        jour du serveur : la page paraît à jour, mais un champ ajouté n'existe pas et
        n'est jamais envoyé — panne invisible et très pénible à diagnostiquer chez un
        client. L'ETag reste géré par StaticFiles, donc une page inchangée coûte un
        304 vide, pas un rechargement complet.
        """

        def file_response(self, *args, **kwargs):
            response = super().file_response(*args, **kwargs)
            response.headers["Cache-Control"] = "no-cache"
            return response

    app.mount("/static", RevalidatedStatic(directory=STATIC_INDEX.parent), name="static")
    return app


def main() -> None:
    import uvicorn

    uvicorn.run(create_app(), host=config.HOST, port=config.PORT)


if __name__ == "__main__":
    main()
