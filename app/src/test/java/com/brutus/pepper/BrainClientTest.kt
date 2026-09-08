package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainSettingsTest {

    @Test
    fun `normalise une adresse saisie sans schema ni port`() {
        assertEquals("http://antenna.local:8770",
            BrainSettings("antenna.local", "t").normalizedUrl())
    }

    @Test
    fun `conserve un schema et un port explicites`() {
        assertEquals("https://cerveau.local:9000",
            BrainSettings("https://cerveau.local:9000/", "t").normalizedUrl())
    }

    @Test
    fun `est incomplet tant que l'adresse ou le jeton manque`() {
        assertFalse(BrainSettings("", "t").isComplete)
        assertFalse(BrainSettings("antenna.local", "  ").isComplete)
        assertTrue(BrainSettings("antenna.local", "t").isComplete)
    }
}

class BrainClientTest {

    private val settings = BrainSettings("antenna.local", "jeton-appairage")

    private class Recorder(private val responses: List<HttpResult>) : BrainTransport {
        val calls = mutableListOf<Triple<String, String, String>>()
        val bodies = mutableListOf<ByteArray?>()
        private var index = 0
        override fun request(
            method: String, url: String, token: String, contentType: String?, body: ByteArray?
        ): HttpResult {
            calls.add(Triple(method, url, token))
            bodies.add(body)
            return responses[minOf(index++, responses.size - 1)]
        }
    }

    private fun client(vararg responses: HttpResult, history: ConversationHistory = ConversationHistory()) =
        Recorder(responses.toList()).let { it to BrainClient({ settings }, it, history) }

    @Test
    fun `le handshake lit le fournisseur et le modele actifs`() {
        val (_, brain) = client(HttpResult(200, """
            {"name":"Pepper Brain",
             "llm":{"active":"anthropic","model":"claude-haiku-4-5","available":[]},
             "stt":{"active":"whisper-local","model":"small","available":[]},
             "media_count":3}
        """.trimIndent()))
        var info: BrainInfo? = null
        brain.hello { info = it.getOrThrow() }
        Thread.sleep(120)
        assertEquals("anthropic", info?.llmProvider)
        assertEquals("claude-haiku-4-5", info?.llmModel)
        assertEquals("small", info?.sttModel)
        assertEquals(3, info?.mediaCount)
        assertTrue(info?.isReady == true)
    }

    @Test
    fun `l'accroche d'hospitalite est lue depuis le cerveau`() {
        val (recorder, brain) = client(HttpResult(200,
            """{"active":true,"speech":"Bienvenue chez Recepta ! La cuisine est au fond à droite."}"""))
        var greeting: Greeting? = null
        brain.greeting { greeting = it.getOrThrow() }
        Thread.sleep(120)
        assertEquals("Bienvenue chez Recepta ! La cuisine est au fond à droite.", greeting?.speech)
        assertTrue(greeting?.isUsable == true)
        assertEquals("http://antenna.local:8770/api/robot/greeting", recorder.calls.single().second)
        assertEquals("jeton-appairage", recorder.calls.single().third)
    }

    @Test
    fun `hospitalite coupee renvoie le robot a son accueil habituel`() {
        val (_, brain) = client(HttpResult(200, """{"active":false,"speech":""}"""))
        var greeting: Greeting? = null
        brain.greeting { greeting = it.getOrThrow() }
        Thread.sleep(120)
        assertFalse(greeting!!.isUsable)
    }

    @Test
    fun `l'accroche porte le geste de pointage choisi par le cerveau`() {
        val greeting = BrainClient.parseGreeting(
            """{"active":true,"speech":"Bienvenue ! La salle du conseil vous attend.",
                 "actions":[{"name":"point_right","arguments":{}}]}""")
        assertTrue(greeting.isUsable)
        assertEquals(listOf(PointRightAction), greeting.actions)
    }

    @Test
    fun `une accroche sans geste reste utilisable`() {
        val greeting = BrainClient.parseGreeting("""{"active":true,"speech":"Bonjour !","actions":[]}""")
        assertTrue(greeting.isUsable)
        assertTrue(greeting.actions.isEmpty())
        // Un cerveau plus ancien ne renvoie pas du tout le champ.
        assertTrue(BrainClient.parseGreeting("""{"active":true,"speech":"Bonjour !"}""").actions.isEmpty())
    }

