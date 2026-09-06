package com.brutus.pepper

class VoiceLoop(
    private val provider: LlmProvider,
    private val speech: SpeechGateway
) {
    fun respond(
        transcript: String,
        onActions: (List<AssistantAction>) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        onSpeech: (String) -> Unit = {},
        onComplete: (Result<String>) -> Unit
    ) {
        provider.respond(transcript) received@{ providerResult ->
            // If the user cancelled while the LLM was thinking, drop the response: don't run
            // actions and don't start speaking.
            if (isCancelled()) {
                onComplete(Result.success(""))
                return@received
            }
            providerResult.fold(
                onSuccess = { rawResponse ->
                    val response = AssistantResponseParser.parse(rawResponse)
                    onActions(response.actions)
                    if (response.speech.isBlank()) {
                        onComplete(Result.success(response.speech))
                    } else {
                        onSpeech(response.speech)
                        if (isCancelled()) { onComplete(Result.success("")); return@received }
                        speech.speak(response.speech) { speechResult ->
                            onComplete(speechResult.map { response.speech })
                        }
                    }
                },
                onFailure = { onComplete(Result.failure(it)) }
            )
        }
    }
}
