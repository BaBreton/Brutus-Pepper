package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCarouselControllerTest {
    @Test
    fun `les demandes sont resolues et presentees une par une dans l ordre`() {
        val resolver = FakeResolver()
        val presenter = FakePresenter()
        val controller = ImageCarouselController(
            resolve = resolver::resolve,
            display = presenter::show,
            clear = { presenter.clearCount++ },
        )

        controller.enqueue(DisplayImageAction("tour Eiffel", null))
        controller.enqueue(DisplayImageAction("Louvre", 4_000L))

        assertEquals(listOf("tour Eiffel"), resolver.queries)
        resolver.complete(Result.success(media("tour Eiffel")))
        assertEquals(listOf("tour Eiffel"), presenter.shown)
        assertEquals(listOf("tour Eiffel"), resolver.queries)

        presenter.complete()
        assertEquals(listOf("tour Eiffel", "Louvre"), resolver.queries)
        resolver.complete(Result.success(media("Louvre")))
        assertEquals(listOf("tour Eiffel", "Louvre"), presenter.shown)

        presenter.complete()
        assertTrue(presenter.completion == null)
    }

    @Test
    fun `une image introuvable ne bloque pas la suivante`() {
        val resolver = FakeResolver()
        val presenter = FakePresenter()
        val controller = ImageCarouselController(
            resolve = resolver::resolve,
            display = presenter::show,
            clear = { presenter.clearCount++ },
        )

        controller.enqueue(DisplayImageAction("introuvable", null))
        controller.enqueue(DisplayImageAction("bonne image", null))
        resolver.complete(Result.failure(IllegalStateException("404")))

        assertEquals(listOf("introuvable", "bonne image"), resolver.queries)
        resolver.complete(Result.success(media("bonne image")))
        assertEquals(listOf("bonne image"), presenter.shown)
    }

    @Test
    fun `annuler vide la file et ignore une resolution deja lancee`() {
        val resolver = FakeResolver()
        val presenter = FakePresenter()
        val controller = ImageCarouselController(
            resolve = resolver::resolve,
            display = presenter::show,
            clear = { presenter.clearCount++ },
        )

        controller.enqueue(DisplayImageAction("ancienne", null))
        controller.cancel()
        resolver.complete(Result.success(media("ancienne")))

        assertTrue(presenter.shown.isEmpty())
        assertEquals(1, presenter.clearCount)
    }

    private fun media(name: String) = ResolvedMedia(name, name, "image", "https://brain.test/$name.jpg")

    private class FakeResolver {
        val queries = mutableListOf<String>()
        private var callback: ((Result<ResolvedMedia>) -> Unit)? = null

        fun resolve(query: String, onComplete: (Result<ResolvedMedia>) -> Unit) {
            queries += query
            callback = onComplete
        }

        fun complete(result: Result<ResolvedMedia>) {
            val next = callback
            callback = null
            next?.invoke(result)
        }
    }

    private class FakePresenter {
        val shown = mutableListOf<String>()
        var completion: (() -> Unit)? = null
        var clearCount = 0

        @Suppress("UNUSED_PARAMETER")
        fun show(media: ResolvedMedia, durationMs: Long?, onComplete: () -> Unit) {
            shown += media.name
            completion = onComplete
        }

        fun complete() {
            val next = completion
            completion = null
            next?.invoke()
        }
    }
}
