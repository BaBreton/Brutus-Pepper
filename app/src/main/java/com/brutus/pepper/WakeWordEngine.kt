package com.brutus.pepper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.gson.JsonParser
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Local, restricted-vocabulary wake detection with real microphone levels. */
class WakeWordEngine(
    private val holder: VoskModelHolder,
    private val onLevel: (Int) -> Unit = {},
    private val onListening: (Boolean) -> Unit = {},
    private val onUnavailable: () -> Unit = {},
    private val onWake: () -> Unit
) : WakeControl {
    private val lock = Any()
    private var wanted = false
    private var generation = 0L
    private var capture: Capture? = null
    private val released = mutableListOf<() -> Unit>()

    override fun start() {
        val gen = synchronized(lock) { wanted = true; generation }
        holder.whenReady(
            onReady = { model ->
                synchronized(lock) {
                    if (wanted && gen == generation && capture == null) {
                        capture = Capture(model, gen).also { it.start() }
                    }
                }
            },
            onError = { onListening(false); onUnavailable(); Log.w(TAG, "Modèle du mot d’éveil indisponible") }
        )
    }

    override fun stop() {
        synchronized(lock) {
            generation++
            wanted = false
            capture?.stopping?.set(true)
        }
        onListening(false)
    }

    override fun stopAndThen(callback: () -> Unit) {
        val immediate = synchronized(lock) {
            generation++
            wanted = false
            if (capture == null) true
            else { released.add(callback); capture?.stopping?.set(true); false }
        }
        onListening(false)
        if (immediate) callback()
    }

    override fun release() = stop()

    private inner class Capture(private val model: Model, private val gen: Long) : Thread("PepperWake") {
        val stopping = AtomicBoolean(false)
        @SuppressLint("MissingPermission")
        override fun run() {
            var mic: AudioRecord? = null
            var decoder: Recognizer? = null
            var detected = false
            try {
                val grammar = WakeWordConfig.KEYWORDS.joinToString(prefix = "[", postfix = ", \"[unk]\"]") { "\"$it\"" }
                decoder = Recognizer(model, 16000f, grammar)
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
                check(mic.state == AudioRecord.STATE_INITIALIZED)
                mic.startRecording()
                if (!stopping.get()) onListening(true)
                val samples = ShortArray(320)
                var frames = 0
                while (!stopping.get()) {
                    val count = mic.read(samples, 0, samples.size)
                    check(count >= 0)
                    if (count == 0 || stopping.get()) continue
                    if (++frames % 2 == 0) {
                        var power = 0L
                        for (i in 0 until count) power += samples[i].toLong() * samples[i]
                        onLevel(sqrt(power.toDouble() / count).toInt())
                    }
                    val full = decoder.acceptWaveForm(samples, count)
                    val json = JsonParser.parseString(if (full) decoder.result else decoder.partialResult).asJsonObject
                    val words = (json.get(if (full) "text" else "partial")?.asString ?: "").lowercase().trim()
                    if (WakeWordConfig.KEYWORDS.any { words == it || words.split(' ').contains(it) }) {
                        detected = true
                        break
                    }
                }
            } catch (_: Exception) {
                if (!stopping.get()) onUnavailable()
                Log.w(TAG, "Écoute locale indisponible ; utilisez le bouton Parler")
            } finally {
                runCatching { mic?.stop() }
                mic?.release()
                decoder?.close()
                onLevel(0)
                onListening(false)
                val callbacks: List<() -> Unit>
                val fire: Boolean
                val restart: Boolean
                synchronized(lock) {
                    capture = null
                    fire = detected && wanted && gen == generation && !stopping.get()
                    restart = wanted && !fire && gen != generation
                    if (fire) wanted = false
                    callbacks = released.toList()
                    released.clear()
                }
                // The microphone is RELEASED before any consumer opens the next capture.
                callbacks.forEach { it() }
                if (fire) onWake()
                // this@WakeWordEngine : sans le qualificateur, Kotlin résout start()
                // vers Thread.start() de cette capture — déjà terminée — et lève
                // IllegalThreadStateException, ce qui tuait l'application. On veut
                // réarmer le moteur, pas relancer le fil qui vient de finir.
                else if (restart) this@WakeWordEngine.start()
            }
        }
    }

    companion object { private const val TAG = "PepperWake" }
}
