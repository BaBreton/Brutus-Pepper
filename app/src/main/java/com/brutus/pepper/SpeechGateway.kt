package com.brutus.pepper

interface SpeechGateway {
    fun speak(text: String, onComplete: (Result<Unit>) -> Unit)

    fun cancel()
}
