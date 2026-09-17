package afield.four.targets

import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The validity rule, which is the whole reason [FrameHistory] exists rather than a pair of matrices.
 *
 * ### Why this file is the important one
 *
 * Reprojection has two halves and only one of them is visible. "Where was this pixel last frame" is
 * a matrix multiply that is either right or produces an obviously smeared image. "*Is* there a last
 * frame for this pixel" is a boolean, and when it is wrong the image looks **completely fine** — it
 * blends against a buffer that happens to hold something plausible. A frame that was dropped, a
 * window that was resized between captures, a camera that cut: all three leave a previous-frame
 * buffer full of a perfectly good picture of the wrong thing.
 *
 * `standardRenderer.kt`'s private `FrameStableHistory` is the worked example of getting this wrong.
 * It advances on `frame != lastFrameSeen`, not on `frame == lastFrameSeen + 1`, so after a skipped
 * frame it hands out a previous value that is two or more frames old and says nothing about it. It
 * has no size, no epoch and no invalidate. [historyIsUnavailableAfterASkippedFrame] is the assertion
 * that separates the two, and it is the assertion that failed when this file was first written
 * against the old semantics.
 *
 * ### The premise assertion
 *
 * [theCounterfactualIsNotVacuous] comes first on purpose. Every check below is of the form "under
 * condition X, `available` is false", and all of them would pass against an implementation that
 * returned false unconditionally. So the first thing asserted is that the *positive* case is
 * reachable at all — a consecutive frame at an unchanged size with the same epoch is available, and
 * its reprojection matrix is not the identity when the camera moved.
 */
class FrameHistoryTest {

    private val epoch = Any()

    /** A view-projection with a recognisable, invertible camera at [x]. */
    private fun viewProjection(x: Float, fov: Float = 1.0f): Matrix4f =
        Matrix4f().setPerspective(fov, 1f, 0.1f, 100f)
            .mul(Matrix4f().lookAt(Vector3f(x, 0f, 3f), Vector3f(), Vector3f(0f, 1f, 0f)))

    /** Drive [history] to a state where history *is* available, and return the frame it stands at. */
    private fun warm(history: FrameHistory, at: Long = 10L, size: Int = 64): Long {
        history.capture(at, viewProjection(0f), size, size, epoch)
        history.capture(at + 1, viewProjection(0.1f), size, size, epoch)
        assertTrue(history.available, "warm-up did not reach an available state")
        return at + 1
    }

    @Test
    fun theCounterfactualIsNotVacuous() {
        val history = FrameHistory()
        assertFalse(history.available, "a fresh FrameHistory cannot have history")
        history.capture(0L, viewProjection(0f), 64, 64, epoch)
        assertFalse(history.available, "frame 0 has no previous frame to be available")
        val became = history.capture(1L, viewProjection(0.25f), 64, 64, epoch)
        assertTrue(became, "capture should report the availability it just computed")
        assertTrue(history.available, "a consecutive frame at the same size must be available")

        // And the reprojection is a real transform, not an identity that would make every
        // "unavailable" assertion below pass for the wrong reason.
        val drift = (0 until 16).maxOf { abs(history.reprojection[it] - Matrix4f()[it]) }
        assertTrue(drift > 1e-4f, "the camera moved but reprojection is identity (max drift $drift)")
    }

