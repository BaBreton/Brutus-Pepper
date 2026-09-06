package com.brutus.pepper

interface SceneRenderer {
    fun clear()
    fun showText(item: TextSceneItem, maxDurationMs: Long? = null, onComplete: () -> Unit)
    fun showMedia(media: ResolvedMedia, durationMs: Long?, onComplete: () -> Unit)
    fun showKiosk()
}

interface RobotGestureDispatcher {
    fun dispatch(action: BoundAction)
}

class SceneController(
    private val resolver: MediaResolver,
    private val renderer: SceneRenderer,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private var generation = 0L

    @Synchronized
    fun run(action: AssistantAction) {
        val activeGeneration = ++generation
        renderer.clear()
        when (action) {
            is DisplayTextAction -> renderer.showText(
                TextSceneItem(action.text, PERSISTENT_DISPLAY_MS, action.fontSizeSp, action.color)
            ) { finish(activeGeneration) }
            is DisplayMediaAction -> showMedia(action.query, action.durationMs, activeGeneration) {
                finish(activeGeneration)
            }
            is PlaySequenceAction -> {
                val deadline = action.loopForMs?.let { nowMs() + it }
                playItem(action, 0, deadline, activeGeneration)
            }
            ShowKioskAction -> renderer.showKiosk()
            is DisplayImageAction -> {
                /* la résolution passe par le cerveau : MainActivity appelle showImageUrl */
            }
            PointRightAction, PointLeftAction, TurnAroundAction -> {
                /* physical gestures handled by MainActivity via actionCoordinator */
            }
        }
    }

    /** Affiche une image déjà résolue en URL par le cerveau. */
    @Synchronized
    fun showImageUrl(url: String, name: String, durationMs: Long?, onComplete: () -> Unit = {}) {
        val activeGeneration = ++generation
        renderer.clear()
        renderer.showMedia(ResolvedMedia("", name, "image", url), durationMs) {
            finish(activeGeneration)
            onComplete()
        }
    }

    @Synchronized
    fun cancel() {
        generation += 1
        renderer.clear()
    }

    private fun playItem(
        action: PlaySequenceAction,
        index: Int,
        deadline: Long?,
        activeGeneration: Long
    ) {
        if (!isActive(activeGeneration)) return
        if (deadline != null && nowMs() >= deadline) return finish(activeGeneration)
        if (index >= action.items.size) {
            if (deadline != null) playItem(action, 0, deadline, activeGeneration)
            else finish(activeGeneration)
            return
        }
        val remaining = deadline?.let { (it - nowMs()).coerceAtLeast(1L) }
        val done = { playItem(action, index + 1, deadline, activeGeneration) }
        when (val item = action.items[index]) {
            is TextSceneItem -> renderer.showText(item, remaining, done)
            is MediaSceneItem -> showMedia(
                item.query,
                minDuration(item.durationMs, remaining),
                activeGeneration,
                done
            )
        }
    }

    private fun showMedia(
        query: String,
        durationMs: Long?,
        activeGeneration: Long,
        onComplete: () -> Unit
    ) {
        resolver.resolve(query) { result ->
            if (!isActive(activeGeneration)) return@resolve
            result.fold(
                onSuccess = { renderer.showMedia(it, durationMs, onComplete) },
                onFailure = { onComplete() }
            )
        }
    }

    @Synchronized
    private fun finish(activeGeneration: Long) {
        if (generation != activeGeneration) return
        generation += 1
        renderer.clear()
    }

    @Synchronized
    private fun isActive(activeGeneration: Long): Boolean = generation == activeGeneration

    private fun minDuration(requested: Long?, remaining: Long?): Long? = when {
        requested == null -> remaining
        remaining == null -> requested
        else -> minOf(requested, remaining)
    }

    companion object {
        // Display text persists until the next action or user tap — no auto-clear during speech.
        private const val PERSISTENT_DISPLAY_MS = 5 * 60 * 1_000L
    }
}
