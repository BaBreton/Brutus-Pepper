package com.brutus.pepper

object PcmAudio {
    const val SAMPLE_RATE = 16_000

    fun toFloatSamples(samples: ShortArray): FloatArray = FloatArray(samples.size) { index ->
        samples[index] / 32768f
    }

    fun durationMs(samples: ShortArray): Long = samples.size * 1_000L / SAMPLE_RATE
}
