package com.brutus.pepper

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.android.StorageService

/**
 * Unpacks and holds the single Vosk French model, shared by both the wake-word engine
 * and the command recognizer. The model is unpacked ONCE, asynchronously; consumers register
 * via [whenReady] and are notified when (or immediately if) the model is loaded.
 *
 * This fixes the previous race where WakeWordEngine called start() before the ~85 MB model
 * finished unpacking and silently no-op'd.
 */
class VoskModelHolder(context: Context) {

    @Volatile var model: Model? = null
        private set
    @Volatile private var error: Throwable? = null

    private val lock = Any()
    private val readyWaiters = mutableListOf<(Model) -> Unit>()
    private val errorWaiters = mutableListOf<(Throwable) -> Unit>()

    init {
        StorageService.unpack(
            context, "model-fr", "model",
            { loaded ->
                val waiters: List<(Model) -> Unit>
                synchronized(lock) {
                    model = loaded
                    waiters = readyWaiters.toList()
                    readyWaiters.clear()
                    errorWaiters.clear()
                }
                Log.i(TAG, "Vosk model loaded")
                waiters.forEach { it(loaded) }
            },
            { e ->
                val waiters: List<(Throwable) -> Unit>
                synchronized(lock) {
                    error = e
                    waiters = errorWaiters.toList()
                    errorWaiters.clear()
                    readyWaiters.clear()
                }
                Log.e(TAG, "Vosk model unpack failed", e)
                waiters.forEach { it(e) }
            }
        )
    }

    /**
     * Invoke [onReady] with the model (immediately if already loaded), or [onError] if the
     * unpack already failed / fails later. Exactly one of the two fires per registration.
     */
    fun whenReady(onReady: (Model) -> Unit, onError: (Throwable) -> Unit = {}) {
        val ready: Model?
        val failed: Throwable?
        synchronized(lock) {
            ready = model
            failed = error
            if (ready == null && failed == null) {
                readyWaiters.add(onReady)
                errorWaiters.add(onError)
                return
            }
        }
        if (ready != null) onReady(ready) else if (failed != null) onError(failed)
    }

    companion object {
        private const val TAG = "VoskModelHolder"
    }
}
