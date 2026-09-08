package com.brutus.pepper

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.human.Human
import com.aldebaran.qi.sdk.`object`.humanawareness.HumanAwareness

/**
 * Wraps HumanAwareness.OnRecommendedHumanToEngageChangedListener to trigger automatic
 * engagement when a human is detected, subject to anti-spam cooldown.
 *
 * @param qiContext     the active robot context
 * @param shouldEngage  returns true only when autoEngage pref is ON and the robot is free
 * @param engage        called to trigger the engagement action
 */
class HumanEngagementListener(
    private val qiContext: QiContext,
    private val shouldEngage: () -> Boolean,
    private val engage: () -> Boolean,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {
    private var lastEngageTimeMs: Long = 0L
    private var lastHuman: Human? = null
    private var resetLastHumanRunnable: Runnable? = null

    /**
     * Vrai tant que Pepper voit quelqu'un devant lui.
     *
     * Sert à savoir quand le hall s'est vidé : c'est à ce moment-là que l'image
     * d'accueil revient et que le robot se remet face à l'entrée. Volatile parce que
     * le battement de MainActivity la lit hors du fil principal.
     *
     * Suit le même anti-rebond que l'engagement : une personne perdue une seconde
     * parce qu'elle s'est tournée ne doit pas faire croire que la salle est vide.
     */
    @Volatile
    var humanPresent: Boolean = false
        private set

    private val listener = HumanAwareness.OnRecommendedHumanToEngageChangedListener { human ->
        // This callback arrives on a QiSDK thread; dispatch to main thread for state access
        mainHandler.post { onRecommendedHumanChanged(human) }
    }

    /**
     * Start listening. Registers the listener and immediately checks for a current recommendation.
     * Uses humanAwarenessAsync / thenConsume to run off the main thread (QiSDK requirement).
     */
    fun start() {
        Log.d(TAG, "start()")
        qiContext.humanAwarenessAsync.thenConsume { futureAwareness ->
            if (futureAwareness.hasError() || futureAwareness.isCancelled) {
                Log.w(TAG, "humanAwarenessAsync failed: ${runCatching { futureAwareness.error }.getOrNull()}")
                return@thenConsume
            }
            val awareness = futureAwareness.value ?: return@thenConsume
            awareness.addOnRecommendedHumanToEngageChangedListener(listener)
            // Immediate check: if a human is already recommended, trigger right away
            val current = runCatching { awareness.getRecommendedHumanToEngage() }.getOrNull()
            Log.d(TAG, "listener registered, immediate recommended human=$current")
            if (current != null) {
                mainHandler.post { onRecommendedHumanChanged(current) }
            }
        }
    }

    /**
     * Stop listening and reset internal state.
     */
    fun stop() {
        Log.d(TAG, "stop()")
        qiContext.humanAwarenessAsync.thenConsume { futureAwareness ->
            if (!futureAwareness.hasError() && !futureAwareness.isCancelled) {
                runCatching {
                    futureAwareness.value?.removeAllOnRecommendedHumanToEngageChangedListeners()
                }.onFailure { Log.w(TAG, "removeAllListeners failed", it) }
            }
        }
        // Reset state on main thread
        mainHandler.post {
            resetLastHumanRunnable?.let {
                mainHandler.removeCallbacks(it)
                resetLastHumanRunnable = null
            }
            lastHuman = null
            lastEngageTimeMs = 0L
            humanPresent = false
        }
    }

    /**
     * Force-checks if there is currently a recommended human and attempts to engage.
     * Useful when the robot transitions to idle, to catch humans we missed while busy.
     */
    fun checkEngagement() {
        qiContext.humanAwarenessAsync.thenConsume { futureAwareness ->
            if (!futureAwareness.hasError() && !futureAwareness.isCancelled) {
                val awareness = futureAwareness.value ?: return@thenConsume
                val current = runCatching { awareness.getRecommendedHumanToEngage() }.getOrNull()
                if (current != null) {
                    mainHandler.post { onRecommendedHumanChanged(current) }
                }
            }
        }
    }

    // Called on main thread
    private fun onRecommendedHumanChanged(human: Human?) {
        if (human == null) {
            // Human lost — debounce reset so jitter doesn't clear lastHuman immediately
            if (resetLastHumanRunnable == null) {
                val runnable = Runnable {
                    Log.d(TAG, "Debounce timer expired: resetting lastHuman to null")
                    lastHuman = null
                    humanPresent = false
                    resetLastHumanRunnable = null
                }
                resetLastHumanRunnable = runnable
                mainHandler.postDelayed(runnable, DEBOUNCE_HUMAN_LOSS_MS)
            }
            return
        }

        humanPresent = true
        // Cancel pending reset if human returns
        resetLastHumanRunnable?.let {
            mainHandler.removeCallbacks(it)
            resetLastHumanRunnable = null
            Log.d(TAG, "Human returned before debounce timer expired, cancelled reset")
        }

        // Only engage on a newly-recommended human (transition, not the same object repeated)
        if (human == lastHuman) return

        if (!shouldEngage()) {
            Log.d(TAG, "human detected but shouldEngage()=false, skipping")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastEngageTimeMs < ANTI_SPAM_MS) {
            Log.d(TAG, "human detected but within cooldown (${now - lastEngageTimeMs} ms < $ANTI_SPAM_MS ms), skipping")
            return
        }

        Log.i(TAG, "Engaging detected human")
        if (engage()) {
            lastHuman = human
            lastEngageTimeMs = now
        } else {
            Log.d(TAG, "Engagement action rejected by coordinator (robot busy or not focused)")
        }
    }

    companion object {
        private const val TAG = "HumanEngagementListener"
        private const val ANTI_SPAM_MS = 15_000L
        private const val DEBOUNCE_HUMAN_LOSS_MS = 10_000L
    }
}
