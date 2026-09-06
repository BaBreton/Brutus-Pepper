package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelloControllerTest {
    @Test
    fun `initial state waits for Pepper and disables hello`() {
        val controller = HelloController()

        assertEquals("En attente de Pepper", controller.state.status)
        assertFalse(controller.state.helloEnabled)
    }

    @Test
    fun `robot focus enables hello`() {
        val controller = HelloController()

        controller.onRobotFocusGained(FakeSpeechGateway())

        assertEquals("Pepper est prêt", controller.state.status)
        assertTrue(controller.state.helloEnabled)
    }

    @Test
    fun `hello press requests exact phrase and blocks duplicate presses`() {
        val gateway = FakeSpeechGateway()
        val controller = HelloController()
        controller.onRobotFocusGained(gateway)

        controller.onHelloPressed()
        controller.onHelloPressed()

        assertEquals(listOf("Bonjour !"), gateway.phrases)
        assertEquals("Pepper parle…", controller.state.status)
        assertFalse(controller.state.helloEnabled)
    }

    @Test
    fun `speech completion restores ready state`() {
        val gateway = FakeSpeechGateway()
        val controller = HelloController()
        controller.onRobotFocusGained(gateway)
        controller.onHelloPressed()

        gateway.complete(Result.success(Unit))

        assertEquals("Pepper est prêt", controller.state.status)
        assertTrue(controller.state.helloEnabled)
    }

    @Test
    fun `speech failure reports error and allows retry`() {
        val gateway = FakeSpeechGateway()
        val controller = HelloController()
        controller.onRobotFocusGained(gateway)
        controller.onHelloPressed()

        gateway.complete(Result.failure(IllegalStateException("offline")))

        assertEquals("Impossible de faire parler Pepper", controller.state.status)
        assertTrue(controller.state.helloEnabled)
    }

    @Test
    fun `focus loss cancels speech and returns to waiting`() {
        val gateway = FakeSpeechGateway()
        val controller = HelloController()
        controller.onRobotFocusGained(gateway)
        controller.onHelloPressed()

        controller.onRobotFocusLost()

        assertEquals(1, gateway.cancelCount)
        assertEquals("En attente de Pepper", controller.state.status)
        assertFalse(controller.state.helloEnabled)
    }

    private class FakeSpeechGateway : SpeechGateway {
        val phrases = mutableListOf<String>()
        var cancelCount = 0
        private var completion: ((Result<Unit>) -> Unit)? = null

        override fun speak(text: String, onComplete: (Result<Unit>) -> Unit) {
            phrases += text
            completion = onComplete
        }

        override fun cancel() {
            cancelCount += 1
        }

        fun complete(result: Result<Unit>) {
            completion?.invoke(result)
        }
    }
}
