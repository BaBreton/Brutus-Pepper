package com.brutus.pepper

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {
    fun encode(samples: ShortArray, sampleRate: Int = PcmAudio.SAMPLE_RATE): ByteArray {
        val dataSize = samples.size * 2
        return ByteBuffer.allocate(44 + dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(sampleRate)
                putInt(sampleRate * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
                samples.forEach(::putShort)
            }
            .array()
    }
}
