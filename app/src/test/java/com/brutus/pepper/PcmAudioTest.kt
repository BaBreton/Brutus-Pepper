package com.brutus.pepper

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmAudioTest {
    @Test
    fun `pcm shorts convert to normalized floats`() {
        assertArrayEquals(
            floatArrayOf(-1f, 0f, 32767f / 32768f),
            PcmAudio.toFloatSamples(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE)),
            0.00001f
        )
    }

    @Test
    fun `duration uses sixteen kilohertz sample rate`() {
        assertEquals(500L, PcmAudio.durationMs(ShortArray(8_000)))
    }
}
