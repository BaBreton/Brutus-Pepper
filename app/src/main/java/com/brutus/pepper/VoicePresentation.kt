package com.brutus.pepper

import kotlin.math.ln

enum class VoiceMode { MUTED, READY, LISTENING, PROCESSING, SPEAKING, ERROR }

/** Amplitude réelle, lissée à l'affichage. Aucune pseudo-activité micro pendant le TTS. */
class VoicePresentation {
    var mode = VoiceMode.MUTED
        set(value) {
            field = value
            if (value != VoiceMode.LISTENING && value != VoiceMode.READY) {
                target = 0f
                level = 0f
            }
        }
    private var target = 0f
    var level = 0f
        private set

    fun acceptLevel(rms: Int) {
        target = if (mode == VoiceMode.LISTENING || mode == VoiceMode.READY)
            (ln(1f + rms.coerceAtLeast(0) / 220f) / ln(25f)).coerceIn(0f, 1f)
        else 0f
    }

    fun step() {
        level += (target - level) * if (target > level) .32f else .14f
    }

    val scale: Float get() = when (mode) {
        VoiceMode.LISTENING -> 1f + level * .26f
        VoiceMode.READY -> .91f + level * .09f
        VoiceMode.PROCESSING -> .78f
        VoiceMode.SPEAKING -> .94f
        VoiceMode.MUTED -> .72f
        VoiceMode.ERROR -> .76f
    }
}
