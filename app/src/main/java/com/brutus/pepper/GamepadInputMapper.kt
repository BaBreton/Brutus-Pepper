package com.brutus.pepper

import kotlin.math.abs

class GamepadInputMapper(
    var customKeycodes: Map<Int, GamepadControl> = emptyMap(),
    private val triggerThreshold: Float = 0.55f
) {
    private val triggerStates = mutableMapOf<GamepadControl, Boolean>()

    fun centeredAxis(value: Float, flat: Float): Float = if (abs(value) <= flat) 0f else value

    fun controlForKeyCode(keyCode: Int): GamepadControl? {
        // Custom keycodes take priority over defaults
        customKeycodes[keyCode]?.let { return it }
        return when (keyCode) {
            96  -> GamepadControl.A
            97  -> GamepadControl.B
            99  -> GamepadControl.X
            100 -> GamepadControl.Y
            102 -> GamepadControl.LEFT_BUMPER
            103 -> GamepadControl.RIGHT_BUMPER
            104 -> GamepadControl.LEFT_TRIGGER
            105 -> GamepadControl.RIGHT_TRIGGER
            109 -> GamepadControl.SELECT
            108 -> GamepadControl.START
            else -> null
        }
    }

    fun triggerEdge(control: GamepadControl, value: Float): GamepadEdge? {
        require(control == GamepadControl.LEFT_TRIGGER || control == GamepadControl.RIGHT_TRIGGER)
        val pressed = value >= triggerThreshold
        val previous = triggerStates.put(control, pressed) ?: false
        return if (pressed == previous) null else GamepadEdge(control, pressed)
    }
}
