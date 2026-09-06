package com.brutus.pepper

import org.junit.Assert.*
import org.junit.Test

class ConversationHistoryTest {
    @Test fun `starts empty`() {
        assertTrue(ConversationHistory().getMessages().isEmpty())
    }

    @Test fun `addUser then addAssistant gives alternating messages`() {
        val h = ConversationHistory()
        h.addUser("Bonjour")
        h.addAssistant("Salut !")
        val msgs = h.getMessages()
        assertEquals(2, msgs.size)
        assertEquals("user", msgs[0].role)
        assertEquals("assistant", msgs[1].role)
    }

    @Test fun `rollbackLastUser removes the user message`() {
        val h = ConversationHistory()
        h.addUser("test")
        h.rollbackLastUser()
        assertTrue(h.getMessages().isEmpty())
    }

    @Test fun `rollbackLastUser does nothing when last is assistant`() {
        val h = ConversationHistory()
        h.addUser("a")
        h.addAssistant("b")
        h.rollbackLastUser()
        assertEquals(2, h.getMessages().size)
    }

    @Test fun `reset clears all messages`() {
        val h = ConversationHistory()
        h.addUser("a")
        h.addAssistant("b")
        h.reset()
        assertTrue(h.getMessages().isEmpty())
    }

    @Test fun `respects max 20 messages, drops oldest pair`() {
        val h = ConversationHistory(maxMessages = 4)
        h.addUser("u1"); h.addAssistant("a1")
        h.addUser("u2"); h.addAssistant("a2")
        h.addUser("u3") // triggers trim: drops u1+a1
        val msgs = h.getMessages()
        assertEquals(3, msgs.size)
        assertEquals("u2", msgs[0].text)
    }

    @Test fun `rollbackLastUser on empty history does nothing`() {
        val h = ConversationHistory()
        h.rollbackLastUser()
        assertTrue(h.getMessages().isEmpty())
    }

    @Test fun `multiple consecutive trims keep most recent messages`() {
        val h = ConversationHistory(maxMessages = 4)
        // Add 8 alternating messages: u1/a1 … u4/a4
        for (i in 1..4) {
            h.addUser("u$i")
            h.addAssistant("a$i")
        }
        val msgs = h.getMessages()
        assertEquals(4, msgs.size)
        // Last 4 messages should be u3, a3, u4, a4
        assertEquals("u3", msgs[0].text)
        assertEquals("a3", msgs[1].text)
        assertEquals("u4", msgs[2].text)
        assertEquals("a4", msgs[3].text)
    }

    @Test fun `getMessages returns defensive copy`() {
        val h = ConversationHistory()
        h.addUser("hello")
        val copy = h.getMessages().toMutableList()
        copy.clear()
        // Internal state should still have 1 message
        assertEquals(1, h.getMessages().size)
    }
}