    @Test
    fun `un geste inconnu est ignore plutot que de rendre l'accueil muet`() {
        val greeting = BrainClient.parseGreeting(
            """{"active":true,"speech":"Bonjour !",
                 "actions":[{"name":"danser"},{"name":"point_left"}]}""")
        assertEquals(listOf(PointLeftAction), greeting.actions)
        assertEquals("Bonjour !", greeting.speech)
    }

    @Test
    fun `l'image d'accueil est lue depuis le cerveau`() {
        val (recorder, brain) = client(HttpResult(200,
            """{"id":"a1b2","url":"http://antenna.local:8770/api/robot/media/a1b2/content","name":"Hall"}"""))
        var image: IdleImage? = null
        brain.idleImage { image = it.getOrThrow() }
        Thread.sleep(120)
        assertEquals("a1b2", image?.id)
        assertEquals("Hall", image?.name)
        assertTrue(image?.isUsable == true)
        assertEquals("http://antenna.local:8770/api/robot/idle-image", recorder.calls.single().second)
        assertEquals("jeton-appairage", recorder.calls.single().third)
    }

    @Test
    fun `une image d'accueil absente rend la tablette a son interface`() {
        assertFalse(BrainClient.parseIdleImage("""{"id":"","url":"","name":""}""").isUsable)
        // Le cerveau peut aussi n'annoncer aucun champ : même conclusion.
        assertFalse(BrainClient.parseIdleImage("{}").isUsable)
    }

    @Test
    fun `une accroche vide malgre l'hospitalite active n'est pas utilisable`() {
        // Réglages écrits à la main, ou rédaction qui a échoué : la tablette retombe
        // sur ses formules plutôt que de laisser un visiteur sans bonjour.
        assertFalse(Greeting(active = true, speech = "   ").isUsable)
    }

    @Test
    fun `un cerveau sans fournisseur configure n'est pas pret`() {
        assertFalse(BrainClient.parseHello("""
            {"name":"Pepper Brain","llm":{"active":"","model":""},
             "stt":{"active":"whisper-local","model":"small"},"media_count":0}
        """.trimIndent()).isReady)
    }

    @Test
    fun `la conversation envoie l'historique et porte le jeton`() {
        val history = ConversationHistory()
        val (recorder, brain) = client(
            HttpResult(200, """{"response":"Bonjour !"}"""), history = history)
        var answer: String? = null
        brain.respond("Salut") { answer = it.getOrThrow() }
        Thread.sleep(120)
        assertEquals("Bonjour !", answer)
        val (method, url, token) = recorder.calls.single()
        assertEquals("POST", method)
        assertEquals("http://antenna.local:8770/api/robot/chat", url)
        assertEquals("jeton-appairage", token)
        assertTrue(String(recorder.bodies.single()!!).contains("Salut"))
    }

    @Test
    fun `une erreur de conversation retire le tour de l'historique`() {
        val history = ConversationHistory()
        val (_, brain) = client(
            HttpResult(503, """{"detail":"aucun connecteur LLM configuré"}"""), history = history)
        var failure: Throwable? = null
        brain.respond("Salut") { failure = it.exceptionOrNull() }
        Thread.sleep(120)
        // Le message rédigé par le cerveau doit remonter tel quel : c'est lui qui est
        // affiché dans la bulle de conversation, et il est écrit pour l'installateur.
        assertEquals("aucun connecteur LLM configuré", failure?.message)
        assertTrue(history.getMessages().isEmpty())
    }

    @Test
    fun `un jeton refuse donne un message explicite`() {
        val (_, brain) = client(HttpResult(401, """{"detail":"token d'appairage invalide"}"""))
        var failure: Throwable? = null
        brain.hello { failure = it.exceptionOrNull() }
        Thread.sleep(120)
        assertTrue(failure?.message!!.contains("Jeton d'appairage refusé"))
    }

