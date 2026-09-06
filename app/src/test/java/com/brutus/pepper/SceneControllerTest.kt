package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneControllerTest {
    @Test
    fun `sequence advances in order and stops after one pass`() {
        val renderer = FakeRenderer()
        val controller = SceneController(FakeResolver(), renderer) { 1_000L }
        controller.run(PlaySequenceAction(
            listOf(TextSceneItem("A", 1_000, 50f, "#FFFFFF"), MediaSceneItem("clip", null)),
            loopForMs = null
        ))

        assertEquals(listOf("clear", "text:A"), renderer.events)
        renderer.complete()
        assertEquals(listOf("clear", "text:A", "media:clip.mp4"), renderer.events)
        renderer.complete()
        assertEquals(listOf("clear", "text:A", "media:clip.mp4", "clear"), renderer.events)
    }

    @Test
    fun `new action invalidates stale completion`() {
        val renderer = FakeRenderer()
        val controller = SceneController(FakeResolver(), renderer) { 0L }
        controller.run(DisplayMediaAction("old", null))
        val stale = renderer.completion

        controller.run(DisplayTextAction("new", 1_000, 50f, "#FFFFFF"))
        stale?.invoke()

        assertEquals(listOf("clear", "media:old.mp4", "clear", "text:new"), renderer.events)
    }

    @Test
    fun `loop stops when wall clock deadline is reached`() {
        var now = 0L
        val renderer = FakeRenderer()
        val controller = SceneController(FakeResolver(), renderer) { now }
        controller.run(PlaySequenceAction(
            listOf(TextSceneItem("A", 2_000, 50f, "#FFFFFF")),
            loopForMs = 5_000
        ))

        now = 2_000; renderer.complete()
        now = 4_000; renderer.complete()
        now = 5_000; renderer.complete()

        assertEquals(listOf("clear", "text:A", "text:A", "text:A", "clear"), renderer.events)
    }

    @Test
    fun `kiosk delegates to persistent renderer`() {
        val renderer = FakeRenderer()
        SceneController(FakeResolver(), renderer).run(ShowKioskAction)

        assertEquals(listOf("clear", "kiosk"), renderer.events)
    }

    @Test
    fun `une image appelle le carrousel apres avoir ete retiree`() {
        val renderer = FakeRenderer()
        var completed = false
        val controller = SceneController(FakeResolver(), renderer)

        controller.showImageUrl("http://brain/image.jpg", "Image", 8_000L) {
            completed = true
        }
        renderer.complete()

        assertEquals(listOf("clear", "media:image.jpg", "clear"), renderer.events)
        assertTrue(completed)
    }

    private class FakeResolver : MediaResolver {
        override fun resolve(query: String, onComplete: (Result<ResolvedMedia>) -> Unit) {
            onComplete(Result.success(ResolvedMedia("id", query, "video", "http://machine-antenne.local/$query.mp4")))
        }
    }

    private class FakeRenderer : SceneRenderer {
        val events = mutableListOf<String>()
        var completion: (() -> Unit)? = null
        override fun clear() { events += "clear"; completion = null }
        override fun showText(item: TextSceneItem, maxDurationMs: Long?, onComplete: () -> Unit) {
            events += "text:${item.text}"; completion = onComplete
        }
        override fun showMedia(media: ResolvedMedia, durationMs: Long?, onComplete: () -> Unit) {
            events += "media:${media.url.substringAfterLast('/')}"; completion = onComplete
        }
        override fun showKiosk() { events += "kiosk" }
        fun complete() { val done = completion; completion = null; done?.invoke() }
    }
}
