package com.brutus.pepper

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException

/** Le cerveau tel que la tablette le voit après un appairage réussi. */
data class BrainInfo(
    val name: String,
    val llmProvider: String,
    val llmModel: String,
    val sttProvider: String,
    val sttModel: String,
    val mediaCount: Int
) {
    /** Vrai quand un fournisseur de conversation est réellement sélectionné côté cerveau. */
    val isReady: Boolean get() = llmProvider.isNotBlank()
}

/**
 * Ce que le cerveau répond quand on lui demande comment accueillir.
 *
 * `active` faux veut dire « reprends ton accueil habituel », pas « reste muet » : la
 * tablette garde ses formules de repli, et un cerveau injoignable ne doit pas priver
 * un visiteur de bonjour.
 */
data class Greeting(val active: Boolean, val speech: String) {
    val isUsable: Boolean get() = active && speech.isNotBlank()
}

fun interface BrainTransport {
    fun request(
        method: String,
        url: String,
        token: String,
        contentType: String?,
        body: ByteArray?
    ): HttpResult
}

interface CancellableBrainTransport { fun cancelPending() }

class UrlBrainTransport : BrainTransport, CancellableBrainTransport {
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    override fun cancelPending() { connections.forEach { it.disconnect() } }
    override fun request(
        method: String,
        url: String,
        token: String,
        contentType: String?,
        body: ByteArray?
    ): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        connections.add(connection)
        try {
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return HttpResult(status, text)
        } finally {
            connections.remove(connection)
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        // Le cerveau coupe lui-même ses appels fournisseur à 15 s ; on lui laisse
        // une marge sans jamais laisser la tablette attendre indéfiniment.
        private const val READ_TIMEOUT_MS = 25_000
    }
}

/**
 * Unique porte de sortie de la tablette. Remplace BedrockProvider, AwsTranscribeRecognizer,
 * ControlPlaneProvider et MediaCatalogClient : la tablette ne parle qu'au cerveau, et ne
 * détient aucune clé de fournisseur.
 */
