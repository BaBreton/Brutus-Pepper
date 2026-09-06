package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionMenuTest {

    @Test
    fun `propose les mouvements du robot`() {
        val runnable = ActionMenu.runnable()
        for (action in listOf(BoundAction.BONJOUR, BoundAction.POINT_LEFT,
                              BoundAction.POINT_RIGHT,
                              BoundAction.TURN_AROUND, BoundAction.RAISE_BOTH_ARMS,
                              BoundAction.ALTERNATE_ARMS, BoundAction.ENGAGE_HUMAN)) {
            assertTrue(action.name, action in runnable)
        }
    }

    @Test
    fun `ne propose pas ce que le coordinateur refuse`() {
        // Un bouton qui ne fait rien est pire que pas de bouton : la personne appuie,
        // rien ne bouge, elle recommence.
        for (action in listOf(BoundAction.NONE, BoundAction.PUSH_TO_TALK,
                              BoundAction.RESET_CONTEXT)) {
            assertFalse(action.name, action in ActionMenu.runnable())
            assertFalse(action.name, ActionMenu.isRunnable(action))
        }
    }

    @Test
    fun `chaque action proposee porte un libelle lisible`() {
        // Les libellés sont ce qu'on écrit sur les boutons.
        for (action in ActionMenu.runnable()) {
            assertTrue(action.name, action.label.isNotBlank())
            assertFalse(action.name, action.label == action.name)
        }
    }

    @Test
    fun `la liste couvre tout le reste de l'enumeration`() {
        // Une action ajoutée un jour à BoundAction apparaîtra d'elle-même dans le menu,
        // sans qu'on ait à y repenser.
        assertEquals(BoundAction.entries.size - 3, ActionMenu.runnable().size)
    }
}
