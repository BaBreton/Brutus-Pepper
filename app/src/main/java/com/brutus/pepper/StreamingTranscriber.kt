package com.brutus.pepper

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class StreamingAudioLimits(
    val finalTimeoutMs: Long = 25_000,
    val maxAudioBytes: Int = 1_920_000,
    val maxQueuedBytes: Long = 128_000,
    val sessionTimeoutMs: Long = 75_000
)

/** Sends copied PCM while recording; failed sockets fall back with the COMPLETE capture. */
class StreamingTranscriber(
    private val settingsProvider: () -> BrainSettings,
    private val fallback: WhisperRecognizer,
    private val sockets: WebSocket.Factory = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).pingInterval(10, TimeUnit.SECONDS).build(),
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val limits: StreamingAudioLimits = StreamingAudioLimits()
) {
    private var active: Capture? = null
    private var closed = false
    private val fallbackRunning = AtomicBoolean(false)

    @Synchronized fun begin(onPartial: (String) -> Unit): Capture {
        check(!closed) { "Transcription fermée" }
        active?.cancel()
        return Capture(onPartial).also { active = it; it.open() }
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        active?.cancel()
        scheduler.shutdownNow()
        (sockets as? OkHttpClient)?.let { it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() }
    }

    @Synchronized fun cancel() { active?.cancel(); active = null }

    inner class Capture(private val onPartial: (String) -> Unit) : WebSocketListener() {
        private var socket: WebSocket? = null
        private val offered = ByteArrayOutputStream()
        private var failed = false
        private var ended = false
        private var finishing = false
        private var fallbackStarted = false
        private var complete: ((Result<WhisperResult>) -> Unit)? = null
        private var samples: ShortArray? = null
        private var deadline: ScheduledFuture<*>? = null
        private var sessionDeadline: ScheduledFuture<*>? = null

        internal fun open() {
            runCatching {
                val settings = settingsProvider()
                check(settings.isComplete)
                val request = Request.Builder()
                    .url(settings.normalizedUrl() + "/api/robot/transcribe/stream")
                    .header("Authorization", "Bearer ${settings.pairingToken}").build()
                socket = sockets.newWebSocket(request, this)
                if (failed) socket?.cancel()
                sessionDeadline = scheduler.schedule({ failStream() }, limits.sessionTimeoutMs, TimeUnit.MILLISECONDS)
            }.onFailure { failStream() }
        }

        @Synchronized fun offer(chunk: ShortArray) {
            if (ended || finishing || failed || chunk.isEmpty()) return
            val pcm = BrainClient.toLittleEndianBytes(chunk)
            if (offered.size() + pcm.size > limits.maxAudioBytes ||
                (socket?.queueSize() ?: 0) > limits.maxQueuedBytes) {
                failStream(); return
            }
            offered.write(pcm)
            // OkHttp enqueues immediately, including before the handshake, never blocks mic.
            if (socket?.send(pcm.toByteString()) != true) failStream()
        }

        @Synchronized fun finish(audio: ShortArray, onComplete: (Result<WhisperResult>) -> Unit) {
            if (ended || finishing) return
            finishing = true
            complete = onComplete
            if (audio.isEmpty()) {
                deliver(Result.success(WhisperResult("", 0)))
                return
            }
            if (audio.size > limits.maxAudioBytes / 2) {
                deliver(Result.failure(IllegalArgumentException("Phrase trop longue")))
                return
            }
            samples = audio.copyOf()
            if (!offered.toByteArray().contentEquals(BrainClient.toLittleEndianBytes(audio))) failed = true
            offered.reset()
            if (failed) { useFallback(); return }
            deadline = scheduler.schedule({ failStream() }, limits.finalTimeoutMs, TimeUnit.MILLISECONDS)
            if (socket?.send("""{"type":"finish"}""") != true) failStream()
        }

        @Synchronized fun cancel() {
            if (ended) return
            ended = true
            complete = null
            samples = null
            offered.reset()
            deadline?.cancel(false)
            sessionDeadline?.cancel(false)
            socket?.cancel()
        }

        @Synchronized override fun onMessage(webSocket: WebSocket, text: String) {
            if (ended || failed) return
            if (text.length > 20_000) { failStream(); return }
            runCatching {
                val event = JsonParser.parseString(text).asJsonObject
                when (event.get("type")?.asString) {
                    "ready" -> Unit
                    "partial" -> if (!finishing) onPartial(event.get("text").asString.take(4000))
                    "final" -> {
                        check(finishing) { "Unexpected final" }
                        val value = event.get("text").asString.trim()
                        deliver(Result.success(WhisperResult(value,
                            event.get("elapsed_ms")?.asLong?.coerceAtLeast(0) ?: 0)))
                    }
                    else -> failStream()
                }
            }.onFailure { failStream() }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failStream() }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { failStream() }

        @Synchronized private fun failStream() {
            if (ended) return
            failed = true
            socket?.cancel()
            deadline?.cancel(false)
            sessionDeadline?.cancel(false)
            if (finishing) useFallback()
        }

        private fun useFallback() {
            if (ended || fallbackStarted) return
            fallbackStarted = true
            socket?.cancel()
            if (!fallbackRunning.compareAndSet(false, true)) {
                deliver(Result.failure(IllegalStateException("La transcription précédente se termine. Réessayez.")))
                return
            }
            val released = AtomicBoolean(false)
            try {
                fallback.transcribe(samples ?: ShortArray(0)) { result ->
                    if (released.compareAndSet(false, true)) {
                        fallbackRunning.set(false)
                        synchronized(this) { deliver(result) }
                    }
                }
            } catch (_: Exception) {
                if (released.compareAndSet(false, true)) fallbackRunning.set(false)
                deliver(Result.failure(IllegalStateException("Antenne indisponible")))
            }
        }

        private fun deliver(result: Result<WhisperResult>) {
            if (ended) return
            val callback = complete
            ended = true
            complete = null
            samples = null
            offered.reset()
            deadline?.cancel(false)
            sessionDeadline?.cancel(false)
            socket?.cancel()
            callback?.invoke(result)
        }
    }
}
