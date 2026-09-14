package afield.four.audio.steamaudio

import java.lang.foreign.Arena
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks down the 2D-to-3D mapping's handedness.
 *
 * Getting this wrong mirrors left and right. It is trivially easy to do -- one sign in a cross
 * product, or mapping game `y` to world `z` with the wrong polarity -- and the result is
 * plausible-sounding audio that is simply backwards. Reasoning about it is not good enough, so
 * these tests render actual impulses and check which ear leads and which is louder.
 *
 * The convention being asserted: for a listener at the 2D origin facing game **north**
 * `(0, +1)`, a source to the **east** `(+x)` is on the listener's **right**.
 */
class World2DHandednessTest {

    /**
     * Whether the tests that touch the native library can run.
     *
     * Field does not commit the Steam Audio binaries, so a clone that has never run
     * `fetchSteamAudioNatives` has none. Skipping with a stated reason keeps `check` green there
     * and says what is missing, rather than failing on a link error that names no remedy.
     */
    private fun available(): Boolean {
        if (!SteamAudioNatives.isAvailable) {
            println("SKIPPED World2D handedness renders: ${SteamAudioNatives.describe()}")
            return false
        }
        return true
    }

    private val world = World2D(floorDepth = -2f, ceilingDepth = 2f, earDepth = 0f)
    private val settings = AudioSettings(samplingRate = 48_000, frameSize = 1024)

    /**
     * Measures an impulse through the binaural chain for a source at a 2D offset from a
     * listener with a 2D facing, and returns interaural level and time difference.
     *
     * ILD is positive when the right ear is louder; ITD is positive when the right ear leads.
     */
    private fun measure(
        sourceX: Float,
        sourceY: Float,
        facingX: Float,
        facingY: Float,
    ): Pair<Double, Int> {
        SteamAudioContext().use { context ->
            Arena.ofConfined().use { arena ->
                Hrtf.default(context, settings).use { hrtf ->
                    BinauralEffect(context, settings, hrtf).use { binaural ->
                        val input = AudioBuffer(arena, 1, settings.frameSize)
                        val output = AudioBuffer(arena, 2, settings.frameSize)
                        val params = BinauralEffectParams(
                            arena,
                            hrtf = hrtf,
                            interpolation = HrtfInterpolation.NEAREST,
                        )

                        val listener = world.listenerFacing(0f, 0f, facingX, facingY)
                        val direction = context.calculateRelativeDirection(
                            sourcePosition = world.point(sourceX, sourceY),
                            listenerPosition = listener.origin,
                            listenerAhead = listener.ahead,
                            listenerUp = listener.up,
                        )
                        params.setDirection(direction.x, direction.y, direction.z)

                        input.clear()
                        input[0, 0] = 1f
                        output.clear()
                        binaural.apply(params, input, output)

                        val left = FloatArray(settings.frameSize)
                        val right = FloatArray(settings.frameSize)
                        output.readChannel(0, left)
                        output.readChannel(1, right)

                        val peakLeft = left.maxOf { abs(it) }
                        val peakRight = right.maxOf { abs(it) }
                        val ild = 20.0 * log10((peakRight + 1e-12) / (peakLeft + 1e-12))
                        val itd = onset(left) - onset(right)
                        return ild to itd
                    }
                }
            }
        }
    }

    private fun onset(samples: FloatArray, fraction: Float = 0.1f): Int {
        val peak = samples.maxOf { abs(it) }
        if (peak == 0f) return -1
        val threshold = peak * fraction
        return samples.indices.first { abs(samples[it]) >= threshold }
    }

    @Test
    fun sourceToTheEastOfANorthFacingListenerIsHeardOnTheRight() {
        if (!available()) return
        val (ild, itd) = measure(sourceX = 5f, sourceY = 0f, facingX = 0f, facingY = 1f)
        assertTrue(ild > 6.0, "expected the right ear louder by >6 dB, got ${"%.1f".format(ild)} dB")
        assertTrue(itd > 0, "expected the right ear to lead, got an ITD of $itd samples")
    }

    @Test
    fun sourceToTheWestOfANorthFacingListenerIsHeardOnTheLeft() {
        if (!available()) return
        val (ild, itd) = measure(sourceX = -5f, sourceY = 0f, facingX = 0f, facingY = 1f)
        assertTrue(ild < -6.0, "expected the left ear louder by >6 dB, got ${"%.1f".format(ild)} dB")
        assertTrue(itd < 0, "expected the left ear to lead, got an ITD of $itd samples")
    }

