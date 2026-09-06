package com.brutus.pepper

/**
 * Résout et affiche les images dans l'ordre où Pepper les a demandées.
 *
 * La résolution web est asynchrone et peut être plus lente que la réponse parlée.
 * Une seule image est donc résolue à la fois : la suivante ne remplace pas la
 * précédente et une image lente ne peut pas arriver après une image demandée plus
 * tard. [display] appelle son callback quand l'image a fini d'être visible.
 */
class ImageCarouselController(
    private val resolve: (String, (Result<ResolvedMedia>) -> Unit) -> Unit,
    private val display: (ResolvedMedia, Long?, () -> Unit) -> Unit,
    private val clear: () -> Unit = {},
    private val dispatch: ((() -> Unit) -> Unit) = { it() },
    private val onFailure: (DisplayImageAction, Throwable?) -> Unit = { _, _ -> },
    private val maxPending: Int = MAX_PENDING_IMAGES,
) {
    private data class Pending(
        val id: Long,
        val action: DisplayImageAction,
        val generation: Long,
    )

    private val queue = ArrayDeque<DisplayImageAction>()
    private var active: Pending? = null
    private var generation = 0L
    private var nextId = 0L

    /** Ajoute une image au carrousel sans interrompre celle qui est déjà affichée. */
    @Synchronized
    fun enqueue(action: DisplayImageAction) {
        if (queue.size >= maxPending) return
        queue.addLast(action)
        if (active == null) startNextLocked()?.let(::resolveNext)
    }

    /** Retire l'image visible et ignore les résolutions encore en vol. */
    fun cancel() {
        synchronized(this) {
            generation++
            queue.clear()
            active = null
        }
        clear()
    }

    private fun startNextLocked(): Pending? {
        if (queue.isEmpty()) return null
        val pending = Pending(++nextId, queue.first(), generation)
        active = pending
        return pending
    }

    private fun resolveNext(pending: Pending) {
        resolve(pending.action.query) { result ->
            if (!isCurrent(pending)) return@resolve
            if (result.isFailure) {
                onFailure(pending.action, result.exceptionOrNull())
                advance(pending)
                return@resolve
            }
            val media = result.getOrNull() ?: run {
                onFailure(pending.action, null)
                advance(pending)
                return@resolve
            }
            dispatch {
                if (!isCurrent(pending)) return@dispatch
                display(media, pending.action.durationMs) { advance(pending) }
            }
        }
    }

    private fun advance(pending: Pending) {
        val next = synchronized(this) {
            if (active?.id != pending.id || pending.generation != generation) return
            queue.removeFirst()
            active = null
            startNextLocked()
        }
        next?.let(::resolveNext)
    }

    @Synchronized
    private fun isCurrent(pending: Pending): Boolean =
        active?.id == pending.id && pending.generation == generation

    private companion object {
        const val MAX_PENDING_IMAGES = 20
    }
}
