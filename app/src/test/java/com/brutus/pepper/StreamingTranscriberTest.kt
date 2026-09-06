package com.brutus.pepper

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamingTranscriberTest {
    private val sockets = FakeSockets()
    private val fallback = FakeRecognizer()
    private var settings = BrainSettings("https://brain.example:8770", "pairing-token")
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var streamer = StreamingTranscriber({ settings }, fallback, sockets, scheduler,
        StreamingAudioLimits())

    @After fun close() { streamer.close() }

    @Test fun authenticatedPcmIsSentBeforeFinishAndCallerMutationCannotChangeIt() {
        val partials = mutableListOf<String>()
        val capture = streamer.begin { partials += it }
        val socket = sockets.last
        assertEquals("Bearer pairing-token", socket.request().header("Authorization"))
        assertEquals("/api/robot/transcribe/stream", socket.request().url.encodedPath)
        assertNull(socket.request().url.query)
        val chunk = shortArrayOf(0x1234, -2)
        capture.offer(chunk)
        chunk[0] = 99
        assertArrayEquals(byteArrayOf(0x34, 0x12, -2, -1), socket.binary.single().toByteArray())
        socket.event("""{"type":"ready"}""")
        socket.event("""{"type":"partial","text":"bonjour"}""")
        assertEquals(listOf("bonjour"), partials)
        val results = mutableListOf<Result<WhisperResult>>()
        capture.finish(shortArrayOf(0x1234, -2)) { results += it }
        assertEquals(listOf("""{"type":"finish"}"""), socket.text)
        socket.event("""{"type":"final","text":"bonjour Pepper","elapsed_ms":120}""")
        socket.event("""{"type":"final","text":"duplicate","elapsed_ms":120}""")
        socket.fail()
        assertEquals(1, results.size)
        assertEquals(WhisperResult("bonjour Pepper", 120), results.single().getOrThrow())
        assertEquals(0, fallback.calls.size)
    }

    @Test fun socketFailureFallsBackOnceWithSavedCompleteSamples() {
        val capture = streamer.begin {}
        capture.offer(shortArrayOf(1, 2))
        sockets.last.fail()
        assertTrue(fallback.calls.isEmpty())
        val samples = shortArrayOf(1, 2, 3, 4)
        val results = mutableListOf<Result<WhisperResult>>()
        capture.finish(samples) { results += it }
        samples[0] = 100
        capture.finish(shortArrayOf(99)) { fail("duplicate finish") }
        sockets.last.fail()
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), fallback.calls.single())
        fallback.complete(Result.success(WhisperResult("HTTP", 1)))
        fallback.complete(Result.success(WhisperResult("duplicate", 2)))
        assertEquals(1, results.size)
        assertEquals("HTTP", results.single().getOrThrow().text)
    }

    @Test fun cancellationSuppressesLateSocketAndHttpCallbacksAndDoesNotCloseSharedFallback() {
        val partials = mutableListOf<String>()
        val capture = streamer.begin { partials += it }
        capture.offer(shortArrayOf(1))
        sockets.last.fail()
        capture.finish(shortArrayOf(1)) { fail("cancelled capture must not complete") }
        capture.cancel()
        sockets.last.event("""{"type":"partial","text":"stale"}""")
        fallback.complete(Result.success(WhisperResult("stale", 1)))
        assertTrue(partials.isEmpty())
        assertTrue(sockets.last.cancelled)
        streamer.close()
        assertFalse(fallback.closed)
    }

    @Test fun cancelBeforeFinishNeverStartsHttp() {
        val capture = streamer.begin {}
        capture.offer(shortArrayOf(1))
        capture.cancel()
        sockets.last.fail()
        capture.finish(shortArrayOf(1)) { fail("cancelled") }
        assertTrue(fallback.calls.isEmpty())
    }

    @Test fun reconnectDoesNotQueueFallbackBehindStillRunningCancelledHttp() {
        val first = streamer.begin {}
        sockets.last.fail()
        first.finish(shortArrayOf(1)) { fail("cancelled") }
        first.cancel()
        val second = streamer.begin {}
        sockets.last.fail()
        var result: Result<WhisperResult>? = null
        second.finish(shortArrayOf(2)) { result = it }
        assertTrue(result!!.isFailure)
        assertEquals(1, fallback.calls.size)
        fallback.complete(Result.success(WhisperResult("old", 1)))
        val third = streamer.begin {}
        sockets.last.fail()
        third.finish(shortArrayOf(3)) {}
        assertEquals(2, fallback.calls.size)
    }

    @Test fun failedSendAndQueueOverflowUseFullHttpAudio() {
        for (overflow in listOf(false, true)) {
            val capture = streamer.begin {}
            sockets.last.acceptSends = overflow
            sockets.last.queued = if (overflow) Long.MAX_VALUE else 0
            capture.offer(shortArrayOf(1, 2))
            capture.finish(shortArrayOf(1, 2)) {}
            assertArrayEquals(shortArrayOf(1, 2), fallback.calls.last())
            fallback.complete(Result.success(WhisperResult("ok", 0)))
        }
        assertEquals(2, fallback.calls.size)
    }

    @Test fun missingOrChangedChunksCannotProduceTruncatedStreamFinal() {
        for (samples in listOf(shortArrayOf(1, 2, 3), shortArrayOf(1, 9))) {
            val capture = streamer.begin {}
            capture.offer(shortArrayOf(1, 2))
            sockets.last.event("""{"type":"ready"}""")
            capture.finish(samples) {}
            assertArrayEquals(samples, fallback.calls.last())
            fallback.complete(Result.success(WhisperResult("ok", 0)))
        }
    }

    @Test fun silenceReturnsEmptyWithoutSubmittingCloudAudio() {
        val capture = streamer.begin {}
        capture.offer(shortArrayOf(1, 2))
        var result: Result<WhisperResult>? = null
        capture.finish(shortArrayOf()) { result = it }
        assertEquals("", result!!.getOrThrow().text)
        assertTrue(fallback.calls.isEmpty())
        assertTrue(sockets.last.cancelled)
    }

    @Test fun malformedMessageServerErrorOrPrematureCloseTriggerFallback() {
        for (message in listOf("not-json", "[]", """{"type":"error","code":"timeout"}""")) {
            val capture = streamer.begin {}
            sockets.last.event(message)
            capture.finish(shortArrayOf(1)) {}
            fallback.complete(Result.success(WhisperResult("ok", 0)))
        }
        val capture = streamer.begin {}
        sockets.last.listener.onClosed(sockets.last, 1000, "")
        capture.finish(shortArrayOf(1)) {}
        assertEquals(4, fallback.calls.size)
    }

    @Test fun finishWaitHasDeadlineAndFallsBackWhenSocketNeverAnswers() {
        streamer.close()
        streamer = StreamingTranscriber({ settings }, fallback, sockets,
            Executors.newSingleThreadScheduledExecutor(),
            StreamingAudioLimits(finalTimeoutMs = 25))
        val capture = streamer.begin {}
        capture.offer(shortArrayOf(1))
        sockets.last.event("""{"type":"ready"}""")
        capture.finish(shortArrayOf(1)) {}
        assertTrue(fallback.started.await(1, TimeUnit.SECONDS))
        assertEquals(1, fallback.calls.size)
    }

    @Test fun newCaptureCancelsOldAndClosedTranscriberCannotRestart() {
        val old = streamer.begin {}
        val oldSocket = sockets.last
        streamer.begin {}
        old.finish(shortArrayOf(1)) { fail("old capture") }
        assertTrue(oldSocket.cancelled)
        streamer.close()
        assertThrows(IllegalStateException::class.java) { streamer.begin {} }
    }

    private class FakeSockets : WebSocket.Factory {
        lateinit var last: FakeSocket
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeSocket(request, listener).also { last = it }
    }

    private class FakeSocket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val binary = mutableListOf<ByteString>()
        val text = mutableListOf<String>()
        var acceptSends = true
        var queued = 0L
        var cancelled = false
        override fun request() = request
        override fun queueSize() = queued
        override fun send(bytes: ByteString): Boolean { binary += bytes; return acceptSends }
        override fun send(text: String): Boolean { this.text += text; return acceptSends }
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
        fun event(text: String) = listener.onMessage(this, text)
        fun fail() = listener.onFailure(this, IOException("socket failed"), null as Response?)
    }

    private class FakeRecognizer : WhisperRecognizer {
        val calls = mutableListOf<ShortArray>()
        val started = CountDownLatch(1)
        private var callback: ((Result<WhisperResult>) -> Unit)? = null
        var closed = false
        override fun load(onComplete: (Result<Long>) -> Unit) = onComplete(Result.success(0L))
        override fun transcribe(samples: ShortArray, onComplete: (Result<WhisperResult>) -> Unit) {
            calls += samples
            callback = onComplete
            started.countDown()
        }
        fun complete(result: Result<WhisperResult>) { callback!!(result) }
        override fun close() { closed = true }
    }
}
