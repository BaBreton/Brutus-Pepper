package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceLoopTest {
    @Test
    fun `la reponse est affichee et l'etat parler annonce avant le TTS`() {
        val provider = FakeProvider()
        val events = mutableListOf<String>()
        val loop = VoiceLoop(provider, FakeSpeech { events += "tts" })
        loop.respond("Bonjour", onSpeech = { events += "visible:$it" }) {}
        provider.complete(Result.success("Bonjour !"))
        assertEquals(listOf("visible:Bonjour !", "tts"), events)
    }

    @Test
    fun `une reponse annulee ne parle ni ne s'affiche`() {
        val provider = FakeProvider()
        val events = mutableListOf<String>()
        VoiceLoop(provider, FakeSpeech { events += "tts" }).respond(
            "Bonjour", isCancelled = { true }, onSpeech = { events += it }
        ) {}
        provider.complete(Result.success("Trop tard"))
        assertEquals(emptyList<String>(), events)
    }
    @Test
    fun `recognized text goes to provider then Pepper speech`() {
        val provider = FakeProvider()
        val speech = FakeSpeech()
        val loop = VoiceLoop(provider, speech)

        loop.respond("Comment vas-tu ?") {}

        assertEquals(listOf("Comment vas-tu ?"), provider.prompts)
        provider.complete(Result.success("Très bien."))
        assertEquals(listOf("Très bien."), speech.phrases)
    }

    @Test
    fun `display action is dispatched before parsed speech`() {
        val provider = FakeProvider()
        val events = mutableListOf<String>()
        val speech = FakeSpeech { events += "speech:$it" }
        val loop = VoiceLoop(provider, speech)

        loop.respond(
            "Montre-moi les horaires",
            onActions = { actions -> events += "action:${(actions.single() as DisplayTextAction).text}" }
        ) {}
        provider.complete(Result.success(
            """{"speech":"Les voici.","actions":[{"name":"display_text","arguments":{"text":"9 h - 19 h","duration_seconds":5,"font_size_sp":60,"color":"#FFFFFF"}}]}"""
        ))

        assertEquals(listOf("action:9 h - 19 h", "speech:Les voici."), events)
    }

    private class FakeProvider : LlmProvider {
        val prompts = mutableListOf<String>()
        private var completion: ((Result<String>) -> Unit)? = null

        override fun respond(prompt: String, onComplete: (Result<String>) -> Unit) {
            prompts += prompt
            completion = onComplete
        }

        fun complete(result: Result<String>) = completion!!.invoke(result)
    }

    private class FakeSpeech(private val onSpeak: (String) -> Unit = {}) : SpeechGateway {
        val phrases = mutableListOf<String>()
        override fun speak(text: String, onComplete: (Result<Unit>) -> Unit) {
            phrases += text
            onSpeak(text)
            onComplete(Result.success(Unit))
        }
        override fun cancel() = Unit
    }
}