    @Test
    fun reprojectionMapsCurrentClipBackToWhereThePointWas() {
        // The oracle for the matrix itself, on the CPU: take a world point, project it with both
        // matrices by hand, and require that `reprojection` carries the current clip position to the
        // previous one. This is what a shader does per pixel, and it is arithmetic, so it belongs
        // here rather than in a GPU test that can only say the picture looked plausible.
        val history = FrameHistory()
        val before = viewProjection(0f)
        val after = viewProjection(0.4f)
        history.capture(1L, before, 64, 64, epoch)
        history.capture(2L, after, 64, 64, epoch)
        assertTrue(history.available)

        var worst = 0.0f
        var samples = 0
        for (wx in -1..1) for (wy in -1..1) for (wz in -1..1) {
            val world = Vector4f(wx * 0.5f, wy * 0.5f, wz * 0.5f, 1f)
            val nowClip = after.transform(Vector4f(world), Vector4f())
            val thenClip = before.transform(Vector4f(world), Vector4f())
            val viaReprojection = history.reprojection.transform(Vector4f(nowClip), Vector4f())
            // Compare in NDC: clip is only defined up to w, and that is how a shader reads it.
            val expected = Vector3f(thenClip.x / thenClip.w, thenClip.y / thenClip.w, thenClip.z / thenClip.w)
            val actual = Vector3f(
                viaReprojection.x / viaReprojection.w,
                viaReprojection.y / viaReprojection.w,
                viaReprojection.z / viaReprojection.w
            )
            worst = maxOf(worst, abs(actual.x - expected.x), abs(actual.y - expected.y), abs(actual.z - expected.z))
            samples++
        }
        assertEquals(27, samples, "the sample set over the unit cube should be 27 points")
        assertTrue(worst < 1e-3f, "reprojection disagreed with hand-composed matrices by $worst NDC over $samples points")
    }

    @Test
    fun historyIsUnavailableOnTheVeryFirstFrame() {
        val history = FrameHistory()
        history.capture(7L, viewProjection(0f), 64, 64, epoch)
        assertFalse(history.available, "the first capture has nothing behind it, whatever its frame id")
        // And `previous` must equal `current` so a shader that ignores `available` degrades to a
        // zero-motion read rather than to whatever was in the matrix.
        assertEquals(0f, (0 until 16).maxOf { abs(history.previous[it] - history.current[it]) })
    }

    @Test
    fun historyIsUnavailableAfterASkippedFrame() {
        // THE assertion. `FrameStableHistory` passes every other check in this file and fails this
        // one, because `frame != lastFrameSeen` is true for a gap of five frames just as it is for a
        // gap of one.
        val history = FrameHistory()
        val at = warm(history)
        history.capture(at + 5, viewProjection(0.9f), 64, 64, epoch)
        assertFalse(history.available, "a five-frame gap is not a previous frame")
        // Then it recovers on its own, without an explicit reset.
        history.capture(at + 6, viewProjection(1.0f), 64, 64, epoch)
        assertTrue(history.available, "history should recover once frames are consecutive again")
    }

    @Test
    fun historyIsUnavailableAfterAResize() {
        val history = FrameHistory()
        val at = warm(history, size = 64)
        history.capture(at + 1, viewProjection(0.2f), 64, 32, epoch)
        assertFalse(history.available, "a previous frame at a different size is not addressable")
        history.capture(at + 2, viewProjection(0.3f), 64, 32, epoch)
        assertTrue(history.available, "history should re-establish at the new size")
    }

    @Test
    fun historyIsUnavailableAfterAnExplicitInvalidate() {
        val history = FrameHistory()
        val at = warm(history)
        history.invalidate()
        history.capture(at + 1, viewProjection(0.2f), 64, 64, epoch)
        assertFalse(history.available, "invalidate() is the camera-cut/scene-edit escape hatch")
        history.capture(at + 2, viewProjection(0.3f), 64, 64, epoch)
        assertTrue(history.available, "one invalidate must not disable history forever")
    }

    @Test
    fun historyIsUnavailableAfterAnEpochChange() {
        val history = FrameHistory()
        val at = warm(history)
        history.capture(at + 1, viewProjection(0.2f), 64, 64, Any())
        assertFalse(history.available, "a new epoch identity means a different scene")
        // Identity, not equality: two epochs that are `==` but not `===` are still a change, because
        // the epoch's job is to be a token somebody swaps, not a value somebody compares.
        val shared = listOf(1)
        history.capture(at + 2, viewProjection(0.3f), 64, 64, shared)
        assertFalse(history.available)
        history.capture(at + 3, viewProjection(0.4f), 64, 64, listOf(1))
        assertFalse(history.available, "an equal-but-not-identical epoch must still invalidate")
        history.capture(at + 4, viewProjection(0.5f), 64, 64, shared)
        assertFalse(history.available, "and going back to the first token is another change")
        history.capture(at + 5, viewProjection(0.6f), 64, 64, shared)
        assertTrue(history.available, "two consecutive frames on one token are available")
    }

