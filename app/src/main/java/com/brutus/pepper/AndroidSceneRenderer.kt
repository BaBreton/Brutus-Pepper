package com.brutus.pepper

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @param settingsProvider coordonnées du cerveau, relues à chaque média : elles
 *   changent quand l'installateur réappaire la tablette, et un instantané pris au
 *   démarrage laisserait le rendu authentifier contre l'ancienne adresse.
 */
class AndroidSceneRenderer(
    private val activity: Activity,
    private val settingsProvider: () -> BrainSettings = { BrainSettings() }
) : SceneRenderer {
    private val handler = Handler(Looper.getMainLooper())
    private val imageWorker = Executors.newSingleThreadExecutor()
    private var renderGeneration = 0L

    private val overlay: FrameLayout get() = activity.findViewById(R.id.presentationOverlay)
    private val textView: TextView get() = activity.findViewById(R.id.sceneText)
    private val imageView: ImageView get() = activity.findViewById(R.id.sceneImage)
    private val idleView: ImageView get() = activity.findViewById(R.id.idleImage)
    private val videoView: VideoView get() = activity.findViewById(R.id.sceneVideo)
    private val kioskPanel: LinearLayout get() = activity.findViewById(R.id.kioskPanel)
    private val kioskDate: TextView get() = activity.findViewById(R.id.kioskDate)
    private val kioskTime: TextView get() = activity.findViewById(R.id.kioskTime)

    override fun clear() = onUi {
        renderGeneration += 1
        handler.removeCallbacksAndMessages(null)
        videoView.stopPlayback()
        videoView.setOnCompletionListener(null)
        videoView.setOnErrorListener(null)
        imageView.setImageDrawable(null)
        idleView.setImageDrawable(null)
        textView.text = ""
        listOf(textView, imageView, idleView, videoView, kioskPanel).forEach { it.visibility = View.GONE }
        overlay.visibility = View.INVISIBLE
        overlay.isClickable = false
        overlay.isFocusable = false
    }

    override fun showText(item: TextSceneItem, maxDurationMs: Long?, onComplete: () -> Unit) = onUi {
        Log.d(TAG, "showText: '${item.text.take(40)}' durationMs=${item.durationMs}")
        val generation = prepare()
        textView.text = item.text
        textView.textSize = item.fontSizeSp
        textView.setTextColor(Color.parseColor(item.color))
        textView.visibility = View.VISIBLE
        textView.requestLayout()
        textView.invalidate()
        Log.d(TAG, "showText: textView=${textView.width}x${textView.height} vis=${textView.visibility}")
        handler.postDelayed({ if (generation == renderGeneration) onComplete() },
            minOf(item.durationMs, maxDurationMs ?: item.durationMs))
    }

    override fun showMedia(media: ResolvedMedia, durationMs: Long?, onComplete: () -> Unit) {
        if (media.kind == "image") showImage(media, durationMs ?: DEFAULT_IMAGE_DURATION_MS, onComplete)
        else if (media.kind == "video") showVideo(media, durationMs, onComplete)
        else onComplete()
    }

    override fun showKiosk() = onUi {
        val generation = prepare()
        kioskPanel.visibility = View.VISIBLE
        val update = object : Runnable {
            override fun run() {
                if (generation != renderGeneration) return
                val now = Date()
                kioskDate.text = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRENCH).format(now)
                    .replaceFirstChar { it.uppercase() }
                kioskTime.text = SimpleDateFormat("HH:mm:ss", Locale.FRENCH).format(now)
                handler.postDelayed(this, 1_000L)
            }
        }
        update.run()
    }

    /**
     * Affiche l'image d'accueil, sans minuterie : elle reste jusqu'à ce qu'on la
     * touche ou qu'une scène de conversation prenne sa place.
     *
     * Elle emprunte volontairement le même écran et la même génération que les
     * scènes : une réponse qui veut montrer quelque chose la remplace du seul fait
     * de s'afficher, sans que personne ait à les coordonner.
     *
     * [onShown] reçoit faux si l'image n'a pas pu être téléchargée — le cerveau
     * éteint, par exemple. L'appelant s'abstient alors de la croire affichée.
     */
    fun showIdleImage(url: String, onShown: (Boolean) -> Unit = {}) = onUi {
        val generation = prepare()
        imageWorker.execute {
            val bitmap = runCatching { loadScaledBitmap(url) }.getOrNull()
            onUi idleResult@{
                if (generation != renderGeneration) return@idleResult onShown(false)
                if (bitmap == null) {
                    Log.w(TAG, "image d'accueil illisible : $url")
                    clear()
                    return@idleResult onShown(false)
                }
                idleView.setImageBitmap(bitmap)
                idleView.visibility = View.VISIBLE
                onShown(true)
            }
        }
    }

    fun close() {
        clear()
        imageWorker.shutdownNow()
    }

    private fun showImage(media: ResolvedMedia, durationMs: Long, onComplete: () -> Unit) = onUi {
        val generation = prepare()
        imageWorker.execute {
            val bitmap = runCatching { loadScaledBitmap(media.url) }.getOrNull()
            onUi imageResult@{
                if (generation != renderGeneration) return@imageResult
                if (bitmap == null) return@imageResult onComplete()
                imageView.setImageBitmap(bitmap)
                imageView.visibility = View.VISIBLE
                handler.postDelayed({ if (generation == renderGeneration) onComplete() }, durationMs)
            }
        }
    }

    private fun showVideo(media: ResolvedMedia, durationMs: Long?, onComplete: () -> Unit) = onUi {
        val generation = prepare()
        val completed = AtomicBoolean(false)
        val done = {
            if (generation == renderGeneration && completed.compareAndSet(false, true)) onComplete()
        }
        videoView.visibility = View.VISIBLE
        videoView.setOnPreparedListener { it.isLooping = false; videoView.start() }
        videoView.setOnCompletionListener { done() }
        videoView.setOnErrorListener { _, _, _ -> done(); true }
        // Même remarque que pour les images : les vidéos de la médiathèque sont servies
        // par le cerveau, donc derrière le jeton.
        val headers = BrainAuth.headerFor(media.url, settingsProvider())
        if (headers == null) videoView.setVideoURI(Uri.parse(media.url))
        else videoView.setVideoURI(Uri.parse(media.url), mapOf(headers))
        if (durationMs != null) handler.postDelayed({ done() }, durationMs)
    }

    private fun prepare(): Long {
        renderGeneration += 1
        handler.removeCallbacksAndMessages(null)
        videoView.stopPlayback()
        listOf(textView, imageView, idleView, videoView, kioskPanel).forEach { it.visibility = View.GONE }
        overlay.visibility = View.VISIBLE
        overlay.isClickable = true
        overlay.isFocusable = true
        // The layout places scenes above content, but below the persistent navigation controls.
        overlay.invalidate()
        Log.d(TAG, "prepare: overlay=${overlay.width}x${overlay.height} visibility=${overlay.visibility}")
        return renderGeneration
    }

    private fun loadScaledBitmap(url: String): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        imageStream(url) { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // inSampleSize divise les deux côtés du même facteur : le rapport de l'image
        // est conservé, jamais déformé. Le plafond évite seulement de décoder une
        // photo d'appareil photo en entier dans la mémoire de la tablette.
        var sample = 1
        while (bounds.outWidth / sample > 1_920 || bounds.outHeight / sample > 1_080) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return imageStream(url) { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun <T> imageStream(url: String, block: (java.io.InputStream) -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 20_000
        // Le cerveau sert ses images derrière le jeton d'appairage : sans cet en-tête
        // la requête revient en 401 et l'écran s'ouvre puis se referme sur du vide.
        BrainAuth.headerFor(url, settingsProvider())?.let { (name, value) ->
            connection.setRequestProperty(name, value)
        }
        return try {
            connection.inputStream.use(block)
        } finally {
            connection.disconnect()
        }
    }

    private fun onUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else activity.runOnUiThread(block)
    }

    private companion object {
        const val DEFAULT_IMAGE_DURATION_MS = 8_000L
        const val TAG = "BrutusScene"
    }
}
