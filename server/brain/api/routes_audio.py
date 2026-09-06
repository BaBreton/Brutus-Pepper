"""PCM WebSocket transport. Register router on the same app as routes_robot.

Authorization: Bearer <pairing token> (header only, never URL/query strings).
Client: binary PCM s16le / mono / 16 kHz, then {"type":"finish"}; cancel is
accepted even during final inference. Server: ready, partial, final, error.
Whisper partials are revisable full-prefix guesses, not committed text deltas.
"""
import asyncio
import os

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from brain import config
from brain.audio_stream import AudioLimits, AudioRuntime, AudioSession
from brain.audio_live import OpenAiLiveSession
from brain.connectors import stt

router = APIRouter(prefix="/api/robot")


@router.websocket("/transcribe/stream")
async def transcribe_stream(ws: WebSocket):
    limits = getattr(ws.app.state, "audio_limits", None) or AudioLimits()
    runtime = getattr(ws.app.state, "audio_runtime", None)
    if runtime is None:
        runtime = ws.app.state.audio_runtime = AudioRuntime(limits)
    if not runtime.enter():
        await ws.close(code=1013)
        return
    try:
        current = ws.app.state.brain
        # Same TokenStore as require_robot; disk reads/decryption stay off the loop.
        accepted = await asyncio.wait_for(asyncio.to_thread(
            current.tokens.accepts_robot, ws.headers.get("authorization")),
            limits.idle_timeout_seconds)
        if not accepted:
            await ws.close(code=1008)
            return
        settings = await asyncio.wait_for(asyncio.to_thread(current.settings.load),
                                          limits.idle_timeout_seconds)
        section = settings["stt"]
        await ws.accept()
        try:
            connector = stt.get(section["active"])
        except KeyError:
            await asyncio.wait_for(ws.send_json({"type": "error", "code": "not_configured",
                                                  "message": "connecteur STT indisponible"}),
                                   limits.send_timeout_seconds)
            await ws.close(code=1000)
            return
        session_class = (OpenAiLiveSession if section['active'] == 'openai'
                         and section['model'] == 'gpt-live-transcribe' else AudioSession)
        await session_class(ws, runtime, limits, connector,
                           section["credentials"].get(section["active"]) or {},
                           section["model"], config.LANGUAGE,
                           local=section["active"] == "whisper-local"
                           and os.environ.get('BRAIN_LOCAL_PARTIALS', '0') == '1').run()
    except (WebSocketDisconnect, TimeoutError, OSError):
        # The peer may disappear while sending a partial or waiting for its final.
        try:
            await asyncio.wait_for(ws.close(code=1011), limits.send_timeout_seconds)
        except (WebSocketDisconnect, RuntimeError, TimeoutError, OSError):
            pass
    finally:
        runtime.leave()
