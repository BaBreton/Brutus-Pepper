package com.brutus.pepper

interface LlmProvider {
    fun respond(prompt: String, onComplete: (Result<String>) -> Unit)
}