    @Test
    fun turningTheListenerSwapsTheEars() {
        if (!available()) return
        // Same source, listener facing the opposite way: the source must swap sides.
        val facingNorth = measure(sourceX = 5f, sourceY = 0f, facingX = 0f, facingY = 1f)
        val facingSouth = measure(sourceX = 5f, sourceY = 0f, facingX = 0f, facingY = -1f)
        assertTrue(
            facingNorth.first > 0 && facingSouth.first < 0,
            "turning around should swap the ears, got ${"%.1f".format(facingNorth.first)} dB " +
                "then ${"%.1f".format(facingSouth.first)} dB",
        )
    }

    @Test
    fun sourceDirectlyAheadOrBehindIsCentred() {
        if (!available()) return
        val ahead = measure(sourceX = 0f, sourceY = 5f, facingX = 0f, facingY = 1f)
        val behind = measure(sourceX = 0f, sourceY = -5f, facingX = 0f, facingY = 1f)
        assertTrue(
            abs(ahead.first) < 4.0,
            "a source straight ahead should be near-centred, got ${"%.1f".format(ahead.first)} dB",
        )
        assertTrue(
            abs(behind.first) < 4.0,
            "a source straight behind should be near-centred, got ${"%.1f".format(behind.first)} dB",
        )
        assertTrue(
            abs(ahead.second) <= 2 && abs(behind.second) <= 2,
            "median-plane sources should have near-zero ITD, got ${ahead.second} and ${behind.second}",
        )
    }

