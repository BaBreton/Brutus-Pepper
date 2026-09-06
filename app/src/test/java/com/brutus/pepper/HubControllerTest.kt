package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Test

class HubControllerTest {
    @Test
    fun `defaults match the requested controller layout`() {
        val controller = HubController(InMemoryBindingStore())

        assertEquals(BoundAction.RAISE_BOTH_ARMS, controller.state.bindings[GamepadControl.A])
        assertEquals(BoundAction.ALTERNATE_ARMS, controller.state.bindings[GamepadControl.B])
        assertEquals(BoundAction.NONE, controller.state.bindings[GamepadControl.X])
        assertEquals(BoundAction.PUSH_TO_TALK, controller.state.bindings[GamepadControl.Y])
    }

    @Test
    fun `navigation and changed binding are retained`() {
        val store = InMemoryBindingStore()
        val controller = HubController(store)

        controller.navigateTo(HubScreen.GAMEPAD)
        controller.bind(GamepadControl.RIGHT_TRIGGER, BoundAction.BONJOUR)

        assertEquals(HubScreen.GAMEPAD, controller.state.screen)
        assertEquals(
            BoundAction.BONJOUR,
            HubController(store).state.bindings[GamepadControl.RIGHT_TRIGGER]
        )
    }
}
