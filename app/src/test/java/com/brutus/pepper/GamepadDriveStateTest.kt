package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadDriveStateTest {
    private val state = GamepadDriveState()

    @Test
    fun `motion update retains left stick translation`() {
        assertEquals(DriveAxes(0.4f, -1f, 0f), state.updateMotion(0.4f, -1f, 0f))
    }

    @Test
    fun `hat right adds yaw without erasing translation`() {
        state.updateMotion(0f, -1f, 0f)

        assertEquals(DriveAxes(0f, -1f, 1f), state.updateMotion(0f, -1f, 1f))
    }

    @Test
    fun `dpad key left overrides hat and release restores it`() {
        state.updateMotion(0.2f, -0.8f, 1f)

        assertEquals(DriveAxes(0.2f, -0.8f, -1f), state.updateKey(DpadDirection.LEFT, true))
        assertEquals(DriveAxes(0.2f, -0.8f, 1f), state.updateKey(DpadDirection.LEFT, false))
    }

    @Test
    fun `opposite dpad keys cancel yaw`() {
        state.updateKey(DpadDirection.LEFT, true)

        assertEquals(DriveAxes(0f, 0f, 0f), state.updateKey(DpadDirection.RIGHT, true))
    }

    @Test
    fun `neutral release preserves no stale rotation`() {
        state.updateKey(DpadDirection.RIGHT, true)
        state.updateKey(DpadDirection.RIGHT, false)

        assertEquals(DriveAxes(0f, 0f, 0f), state.current())
    }

    @Test
    fun `reset clears every retained input`() {
        state.updateMotion(1f, -1f, 1f)
        state.updateKey(DpadDirection.LEFT, true)

        assertEquals(DriveAxes(0f, 0f, 0f), state.reset())
    }
}
