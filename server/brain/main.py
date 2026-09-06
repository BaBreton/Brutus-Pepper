from pathlib import Path
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles

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
    def webapp() -> str:
        """La page est publique ; elle ne montre rien sans le jeton administrateur,
        qui est saisi dans le navigateur et n'est jamais servi par le serveur."""
        return STATIC_INDEX.read_text("utf-8")

    app.include_router(routes_robot.router)
    app.include_router(routes_admin.router)
    app.include_router(routes_audio.router)
    app.mount("/static", StaticFiles(directory=STATIC_INDEX.parent), name="static")
    return app


def main() -> None:
    import uvicorn

    uvicorn.run(create_app(), host=config.HOST, port=config.PORT)


if __name__ == "__main__":
    main()