    @Test
    fun ildFollowsAzimuthAroundTheFullCircle() {
        if (!available()) return
        // Walk a source around the listener in the 2D plane and check the ear balance tracks
        // the azimuth: positive on the listener's right half, negative on the left.
        val listenerFacing = 0f to 1f   // north
        val failures = mutableListOf<String>()

        for (degrees in 0 until 360 step 30) {
            val radians = degrees * PI / 180.0
            // Measured clockwise from game +y (north), so 90 degrees is due east (+x),
            // which for a north-facing listener is the right-hand side.
            val x = (5.0 * sin(radians)).toFloat()
            val y = (5.0 * cos(radians)).toFloat()
            val (ild, _) = measure(x, y, listenerFacing.first, listenerFacing.second)

            val expectRight = degrees in 30..150
            val expectLeft = degrees in 210..330
            if (expectRight && ild <= 0) {
                failures += "azimuth $degrees should favour the right ear, got ${"%.1f".format(ild)} dB"
            }
            if (expectLeft && ild >= 0) {
                failures += "azimuth $degrees should favour the left ear, got ${"%.1f".format(ild)} dB"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test
    fun movableWallTransformPlacesSegmentEndpointsCorrectly() {
        // The transform MovableWall builds must land the quad's ends on the segment's ends.
        // Checked arithmetically, since a wrong rotation sign would put a door across a
        // corridor rather than along it.
        val length = 2.0f
        val half = length / 2f

        for ((x0, y0, x1, y1) in listOf(
            floatArrayOf(0f, 0f, 4f, 0f),
            floatArrayOf(0f, 0f, 0f, 4f),
            floatArrayOf(1f, 1f, 4f, 5f),
            floatArrayOf(-3f, 2f, -7f, -1f),
        ).map { it.toList() }) {
            // The transform MovableWall.moveTo actually applies, not a copy of it. Asserting a
            // reimplementation here is what let the mapping and the transform disagree: the
            // mapping was corrected and this case still failed, because it was checking its own
            // arithmetic rather than the code that runs.
            val transform = world.segmentTransform(x0, y0, x1, y1, quadLength = length)

            val startWorld = transform.transformPoint(Vector3(-half, 0f, 0f))
            val endWorld = transform.transformPoint(Vector3(half, 0f, 0f))

            // Game (x, y) maps to world (x, _, -y).
            assertTrue(
                abs(startWorld.x - x0) < 1e-3 && abs(startWorld.z - (-y0)) < 1e-3,
                "segment start ($x0,$y0) mapped to world (${startWorld.x},${startWorld.z}), " +
                    "expected (${x0},${-y0})",
            )
            assertTrue(
                abs(endWorld.x - x1) < 1e-3 && abs(endWorld.z - (-y1)) < 1e-3,
                "segment end ($x1,$y1) mapped to world (${endWorld.x},${endWorld.z}), " +
                    "expected (${x1},${-y1})",
            )
        }
    }
}

private operator fun List<Float>.component4(): Float = this[3]

/**
 * Pins [CoordinateSpace3.lookingAt] against [CoordinateSpace3.IDENTITY].
 *
 * These two must agree, and for a while they did not: `lookingAt` computed `right` as
 * `up cross forward`, which is `(-1, 0, 0)` for Steam Audio's canonical frame where
 * [CoordinateSpace3.IDENTITY] declares `(1, 0, 0)`.
 *
 * It is tempting to say that swapped the ears. It did not, and the correction is worth keeping
 * because it is what makes this test necessary rather than redundant. Measured with a reflector
 * on one known side and only the reflection bus, the two conventions give **+6.64 dB and
 * +6.61 dB** -- the same answer: Steam Audio derives the listener's right internally from the
 * `ahead`/`up` it is handed and ignores the field we write. So nothing audible distinguishes
 * them, `ReflectionHandednessTest` is green under both, and no amount of rendering or listening
 * can catch a regression here.
 *
 * What the disagreement does cost is a frame from [CoordinateSpace3.lookingAt] and the identity
 * frame contradicting each other about which way is right, for every Kotlin-side reader of
 * `right`. This arithmetic is the only guard on that, which is why it asserts the invariant
 * directly instead of measuring anything.
 */
class CoordinateSpaceHandednessTest {

    @Test
    fun lookingAtTheCanonicalDirectionReproducesIdentity() {
        val frame = CoordinateSpace3.lookingAt(
            origin = Vector3.ZERO,
            ahead = Vector3.FORWARD,
            up = Vector3.UP,
        )
        // Compared with a tolerance rather than by equality: these components are computed
        // through cross products, and a cross product readily produces -0.0f, which a data
        // class's equals() treats as distinct from 0.0f even though they are numerically equal.
        assertVectorEquals(CoordinateSpace3.IDENTITY.right, frame.right, "right")
        assertVectorEquals(CoordinateSpace3.IDENTITY.up, frame.up, "up")
        assertVectorEquals(CoordinateSpace3.IDENTITY.ahead, frame.ahead, "ahead")
    }

    private fun assertVectorEquals(expected: Vector3, actual: Vector3, what: String) {
        assertTrue(
            abs(expected.x - actual.x) < 1e-6 &&
                abs(expected.y - actual.y) < 1e-6 &&
                abs(expected.z - actual.z) < 1e-6,
            "$what: expected $expected but was $actual",
        )
    }

    @Test
    fun theIdentityFrameSatisfiesTheConventionItDeclares() {
        // IDENTITY is a literal, so nothing else checks that its own three axes are consistent.
        // If it drifts, every comparison in this class silently changes meaning.
        val identity = CoordinateSpace3.IDENTITY
        assertVectorEquals(identity.ahead cross identity.up, identity.right, "ahead cross up")
    }

    @Test
    fun frameIsRightHanded() {
        // For any facing, right x up must equal -ahead: that is what right-handed means here,
        // and it is the invariant a mirrored frame violates.
        val horizontal = listOf(0.0, 0.7, 1.9, 3.0, 4.4, 5.8).map {
            Vector3(kotlin.math.cos(it).toFloat(), 0f, kotlin.math.sin(it).toFloat())
        }
        // Directions off the horizontal plane too: a sign error confined to one axis can leave
        // every level facing correct while pitched ones are wrong, and `up` is re-orthogonalised
        // for these rather than passed through.
        val oblique = listOf(
            Vector3(-2f, 0.5f, 3f),
            Vector3(0.3f, -0.8f, -0.5f),
            Vector3(0f, 0.9f, -0.1f),
        )
        for (ahead in horizontal + oblique) {
            val frame = CoordinateSpace3.lookingAt(
                origin = Vector3.ZERO,
                ahead = ahead,
                up = Vector3.UP,
            )
            val cross = frame.right cross frame.up
            val expected = -frame.ahead
            assertTrue(
                abs(cross.x - expected.x) < 1e-5 &&
                    abs(cross.y - expected.y) < 1e-5 &&
                    abs(cross.z - expected.z) < 1e-5,
                "ahead=$ahead: right x up = $cross but -ahead = $expected",
            )
        }
    }

    @Test
    fun axesAreOrthonormal() {
        val frame = CoordinateSpace3.lookingAt(
            origin = Vector3.ZERO,
            ahead = Vector3(1f, 0f, -2f),
            up = Vector3.UP,
        )
        for (axis in listOf(frame.right, frame.up, frame.ahead)) {
            assertTrue(abs(axis.length - 1f) < 1e-5, "axis $axis is not unit length")
        }
        assertTrue(abs(frame.right dot frame.up) < 1e-5)
        assertTrue(abs(frame.right dot frame.ahead) < 1e-5)
        assertTrue(abs(frame.up dot frame.ahead) < 1e-5)
    }
}