class BrainClient(
    private val settingsProvider: () -> BrainSettings,
    private val transport: BrainTransport = UrlBrainTransport(),
    private val history: ConversationHistory = ConversationHistory()
) : LlmProvider, WhisperRecognizer, MediaResolver {

    private val worker = Executors.newFixedThreadPool(2)
    private val mediaWorker = Executors.newFixedThreadPool(2)
    private val audioWorker = Executors.newSingleThreadExecutor()
    private val conversationLock = Any()
    private var generation = 0L
    private var pendingTurn: Long? = null

    /** An old network callback must never restore a cancelled message after reset. */
    fun cancelPending() {
        synchronized(conversationLock) {
            generation++
            // Do not remove the last completed user message when the UI merely
            // stops listening or interrupts an already finished answer.
            if (pendingTurn != null) {
                history.rollbackLastUser()
                pendingTurn = null
            }
        }
        (transport as? CancellableBrainTransport)?.cancelPending()
    }

    // ── Handshake ───────────────────────────────────────────────────────────

    fun hello(onComplete: (Result<BrainInfo>) -> Unit) {
        worker.execute { onComplete(runCatching { helloBlocking() }) }
    }

    private fun helloBlocking(): BrainInfo {
        val settings = requireSettings()
        val response = transport.request(
            "GET", "${settings.normalizedUrl()}/api/robot/hello",
            settings.pairingToken, null, null
        )
        check(response.status != 401) { "Jeton d'appairage refusé par le cerveau" }
        check(response.status in 200..299) { describe(response) }
        return parseHello(response.body)
    }

    // ── Hospitalité ─────────────────────────────────────────────────────────

    /**
     * Accroche à prononcer quand Pepper voit quelqu'un arriver.
     *
     * Le cerveau la tient prête : l'appel ne coûte qu'un aller-retour réseau, pas une
     * rédaction. On peut donc la demander à chaque engagement, ce qui fait que couper
     * l'hospitalité depuis la webapp prend effet à la personne suivante, sans
     * redémarrer la tablette.
     */
    fun greeting(onComplete: (Result<Greeting>) -> Unit) {
        worker.execute {
            onComplete(runCatching {
                val settings = requireSettings()
                val response = transport.request(
                    "GET", "${settings.normalizedUrl()}/api/robot/greeting",
                    settings.pairingToken, null, null
                )
                check(response.status in 200..299) { describe(response) }
                val root = JsonParser.parseString(response.body).asJsonObject
                Greeting(
                    active = root.get("active")?.asBoolean ?: false,
                    speech = root.get("speech")?.asString.orEmpty().trim()
                )
            })
        }
    }

    // ── Conversation ────────────────────────────────────────────────────────

    override fun respond(prompt: String, onComplete: (Result<String>) -> Unit) {
        val (turn, messages) = synchronized(conversationLock) {
            // The controller normally cancels before starting a new turn. Keep
            // this invariant here too so a double tap cannot leave two user
            // messages competing for the same history slot.
            if (pendingTurn != null) {
                generation++
                history.rollbackLastUser()
            }
            history.addUser(prompt)
            val newTurn = generation
            pendingTurn = newTurn
            newTurn to history.getMessages()
        }
        worker.execute {
            val result = runCatching {
                synchronized(conversationLock) {
                    if (turn != generation) throw CancellationException("Tour annulé")
                }
                val settings = requireSettings()
                val response = transport.request(
                    "POST", "${settings.normalizedUrl()}/api/robot/chat",
                    settings.pairingToken, "application/json; charset=utf-8",
                    buildChatBody(messages).toByteArray(StandardCharsets.UTF_8)
                )
                check(response.status in 200..299) { describe(response) }
                parseChat(response.body)
            }
            val current = synchronized(conversationLock) {
                if (turn == generation) {
                    result.fold(onSuccess = { history.addAssistant(it) }, onFailure = { history.rollbackLastUser() })
                    if (pendingTurn == turn) pendingTurn = null
                    true
                } else {
                    // A cancellation normally rolls this back synchronously;
                    // this branch covers a generation invalidated by another
                    // caller and keeps the transaction closed.
                    if (pendingTurn == turn) {
                        history.rollbackLastUser()
                        pendingTurn = null
                    }
                    false
                }
            }
            onComplete(if (current) result else Result.failure(CancellationException("Tour annulé")))
        }
    }

    // ── Transcription ───────────────────────────────────────────────────────

    /** Le cerveau n'a rien à charger côté tablette : le handshake suffit à dire s'il répond. */
    override fun load(onComplete: (Result<Long>) -> Unit) {
        worker.execute {
            val started = System.currentTimeMillis()
            onComplete(runCatching {
                helloBlocking()
                System.currentTimeMillis() - started
            })
        }
    }

    override fun transcribe(samples: ShortArray, onComplete: (Result<WhisperResult>) -> Unit) {
        audioWorker.execute {
            val started = System.currentTimeMillis()
            onComplete(runCatching {
                val settings = requireSettings()
                val response = transport.request(
                    "POST", "${settings.normalizedUrl()}/api/robot/transcribe",
                    settings.pairingToken, "application/octet-stream", toLittleEndianBytes(samples)
                )
                check(response.status in 200..299) { describe(response) }
                val text = JsonParser.parseString(response.body).asJsonObject
                    .get("text")?.asString?.trim().orEmpty()
                check(text.isNotEmpty()) { "Transcription vide" }
                WhisperResult(text, System.currentTimeMillis() - started)
            })
        }
    }

    // ── Médias ──────────────────────────────────────────────────────────────

    override fun resolve(query: String, onComplete: (Result<ResolvedMedia>) -> Unit) {
        mediaWorker.execute {
            onComplete(runCatching {
                val settings = requireSettings()
                val base = settings.normalizedUrl()
                val encoded = URLEncoder.encode(query, "UTF-8")
                val response = transport.request(
                    "GET", "$base/api/robot/media/resolve?q=$encoded",
                    settings.pairingToken, null, null
                )
                check(response.status in 200..299) { "Média introuvable : $query" }
                val root = JsonParser.parseString(response.body).asJsonObject
                val relative = root.get("url")?.asString ?: error("URL média absente")
                ResolvedMedia(
                    id = root.get("id")?.asString ?: error("ID média absent"),
                    name = root.get("name")?.asString ?: query,
                    kind = root.get("kind")?.asString ?: error("Type média absent"),
                    url = if (relative.startsWith("http")) relative else base + relative
                )
            })
        }
    }

    /**
     * Résout une description libre en URL affichable. Le cerveau consulte la
     * médiathèque du client avant le web.
     */
    fun resolveImage(query: String, onComplete: (Result<ResolvedMedia>) -> Unit) {
        mediaWorker.execute {
            onComplete(runCatching {
                val settings = requireSettings()
                val encoded = URLEncoder.encode(query, "UTF-8")
                val response = transport.request(
                    "GET", "${settings.normalizedUrl()}/api/robot/image?q=$encoded",
                    settings.pairingToken, null, null
                )
                check(response.status in 200..299) { describe(response) }
                val root = JsonParser.parseString(response.body).asJsonObject
                ResolvedMedia(
                    id = "",
                    name = root.get("title")?.asString ?: query,
                    kind = "image",
                    url = root.get("url")?.asString ?: error("URL image absente")
                )
            })
        }
    }

    override fun close() {
        cancelPending()
        worker.shutdownNow()
        mediaWorker.shutdownNow()
        audioWorker.shutdownNow()
    }

    private fun requireSettings(): BrainSettings {
        val settings = settingsProvider()
        check(settings.isComplete) { "Cerveau non appairé — renseigne son adresse et le jeton" }
        return settings
    }

    companion object {
        /** Le cerveau renvoie un message rédigé pour l'installateur : on l'affiche tel quel. */
        fun describe(response: HttpResult): String {
            val detail = runCatching {
                JsonParser.parseString(response.body).asJsonObject.get("detail")?.asString
            }.getOrNull()
            return detail ?: "Cerveau HTTP ${response.status}"
        }

        fun buildChatBody(messages: List<ConversationHistory.Message>): String =
            JsonObject().apply {
                add("messages", JsonArray().apply {
                    messages.forEach { message ->
                        add(JsonObject().apply {
                            addProperty("role", message.role)
                            addProperty("text", message.text)
                        })
                    }
                })
            }.toString()

        fun parseChat(body: String): String {
            val text = JsonParser.parseString(body).asJsonObject.get("response")?.asString?.trim()
            check(!text.isNullOrEmpty()) { "Réponse du cerveau vide" }
            return text
        }

        fun parseHello(body: String): BrainInfo {
            val root = JsonParser.parseString(body).asJsonObject
            val llm = root.getAsJsonObject("llm")
            val stt = root.getAsJsonObject("stt")
            return BrainInfo(
                name = root.get("name")?.asString.orEmpty(),
                llmProvider = llm?.get("active")?.asString.orEmpty(),
                llmModel = llm?.get("model")?.asString.orEmpty(),
                sttProvider = stt?.get("active")?.asString.orEmpty(),
                sttModel = stt?.get("model")?.asString.orEmpty(),
                mediaCount = root.get("media_count")?.asInt ?: 0
            )
        }

        /** PCM 16 bits petit-boutiste, exactement ce que le cerveau attend. */
        fun toLittleEndianBytes(samples: ShortArray): ByteArray {
            val bytes = ByteArray(samples.size * 2)
            for (index in samples.indices) {
                val value = samples[index].toInt()
                bytes[index * 2] = (value and 0xff).toByte()
                bytes[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
            }
            return bytes
        }
    }
}
