package com.brutus.pepper

/**
 * Les actions qu'on peut proposer sous forme de boutons.
 *
 * La liste n'est pas écrite à la main : elle se déduit de ce que
 * [RobotActionCoordinator.run] accepte réellement. Un bouton qui ne ferait rien est
 * pire que pas de bouton — la personne appuie, il ne se passe rien, elle recommence.
 */
object ActionMenu {

    /**
     * Actions écartées, parce que le coordinateur les refuse : elles ne sont pas des
     * mouvements du robot mais des commandes de l'application, déjà accessibles
     * ailleurs — le micro se déclenche à la voix ou au bouton dédié, et l'écran
     * d'accueil a son bouton « Nouvelle conversation ».
     */
    private val NOT_RUNNABLE = setOf(
        BoundAction.NONE,
        BoundAction.PUSH_TO_TALK,
        BoundAction.RESET_CONTEXT
    )

    fun runnable(): List<BoundAction> = BoundAction.entries.filter { it !in NOT_RUNNABLE }

    fun isRunnable(action: BoundAction): Boolean = action !in NOT_RUNNABLE
}
