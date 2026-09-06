package com.brutus.pepper

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavEncoderTest {
    @Test
    fun `encodes mono sixteen kilohertz pcm wav`() {
        val wav = WavEncoder.encode(shortArrayOf(0x1234, (-2).toShort()))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(40, header.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(4, header.getInt(40))
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0xfe.toByte(), 0xff.toByte()), wav.copyOfRange(44, 48))
    }
}
