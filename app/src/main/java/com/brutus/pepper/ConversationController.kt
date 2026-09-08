package com.brutus.pepper

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Host interface that MainActivity implements to keep ConversationController UI-agnostic.
 */
interface WakeControl {
    fun start()
    fun stop()
    fun release()
    fun stopAndThen(callback: () -> Unit) { stop(); callback() }
}

interface ConversationHost {
    /**
     * Start a listening window with a specific onset timeout.
     * [onResult] is called (on the main thread) with the captured samples.
     */
    fun listen(timeoutMs: Long, onResult: (ShortArray) -> Unit)

    /**
     * Transcribe [samples] via Whisper. [onText] is called on the main thread with the text,
     * or null if recognition failed / result is blank.
     */
    fun transcribe(samples: ShortArray, onText: (String?) -> Unit)

    /**
     * Send [transcript] to the LLM, speak the response via TTS.
     * [onSpoken] is called after Pepper finishes speaking (or on error).
     */
    fun respond(transcript: String, onSpoken: () -> Unit)
}

/**
 * State machine for the hands-free conversation loop.
 *
 * States:
 *   IDLE_WAKE   — Vosk is listening for the wake-word
 *   LISTENING   — PcmAudioRecorder is capturing audio (Vosk stopped)
 *   TRANSCRIBING— Audio sent to Whisper
 *   THINKING    — LLM is processing
 *   SPEAKING    — TTS in progress
 *
 * Mic invariant: Vosk and recorder never run at the same time.
 *   - Before host.listen() → wake.stop()
 *   - After speaking → wake.start() (goIdle)
 */
