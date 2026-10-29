package afield.animation.mocap

import afield.document.graph.Box
import afield.graphics.three.ThreeSceneBuilder
import afield.graphics.three.threeViewsOwnedBy
import afield.math.linalg.Vec3
import afield.ui.output.getStringCallback
import afield.ui.output.stringInput
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Portable motion authoring and viewer publication require neither a model nor a browser. */
class MotionPresentationTest {
    /** Completed worker futures preserve both the value and the underlying failure. */
    @Test
    fun awaitMotionPreservesCompletionAndFailure() = runBlocking {
        assertEquals("ready", CompletableFuture.completedFuture("ready").awaitMotion())
        val failed = CompletableFuture<String>()
        failed.completeExceptionally(IllegalStateException("worker failed"))
        assertFailsWith<IllegalStateException> { failed.awaitMotion() }
        Unit
    }

    /** The explicit follow target survives scene building and the real motion-output path. */
    @Test
    fun motionOutputPublishesItsFollowTargetAndEpisode() {
        val builder = ThreeSceneBuilder(48)
        builder.followCamera(Vec3(1f, 2f, 3f), halfLifeSeconds = .25, epoch = "reset-2")
        val explicit = assertNotNull(builder.payload("motion-follow-test").cameraFollow)
        assertEquals(listOf(1.0, 2.0, 3.0), explicit.target)
        assertEquals(.25, explicit.halfLifeSeconds)
        assertEquals("reset-2", explicit.epoch)

        val clip = MotionExamples.contractClip()
        val frame = clip.sample(1.35).frame
        val box = Box("motion-output-test")
        val shown = mutableListOf<Pair<String, Any?>>()
        box.output.displayProxy = { channel, value -> shown += channel to value }
        box.showMotion(frame, clip.skeleton, follow = true)
        assertEquals("motion", shown.single().first)
        assertTrue(shown.single().second.toString().contains("<iframe"))
        val payload = assertNotNull(threeViewsOwnedBy(listOf(box)).single().sourcePayload)
        val follow = assertNotNull(payload.cameraFollow)
        assertEquals(frame.executed.root, follow.target)
        assertEquals("${clip.skeleton.hash}:${frame.episode}", follow.epoch)
    }

    /** Editing the published intention control invokes the registered callback with the new prompt. */
    @Test
    fun intentionInputPublishesAndAcceptsAnEdit() {
        val box = Box("motion-intention-test")
        var html = ""
        var prompt = ""
        box.output.displayProxy = { _, value -> html = value.toString() }
        box.stringInput("Intention", initialValue = "walk <forward>") { prompt = it }
        assertTrue(html.contains("walk &lt;forward&gt;"))
        val id = Regex("id=\"(string-[^\"]+)\"").find(html)!!.groupValues[1]
        assertNotNull(getStringCallback(id)).invoke("turn left")
        assertEquals("turn left", prompt)
    }
}
