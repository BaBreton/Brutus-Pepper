package com.brutus.pepper

data class HelloUiState(
    val status: String,
    val helloEnabled: Boolean,
)

class HelloController(
    private val onStateChanged: (HelloUiState) -> Unit = {},
) {
    var state = HelloUiState(status = "En attente de Pepper", helloEnabled = false)
        private set

    private var speechGateway: SpeechGateway? = null
    private var speaking = false

    fun onRobotFocusGained(gateway: SpeechGateway) {
        speechGateway = gateway
        speaking = false
        updateState(status = "Pepper est prêt", helloEnabled = true)
    }

    fun onRobotFocusLost() {
        if (speaking) {
            speechGateway?.cancel()
        }
        speechGateway = null
        speaking = false
        updateState(status = "En attente de Pepper", helloEnabled = false)
    }

    fun onHelloPressed() {
        val activeGateway = speechGateway ?: return
        if (speaking) return

        speaking = true
        updateState(status = "Pepper parle…", helloEnabled = false)
        activeGateway.speak(HELLO_TEXT) { result ->
            if (speechGateway !== activeGateway) return@speak

            speaking = false
            if (result.isSuccess) {
                updateState(status = "Pepper est prêt", helloEnabled = true)
            } else {
                updateState(status = "Impossible de faire parler Pepper", helloEnabled = true)
            }
        }
    }

    private fun updateState(status: String, helloEnabled: Boolean) {
        state = HelloUiState(status = status, helloEnabled = helloEnabled)
        onStateChanged(state)
    }

    private companion object {
        const val HELLO_TEXT = "Bonjour !"
    }
}