class ConversationController(
    private val host: ConversationHost,
    private val wake: WakeControl,
    private val dispatch: (() -> Unit) -> Unit = { action ->
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else Handler(Looper.getMainLooper()).post { action() }
    }
) {
    enum class State { IDLE_WAKE, LISTENING, TRANSCRIBING, THINKING, SPEAKING }

    var state: State = State.IDLE_WAKE
        private set(value) {
            field = value
            onStateChanged(value)
        }

    /** Notified (on the main thread, where all transitions run) whenever the state changes. */
    var onStateChanged: (State) -> Unit = {}

    private var active = false      // true between enable() and disable()
    private var suspended = false   // true while PTT is active
    private var customListenTimeoutMs: Long? = null
    private var hadTurn = false      // true once a real user utterance was processed this conversation
    /** Sticky across mode/focus changes; only a wake-word or a manual request releases it. */
    var awaitingExplicitWake = false
        private set
    // Bumped on every new listen segment and on cancelTurn(); in-flight callbacks compare against it
    // and bail if it changed, so a cancelled turn's pending results are ignored.
    private var generation = 0L

    /** True while a turn is in progress (anything other than waiting for the wake-word). */
    fun isBusy(): Boolean = state != State.IDLE_WAKE

    /** Invoked when a conversation that had at least one real turn returns to idle. */
    var onConversationEnded: () -> Unit = {}

    // Minimum sample count considered "speech" (0.4s at 16 kHz)
    private val minSpeechSamples = (PcmAudio.SAMPLE_RATE * MIN_SPEECH_SECONDS).toInt()

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Enable conversation mode: start Vosk and wait for the wake-word.
     */
    fun enable() {
        dispatch {
            if (active && !suspended) return@dispatch
            generation++
            Log.i(TAG, "enable()")
            active = true
            suspended = false
            state = State.IDLE_WAKE
            wake.start()
        }
    }

    /**
     * Disable conversation mode: stop everything and release Vosk.
     */
    fun disable() {
        dispatch {
            generation++
            hadTurn = false
            customListenTimeoutMs = null
            Log.i(TAG, "disable()")
            active = false
            suspended = false
            state = State.IDLE_WAKE
            wake.release()
        }
    }

    /**
     * Called by WakeWordEngine when a keyword is detected.
     * If not in IDLE_WAKE (e.g. already speaking), the wake event is ignored.
     */
    fun onWake() {
        dispatch {
            if (state != State.IDLE_WAKE || !active || suspended) {
                Log.d(TAG, "onWake() ignored (state=$state active=$active suspended=$suspended)")
                return@dispatch
            }
            Log.i(TAG, "Wake-word detected → starting turn")
            awaitingExplicitWake = false
            beginListening()
        }
    }

    /**
     * PTT pressed: suspend Vosk so the manual recorder can own the mic.
     */
    fun suspendForPtt() {
        dispatch {
            generation++
            Log.d(TAG, "suspendForPtt()")
            state = State.IDLE_WAKE
            suspended = true
            wake.stop()  // release mic so PTT recorder can open it
        }
    }

    /**
     * PTT turn finished: re-arm the wake-word if conversation mode is still active.
     */
    fun resumeAfterPtt() {
        dispatch {
            Log.d(TAG, "resumeAfterPtt()")
            suspended = false
            if (active) {
                state = State.IDLE_WAKE
                wake.start()
            }
        }
    }

    /**
     * Force-starts a listening window directly (bypassing wake-word) with a custom timeout.
     */
    fun triggerDirectListening(timeoutMs: Long, automatic: Boolean = false) {
        dispatch {
            if (automatic && (!active || awaitingExplicitWake)) return@dispatch
            // Guard: if a turn is already in progress (e.g. the wake-word already opened the mic),
            // do NOT start a second listen — that would call recorder.start() twice and crash with
            // "Un enregistrement est déjà actif".
            if (state != State.IDLE_WAKE) {
                Log.d(TAG, "triggerDirectListening ignored — turn already active (state=$state)")
                return@dispatch
            }
            active = true
            suspended = false
            if (!automatic) awaitingExplicitWake = false
            customListenTimeoutMs = timeoutMs
            beginListening()
        }
    }

    /** Called before the response, including PTT, so pending hospitality cannot interrupt it. */
    fun acceptTranscript(text: String) = dispatch {
        awaitingExplicitWake = ConversationEnding.isExplicit(text)
    }

    fun finishPttTurn(timeoutMs: Long) = dispatch {
        if (awaitingExplicitWake) {
            suspended = false
            hadTurn = true
            goIdle()
        } else {
            triggerDirectListening(timeoutMs)
        }
    }

    /**
     * Cancel the in-progress turn: invalidate pending callbacks, stop the loop, return to idle.
     * (TTS itself is stopped by the host via speechGateway.cancel().)
     */
    fun cancelTurn() {
        dispatch {
            Log.i(TAG, "cancelTurn()")
            generation++           // any in-flight listen/transcribe/respond callback now bails
            hadTurn = false        // a cancelled turn doesn't count as a finished conversation
            suspended = false
            goIdle()
        }
    }

    /**
     * Le hall est resté calme : la personne suivante ne doit pas avoir à connaître le
     * mot d'éveil parce que la précédente a dit « au revoir ».
     */
    fun releaseExplicitWake() = dispatch { awaitingExplicitWake = false }

    /** Called immediately before the speech gateway starts, not after it finishes. */
    fun onSpeechStarted() = dispatch {
        if (active && !suspended && state == State.THINKING) state = State.SPEAKING
    }

    // ── State machine ────────────────────────────────────────────────────────

    /** IDLE_WAKE → LISTENING: stop wake-word, open microphone window. */
    private fun beginListening() {
        // Defense in depth: never open a second recording window while one is active.
        if (state == State.LISTENING) {
            Log.w(TAG, "beginListening ignored — already LISTENING")
            return
        }
        val gen = ++generation
        state = State.LISTENING
        wake.stop()  // mic invariant: wake-word must be stopped before recorder starts
        val timeout = customListenTimeoutMs ?: 10_000L
        customListenTimeoutMs = null
        Log.d(TAG, "state → LISTENING (timeout=$timeout ms)")
        wake.stopAndThen {
            dispatch {
                if (gen == generation && active && !suspended) {
                    host.listen(timeout) { samples -> if (gen == generation) onAudioCaptured(samples, gen) }
                }
            }
        }
    }

    /** LISTENING → TRANSCRIBING or back to IDLE_WAKE on silence/timeout. */
    private fun onAudioCaptured(samples: ShortArray, gen: Long) {
        if (!active) { goIdle(); return }

        if (samples.size < minSpeechSamples) {
            // Too short — no speech detected (onset timeout fired or empty capture)
            Log.i(TAG, "No speech captured (${samples.size} samples < $minSpeechSamples) → idle")
            goIdle()
            return
        }

        state = State.TRANSCRIBING
        Log.d(TAG, "state → TRANSCRIBING (${samples.size} samples)")
        host.transcribe(samples) { text ->
            if (gen != generation) return@transcribe   // turn cancelled / superseded
            if (!active) { goIdle(); return@transcribe }
            if (text.isNullOrBlank()) {
                Log.i(TAG, "Transcription empty → idle")
                goIdle()
            } else {
                onTranscript(text, gen)
            }
        }
    }

    /** TRANSCRIBING → THINKING: send to LLM + TTS. */
    private fun onTranscript(text: String, gen: Long) {
        hadTurn = true
        acceptTranscript(text)
        state = State.THINKING
        Log.d(TAG, "state → THINKING")
        host.respond(text) { if (gen == generation) onSpoken() }
    }

    /** SPEAKING → LISTENING (follow-up, no wake-word needed) or idle if disabled. */
    private fun onSpoken() {
        if (!active || suspended || awaitingExplicitWake) {
            Log.d(TAG, "onSpoken() but active=$active suspended=$suspended → idle")
            goIdle()
            return
        }
        Log.i(TAG, "Pepper finished speaking → follow-up listen")
        // Loop back to listening without requiring the wake-word again
        beginListening()
    }

    /** Return to IDLE_WAKE and re-arm the wake-word (only if still active). */
    private fun goIdle() {
        generation++ // Discard duplicate or delayed callbacks from the completed turn.
        state = State.IDLE_WAKE
        Log.d(TAG, "state → IDLE_WAKE")
        if (active && !suspended) {
            wake.start()
        }
        // A conversation with at least one real turn just ended → signal (resets engage gate
        // and LLM context so the next person starts fresh).
        if (hadTurn) {
            hadTurn = false
            Log.i(TAG, "Conversation ended → onConversationEnded")
            onConversationEnded()
        }
    }

    companion object {
        private const val TAG = "ConversationController"
        private const val MIN_SPEECH_SECONDS = 0.4
    }
}
