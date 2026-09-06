package com.brutus.pepper

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.AnimateBuilder
import com.aldebaran.qi.sdk.builder.AnimationBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Base control adapted from SoftBank Robotics Labs pepper-gamepad (BSD-3-Clause). */
class PepperDriveController(
    private val qiContext: QiContext,
    private val mapper: HolonomicDriveMapper = HolonomicDriveMapper()
) : RobotDrive {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var running = false
    private var command = NEUTRAL
    private var generation = 0L
    private var animateFuture: Future<Void>? = null

    override fun start() {
        if (running) return
        running = true
        worker.execute { launchCurrentCommand() }
        Log.i(TAG, "Holonomic drive started")
    }

    override fun stop() {
        if (!running && command.isNeutral) return
        running = false
        worker.execute {
            command = NEUTRAL
            generation += 1
            animateFuture?.requestCancellation()
        }
        Log.i(TAG, "Holonomic drive stopped")
    }

    /** Called by Android input state: left stick translation plus D-pad yaw. */
    fun updateTarget(leftX: Float, leftY: Float, yawInput: Float, unusedRightY: Float) {
        val next = mapper.map(leftX, leftY, yawInput)
        worker.execute { applyCommand(next) }
        Log.d(TAG, "axes lx=$leftX ly=$leftY dpadYaw=$yawInput ry=$unusedRightY -> $next")
    }

    fun close() {
        stop()
        worker.shutdown()
    }

    private fun applyCommand(next: HolonomicDriveCommand) {
        if (!running || next == command) return
        command = next
        generation += 1

        val active = animateFuture
        if (active != null && !active.isDone) {
            active.requestCancellation()
        } else {
            animateFuture = null
            launchCurrentCommand()
        }
    }

    private fun launchCurrentCommand() {
        if (!running || command.isNeutral || animateFuture?.isDone == false) return

        val launchedCommand = command
        val launchedGeneration = generation
        runCatching {
            val animation = AnimationBuilder.with(qiContext)
                .withTexts(launchedCommand.toAnimationText())
                .build()
            AnimateBuilder.with(qiContext)
                .withAnimation(animation)
                .build()
                .also { animate ->
                    animate.addOnStartedListener {
                        Log.i(TAG, "Holonomic motion confirmed by QiSDK: $launchedCommand")
                    }
                }
                .async()
                .run()
        }.onSuccess { future ->
            animateFuture = future
            Log.i(TAG, "Holonomic trajectory started: $launchedCommand")
            future.thenConsume { completed ->
                if (worker.isShutdown) return@thenConsume
                worker.execute {
                    if (animateFuture !== future) return@execute
                    animateFuture = null
                    when {
                        completed.hasError() -> {
                            Log.e(TAG, "Holonomic trajectory failed", completed.error)
                            if (launchedGeneration != generation) launchCurrentCommand()
                        }
                        completed.isCancelled -> {
                            Log.i(TAG, "Holonomic trajectory cancelled")
                            launchCurrentCommand()
                        }
                        else -> {
                            Log.i(TAG, "Holonomic trajectory completed")
                            launchCurrentCommand()
                        }
                    }
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to build Holonomic trajectory", error)
        }
    }

    companion object {
        private const val TAG = "BrutusPepperDrive"
        private val NEUTRAL = HolonomicDriveCommand(0.0, 0.0, 0.0)
    }
}
