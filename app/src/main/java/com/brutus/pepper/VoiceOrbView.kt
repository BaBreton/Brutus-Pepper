package com.brutus.pepper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Dessin natif léger (30 i/s) pour la tablette ARM 32 bits. */
class VoiceOrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val presentation = VoicePresentation()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contour = Path()
    private var start = SystemClock.uptimeMillis()
    private var displayedScale = .72f
    private var shader: Shader? = null
    private var radius = 1f
    private var lastLevelAt = 0L
    var mode: VoiceMode
        get() = presentation.mode
        set(value) {
            presentation.mode = value
            contentDescription = when (value) {
                VoiceMode.MUTED -> "Micro coupé"
                VoiceMode.READY -> "En attente du mot Pepper"
                VoiceMode.LISTENING -> "Pepper vous écoute"
                VoiceMode.PROCESSING -> "Pepper prépare sa réponse"
                VoiceMode.SPEAKING -> "Pepper parle"
                VoiceMode.ERROR -> "Conversation indisponible"
            }
            invalidate()
        }

    fun setAudioLevel(rms: Int) {
        lastLevelAt = SystemClock.uptimeMillis()
        presentation.acceptLevel(rms)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radius = min(w, h) * .29f
        shader = RadialGradient(-radius * .36f, -radius * .38f, radius * 1.9f,
            intArrayOf(Color.rgb(202, 251, 242), Color.rgb(42, 183, 196), Color.rgb(28, 94, 171), Color.rgb(26, 58, 94)),
            floatArrayOf(0f, .34f, .73f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        if (now - lastLevelAt > 250) presentation.acceptLevel(0)
        presentation.step()
        displayedScale += (presentation.scale - displayedScale) * .13f
        val t = (now - start) / 1000f
        val active = mode != VoiceMode.MUTED && mode != VoiceMode.ERROR
        val organic = if (mode == VoiceMode.LISTENING) .035f + presentation.level * .065f else .018f
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        paint.color = Color.argb(23, 8, 127, 140)
        canvas.drawCircle(0f, 0f, radius * 1.48f, paint)
        canvas.drawCircle(0f, 0f, radius * 1.7f, paint)
        canvas.scale(displayedScale, displayedScale)
        contour.reset()
        for (i in 0..96) {
            val a = i * 2 * PI / 96
            val variation = if (active) sin(a * 3 + t * .9) * organic + cos(a * 5 - t * .7) * organic * .45 else 0.0
            val r = radius * (1 + variation)
            val x = (cos(a) * r).toFloat()
            val y = (sin(a) * r).toFloat()
            if (i == 0) contour.moveTo(x, y) else contour.lineTo(x, y)
        }
        contour.close()
        paint.style = Paint.Style.FILL
        paint.shader = if (active) shader else null
        paint.color = if (mode == VoiceMode.ERROR) Color.rgb(218, 173, 152) else Color.rgb(179, 198, 198)
        canvas.drawPath(contour, paint)
        paint.shader = null
        if (mode == VoiceMode.PROCESSING) {
            paint.color = Color.argb(225, 255, 255, 255)
            for (i in 0..2) canvas.drawCircle((i - 1) * radius * .27f, 0f,
                radius * (.045f + .012f * sin(t * 4 - i).toFloat()), paint)
        } else if (mode == VoiceMode.SPEAKING) {
            // Indique le TTS actif : animation d'état, pas mesure du volume de sortie.
            paint.color = Color.argb(210, 255, 255, 255)
            paint.strokeWidth = radius * .045f
            paint.strokeCap = Paint.Cap.ROUND
            for (i in -2..2) {
                val h = radius * (.12f + .055f * sin(t * 4 + i).toFloat())
                canvas.drawLine(i * radius * .15f, -h, i * radius * .15f, h, paint)
            }
        }
        canvas.restore()
        if (isShown && windowVisibility == VISIBLE && (active || kotlin.math.abs(displayedScale - presentation.scale) > .002f)) {
            postInvalidateDelayed(33)
        }
    }
}
