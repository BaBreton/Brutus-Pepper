"""Bounded PCM sessions and inference admission, shared by an application's sockets.

Whisper is NOT an incremental STT engine: partials re-decode accumulated audio.
Only the latest prefix is retained; at most one inference per session is active.
Limits are per application/process (use one uvicorn worker for a single CPU budget).
Set app.state.audio_limits before the first connection; optionally supply an
AudioRuntime and close it from the application's lifespan on shutdown.
"""
import asyncio
import json
import math
import threading
import time
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass
from functools import partial

from starlette.websockets import WebSocket, WebSocketDisconnect

from brain.connectors import stt

SAMPLE_RATE = 16000
BYTES_PER_SECOND = SAMPLE_RATE * 2
MIN_PCM_BYTES = BYTES_PER_SECOND * 3 // 10


@dataclass(frozen=True)
class AudioLimits:
    max_audio_seconds: float = 60
    max_frame_bytes: int = 64 * 1024
    max_control_chars: int = 1024
    max_text_chars: int = 16000
    max_sessions: int = 4
    max_inferences: int = 1
    session_timeout_seconds: float = 75
    idle_timeout_seconds: float = 10
    final_timeout_seconds: float = 45
    inference_timeout_seconds: float = 40
    send_timeout_seconds: float = 2
    partial_min_audio_seconds: float = 1.0
    partial_step_seconds: float = 0.75
    partial_interval_seconds: float = 1.5

    def __post_init__(self):
        for name, value in vars(self).items():
            if not math.isfinite(value) or value <= 0:
                raise ValueError("audio limit %s must be positive and finite" % name)
        for name in ("max_frame_bytes", "max_control_chars", "max_text_chars",
                     "max_sessions", "max_inferences"):
            if not isinstance(getattr(self, name), int):
                raise ValueError("audio limit %s must be an integer" % name)


class AudioRuntime:
    """No executor backlog: a slot is acquired BEFORE submission.

    Cancellation/disconnect cannot stop native inference. Its slot belongs to the
    concurrent Future and is released only when the real thread finishes, never
    when the requesting coroutine stops waiting. Repeated cancel/reconnect thus
    cannot create unlimited surviving workers. HTTP callers are outside this
    runtime; WhisperLocalConnector serializes local inference across both paths.
    """
    def __init__(self, limits: AudioLimits):
        self._sessions = threading.BoundedSemaphore(limits.max_sessions)
        self._inferences = threading.BoundedSemaphore(limits.max_inferences)
        self._executor = ThreadPoolExecutor(max_workers=limits.max_inferences,
                                            thread_name_prefix="pepper-stt")
        self._lock = threading.Lock()
        self.active_sessions = 0

    def enter(self) -> bool:
        if not self._sessions.acquire(blocking=False):
            return False
        with self._lock:
            self.active_sessions += 1
        return True

    def leave(self):
        with self._lock:
            self.active_sessions -= 1
        self._sessions.release()

    def submit(self, operation) -> Future | None:
        if not self._inferences.acquire(blocking=False):
            return None
        try:
            result = self._executor.submit(operation)
        except BaseException:
            self._inferences.release()
            raise
        result.add_done_callback(lambda _: self._inferences.release())
        return result

    def close(self, wait: bool = True):
        self._executor.shutdown(wait=wait, cancel_futures=True)


class AudioProtocolError(Exception):
    def __init__(self, code: str, message: str):
        self.code, self.message = code, message


