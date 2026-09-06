package com.brutus.pepper

import android.os.Handler
import android.os.Looper
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import android.util.Log
import com.aldebaran.qi.sdk.builder.AnimateBuilder
import com.aldebaran.qi.sdk.builder.AnimationBuilder
import com.aldebaran.qi.sdk.builder.ApproachHumanBuilder
import com.aldebaran.qi.sdk.builder.SayBuilder
import java.util.concurrent.CancellationException

class QiRobotActions(
    private val qiContext: QiContext,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) : RobotActions {
    private var current: Future<Void>? = null

    override fun run(action: BoundAction, onComplete: (Result<Unit>) -> Unit) {
        val future = when (action) {
            BoundAction.RAISE_BOTH_ARMS -> runAnimation(R.raw.raise_both_hands_b001)
            BoundAction.ALTERNATE_ARMS -> runAnimation(R.raw.raise_left_hand_b001)
                .andThenCompose { runAnimation(R.raw.raise_right_hand_b001) }
            BoundAction.BONJOUR -> SayBuilder.with(qiContext)
                .withText("Bonjour !")
                .buildAsync()
                .andThenCompose { it.async().run() }
            BoundAction.POINT_RIGHT  -> runAnimation(R.raw.point_right_b001)
            BoundAction.POINT_LEFT   -> runAnimation(R.raw.point_left_b001)
            BoundAction.TURN_AROUND  -> runTurnAround()
            BoundAction.ENGAGE_HUMAN -> runEngageHuman()
            else -> return onComplete(Result.success(Unit))
        }
        current = future
        future.thenConsume { completed ->
            val result = when {
                completed.isCancelled -> Result.failure(CancellationException("Action annulée"))
                completed.hasError() -> Result.failure(completed.error)
                else -> Result.success(Unit)
            }
            mainHandler.post { onComplete(result) }
        }
    }

    override fun cancel() {
        current?.requestCancellation()
        current = null
    }

    private fun runEngageHuman(): Future<Void> {
        // humanAwarenessAsync ensures the callback runs on a QiSDK thread, not the main thread,
        // which is required for the synchronous getRecommendedHumanToEngage / getHumansAround calls.
        return qiContext.humanAwarenessAsync
            .andThenCompose<Void> { awareness ->
                val human = awareness.getRecommendedHumanToEngage()
                    ?.also { Log.i(TAG, "runEngageHuman: recommended human found") }
                    ?: awareness.getHumansAround().firstOrNull()
                        ?.also { Log.i(TAG, "runEngageHuman: fallback to humansAround") }
                if (human == null) {
                    Log.w(TAG, "runEngageHuman: no human detected (humansAround=${runCatching { awareness.getHumansAround().size }.getOrDefault(0)})")
                    noop()
                } else {
                    Log.i(TAG, "runEngageHuman: starting ApproachHuman")
                    ApproachHumanBuilder.with(qiContext)
                        .withHuman(human)
                        .buildAsync()
                        .andThenCompose { it.async().run() }
                }
            }
    }

    /** Full 360° spin in place, reusing the holonomic animation mechanism (yaw = 2π). */
    private fun runTurnAround(): Future<Void> {
        val command = HolonomicDriveCommand(0.0, 0.0, 2 * Math.PI, durationSeconds = 5.0)
        return AnimationBuilder.with(qiContext)
            .withTexts(command.toAnimationText())
            .buildAsync()
            .andThenCompose { animation ->
                AnimateBuilder.with(qiContext)
                    .withAnimation(animation)
                    .buildAsync()
                    .andThenCompose { it.async().run() }
            }
    }

    private fun noop(): Future<Void> {
        val p = com.aldebaran.qi.Promise<Void>()
        p.setValue(null)
        return p.future
    }

    companion object { private const val TAG = "BrutusRobotActions" }

    private fun runAnimation(resource: Int): Future<Void> = AnimationBuilder.with(qiContext)
        .withResources(resource)
        .buildAsync()
        .andThenCompose { animation ->
            AnimateBuilder.with(qiContext)
                .withAnimation(animation)
                .buildAsync()
                .andThenCompose { it.async().run() }
        }
}
