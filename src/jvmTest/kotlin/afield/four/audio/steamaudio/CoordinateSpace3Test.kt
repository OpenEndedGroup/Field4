package afield.four.audio.steamaudio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the handedness convention of [CoordinateSpace3], as arithmetic rather than as prose.
 *
 * Worth its own test for an awkward reason: measurement cannot cover this. Steam Audio derives
 * the listener's right internally from the `ahead`/`up` it is given and ignores the `right` field
 * we write, which was established by measuring the reflected field with both signs and getting
 * +6.64 dB and +6.61 dB -- the same answer either way. So no amount of rendering, listening or
 * ILD measurement can tell the two conventions apart, and `ReflectionHandednessTest` is green
 * under both.
 *
 * That makes this the *only* guard on the field, and it matters because `right` is still read on
 * the Kotlin side and still written into every `IPLCoordinateSpace3` the API hands out. Leaving
 * it inconsistent with [CoordinateSpace3.IDENTITY] would mean a frame built by [lookingAt] and
 * the identity frame disagreed about which way is right, which is the kind of latent
 * disagreement that surfaces much later as a mirrored something.
 *
 * The convention: `(right, up, ahead)` with `right = ahead cross up`. For Steam Audio's own
 * frame -- +x right, +y up, **-z** ahead -- that is what holds, and note that this makes the
 * ordered triple left-handed even though the coordinate system is right-handed. `ahead` being
 * negative z is what does it, and it is exactly the step that is easy to get backwards.
 */
class CoordinateSpace3Test {

    private val tolerance = 1e-5f

    @Test
    fun aFrameLookingDownForwardIsTheIdentityFrame() {
        val frame = CoordinateSpace3.lookingAt(
            origin = Vector3.ZERO,
            ahead = Vector3.FORWARD,
            up = Vector3.UP,
        )
        assertClose(CoordinateSpace3.IDENTITY.right, frame.right, "right")
        assertClose(CoordinateSpace3.IDENTITY.up, frame.up, "up")
        assertClose(CoordinateSpace3.IDENTITY.ahead, frame.ahead, "ahead")
    }

    @Test
    fun theIdentityFrameSatisfiesTheConventionItDocuments() {
        val identity = CoordinateSpace3.IDENTITY
        assertClose(identity.ahead cross identity.up, identity.right, "ahead cross up")
    }

    /**
     * Every frame [lookingAt] builds must satisfy the same rule, not just the canonical one.
     *
     * A sign error confined to one axis can leave the forward-facing case looking right while
     * every turned frame is wrong, so these directions deliberately include turns about all
     * three axes and a couple of oblique ones.
     */
    @Test
    fun everyFrameSatisfiesTheConvention() {
        val aheads = listOf(
            Vector3(0f, 0f, -1f),
            Vector3(0f, 0f, 1f),
            Vector3(1f, 0f, 0f),
            Vector3(-1f, 0f, 0f),
            Vector3(1f, 0f, -1f),
            Vector3(-2f, 0.5f, 3f),
            Vector3(0.3f, -0.8f, -0.5f),
        )

        val failures = mutableListOf<String>()
        for (ahead in aheads) {
            val frame = CoordinateSpace3.lookingAt(origin = Vector3.ZERO, ahead = ahead)
            val expectedRight = (frame.ahead cross frame.up).normalized()
            if (!isClose(expectedRight, frame.right)) {
                failures += "ahead=$ahead gave right=${frame.right}, expected $expectedRight"
            }
            // Orthonormality, so a frame cannot satisfy the cross-product rule while being
            // skewed or unnormalised.
            if (abs(frame.right.length - 1f) > tolerance) failures += "ahead=$ahead right not unit"
            if (abs(frame.up.length - 1f) > tolerance) failures += "ahead=$ahead up not unit"
            if (abs(frame.ahead.length - 1f) > tolerance) failures += "ahead=$ahead ahead not unit"
            if (abs(frame.right dot frame.up) > tolerance) failures += "ahead=$ahead right.up != 0"
            if (abs(frame.right dot frame.ahead) > tolerance) failures += "ahead=$ahead right.ahead != 0"
            if (abs(frame.up dot frame.ahead) > tolerance) failures += "ahead=$ahead up.ahead != 0"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    /**
     * A listener facing Steam Audio's "ahead" has its right where Steam Audio puts it.
     *
     * The same statement as the identity test, said in the terms a caller thinks in, because
     * this is the sentence that was got wrong: facing `-z`, right is `+x`.
     */
    @Test
    fun facingForwardTheRightHandSideIsPositiveX() {
        val frame = CoordinateSpace3.lookingAt(origin = Vector3.ZERO, ahead = Vector3(0f, 0f, -1f))
        assertTrue(
            frame.right.x > 0.99f,
            "facing -z, right must be +x, got ${frame.right}",
        )
    }

    private fun isClose(expected: Vector3, actual: Vector3): Boolean =
        abs(expected.x - actual.x) < tolerance &&
            abs(expected.y - actual.y) < tolerance &&
            abs(expected.z - actual.z) < tolerance

    private fun assertClose(expected: Vector3, actual: Vector3, what: String) {
        assertTrue(isClose(expected, actual), "$what: expected $expected, got $actual")
    }
}
