package com.brutus.pepper

import android.os.Handler
import android.os.Looper
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.SayBuilder
import java.util.concurrent.CancellationException

class QiSpeechGateway(
    private val qiContext: QiContext,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : SpeechGateway {
    private var speechFuture: Future<Void>? = null

    override fun speak(text: String, onComplete: (Result<Unit>) -> Unit) {
        val future = SayBuilder.with(qiContext)
            .withText(text)
            .buildAsync()
            .andThenCompose { say -> say.async().run() }

        speechFuture = future
        future.thenConsume { completed ->
            val result = when {
                completed.isCancelled -> Result.failure(CancellationException("Speech cancelled"))
                completed.hasError() -> Result.failure(completed.error)
                else -> Result.success(Unit)
            }
            mainHandler.post { onComplete(result) }
        }
    }

    override fun cancel() {
        speechFuture?.requestCancellation()
        speechFuture = null
    }
}
