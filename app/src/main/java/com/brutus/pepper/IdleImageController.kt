package com.brutus.pepper

/**
 * Décide quand l'image d'accueil occupe la tablette.
 *
 * En mode hospitalité, le cerveau désigne une image que Pepper montre en plein écran
 * entre deux visiteurs. On la touche pour la retirer et retrouver l'interface —
 * réglages, manette, appairage —, et elle revient d'elle-même une fois le hall
 * redevenu calme.
 *
 * Le délai de retour n'est pas un confort : sans lui, l'image reviendrait à l'instant
 * même où on la retire, et personne ne pourrait plus atteindre les réglages. Toute
 * activité — une conversation, un geste sur l'écran — le relance.
 *
 * Cette classe ne connaît ni vues ni réseau : elle ne fait que trancher, ce qui la
 * rend vérifiable sans robot.
 */
class IdleImageController(
    private val returnDelayMs: Long = DEFAULT_RETURN_DELAY_MS
) {
    /** L'image du moment, telle que le cerveau la donne. Vide = pas d'accueil en image. */
    var image: IdleImage = IdleImage.NONE
        private set

    /** Vrai quand l'image occupe réellement l'écran. */
    var visible: Boolean = false
        private set

    private var quietSinceMs = 0L
    private var readyConsumed = false

    /**
     * Retient l'image annoncée par le cerveau. Renvoie vrai si elle a changé — donc
     * si un écran déjà affiché doit être repeint avec la nouvelle.
     */
    fun setImage(value: IdleImage): Boolean {
        if (value.url == image.url) return false
        image = value
        return true
    }

    /** Un geste sur l'écran, un tour de parole : le hall n'est pas au repos. */
    fun noteActivity(nowMs: Long) {
        quietSinceMs = nowMs
        readyConsumed = false
    }

    /**
     * Vrai une seule fois par période de calme, quand le hall est resté sans rien
     * pendant le délai : c'est le moment de repartir de zéro pour la personne
     * suivante — historique vidé, accueil spontané réarmé, image d'accueil reposée.
     *
     * Une seule fois, sinon on effacerait l'écran de conversation en boucle toutes
     * les cinq secondes tant que personne ne passe.
     */
    fun consumeReadyForNextVisitor(nowMs: Long, conversationIdle: Boolean): Boolean {
        if (!conversationIdle || readyConsumed) return false
        if (nowMs - quietSinceMs < returnDelayMs) return false
        readyConsumed = true
        return true
    }

    /**
     * Vrai quand l'image doit être à l'écran maintenant.
     *
     * @param conversationIdle faux dès que Pepper écoute, réfléchit ou parle.
     * @param sceneBusy vrai quand une image demandée pendant l'échange occupe déjà
     *   l'écran : l'accueil ne doit pas passer par-dessus une réponse.
     */
    fun shouldShow(nowMs: Long, conversationIdle: Boolean, sceneBusy: Boolean): Boolean =
        image.isUsable && conversationIdle && !sceneBusy &&
            nowMs - quietSinceMs >= returnDelayMs

    /** Enregistre l'état réellement obtenu à l'écran. */
    fun markVisible(shown: Boolean) {
        visible = shown
    }

    /** L'image vient d'être touchée : on la retire et on laisse la tablette tranquille. */
    fun dismiss(nowMs: Long) {
        visible = false
        noteActivity(nowMs)
    }

    companion object {
        /**
         * Trente secondes : le temps qu'on accepte de laisser Pepper occupé par
         * quelqu'un qui est déjà parti. Passé ce délai, l'échange est clos, l'image
         * d'accueil revient et le robot est disponible pour la personne suivante.
         */
        const val DEFAULT_RETURN_DELAY_MS = 30_000L
    }
}
