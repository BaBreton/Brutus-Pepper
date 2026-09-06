package com.brutus.pepper

import java.io.Closeable

interface WhisperRecognizer : Closeable {
    fun load(onComplete: (Result<Long>) -> Unit)
    fun transcribe(samples: ShortArray, onComplete: (Result<WhisperResult>) -> Unit)
}

data class WhisperResult(val text: String, val elapsedMs: Long)
