package com.brutus.pepper

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser

private const val PARSE_TAG = "BrutusScene"

data class AssistantResponse(
    val speech: String,
    val actions: List<AssistantAction>
)

sealed interface AssistantAction

data class DisplayTextAction(
    val text: String,
    val durationMs: Long,
    val fontSizeSp: Float,
    val color: String
) : AssistantAction

data class DisplayMediaAction(
    val query: String,
    val durationMs: Long?
) : AssistantAction

/**
 * Affiche une image décrite librement. Le cerveau la résout — médiathèque du client
 * d'abord, recherche web ensuite — pendant que Pepper parle, donc la recherche ne
 * coûte pas de latence perçue.
 */
data class DisplayImageAction(
    val query: String,
    val durationMs: Long?
) : AssistantAction

sealed interface SceneItem

data class TextSceneItem(
    val text: String,
    val durationMs: Long,
    val fontSizeSp: Float,
    val color: String
) : SceneItem

data class MediaSceneItem(
    val query: String,
    val durationMs: Long?
) : SceneItem

data class PlaySequenceAction(
    val items: List<SceneItem>,
    val loopForMs: Long?
) : AssistantAction

data object ShowKioskAction : AssistantAction
data object PointRightAction : AssistantAction
data object PointLeftAction : AssistantAction
data object TurnAroundAction : AssistantAction

object AssistantResponseParser {
    fun parse(raw: String): AssistantResponse {
        val original = raw.trim()
        Log.d(PARSE_TAG, "parse: raw[0..200]=${raw.take(200)}")
        val candidate = removeJsonFence(original)
        if (!candidate.startsWith("{")) {
            Log.e(PARSE_TAG, "parse: fallback - no leading brace. candidate[0..80]=${candidate.take(80)}")
            return AssistantResponse(original, emptyList())
        }
        val root = runCatching { JsonParser.parseString(candidate).asJsonObject }.getOrNull()
        if (root == null) {
            Log.e(PARSE_TAG, "parse: fallback - gson threw on candidate[0..80]=${candidate.take(80)}")
            return AssistantResponse(original, emptyList())
        }
        val speech = root.string("speech")?.trim().orEmpty()
        val actions = root.get("actions")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.asSequence()
            ?.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.toAction()
            }
            ?.take(MAX_ACTIONS)
            ?.toList()
            .orEmpty()
        Log.d(PARSE_TAG, "parse: OK speech[0..60]='${speech.take(60)}' actions=${actions.size}")
        return AssistantResponse(speech, actions)
    }

    private fun JsonObject.toAction(): AssistantAction? {
        val name = string("name") ?: return null
        // No-argument actions resolved before requiring an arguments block
        when (name) {
            "show_kiosk"   -> return ShowKioskAction
            "point_right"  -> return PointRightAction
            "point_left"   -> return PointLeftAction
            "turn_around"  -> return TurnAroundAction
        }
        val arguments = get("arguments")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null
        return when (name) {
            "display_text"  -> arguments.toDisplayText()
            "display_media" -> arguments.toDisplayMedia()
            "display_image" -> arguments.toDisplayImage()
            "play_sequence" -> arguments.toSequence()
            else -> null
        }
    }

    private fun JsonObject.toDisplayText(): DisplayTextAction? {
        val text = string("text")?.trim()?.take(MAX_TEXT_LENGTH).orEmpty()
        if (text.isEmpty()) return null
        val durationSeconds = long("duration_seconds", DEFAULT_DURATION_SECONDS)
            .coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
        val fontSize = float("font_size_sp", DEFAULT_FONT_SIZE_SP)
            .coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        val requestedColor = string("color")?.uppercase().orEmpty()
        val color = requestedColor.takeIf(COLOR_PATTERN::matches) ?: DEFAULT_COLOR
        return DisplayTextAction(text, durationSeconds * 1_000L, fontSize, color)
    }

    private fun JsonObject.toDisplayImage(): DisplayImageAction? {
        val query = string("query")?.trim()?.take(MAX_QUERY_LENGTH).orEmpty()
        if (query.isEmpty()) return null
        return DisplayImageAction(query, optionalDurationMs("duration_seconds"))
    }

    private fun JsonObject.toDisplayMedia(): DisplayMediaAction? {
        val query = string("query")?.trim()?.take(MAX_QUERY_LENGTH).orEmpty()
        if (query.isEmpty()) return null
        return DisplayMediaAction(query, optionalDurationMs("duration_seconds"))
    }

    private fun JsonObject.toSequence(): PlaySequenceAction? {
        val array = get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val items = array.asSequence().mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.toSceneItem()
        }.take(MAX_SCENE_ITEMS).toList()
        if (items.isEmpty()) return null
        val loopFor = optionalLong("loop_for_seconds")
            ?.coerceIn(MIN_DURATION_SECONDS, MAX_LOOP_SECONDS)
            ?.times(1_000L)
        return PlaySequenceAction(items, loopFor)
    }

    private fun JsonObject.toSceneItem(): SceneItem? = when (string("type")) {
        "text" -> {
            val text = string("text")?.trim()?.take(MAX_TEXT_LENGTH).orEmpty()
            if (text.isEmpty()) null else TextSceneItem(
                text = text,
                durationMs = long("duration_seconds", DEFAULT_DURATION_SECONDS)
                    .coerceIn(MIN_DURATION_SECONDS, MAX_ITEM_SECONDS) * 1_000L,
                fontSizeSp = float("font_size_sp", DEFAULT_FONT_SIZE_SP)
                    .coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP),
                color = string("color")?.uppercase()?.takeIf(COLOR_PATTERN::matches) ?: DEFAULT_COLOR
            )
        }
        "media" -> {
            val query = string("query")?.trim()?.take(MAX_QUERY_LENGTH).orEmpty()
            if (query.isEmpty()) null else MediaSceneItem(query, optionalDurationMs("duration_seconds"))
        }
        else -> null
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.long(name: String, fallback: Long): Long =
        runCatching { get(name)?.asLong ?: fallback }.getOrDefault(fallback)

    private fun JsonObject.optionalLong(name: String): Long? =
        runCatching { get(name)?.takeUnless { it.isJsonNull }?.asLong }.getOrNull()

    private fun JsonObject.optionalDurationMs(name: String): Long? = optionalLong(name)
        ?.coerceIn(MIN_DURATION_SECONDS, MAX_ITEM_SECONDS)
        ?.times(1_000L)

    private fun JsonObject.float(name: String, fallback: Float): Float =
        runCatching { get(name)?.asFloat ?: fallback }.getOrDefault(fallback)

    private fun removeJsonFence(text: String): String {
        if (!text.startsWith("```")) return text
        return text
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private const val MAX_TEXT_LENGTH = 500
    private const val MAX_QUERY_LENGTH = 120
    private const val MAX_ACTIONS = 20
    private const val MAX_SCENE_ITEMS = 20
    private const val MIN_DURATION_SECONDS = 1L
    private const val MAX_DURATION_SECONDS = 60L
    private const val DEFAULT_DURATION_SECONDS = 8L
    private const val MAX_ITEM_SECONDS = 300L
    private const val MAX_LOOP_SECONDS = 3_600L
    private const val MIN_FONT_SIZE_SP = 24f
    private const val MAX_FONT_SIZE_SP = 120f
    private const val DEFAULT_FONT_SIZE_SP = 64f
    private const val DEFAULT_COLOR = "#FFFFFF"
    private val COLOR_PATTERN = Regex("^#[0-9A-F]{6}$")
}
