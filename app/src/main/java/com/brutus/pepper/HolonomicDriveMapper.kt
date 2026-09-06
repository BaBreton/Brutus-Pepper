package com.brutus.pepper

import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

data class HolonomicDriveCommand(
    val forwardMeters: Double,
    val lateralMeters: Double,
    val yawRadians: Double,
    val durationSeconds: Double = 40.0
) {
    val isNeutral: Boolean
        get() = forwardMeters == 0.0 && lateralMeters == 0.0 && yawRadians == 0.0

    fun toAnimationText(): String =
        "[\"Holonomic\", [\"Line\", [$forwardMeters, $lateralMeters]], " +
            "$yawRadians, $durationSeconds]"
}

class HolonomicDriveMapper {
    fun map(leftX: Float, leftY: Float, yawInput: Float): HolonomicDriveCommand {
        val translationMagnitude = speedBand(hypot(leftX.toDouble(), leftY.toDouble()))
        val direction = quantizedDirection(leftX, leftY)
        val yawMagnitude = speedBand(abs(yawInput.toDouble()))
        return HolonomicDriveCommand(
            forwardMeters = rounded(-sin(direction) * TRANSLATION_METERS * translationMagnitude),
            lateralMeters = rounded(-cos(direction) * TRANSLATION_METERS * translationMagnitude),
            yawRadians = rounded(-yawInput.sign() * YAW_RADIANS * yawMagnitude)
        )
    }

    private fun quantizedDirection(x: Float, y: Float): Double {
        if (hypot(x.toDouble(), y.toDouble()) <= DEAD_ZONE) return 0.0
        val angle = atan2(y.toDouble(), x.toDouble())
        return (angle / DIRECTION_STEP_RADIANS).roundToInt() * DIRECTION_STEP_RADIANS
    }

    private fun speedBand(rawMagnitude: Double): Double {
        if (rawMagnitude <= DEAD_ZONE) return 0.0
        val normalized = ((rawMagnitude.coerceAtMost(1.0) - DEAD_ZONE) / (1.0 - DEAD_ZONE))
        return (normalized * SPEED_BANDS).roundToInt()
            .coerceIn(1, SPEED_BANDS) / SPEED_BANDS.toDouble()
    }

    private fun rounded(value: Double): Double =
        (value * OUTPUT_PRECISION).roundToInt() / OUTPUT_PRECISION

    private fun Float.sign(): Double = when {
        this > DEAD_ZONE -> 1.0
        this < -DEAD_ZONE -> -1.0
        else -> 0.0
    }

    companion object {
        private const val DEAD_ZONE = 0.2
        private const val SPEED_BANDS = 4
        private const val DIRECTION_STEP_RADIANS = Math.PI / 4.0
        private const val OUTPUT_PRECISION = 1_000.0
        private const val TRANSLATION_METERS = 10.0
        private const val YAW_RADIANS = 12.0
    }
}
