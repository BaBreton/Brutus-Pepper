package com.brutus.pepper

enum class WhisperPhase {
    PERMISSION_REQUIRED,
    LOADING,
    READY,
    RECORDING,
    TRANSCRIBING,
    ERROR
}

data class WhisperUiState(
    val phase: WhisperPhase = WhisperPhase.PERMISSION_REQUIRED,
    val permissionGranted: Boolean = false,
    val modelReady: Boolean = false,
    val pttEnabled: Boolean = false,
    val transcript: String = "",
    val modelLoadMs: Long? = null,
    val audioDurationMs: Long? = null,
    val transcriptionMs: Long? = null,
    val processingRatio: Double? = null,
    val memoryMb: Int? = null,
    val error: String? = null
)

class WhisperBenchmarkController {
    var state = WhisperUiState()
        private set

    fun onPermissionChanged(granted: Boolean) {
        state = state.copy(
            permissionGranted = granted,
            phase = when {
                !granted -> WhisperPhase.PERMISSION_REQUIRED
                state.modelReady -> WhisperPhase.READY
                else -> WhisperPhase.LOADING
            },
            pttEnabled = granted && state.modelReady,
            error = null
        )
    }

    fun onModelReady(loadMs: Long, memoryMb: Int) {
        state = state.copy(
            modelReady = true,
            modelLoadMs = loadMs,
            memoryMb = memoryMb,
            phase = if (state.permissionGranted) WhisperPhase.READY else WhisperPhase.PERMISSION_REQUIRED,
            pttEnabled = state.permissionGranted,
            error = null
        )
    }

    fun onModelFailed(message: String) {
        state = state.copy(phase = WhisperPhase.ERROR, modelReady = false, pttEnabled = false, error = message)
    }

    fun onPttPressed(): Boolean {
        if (!state.pttEnabled || state.phase != WhisperPhase.READY) return false
        state = state.copy(phase = WhisperPhase.RECORDING, pttEnabled = false, error = null)
        return true
    }

    fun onPttReleased(audioDurationMs: Long): Boolean {
        if (state.phase != WhisperPhase.RECORDING) return false
        if (audioDurationMs < MIN_AUDIO_MS) {
            state = state.copy(phase = WhisperPhase.READY, pttEnabled = true, audioDurationMs = audioDurationMs)
            return false
        }
        state = state.copy(
            phase = WhisperPhase.TRANSCRIBING,
            audioDurationMs = audioDurationMs,
            pttEnabled = false
        )
        return true
    }

    fun onTranscriptionComplete(text: String, transcriptionMs: Long, memoryMb: Int) {
        val audioMs = state.audioDurationMs ?: 0L
        state = state.copy(
            phase = WhisperPhase.READY,
            pttEnabled = state.permissionGranted && state.modelReady,
            transcript = text.trim(),
            transcriptionMs = transcriptionMs,
            processingRatio = if (audioMs > 0) transcriptionMs.toDouble() / audioMs else null,
            memoryMb = memoryMb,
            error = null
        )
    }

    fun onTranscriptionFailed(message: String) {
        state = state.copy(
            phase = WhisperPhase.READY,
            pttEnabled = state.permissionGranted && state.modelReady,
            error = message
        )
    }

    companion object {
        const val MIN_AUDIO_MS = 300L
        const val MAX_AUDIO_SECONDS = 15
    }
}
