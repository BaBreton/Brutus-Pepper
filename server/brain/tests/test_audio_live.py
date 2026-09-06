import asyncio
import json
import unittest
from unittest.mock import patch

from brain.audio_stream import AudioLimits, AudioRuntime
from brain.audio_live import OpenAiLiveSession, PcmResampler
from brain.connectors.stt_cloud import OpenAiSttConnector


class Peer:
    def __init__(self):
        self.queue = asyncio.Queue()
        self.sent = []
        self.closed = False

    async def receive(self):
        return await self.queue.get()

    async def send_json(self, value):
        self.sent.append(value)

    async def close(self, **kwargs):
        self.closed = True


class Upstream:
    def __init__(self):
        self.queue = asyncio.Queue()
        self.sent = []

    async def send(self, raw):
        event = json.loads(raw)
        self.sent.append(event)
        if event['type'] == 'session.update':
            await self.queue.put(json.dumps({'type':'session.updated'}))
        elif event['type'] == 'input_audio_buffer.append' and len(self.sent) == 2:
            await self.queue.put(json.dumps({'type':'conversation.item.input_audio_transcription.delta',
                                            'item_id':'turn', 'delta':'Bonjour'}))
        elif event['type'] == 'input_audio_buffer.commit':
            await self.queue.put(json.dumps({'type':'input_audio_buffer.committed', 'item_id':'turn'}))
            await self.queue.put(json.dumps({'type':'conversation.item.input_audio_transcription.completed',
                                            'item_id':'turn', 'transcript':'Bonjour Pepper.'}))

    async def recv(self):
        return await self.queue.get()


class AudioLiveTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.peer, self.upstream = Peer(), Upstream()
        self.limits = AudioLimits(session_timeout_seconds=1, final_timeout_seconds=.5)
        self.runtime = AudioRuntime(self.limits)
        self.session = OpenAiLiveSession(self.peer, self.runtime, self.limits,
                                        OpenAiSttConnector(), {'api_key':'test-only'},
                                        'gpt-live-transcribe', 'fr', local=False)

    def tearDown(self):
        self.runtime.close()

    async def test_partial_before_commit_and_final_after_commit(self):
        task = asyncio.create_task(self.session.relay(self.upstream))
        await self.peer.queue.put({'type':'websocket.receive', 'bytes':b'\x01\x00' * 8000})
        for _ in range(40):
            if any(e['type'] == 'partial' for e in self.peer.sent):
                break
            await asyncio.sleep(.005)
        self.assertTrue(any(e['type'] == 'partial' for e in self.peer.sent))
        self.assertFalse(any(e['type'] == 'final' for e in self.peer.sent))
        await self.peer.queue.put({'type':'websocket.receive', 'text':'{"type":"finish"}'})
        await asyncio.wait_for(task, 1)
        self.assertEqual(self.peer.sent[-1]['text'], 'Bonjour Pepper.')
        config = self.upstream.sent[0]['session']['audio']['input']
        self.assertEqual(config['format']['rate'], 24000)
        self.assertEqual(config['transcription']['model'], 'gpt-live-transcribe')
        self.assertIsNone(config['turn_detection'])
        self.assertNotIn('test-only', json.dumps(self.peer.sent))

    async def test_cancel_does_not_commit(self):
        await self.peer.queue.put({'type':'websocket.receive', 'text':'{"type":"cancel"}'})
        await self.session.relay(self.upstream)
        self.assertFalse(any(e['type'] == 'input_audio_buffer.commit' for e in self.upstream.sent))

    async def test_invalid_audio_never_leaves_antenna(self):
        await self.peer.queue.put({'type':'websocket.receive', 'bytes':b'odd'})
        with self.assertRaisesRegex(Exception, 'PCM'):
            await self.session.relay(self.upstream)
        self.assertEqual([e['type'] for e in self.upstream.sent], ['session.update'])

    async def test_provider_error_is_sanitized(self):
        await self.upstream.queue.put(json.dumps({'type':'error', 'error':{'message':'secret-test-value'}}))
        with self.assertRaises(Exception) as failure:
            await self.session.relay(self.upstream)
        self.assertNotIn('secret-test-value', str(failure.exception))

    async def test_timeout_cleans_up_pending_receivers(self):
        with self.assertRaises(TimeoutError):
            await self.session.relay(self.upstream)
        self.assertFalse(any(e['type'] == 'final' for e in self.peer.sent))

    def test_resampling_keeps_all_samples_across_frame_boundaries(self):
        converter = PcmResampler()
        result = b''.join(converter.convert(b'\x10\x00' * 320) for _ in range(50))
        result += converter.convert(None)
        self.assertEqual(len(result), 24000 * 2)

    def test_live_model_http_fallback_uses_file_compatible_model(self):
        from types import SimpleNamespace
        calls = []
        client = SimpleNamespace(audio=SimpleNamespace(transcriptions=SimpleNamespace(
            create=lambda **kw: calls.append(kw) or SimpleNamespace(text='bonjour'))))
        OpenAiSttConnector().transcribe({'api_key':'test'}, b'\x00\x00'*8000,
                                      model='gpt-live-transcribe', client=client)
        self.assertEqual(calls[0]['model'], 'gpt-4o-mini-transcribe')
