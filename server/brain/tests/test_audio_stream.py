"""Real ASGI WebSocket sessions; only the expensive STT provider is substituted."""
import threading
import os
import time
import unittest
from dataclasses import replace
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from brain.api.deps import build_state


class AudioStreamTest(unittest.TestCase):
    def setUp(self):
        from brain.audio_stream import AudioLimits, AudioRuntime
        from brain.api.routes_audio import router

        self.tmp = TemporaryDirectory()
        self.app = FastAPI()
        self.app.state.brain = build_state(Path(self.tmp.name))
        self.app.include_router(router)
        self.limits = AudioLimits(partial_min_audio_seconds=0.3,
                                  partial_step_seconds=0.3, partial_interval_seconds=0.03)
        self.app.state.audio_limits = self.limits
        self.runtime = AudioRuntime(self.limits)
        self.app.state.audio_runtime = self.runtime
        self.client = TestClient(self.app)
        self.headers = {"Authorization": "Bearer " + self.app.state.brain.tokens.pairing_token}
        self.calls = []
        self.started = threading.Event()
        self.release = threading.Event()
        self.release.set()
        self.provider = patch("brain.api.routes_audio.stt.get").start()
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, {'BRAIN_LOCAL_PARTIALS':'1'}).start()
        self.provider.return_value.transcribe.side_effect = self.transcribe

    def tearDown(self):
        self.release.set()
        self.runtime.close()
        self.tmp.cleanup()

    def transcribe(self, **kwargs):
        self.calls.append(kwargs["pcm16"])
        self.started.set()
        if not self.release.wait(3):
            raise RuntimeError("test worker was not released")
        return "bonjour %d" % len(kwargs["pcm16"])

    def connect(self, headers=None):
        return self.client.websocket_connect("/api/robot/transcribe/stream",
                                             headers=self.headers if headers is None else headers)

    def ready(self, ws):
        self.assertEqual(ws.receive_json()["type"], "ready")

    def final(self, ws):
        while True:
            event = ws.receive_json()
            if event["type"] != "partial":
                return event

    def configure(self, **kwargs):
        self.app.state.audio_limits = replace(self.limits, **kwargs)

    def test_pairing_header_required_admin_and_query_token_rejected(self):
        for headers in ({}, {"Authorization": "Bearer wrong"},
                        {"Authorization": "Bearer " + self.app.state.brain.tokens.admin_token}):
            with self.assertRaises(WebSocketDisconnect) as failure:
                with self.connect(headers):
                    pass
            self.assertEqual(failure.exception.code, 1008)
        with self.assertRaises(WebSocketDisconnect):
            with self.client.websocket_connect("/api/robot/transcribe/stream?token=" +
                                               self.app.state.brain.tokens.pairing_token):
                pass
        self.assertEqual(self.calls, [])

    def test_partial_during_capture_then_reuses_snapshot_for_exactly_one_final(self):
        with self.connect() as ws:
            self.ready(ws)
            ws.send_bytes(b"\x01\x00" * 4800)
            self.assertEqual(ws.receive_json(), {"type": "partial", "text": "bonjour 9600"})
            ws.send_json({"type": "finish"})
            event = self.final(ws)
            self.assertEqual(event["type"], "final")
            self.assertEqual(event["text"], "bonjour 9600")
            self.assertGreaterEqual(event["elapsed_ms"], 0)
            with self.assertRaises(WebSocketDisconnect) as ended:
                ws.receive_json()
            self.assertEqual(ended.exception.code, 1000)
        self.assertEqual(len(self.calls), 1)

    def test_inflight_snapshot_reused_and_duplicate_finish_does_not_duplicate_result(self):
        self.release.clear()
        with self.connect() as ws:
            self.ready(ws)
            ws.send_bytes(b"\x01\x00" * 4800)
            self.assertTrue(self.started.wait(1))
            ws.send_json({"type": "finish"})
            ws.send_json({"type": "finish"})
            self.release.set()
            self.assertEqual(self.final(ws)["type"], "final")
        self.assertEqual(len(self.calls), 1)

    def test_slow_worker_coalesces_new_chunks_into_latest_final_without_backlog(self):
        self.release.clear()
        with self.connect() as ws:
            self.ready(ws)
            ws.send_bytes(b"\x01\x00" * 4800)
            self.assertTrue(self.started.wait(1))
            for _ in range(30):
                ws.send_bytes(b"\x02\x00" * 320)
            ws.send_json({"type": "finish"})
            # FIFO cancel/control processing can continue while inference is blocked.
            time.sleep(0.05)
            self.assertEqual(len(self.calls), 1)
            self.release.set()
            self.assertEqual(self.final(ws)["text"], "bonjour 28800")
        self.assertEqual([len(pcm) for pcm in self.calls], [9600, 28800])

    def test_cloud_is_only_called_at_finish(self):
        self.app.state.brain.settings.update_section("stt", {"active": "openai"})
        with self.connect() as ws:
            self.ready(ws)
            ws.send_bytes(b"\x01\x00" * 16000)
            self.assertFalse(self.started.wait(0.1))
            ws.send_json({"type": "finish"})
            self.assertEqual(self.final(ws)["type"], "final")
        self.assertEqual(len(self.calls), 1)

    def test_local_default_avoids_speculative_cpu_work(self):
        with patch.dict(os.environ, {'BRAIN_LOCAL_PARTIALS':'0'}):
            with self.connect() as ws:
                ready = ws.receive_json()
                self.assertFalse(ready['partials'])
                ws.send_bytes(b'\x01\x00' * 16000)
                self.assertFalse(self.started.wait(.1))
                ws.send_json({'type':'finish'})
                self.assertEqual(self.final(ws)['type'], 'final')
        self.assertEqual(len(self.calls), 1)

    def test_live_selection_without_key_fails_without_provider_audio(self):
        self.app.state.brain.settings.update_section('stt', {'active':'openai', 'model':'gpt-live-transcribe'})
        with self.connect() as ws:
            self.assertEqual(ws.receive_json()['code'], 'not_configured')
        self.assertEqual(self.calls, [])

    def test_cancellation_does_not_release_running_inference_slot(self):
        self.release.clear()
        with self.connect() as ws:
            self.ready(ws)
            ws.send_bytes(b"\x01\x00" * 4800)
            self.assertTrue(self.started.wait(1))
            ws.send_json({"type": "cancel"})
            with self.assertRaises(WebSocketDisconnect):
                ws.receive_json()
        with self.connect() as ws:
            self.ready(ws)
            ws.send_bytes(b"\x01\x00" * 4800)
            ws.send_json({"type": "finish"})
            time.sleep(0.1)
            self.assertEqual(len(self.calls), 1)
            self.release.set()
            self.assertEqual(self.final(ws)["type"], "final")
        self.assertEqual(len(self.calls), 2)

    def test_cancel_during_final_and_disconnect_leave_no_session_slot_behind(self):
        self.release.clear()
        with self.connect() as ws:
            self.ready(ws)
            ws.send_bytes(b"\x01\x00" * 4800)
            ws.send_json({"type": "finish"})
            self.assertTrue(self.started.wait(1))
            ws.send_json({"type": "cancel"})
            with self.assertRaises(WebSocketDisconnect):
                ws.receive_json()
        with self.connect() as ws:
            self.ready(ws)
        self.assertEqual(self.runtime.active_sessions, 0)

    def test_invalid_pcm_controls_and_short_audio_fail_safely(self):
        for payload in (b"\x00", b"", "not json", "[]", '{"type":"unknown"}', "x" * 1025):
            with self.connect() as ws:
                self.ready(ws)
                if isinstance(payload, bytes):
                    ws.send_bytes(payload)
                else:
                    ws.send_text(payload)
                self.assertEqual(self.final(ws)["type"], "error")
        with self.connect() as ws:
            self.ready(ws)
            ws.send_json({"type": "finish"})
            self.assertEqual(self.final(ws)["code"], "audio_too_short")

    def test_pcm_and_individual_frame_limits(self):
        self.configure(max_audio_seconds=0.3, max_frame_bytes=9600)
        with self.connect() as ws:
            self.ready(ws)
            ws.send_bytes(b"\x00\x00" * 4800)
            ws.send_bytes(b"\x00\x00")
            self.assertEqual(self.final(ws)["code"], "audio_limit")
        with self.connect() as ws:
            self.ready(ws)
            ws.send_bytes(b"\x00\x00" * 4801)
            self.assertEqual(self.final(ws)["code"], "frame_limit")

    def test_idle_and_session_deadlines(self):
        self.configure(idle_timeout_seconds=0.05)
        with self.connect() as ws:
            self.ready(ws)
            self.assertEqual(self.final(ws)["code"], "timeout")
        self.configure(session_timeout_seconds=0.05)
        with self.connect() as ws:
            self.ready(ws)
            self.assertEqual(self.final(ws)["code"], "timeout")

    def test_final_timeout_preserves_worker_cap(self):
        self.configure(final_timeout_seconds=0.05)
        self.release.clear()
        with self.connect() as ws:
            self.ready(ws)
            ws.send_bytes(b"\x01\x00" * 4800)
            ws.send_json({"type": "finish"})
            self.assertEqual(self.final(ws)["code"], "timeout")
        self.assertIsNone(self.runtime.submit(lambda: "would exceed real concurrency"))

    def test_session_admission_bounded(self):
        from brain.audio_stream import AudioRuntime
        self.runtime.close()
        self.runtime = AudioRuntime(replace(self.limits, max_sessions=1))
        self.app.state.audio_runtime = self.runtime
        with self.connect() as first:
            self.ready(first)
            with self.assertRaises(WebSocketDisconnect) as rejected:
                with self.connect():
                    pass
            self.assertEqual(rejected.exception.code, 1013)

    def test_provider_exception_is_sanitized(self):
        self.provider.return_value.transcribe.side_effect = RuntimeError("Authorization: secret")
        with self.connect() as ws:
            self.ready(ws)
            ws.send_bytes(b"\x00\x00" * 4800)
            ws.send_json({"type": "finish"})
            result = self.final(ws)
            self.assertEqual(result["code"], "provider_error")
            self.assertNotIn("secret", str(result))


if __name__ == "__main__":
    unittest.main()
