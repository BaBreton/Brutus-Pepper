package com.brutus.pepper

import java.text.Normalizer
import java.util.Locale

/** Match the whole utterance: extra words may contain a request, even without a question mark. */
object ConversationEnding {
    private val farewell = Regex("(?:au revoir|a bientot|a la prochaine|a plus tard|a demain|bonne journee|bonne soiree|bonne nuit|adieu)")
    private val courtesy = Regex("(?:merci(?: beaucoup| bien)?|pepper|ok|okay|salut|et)")

    fun isExplicit(text: String): Boolean {
        if ('?' in text || '？' in text) return false
        val normalized = Normalizer.normalize(text.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[.,!;:…]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val endings = Regex("\\b${farewell.pattern}\\b")
        if (!endings.containsMatchIn(normalized)) return false
        val rest = endings.replace(normalized, " ")
        return Regex("\\b${courtesy.pattern}\\b").replace(rest, " ").isBlank()
    }
}
