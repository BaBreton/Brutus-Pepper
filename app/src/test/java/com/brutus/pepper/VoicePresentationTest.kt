package com.brutus.pepper

import org.junit.Assert.*
import org.junit.Test

class VoicePresentationTest {
    @Test fun `micro coupe ne reagit jamais au son`() {
        val model = VoicePresentation()
        model.mode = VoiceMode.MUTED
        model.acceptLevel(32000)
        repeat(20) { model.step() }
        assertEquals(0f, model.level, 0.001f)
    }

    @Test fun `la sphere grossit avec une voix puis se raffermit`() {
        val model = VoicePresentation()
        model.mode = VoiceMode.LISTENING
        model.acceptLevel(3000)
        repeat(20) { model.step() }
        val speakingScale = model.scale
        assertTrue(speakingScale > 1f)
        model.acceptLevel(0)
        repeat(40) { model.step() }
        assertTrue(model.scale < speakingScale)
        model.mode = VoiceMode.PROCESSING
        assertTrue(model.scale < 1f)
    }

    @Test fun `traitement et parole ne simulent pas un niveau micro`() {
        val model = VoicePresentation()
        for (mode in listOf(VoiceMode.PROCESSING, VoiceMode.SPEAKING, VoiceMode.ERROR)) {
            model.mode = mode
            model.acceptLevel(Int.MAX_VALUE)
            model.step()
            assertEquals(0f, model.level, 0.001f)
        }
    }
}