    @Test
    fun historyIsAvailableOnAConsecutiveFrameAtTheSameSize() {
        val history = FrameHistory()
        var frame = 3L
        history.capture(frame, viewProjection(0f), 96, 48, epoch)
        var available = 0
        repeat(20) {
            frame++
            if (history.capture(frame, viewProjection(frame * 0.01f), 96, 48, epoch)) available++
        }
        // A floor, not "differs at all": every one of the twenty consecutive frames must be
        // available, because there is no condition in the rule that any of them trips.
        assertEquals(20, available, "only $available of 20 consecutive same-size frames were available")
        assertEquals(96, history.width)
        assertEquals(48, history.height)
    }

    @Test
    fun recapturingTheSameFrameIsIdempotent() {
        // Several passes in one chain each ask for history. Only the first may advance it; the rest
        // must see the same answer. Without this, a two-pass chain reprojects the second pass
        // against the *first pass's* frame and the history is never more than one pass old.
        val history = FrameHistory()
        val at = warm(history)
        val previousBefore = Matrix4f(history.previous)
        repeat(5) { assertTrue(history.capture(at, viewProjection(99f), 64, 64, epoch)) }
        assertEquals(0f, (0 until 16).maxOf { abs(history.previous[it] - previousBefore[it]) },
            "re-capturing the frame it already stands at moved the history")
        assertEquals(at, history.serial)
    }

    @Test
    fun goingBackwardsInTimeIsNotHistory() {
        val history = FrameHistory()
        val at = warm(history)
        history.capture(at - 1, viewProjection(0.2f), 64, 64, epoch)
        assertFalse(history.available, "frame n-1 after frame n is not a consecutive frame")
    }

    @Test
    fun packedCarriesEverythingAShaderNeeds() {
        val history = FrameHistory()
        val at = warm(history)
        assertEquals(FrameHistory.PACKED_FLOATS, history.packed.size)
        fun mat(base: Int) = (0 until 16).map { history.packed[base + it] }
        assertEquals((0 until 16).map { history.current[it] }, mat(FrameHistory.PACKED_CURRENT))
        assertEquals((0 until 16).map { history.previous[it] }, mat(FrameHistory.PACKED_PREVIOUS))
        assertEquals((0 until 16).map { history.inverse[it] }, mat(FrameHistory.PACKED_INVERSE))
        assertEquals((0 until 16).map { history.reprojection[it] }, mat(FrameHistory.PACKED_REPROJECTION))
        assertEquals(64f, history.packed[FrameHistory.PACKED_WIDTH])
        assertEquals(64f, history.packed[FrameHistory.PACKED_HEIGHT])
        assertEquals(1f, history.packed[FrameHistory.PACKED_AVAILABLE])

        history.invalidate()
        history.capture(at + 1, viewProjection(0.2f), 64, 64, epoch)
        assertEquals(0f, history.packed[FrameHistory.PACKED_AVAILABLE],
            "the packed availability flag has to track `available`, not just be written once")
    }

    @Test
    fun aZeroOrNegativeSizeIsRejected() {
        val history = FrameHistory()
        assertFailsWith<IllegalArgumentException> { history.capture(1L, viewProjection(0f), 0, 64, epoch) }
        assertFailsWith<IllegalArgumentException> { history.capture(1L, viewProjection(0f), 64, -1, epoch) }
    }

    @Test
    fun paramsAreTheFourFloatsThePushConstantCarries() {
        val history = FrameHistory()
        val at = warm(history, size = 128)
        val params = history.params()
        assertEquals(128f, params.x)
        assertEquals(128f, params.y)
        assertEquals(1f, params.z)
        assertTrue(params.w >= 0f, "the fourth slot is reserved and must stay finite")
        history.invalidate()
        history.capture(at + 1, viewProjection(0.5f), 128, 128, epoch)
        assertEquals(0f, history.params().z, "params must re-read availability, not cache it")
    }
}
