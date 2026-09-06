package com.brutus.pepper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class PcmAudioRecorder {
    private var thread: CaptureThread? = null
    private val released = mutableListOf<() -> Unit>()

    /**
     * Start recording.
     *
     * @param gateFactory  Fabrique le portier qui décide quand la personne a commencé
     *   et fini de parler. Une fabrique et non un portier déjà construit : allouer un
     *   décodeur de parole prend quelques dizaines de millisecondes, et ça se ferait
     *   sur le fil principal, juste après que Pepper a fini de parler. Null en
     *   push-to-talk : c'est le doigt de l'utilisateur qui commande, on ne coupe
     *   jamais tout seul.
     * @param maxDurationMs  Plafond absolu de la capture. Distinct du délai d'attente
     *   porté par le portier : les confondre tronquait une phrase longue au bout du
     *   temps prévu pour *commencer* à parler.
     * @param onStopped  Callback with the captured samples or an error.
     */
    @Synchronized
    fun start(
        gateFactory: (() -> SpeechGate)? = null,
        maxDurationMs: Long? = null,
        onLevel: ((Int) -> Unit)? = null,
        onChunk: ((ShortArray) -> Unit)? = null,
        onStopped: (Result<ShortArray>) -> Unit
    ) {
        check(thread == null) { "Un enregistrement est déjà actif" }
        thread = CaptureThread(gateFactory, maxDurationMs, onLevel, onChunk) { result ->
            val callbacks = synchronized(this) {
                thread = null
                released.toList().also { released.clear() }
            }
            onStopped(result)
            callbacks.forEach { it() }
        }.also(Thread::start)
    }

    @Synchronized
    fun stop() {
        thread?.requestStop()
    }

    fun stopAndThen(callback: () -> Unit) {
        val immediate = synchronized(this) {
            if (thread == null) true
            else { released.add(callback); thread?.requestStop(); false }
        }
        if (immediate) callback()
    }

    private class CaptureThread(
        private val gateFactory: (() -> SpeechGate)?,
        private val maxDurationMs: Long?,
        private val onLevel: ((Int) -> Unit)?,
        private val onChunk: ((ShortArray) -> Unit)?,
        private val onStopped: (Result<ShortArray>) -> Unit
    ) : Thread("PepperPtt") {
        private val stopping = AtomicBoolean(false)

        fun requestStop() {
            stopping.set(true)
        }

        @SuppressLint("MissingPermission")
        override fun run() {
            onStopped(runCatching { capture() })
        }

        @SuppressLint("MissingPermission")
        private fun capture(): ShortArray {
            val minimum = AudioRecord.getMinBufferSize(
                PcmAudio.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimum > 0) { "Configuration micro 16 kHz indisponible" }
            // Use 20ms chunks for responsive VAD
            val chunkSamples = PcmAudio.SAMPLE_RATE / 50
            val buffer = ShortArray(chunkSamples)
            val bytes = ByteArrayOutputStream()
            // Deux secondes de marge dans le tampon système, et non quelques blocs :
            // le décodage de parole s'intercale entre deux lectures, et sur une tablette
            // modeste un à-coup ferait perdre de l'audio — la personne se retrouverait
            // coupée en plein mot.
            val cushionSamples = PcmAudio.SAMPLE_RATE * 2
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                PcmAudio.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, cushionSamples * 2)
            )
            // En mode mains libres le portier décide de tout ; en push-to-talk il n'y
            // en a pas et c'est le doigt de l'utilisateur qui commande.
            val ceilingMs = maxDurationMs?.toInt()
                ?: (WhisperBenchmarkController.MAX_AUDIO_SECONDS * 1000)
            var gate: SpeechGate? = null

            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Micro non initialisé" }
                gate = gateFactory?.invoke()
                recorder.startRecording()
                val maximum = PcmAudio.SAMPLE_RATE * ceilingMs / 1000
                var total = 0
                var chunkCount = 0
                var decodeNanos = 0L

                while (!stopping.get() && total < maximum) {
                    val toRead = minOf(buffer.size, maximum - total)
                    val count = recorder.read(buffer, 0, toRead)
                    check(count >= 0) { "Erreur de lecture micro: $count" }
                    if (count == 0) continue

                    var sumSq = 0L
                    for (i in 0 until count) sumSq += buffer[i].toLong() * buffer[i].toLong()
                    val rms = Math.sqrt(sumSq.toDouble() / count).toInt()

                    chunkCount++
                    // Niveau live pour le vu-mètre (un bloc sur deux ≈ 25 Hz)
                    if (chunkCount % 2 == 0) onLevel?.invoke(rms)

                    if (gate != null) {
                        val before = System.nanoTime()
                        val decision = gate.accept(buffer, count, rms)
                        decodeNanos += System.nanoTime() - before
                        if (decision == SpeechGate.Decision.DONE) stopping.set(true)
                    }

                    for (index in 0 until count) {
                        val value = buffer[index].toInt()
                        bytes.write(value and 0xff)
                        bytes.write(value ushr 8 and 0xff)
                    }
                    onChunk?.invoke(buffer.copyOf(count))
                    total += count
                }
                recorder.stop()
                if (gate != null) {
                    val capturedMs = total * 1000 / PcmAudio.SAMPLE_RATE
                    val decodeMs = decodeNanos / 1_000_000
                    // Le rapport décodage/audio dit si la tablette suit le temps réel.
                    // Au-delà de 100 %, elle décroche et on perdrait de l'audio sans le
                    // coussin du tampon système : c'est la mesure à regarder en premier
                    // si quelqu'un se plaint d'être coupé.
                    val load = if (capturedMs > 0) decodeMs * 100 / capturedMs else 0
                    android.util.Log.i(
                        "PcmAudioRecorder",
                        "fin de capture : ${gate.describe()} durée=${capturedMs}ms " +
                            "analyse=${decodeMs}ms (${load}% du temps réel)"
                    )
                }
            } finally {
                recorder.release()
                // Le décodeur tient de la mémoire native : le laisser derrière soi à
                // chaque tour de conversation finirait par saturer la tablette.
                gate?.close()
            }

            // Rien n'a été dit : on rend un tableau vide plutôt que du bruit de fond,
            // que le cerveau refuserait de toute façon.
            if (gate != null && !gate.heardSpeech) return ShortArray(0)

            val raw = bytes.toByteArray()
            return ShortArray(raw.size / 2) { index ->
                ((raw[index * 2].toInt() and 0xff) or (raw[index * 2 + 1].toInt() shl 8)).toShort()
            }
        }
    }

}
