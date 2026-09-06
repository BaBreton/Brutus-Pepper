package com.brutus.pepper

/**
 * Configuration du Wake-word pour Vosk (reconnaissance locale gratuite).
 */
object WakeWordConfig {
    /**
     * Mots-clés en minuscules détectant le réveil du robot.
     */
    val KEYWORDS = listOf(
        "pepper",
        "robot",
        "robot pepper",
        "pepère",
        "pépère",
        "ppr"
    )
}
