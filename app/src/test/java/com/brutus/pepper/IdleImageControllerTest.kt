package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleImageControllerTest {

    private val delay = 60_000L
    private val image = IdleImage("m1", "http://antenna.local:8770/api/robot/media/m1/content", "Accueil")

    private fun controller() = IdleImageController(returnDelayMs = delay)

    @Test
    fun `sans image designee l'ecran reste a l'interface`() {
        val idle = controller()
        assertFalse(idle.shouldShow(nowMs = delay * 10, conversationIdle = true, sceneBusy = false))
    }

    @Test
    fun `l'image s'affiche une fois le delai passe`() {
        val idle = controller()
        idle.setImage(image)
        idle.noteActivity(0L)
        assertFalse(idle.shouldShow(delay - 1, conversationIdle = true, sceneBusy = false))
        assertTrue(idle.shouldShow(delay, conversationIdle = true, sceneBusy = false))
    }

    @Test
    fun `un geste sur l'ecran repousse le retour d'un delai complet`() {
        val idle = controller()
        idle.setImage(image)
        idle.noteActivity(0L)
        idle.noteActivity(delay)
        assertFalse(idle.shouldShow(delay + delay - 1, conversationIdle = true, sceneBusy = false))
        assertTrue(idle.shouldShow(delay + delay, conversationIdle = true, sceneBusy = false))
    }

    @Test
    fun `retirer l'image la cache et relance le delai`() {
        val idle = controller()
        idle.setImage(image)
        idle.markVisible(true)
        idle.dismiss(delay)
        assertFalse(idle.visible)
        // C'est tout l'intérêt du délai : sans lui, l'image reviendrait aussitôt et
        // les réglages de la tablette seraient inatteignables.
        assertFalse(idle.shouldShow(delay + 1, conversationIdle = true, sceneBusy = false))
        assertTrue(idle.shouldShow(delay * 2, conversationIdle = true, sceneBusy = false))
    }

    @Test
    fun `une conversation en cours garde l'ecran`() {
        val idle = controller()
        idle.setImage(image)
        idle.noteActivity(0L)
        assertFalse(idle.shouldShow(delay, conversationIdle = false, sceneBusy = false))
    }

    @Test
    fun `une image demandee pendant l'echange n'est pas recouverte`() {
        val idle = controller()
        idle.setImage(image)
        idle.noteActivity(0L)
        assertFalse(idle.shouldShow(delay, conversationIdle = true, sceneBusy = true))
    }

    @Test
    fun `couper l'hospitalite rend la tablette a son interface`() {
        val idle = controller()
        idle.setImage(image)
        idle.noteActivity(0L)
        assertTrue(idle.setImage(IdleImage.NONE))
        assertFalse(idle.shouldShow(delay, conversationIdle = true, sceneBusy = false))
    }

    @Test
    fun `la meme image annoncee deux fois ne fait pas clignoter l'ecran`() {
        val idle = controller()
        assertTrue(idle.setImage(image))
        assertFalse(idle.setImage(image.copy(name = "Autre nom")))
        assertTrue(idle.setImage(image.copy(url = image.url + "?v=2")))
    }

    @Test
    fun `l'etat affiche suit ce que l'ecran a reellement obtenu`() {
        val idle = controller()
        idle.markVisible(true)
        assertTrue(idle.visible)
        idle.markVisible(false)
        assertFalse(idle.visible)
    }

    @Test
    fun `le hall calme ne declenche la remise a zero qu'une seule fois`() {
        val idle = controller()
        idle.noteActivity(0L)
        assertFalse(idle.consumeReadyForNextVisitor(delay - 1, conversationIdle = true))
        assertTrue(idle.consumeReadyForNextVisitor(delay, conversationIdle = true))
        // Sans ce verrou, l'ecran de conversation serait efface a chaque battement.
        assertFalse(idle.consumeReadyForNextVisitor(delay + 5_000, conversationIdle = true))
    }

    @Test
    fun `une conversation en cours ne remet rien a zero`() {
        val idle = controller()
        idle.noteActivity(0L)
        assertFalse(idle.consumeReadyForNextVisitor(delay * 3, conversationIdle = false))
    }

    @Test
    fun `un nouveau visiteur redonne droit a une remise a zero`() {
        val idle = controller()
        idle.noteActivity(0L)
        assertTrue(idle.consumeReadyForNextVisitor(delay, conversationIdle = true))
        idle.noteActivity(delay + 1)          // quelqu'un arrive et parle
        assertFalse(idle.consumeReadyForNextVisitor(delay + 2, conversationIdle = true))
        assertTrue(idle.consumeReadyForNextVisitor(delay * 2 + 1, conversationIdle = true))
    }

    @Test
    fun `le delai de retour est de trente secondes`() {
        assertEquals(30_000L, IdleImageController.DEFAULT_RETURN_DELAY_MS)
    }

    @Test
    fun `une url vide n'est jamais affichable`() {
        assertFalse(IdleImage.NONE.isUsable)
        assertTrue(image.isUsable)
        assertEquals("", IdleImage.NONE.url)
    }
}