class AudioSession:
    def __init__(self, ws: WebSocket, runtime: AudioRuntime, limits: AudioLimits,
                 connector, credentials: dict, model: str, language: str, local: bool):
        self.ws, self.runtime, self.limits = ws, runtime, limits
        self.transcribe = partial(connector.transcribe, credentials=credentials,
                                  model=model, language=language, sample_rate=SAMPLE_RATE)
        self.local = local
        self.pcm = bytearray()
        self.job: Future | None = None
        self.job_size = 0
        self.job_started = 0.0
        self.attempt_size = 0
        self.last_attempt = 0.0
        self.cached_size = -1
        self.cached_text = ""
        self.started = time.monotonic()
        self.last_receive = self.started
        self.finishing: float | None = None

    async def send(self, event: dict):
        await asyncio.wait_for(self.ws.send_json(event), self.limits.send_timeout_seconds)

    def receive(self, message: dict) -> bool:
        """False means cancellation/disconnection, with no terminal transcript."""
        if message["type"] == "websocket.disconnect":
            return False
        self.last_receive = time.monotonic()
        data = message.get("bytes")
        if data is not None:
            if self.finishing is not None:
                raise AudioProtocolError("protocol_error", "audio reçu après finish")
            if len(data) > self.limits.max_frame_bytes:
                raise AudioProtocolError("frame_limit", "bloc audio trop grand")
            if not data or len(data) % 2:
                raise AudioProtocolError("invalid_pcm", "PCM 16 bits mono little-endian requis")
            if len(self.pcm) + len(data) > self.limits.max_audio_seconds * BYTES_PER_SECOND:
                raise AudioProtocolError("audio_limit", "durée audio maximale dépassée")
            self.pcm.extend(data)
            return True
        text = message.get("text") or ""
        if len(text) > self.limits.max_control_chars:
            raise AudioProtocolError("protocol_error", "commande trop longue")
        try:
            control = json.loads(text)
        except (ValueError, RecursionError):
            raise AudioProtocolError("protocol_error", "commande JSON invalide") from None
        kind = control.get("type") if isinstance(control, dict) else None
        if kind == "cancel":
            return False
        if kind != "finish":
            raise AudioProtocolError("protocol_error", "commande attendue : finish ou cancel")
        if len(self.pcm) < MIN_PCM_BYTES:
            raise AudioProtocolError("audio_too_short", "extrait audio trop court")
        if self.finishing is None:
            self.finishing = time.monotonic()
        return True

    async def advance(self) -> bool:
        now = time.monotonic()
        if (now - self.started >= self.limits.session_timeout_seconds or
                (self.finishing is None and
                 now - self.last_receive >= self.limits.idle_timeout_seconds) or
                (self.finishing is not None and
                 now - self.finishing >= self.limits.final_timeout_seconds)):
            raise AudioProtocolError("timeout", "délai de transcription dépassé")

        if self.job is not None:
            if self.job.done():
                completed, self.job = self.job, None
                try:
                    text = completed.result()
                    if not isinstance(text, str) or len(text) > self.limits.max_text_chars:
                        raise ValueError("invalid transcript")
                except Exception:
                    # A failed speculative prefix does not prevent a final attempt.
                    if self.finishing is not None and self.job_size == len(self.pcm):
                        raise AudioProtocolError("provider_error", "la transcription a échoué") from None
                else:
                    self.cached_size, self.cached_text = self.job_size, text
                    if self.finishing is None:
                        await self.send({"type": "partial", "text": text})
            elif now - self.job_started >= self.limits.inference_timeout_seconds:
                raise AudioProtocolError("timeout", "délai du moteur de transcription dépassé")

        if self.finishing is not None and self.cached_size == len(self.pcm):
            await self.send({"type": "final", "text": self.cached_text,
                             "elapsed_ms": int((now - self.started) * 1000)})
            return False

        speculative = (
            self.local and len(self.pcm) >= max(MIN_PCM_BYTES,
                self.limits.partial_min_audio_seconds * BYTES_PER_SECOND) and
            len(self.pcm) - self.attempt_size >= self.limits.partial_step_seconds * BYTES_PER_SECOND and
            now - self.last_attempt >= self.limits.partial_interval_seconds
        )
        if self.job is None and (self.finishing is not None or speculative):
            # Convert/snapshot only when there is work to submit. If another session
            # owns the slot, retry later with the newest prefix, without queueing.
            snapshot = bytes(self.pcm)
            job = self.runtime.submit(partial(self.transcribe, pcm16=snapshot))
            if job is not None:
                self.job = job
                self.job_size = self.attempt_size = len(snapshot)
                self.job_started = self.last_attempt = now
        return True

    async def run(self):
        await self.send({"type": "ready", "sample_rate": SAMPLE_RATE,
                         "encoding": "pcm_s16le", "channels": 1,
                         "partials": self.local,
                         "max_audio_bytes": int(self.limits.max_audio_seconds * BYTES_PER_SECOND),
                         "max_frame_bytes": self.limits.max_frame_bytes})
        receiving = asyncio.create_task(self.ws.receive())
        try:
            while True:
                # One pending receive and one real inference, never one task per
                # audio frame. Receiving continues during final inference so cancel
                # and disconnect take effect promptly. Polling adds at most 50 ms.
                done, _ = await asyncio.wait({receiving}, timeout=0.05)
                if done:
                    message = receiving.result()
                    if not self.receive(message):
                        break
                    receiving = asyncio.create_task(self.ws.receive())
                if not await self.advance():
                    break
        except AudioProtocolError as error:
            await self.send({"type": "error", "code": error.code, "message": error.message})
        finally:
            receiving.cancel()
            await asyncio.gather(receiving, return_exceptions=True)
            # Do not cancel the real Future: native code can still be running.
            self.pcm.clear()
        try:
            await asyncio.wait_for(self.ws.close(code=1000), self.limits.send_timeout_seconds)
        except (WebSocketDisconnect, RuntimeError):
            pass
