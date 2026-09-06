package com.brutus.pepper

import org.junit.Assert.*
import org.junit.Test

class ConversationControllerTest {
    @Test fun `ancienne reponse de cloture ne perturbe pas le nouveau tour`() {
        val host = Host(); val wake = Wake()
        val controller = ConversationController(host, wake, dispatch = { it() })
        var ended = 0
        controller.onConversationEnded = { ended++ }
        controller.enable(); controller.onWake(); host.capture!!(ShortArray(16000))
        host.text!!("Au revoir")
        val oldSpoken = host.spoken!!
        oldSpoken(); oldSpoken()
        assertEquals(1, ended)
        controller.onWake(); oldSpoken()
        assertEquals(ConversationController.State.LISTENING, controller.state)
        assertEquals(2, host.captures)
        assertFalse(wake.listening)
    }

    @Test fun `annuler une cloture PTT rearme wake sans autoriser hospitalite`() {
        val host = Host(); val wake = Wake()
        val controller = ConversationController(host, wake, dispatch = { it() })
        controller.enable(); controller.suspendForPtt(); controller.acceptTranscript("Au revoir")
        controller.cancelTurn()
        assertTrue(wake.listening)
        assertTrue(controller.awaitingExplicitWake)
        controller.triggerDirectListening(10000, automatic = true)
        assertEquals(0, host.captures)
    }

    @Test fun `accueil automatique normal fonctionne mais ne reactive pas un mode desactive`() {
        val host = Host()
        val controller = ConversationController(host, Wake(), dispatch = { it() })
        controller.enable(); controller.suspendForPtt()
        controller.triggerDirectListening(10000, automatic = true)
        assertEquals(1, host.captures)
        controller.disable()
        controller.triggerDirectListening(10000, automatic = true)
        assertEquals(1, host.captures)
    }

    @Test fun `adieu attend la reponse puis bloque les relances automatiques jusqu au wake word`() {
        val host = Host(); val wake = Wake()
        val controller = ConversationController(host, wake, dispatch = { it() })
        var ended = 0
        controller.onConversationEnded = { ended++ }
        controller.enable(); controller.onWake(); host.capture!!(ShortArray(16000))
        host.text!!("Merci Pepper à bientôt")
        assertTrue(controller.awaitingExplicitWake)
        assertFalse(wake.listening)
        assertEquals(1, host.responses)
        controller.onSpeechStarted(); host.spoken!!()
        assertEquals(ConversationController.State.IDLE_WAKE, controller.state)
        assertTrue(wake.listening)
        assertEquals(1, ended)
        controller.triggerDirectListening(10000, automatic = true)
        controller.disable(); controller.enable()
        controller.triggerDirectListening(10000, automatic = true)
        assertEquals(1, host.captures)
        controller.onWake()
        assertFalse(controller.awaitingExplicitWake)
        assertEquals(2, host.captures)
    }

    @Test fun `merci et demande apres adieu gardent le suivi`() {
        listOf("Merci", "Merci Pepper", "Au revoir mais où est la sortie ?").forEach { text ->
            val host = Host(); val controller = ConversationController(host, Wake(), dispatch = { it() })
            controller.enable(); controller.onWake(); host.capture!!(ShortArray(16000))
            host.text!!(text); host.spoken!!()
            assertEquals(text, 2, host.captures)
            assertFalse(controller.awaitingExplicitWake)
        }
    }

    @Test fun `fin manuelle rearme wake et permet un nouvel appui parler`() {
        val host = Host(); val wake = Wake()
        val controller = ConversationController(host, wake, dispatch = { it() })
        controller.enable(); controller.suspendForPtt()
        controller.acceptTranscript("Au revoir")
        controller.finishPttTurn(10000)
        assertTrue(controller.awaitingExplicitWake)
        assertTrue(wake.listening)
        controller.triggerDirectListening(10000, automatic = true)
        assertEquals(0, host.captures)
        controller.triggerDirectListening(10000)
        assertFalse(controller.awaitingExplicitWake)
        assertEquals(1, host.captures)
    }

    private class Wake : WakeControl {
        var listening = false
        override fun start() { listening = true }
        override fun stop() { listening = false }
        override fun release() { stop() }
    }
    private class Host : ConversationHost {
        var capture: ((ShortArray) -> Unit)? = null
        var text: ((String?) -> Unit)? = null
        var spoken: (() -> Unit)? = null
        var responses = 0
        var captures = 0
        override fun listen(timeoutMs: Long, onResult: (ShortArray) -> Unit) { captures++; capture = onResult }
        override fun transcribe(samples: ShortArray, onText: (String?) -> Unit) { text = onText }
        override fun respond(transcript: String, onSpoken: () -> Unit) { responses++; spoken = onSpoken }
    }
    @Test fun `couper puis reactiver ignore les anciens callbacks`() {
        val host = Host(); val wake = Wake()
        val controller = ConversationController(host, wake, dispatch = { it() })
        controller.enable(); controller.onWake()
        host.capture!!(ShortArray(16000)); val old = host.text!!
        controller.disable(); controller.enable()
        old("Phrase annulée")
        assertEquals(0, host.responses)
        assertEquals(ConversationController.State.IDLE_WAKE, controller.state)
        assertTrue(wake.listening)
    }
    @Test fun `etat parlant durant la synthese puis relance sans mot de reveil`() {
        val host = Host(); val wake = Wake()
        val controller = ConversationController(host, wake, dispatch = { it() })
        controller.enable(); controller.onWake(); host.capture!!(ShortArray(16000))
        host.text!!("Bonjour"); controller.onSpeechStarted()
        assertEquals(ConversationController.State.SPEAKING, controller.state)
        assertFalse(wake.listening)
        host.spoken!!()
        assertEquals(ConversationController.State.LISTENING, controller.state)
        assertEquals(2, host.captures)
    }
    @Test fun `reactiver en cours de tour ne detourne pas le micro`() {
        val host = Host(); val wake = Wake()
        val controller = ConversationController(host, wake, dispatch = { it() })
        controller.enable(); controller.onWake(); controller.enable()
        assertFalse(wake.listening)
        assertEquals(ConversationController.State.LISTENING, controller.state)
    }

    @Test fun `attend la liberation du micro et ignore une ouverture annulee`() {
        val host = Host()
        var released: (() -> Unit)? = null
        val wake = object : WakeControl {
            override fun start() {}
            override fun stop() {}
            override fun release() {}
            override fun stopAndThen(callback: () -> Unit) { released = callback }
        }
        val controller = ConversationController(host, wake, dispatch = { it() })
        controller.enable(); controller.onWake()
        assertEquals(0, host.captures)
        controller.disable()
        released!!()
        assertEquals(0, host.captures)
        controller.enable(); controller.onWake(); released!!()
        assertEquals(1, host.captures)
    }
}