    @Test
    fun `la transcription envoie du PCM petit-boutiste`() {
        val (recorder, brain) = client(HttpResult(200, """{"text":"Bonjour Pepper"}"""))
        var result: WhisperResult? = null
        brain.transcribe(shortArrayOf(1, -1)) { result = it.getOrThrow() }
        Thread.sleep(120)
        assertEquals("Bonjour Pepper", result?.text)
        val body = recorder.bodies.single()!!
        assertEquals(4, body.size)
        assertEquals(0x01.toByte(), body[0]); assertEquals(0x00.toByte(), body[1])
        assertEquals(0xff.toByte(), body[2]); assertEquals(0xff.toByte(), body[3])
    }

    @Test
    fun `la resolution de media compose une URL absolue`() {
        val (_, brain) = client(HttpResult(200, """
            {"id":"abc","name":"Plan du site","kind":"image","url":"/api/robot/media/abc/content"}
        """.trimIndent()))
        var media: ResolvedMedia? = null
        brain.resolve("plan du site") { media = it.getOrThrow() }
        Thread.sleep(120)
        assertEquals("http://antenna.local:8770/api/robot/media/abc/content", media?.url)
        assertEquals("image", media?.kind)
    }

    @Test
    fun `sans appairage rien n'est tente sur le reseau`() {
        val recorder = Recorder(listOf(HttpResult(200, "{}")))
        val brain = BrainClient({ BrainSettings() }, recorder)
        var failure: Throwable? = null
        brain.respond("Salut") { failure = it.exceptionOrNull() }
        Thread.sleep(120)
        assertTrue(failure?.message!!.contains("non appairé"))
        assertTrue(recorder.calls.isEmpty())
    }
}

class DisplayImageTest {

    @Test
    fun `le parseur lit une requete libre et sa duree`() {
        val response = AssistantResponseParser.parse("""
            {"speech":"La voici.","actions":[{"name":"display_image",
             "arguments":{"query":"la tour Eiffel","duration_seconds":12}}]}
        """.trimIndent())
        val action = response.actions.single() as DisplayImageAction
        assertEquals("la tour Eiffel", action.query)
        assertEquals(12_000L, action.durationMs)
        assertEquals("La voici.", response.speech)
    }

    @Test
    fun `une requete vide est ignoree plutot que d'afficher du vide`() {
        val response = AssistantResponseParser.parse("""
            {"speech":"Hmm.","actions":[{"name":"display_image","arguments":{"query":"  "}}]}
        """.trimIndent())
        assertTrue(response.actions.isEmpty())
    }

    @Test
    fun `sans duree l'image reste affichee jusqu'a la suite`() {
        val response = AssistantResponseParser.parse("""
            {"speech":"Voilà.","actions":[{"name":"display_image","arguments":{"query":"chat"}}]}
        """.trimIndent())
        assertEquals(null, (response.actions.single() as DisplayImageAction).durationMs)
    }
}

/**
 * Sorties réellement produites par Claude Haiku 4.5 le 2026-07-28, capturées en test
 * sur le robot. Le modèle encadre son JSON dans une clôture ```json : le parseur doit
 * la traverser, sinon toute action se transformerait en réponse parlée bavarde.
 */
class RealModelOutputTest {

    @Test
    fun `traverse la cloture json du modele et lit l'action`() {
        val response = AssistantResponseParser.parse(
            "```json\n" +
            "{\n" +
            "  \"speech\": \"Bien sûr, je vais t'afficher une belle image de la tour Eiffel !\",\n" +
            "  \"actions\": [\n" +
            "    {\n" +
            "      \"name\": \"display_image\",\n" +
            "      \"arguments\": {\n" +
            "        \"query\": \"tour Eiffel Paris\"\n" +
            "      }\n" +
            "    }\n" +
            "  ]\n" +
            "}\n" +
            "```"
        )
        assertEquals("Bien sûr, je vais t'afficher une belle image de la tour Eiffel !",
            response.speech)
        assertEquals("tour Eiffel Paris", (response.actions.single() as DisplayImageAction).query)
    }

    @Test
    fun `une reponse conversationnelle reste du texte parle`() {
        val response = AssistantResponseParser.parse(
            "Bonjour ! Je suis Pepper, ton robot d'accueil. Je suis là pour te souhaiter " +
            "la bienvenue et t'aider avec tes questions !")
        assertTrue(response.actions.isEmpty())
        assertTrue(response.speech.startsWith("Bonjour !"))
    }
}
