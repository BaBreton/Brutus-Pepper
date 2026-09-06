package com.brutus.pepper

data class DriveAxes(
    val leftX: Float,
    val leftY: Float,
    val yaw: Float
)

enum class DpadDirection { LEFT, RIGHT }

class GamepadDriveState {
    private var leftX = 0f
    private var leftY = 0f
    private var hatYaw = 0f
    private var dpadLeftPressed = false
    private var dpadRightPressed = false

    fun updateMotion(leftX: Float, leftY: Float, hatX: Float): DriveAxes {
        this.leftX = leftX
        this.leftY = leftY
        hatYaw = hatX.toDirection()
        return current()
    }

    fun updateKey(direction: DpadDirection, pressed: Boolean): DriveAxes {
        when (direction) {
            DpadDirection.LEFT -> dpadLeftPressed = pressed
            DpadDirection.RIGHT -> dpadRightPressed = pressed
        }
        return current()
    }

    fun current(): DriveAxes = DriveAxes(leftX, leftY, currentYaw())

    fun reset(): DriveAxes {
        leftX = 0f
        leftY = 0f
        hatYaw = 0f
        dpadLeftPressed = false
        dpadRightPressed = false
        return current()
    }

    private fun currentYaw(): Float = when {
        dpadLeftPressed && dpadRightPressed -> 0f
        dpadLeftPressed -> -1f
        dpadRightPressed -> 1f
        else -> hatYaw
    }

    private fun Float.toDirection(): Float = when {
        this < -DPAD_THRESHOLD -> -1f
        this > DPAD_THRESHOLD -> 1f
        else -> 0f
    }

    private companion object {
        const val DPAD_THRESHOLD = 0.5f
    }
}
