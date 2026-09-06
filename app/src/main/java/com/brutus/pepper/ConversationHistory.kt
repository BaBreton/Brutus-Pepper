package com.brutus.pepper

class ConversationHistory(private val maxMessages: Int = 20) {
    init { require(maxMessages >= 2) { "maxMessages must be >= 2" } }
    data class Message(val role: String, val text: String)

    private val messages = mutableListOf<Message>()

    @Synchronized fun addUser(text: String) {
        messages.add(Message("user", text))
        trim()
    }

    @Synchronized fun addAssistant(text: String) {
        messages.add(Message("assistant", text))
        trim()
    }

    /** Supprime le dernier message user si c'est bien le dernier (rollback sur erreur). */
    @Synchronized fun rollbackLastUser() {
        if (messages.lastOrNull()?.role == "user") messages.removeAt(messages.lastIndex)
    }

    @Synchronized fun getMessages(): List<Message> = messages.toList()

    @Synchronized fun reset() = messages.clear()

    private fun trim() {
        // Supprime les paires user+assistant les plus anciennes
        while (messages.size > maxMessages) {
            val firstUser = messages.indexOfFirst { it.role == "user" }
            if (firstUser >= 0 && firstUser + 1 < messages.size
                && messages[firstUser + 1].role == "assistant") {
                // Remove higher index first to avoid shifting the lower index
                messages.removeAt(firstUser + 1)
                messages.removeAt(firstUser)
            } else break
        }
    }
}
