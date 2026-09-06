package com.brutus.pepper

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BrainCancellationTest {
    @Test fun `annuler sans tour en cours conserve le dernier échange`() {
        val history = ConversationHistory()
        history.addUser("Question terminée")
        history.addAssistant("Réponse terminée")
        val brain = BrainClient({ BrainSettings("localhost", "pair") }, BrainTransport { _, _, _, _, _ ->
            HttpResult(200, "{}")
        }, history)
        try {
            brain.cancelPending()
            assertEquals(
                listOf("Question terminée", "Réponse terminée"),
                history.getMessages().map { it.text }
            )
        } finally { brain.close() }
    }

    @Test fun `une reponse tardive ne pollue pas le nouvel historique`() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val history = ConversationHistory()
        val transport = BrainTransport { _, _, _, _, _ ->
            started.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            HttpResult(200, """{"response":"ancienne réponse"}""")
        }
        val brain = BrainClient({ BrainSettings("localhost", "pair") }, transport, history)
        try {
            brain.respond("Ancienne question") { done.countDown() }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            brain.cancelPending()
            history.reset()
            release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertTrue(history.getMessages().isEmpty())
        } finally { release.countDown(); brain.close() }
    }

    @Test fun `une image lente ne retarde pas le tour de parole`() {
        val imageStarted = CountDownLatch(1); val release = CountDownLatch(1); val chatDone = CountDownLatch(1)
        val transport = BrainTransport { _, url, _, _, _ ->
            if (url.contains("/image")) {
                imageStarted.countDown(); check(release.await(3, TimeUnit.SECONDS))
                HttpResult(200, """{"url":"http://localhost/image","title":"Image"}""")
            } else HttpResult(200, """{"response":"Bonjour"}""")
        }
        val brain = BrainClient({ BrainSettings("localhost", "pair") }, transport)
        try {
            brain.resolveImage("photo") {}
            assertTrue(imageStarted.await(3, TimeUnit.SECONDS))
            brain.respond("Bonjour") { chatDone.countDown() }
            assertTrue("La conversation attend l'image", chatDone.await(1, TimeUnit.SECONDS))
        } finally { release.countDown(); brain.close() }
    }
}
