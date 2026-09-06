package com.brutus.pepper

import org.junit.Assert.*
import org.junit.Test

class ConversationEndingTest {
    @Test fun `reconnait seulement une cloture complete`() {
        listOf("merci Pepper à bientôt", "Au revoir !", "Merci beaucoup, Pepper. À bientôt !",
            "À la prochaine Pepper", "Bonne soirée", "OK merci et au revoir", "Salut Pepper à bientôt")
            .forEach { assertTrue(it, ConversationEnding.isExplicit(it)) }
    }

    @Test fun `conserve les remerciements salutations et demandes restantes`() {
        listOf("merci", "merci Pepper", "merci beaucoup", "salut", "Bonjour Pepper",
            "Au revoir ?", "Merci Pepper à bientôt, mais où est la sortie ?",
            "Au revoir, peux-tu ouvrir le menu", "Comment dit-on au revoir en anglais",
            "Merci Pepper à bientôt et donne-moi la météo", "Je ne veux pas dire au revoir",
            "Au revoir et quelle heure est-il", "Bonne soirée, tu peux me guider", "")
            .forEach { assertFalse(it, ConversationEnding.isExplicit(it)) }
    }
}
