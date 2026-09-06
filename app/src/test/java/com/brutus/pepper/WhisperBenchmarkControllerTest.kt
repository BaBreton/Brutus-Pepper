package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperBenchmarkControllerTest {
    @Test
    fun `ptt stays disabled until permission and model are ready`() {
        val controller = WhisperBenchmarkController()

        controller.onPermissionChanged(true)
        assertFalse(controller.state.pttEnabled)

        controller.onModelReady(loadMs = 1200, memoryMb = 180)
        assertTrue(controller.state.pttEnabled)
    }

    @Test
    fun `hold records and release starts transcription`() {
        val controller = readyController()

        assertTrue(controller.onPttPressed())
        assertEquals(WhisperPhase.RECORDING, controller.state.phase)
        assertTrue(controller.onPttReleased(audioDurationMs = 2_000))
        assertEquals(WhisperPhase.TRANSCRIBING, controller.state.phase)
        assertFalse(controller.state.pttEnabled)
    }

    @Test
    fun `short recording does not invoke transcription`() {
        val controller = readyController()
        controller.onPttPressed()

        assertFalse(controller.onPttReleased(audioDurationMs = 100))
        assertEquals(WhisperPhase.READY, controller.state.phase)
    }

    @Test
    fun `result reports text timing ratio and memory`() {
        val controller = readyController()
        controller.onPttPressed()
        controller.onPttReleased(audioDurationMs = 2_000)

        controller.onTranscriptionComplete("Bonjour Pepper", 1_000, 205)

        assertEquals("Bonjour Pepper", controller.state.transcript)
        assertEquals(0.5, controller.state.processingRatio!!, 0.001)
        assertEquals(205, controller.state.memoryMb)
        assertTrue(controller.state.pttEnabled)
    }

    @Test
    fun `permission denial and model error remain retryable`() {
        val controller = WhisperBenchmarkController()
        controller.onPermissionChanged(false)
        assertEquals(WhisperPhase.PERMISSION_REQUIRED, controller.state.phase)

        controller.onModelFailed("Modèle corrompu")
        assertEquals(WhisperPhase.ERROR, controller.state.phase)
        assertEquals("Modèle corrompu", controller.state.error)
    }

    private fun readyController() = WhisperBenchmarkController().apply {
        onPermissionChanged(true)
        onModelReady(loadMs = 1_200, memoryMb = 180)
    }
}
