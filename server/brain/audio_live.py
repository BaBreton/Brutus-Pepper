"""Opt-in cloud STT. One upstream session per utterance; no API key on Pepper.

Protocol: https://developers.openai.com/api/docs/guides/realtime-transcription
Provider access and latency must be validated with the client's account/audio.
"""
import asyncio
import base64
import json
import time

import av
import numpy as np

from brain.audio_stream import AudioSession, AudioProtocolError

ENDPOINT = 'wss://api.openai.com/v1/realtime?intent=transcription'


class PcmResampler:
    """Stateful band-limited 16 → 24 kHz conversion, including filter tail."""
    def __init__(self):
        self.resampler = av.AudioResampler(format='s16', layout='mono', rate=24000)

    def convert(self, pcm: bytes | None) -> bytes:
        frame = None
        if pcm is not None:
            frame = av.AudioFrame.from_ndarray(np.frombuffer(pcm, dtype='<i2').reshape(1, -1),
                                               format='s16', layout='mono')
            frame.sample_rate = 16000
        return b''.join(output.to_ndarray().astype('<i2', copy=False).tobytes()
                        for output in self.resampler.resample(frame))


class OpenAiLiveSession(AudioSession):
    async def run(self):
        from websockets.asyncio.client import connect
        try:
            key = self.transcribe.keywords['credentials'].get('api_key', '').strip()
            if not key:
                raise AudioProtocolError('not_configured', 'Clé OpenAI absente sur l’antenne.')
            # Fixed provider endpoint, TLS and header auth. Never log upstream errors.
            async with connect(ENDPOINT, additional_headers={'Authorization': 'Bearer ' + key},
                               open_timeout=5, close_timeout=1, max_size=65536,
                               max_queue=8, write_limit=65536) as upstream:
                await self.relay(upstream)
        except AudioProtocolError as error:
            await self.send({'type':'error', 'code':error.code, 'message':error.message})
        except Exception:
            await self.send({'type':'error', 'code':'provider_error',
                             'message':'Transcription en continu indisponible. Repli sur la transcription complète.'})
        finally:
            self.pcm.clear()
            try:
                await asyncio.wait_for(self.ws.close(code=1000), self.limits.send_timeout_seconds)
            except Exception:
                pass

    async def relay(self, upstream):
        async def send(event):
            await asyncio.wait_for(upstream.send(json.dumps(event)), self.limits.send_timeout_seconds)

        async def event():
            raw = await upstream.recv()
            if len(raw) > 65536:
                raise AudioProtocolError('provider_error', 'Événement fournisseur trop grand.')
            value = json.loads(raw)
            if not isinstance(value, dict) or value.get('type') in (
                    'error', 'conversation.item.input_audio_transcription.failed'):
                raise AudioProtocolError('provider_error', 'Le fournisseur ne peut pas transcrire cette prise.')
            return value

        await send({'type':'session.update', 'session':{'type':'transcription', 'audio':{'input':{
            'format':{'type':'audio/pcm', 'rate':24000},
            'transcription':{'model':'gpt-live-transcribe', 'languages':[self.transcribe.keywords['language']],
                             'keywords':['Pepper'], 'delay':'low'},
            'turn_detection':None}}}})
        async with asyncio.timeout(min(5, self.limits.session_timeout_seconds)):
            while (await event()).get('type') != 'session.updated':
                pass
        await self.send({'type':'ready', 'sample_rate':16000, 'encoding':'pcm_s16le',
                         'channels':1, 'partials':True, 'engine':'openai-live'})
        converter = PcmResampler()

        async def append(pcm):
            if pcm:
                await send({'type':'input_audio_buffer.append',
                            'audio':base64.b64encode(pcm).decode('ascii')})

        async def from_robot():
            while True:
                message = await asyncio.wait_for(self.ws.receive(), self.limits.idle_timeout_seconds
                                                 if self.finishing is None else self.limits.final_timeout_seconds)
                was_finishing = self.finishing is not None
                if not self.receive(message):
                    return
                if message.get('bytes') is not None:
                    await append(converter.convert(message['bytes']))
                elif not was_finishing and self.finishing is not None:
                    await append(converter.convert(None))
                    await send({'type':'input_audio_buffer.commit'})

        async def from_provider():
            text, item_id = '', None
            while True:
                value = await event()
                kind = value.get('type')
                if kind not in ('input_audio_buffer.committed',
                                 'conversation.item.input_audio_transcription.delta',
                                 'conversation.item.input_audio_transcription.completed'):
                    continue
                incoming = value.get('item_id')
                if not isinstance(incoming, str) or (item_id is not None and incoming != item_id):
                    raise AudioProtocolError('provider_error', 'Tour de transcription inattendu.')
                item_id = incoming
                if kind.endswith('.delta'):
                    delta = value.get('delta')
                    if not isinstance(delta, str):
                        raise AudioProtocolError('provider_error', 'Transcription intermédiaire invalide.')
                    text += delta
                    if len(text) > self.limits.max_text_chars:
                        raise AudioProtocolError('text_limit', 'Transcription trop longue.')
                    if self.finishing is None:
                        await self.send({'type':'partial', 'text':text})
                elif kind.endswith('.completed'):
                    final = value.get('transcript')
                    if self.finishing is None or not isinstance(final, str) or len(final) > self.limits.max_text_chars:
                        raise AudioProtocolError('provider_error', 'Transcription finale invalide.')
                    await self.send({'type':'final', 'text':final.strip(),
                                     'elapsed_ms':int((time.monotonic()-self.started)*1000)})
                    return

        tasks = [asyncio.create_task(from_robot()), asyncio.create_task(from_provider())]
        try:
            async with asyncio.timeout(self.limits.session_timeout_seconds):
                done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    task.result()
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
