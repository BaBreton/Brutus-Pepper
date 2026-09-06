package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamepadInputMapperTest {
    private val mapper = GamepadInputMapper()

    @Test
    fun `values inside hardware flat zone become neutral`() {
        assertEquals(0f, mapper.centeredAxis(0.08f, 0.1f))
        assertEquals(0.4f, mapper.centeredAxis(0.4f, 0.1f))
    }

    @Test
    fun `android Xbox and PlayStation face key codes map consistently`() {
        assertEquals(GamepadControl.A, mapper.controlForKeyCode(96))
        assertEquals(GamepadControl.B, mapper.controlForKeyCode(97))
        assertEquals(GamepadControl.X, mapper.controlForKeyCode(99))
        assertEquals(GamepadControl.Y, mapper.controlForKeyCode(100))
        assertNull(mapper.controlForKeyCode(4))
    }

    @Test
    fun `trigger axis emits only press and release edges`() {
        assertNull(mapper.triggerEdge(GamepadControl.LEFT_TRIGGER, 0.1f))
        assertEquals(
            GamepadEdge(GamepadControl.LEFT_TRIGGER, pressed = true),
            mapper.triggerEdge(GamepadControl.LEFT_TRIGGER, 0.8f)
        )
        assertNull(mapper.triggerEdge(GamepadControl.LEFT_TRIGGER, 0.9f))
        assertEquals(
            GamepadEdge(GamepadControl.LEFT_TRIGGER, pressed = false),
            mapper.triggerEdge(GamepadControl.LEFT_TRIGGER, 0.1f)
        )
    }

    @Test fun `default keycode 96 maps to A`() {
        val m = GamepadInputMapper()
        assertEquals(GamepadControl.A, m.controlForKeyCode(96))
    }

    @Test fun `custom keycode overrides default`() {
        val m = GamepadInputMapper(customKeycodes = mapOf(96 to GamepadControl.X))
        assertEquals(GamepadControl.X, m.controlForKeyCode(96))
    }

    @Test fun `unknown keycode returns null`() {
        assertNull(GamepadInputMapper().controlForKeyCode(9999))
    }

    @Test fun `custom keycodes can be updated at runtime`() {
        val m = GamepadInputMapper()
        assertEquals(GamepadControl.A, m.controlForKeyCode(96))
        m.customKeycodes = mapOf(96 to GamepadControl.B)
        assertEquals(GamepadControl.B, m.controlForKeyCode(96))
    }
}
