package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantResponseParserTest {
    @Test
    fun `plain response remains speakable text`() {
        assertEquals(
            AssistantResponse("Bonjour.", emptyList()),
            AssistantResponseParser.parse("Bonjour.")
        )
    }

    @Test
    fun `structured response extracts speech and display action`() {
        val response = AssistantResponseParser.parse(
            """{"speech":"Voici.","actions":[{"name":"display_text","arguments":{"text":"OUVERT","duration_seconds":8,"font_size_sp":72,"color":"#00aaff"}}]}"""
        )

        assertEquals("Voici.", response.speech)
        assertEquals(
            DisplayTextAction("OUVERT", durationMs = 8_000L, fontSizeSp = 72f, color = "#00AAFF"),
            response.actions.single()
        )
    }

    @Test
    fun `markdown fenced JSON is accepted`() {
        val response = AssistantResponseParser.parse(
            """```json
                {"speech":"Regarde.","actions":[]}
                ```""".trimIndent()
        )

        assertEquals("Regarde.", response.speech)
    }

    @Test
    fun `display parameters are bounded`() {
        val text = "x".repeat(700)
        val response = AssistantResponseParser.parse(
            """{"speech":"Test","actions":[{"name":"display_text","arguments":{"text":"$text","duration_seconds":999,"font_size_sp":500,"color":"red"}}]}"""
        )
        val action = response.actions.single() as DisplayTextAction

        assertEquals(500, action.text.length)
        assertEquals(60_000L, action.durationMs)
        assertEquals(120f, action.fontSizeSp)
        assertEquals("#FFFFFF", action.color)
    }

    @Test
    fun `unknown actions are ignored`() {
        val response = AssistantResponseParser.parse(
            """{"speech":"Non.","actions":[{"name":"shell","arguments":{"command":"rm"}}]}"""
        )

        assertTrue(response.actions.isEmpty())
    }

    @Test
    fun `wrong JSON field types do not crash the conversation`() {
        val response = AssistantResponseParser.parse(
            """{"speech":"Salut.","actions":"not-an-array"}"""
        )

        assertEquals(AssistantResponse("Salut.", emptyList()), response)
    }

    @Test
    fun `display media action keeps a named query`() {
        val response = AssistantResponseParser.parse(
            """{"speech":"Voici.","actions":[{"name":"display_media","arguments":{"query":"Vidéo accueil été","duration_seconds":12}}]}"""
        )

        assertEquals(DisplayMediaAction("Vidéo accueil été", 12_000L), response.actions.single())
    }

    @Test
    fun `plusieurs images sont conservees dans l ordre du modele`() {
        val response = AssistantResponseParser.parse(
            """{"speech":"Voici les deux.","actions":[
                {"name":"display_image","arguments":{"query":"la tour Eiffel"}},
                {"name":"display_image","arguments":{"query":"le Louvre"}}
            ]}""".replace("\n", "")
        )

        assertEquals(
            listOf(
                DisplayImageAction("la tour Eiffel", null),
                DisplayImageAction("le Louvre", null),
            ),
            response.actions
        )
    }

    @Test
    fun `sequence validates text media and loop deadline`() {
        val response = AssistantResponseParser.parse(
            """{"speech":"Je lance la boucle.","actions":[{"name":"play_sequence","arguments":{"loop_for_seconds":600,"items":[{"type":"text","text":"Bienvenue","duration_seconds":4,"font_size_sp":70,"color":"#FFCC00"},{"type":"media","query":"Vidéo accueil été"}]}}]}"""
        )
        val action = response.actions.single() as PlaySequenceAction

        assertEquals(600_000L, action.loopForMs)
        assertEquals(TextSceneItem("Bienvenue", 4_000L, 70f, "#FFCC00"), action.items[0])
        assertEquals(MediaSceneItem("Vidéo accueil été", null), action.items[1])
    }

    @Test
    fun `sequence duration and item count are bounded`() {
        val items = (1..30).joinToString(",") {
            "{\"type\":\"text\",\"text\":\"$it\",\"duration_seconds\":999}"
        }
        val response = AssistantResponseParser.parse(
            """{"speech":"Boucle","actions":[{"name":"play_sequence","arguments":{"loop_for_seconds":99999,"items":[$items]}}]}"""
        )
        val action = response.actions.single() as PlaySequenceAction

        assertEquals(3_600_000L, action.loopForMs)
        assertEquals(20, action.items.size)
        assertEquals(300_000L, (action.items.first() as TextSceneItem).durationMs)
    }

    @Test
    fun `kiosk action is recognized`() {
        val response = AssistantResponseParser.parse(
            """{"speech":"Mode kiosk.","actions":[{"name":"show_kiosk","arguments":{}}]}"""
        )

        assertEquals(ShowKioskAction, response.actions.single())
    }
}
