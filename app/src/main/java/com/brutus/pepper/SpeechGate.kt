package com.brutus.pepper

/**
 * Décide quand la personne a commencé et fini de parler, pendant une fenêtre d'écoute.
 *
 * Seule implémentation aujourd'hui : [AdaptiveSpeechGate]. L'interface reste parce que
 * la décision « où s'arrête une phrase » a de bonnes chances de changer encore, et que
 * la boucle de capture ne doit rien savoir de la méthode employée.
 */
interface SpeechGate {
    enum class Decision { WAITING, SPEAKING, DONE }

    /**
     * Consomme un bloc audio.
     *
     * @param chunk tampon de samples 16 bits
     * @param count nombre de samples valides dans [chunk]
     * @param rms niveau du bloc, déjà calculé par l'appelant pour le vu-mètre
     */
    fun accept(chunk: ShortArray, count: Int, rms: Int): Decision

    /** Vrai dès qu'une vraie prise de parole a été reconnue. */
    val heardSpeech: Boolean

    /** Libère les ressources natives éventuelles. Toujours appelé, même en erreur. */
    fun close() {}

    /** Ce qu'on écrit dans le journal en fin de capture, pour diagnostiquer sur site. */
    fun describe(): String
}

