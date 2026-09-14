package afield.four.audio.steamaudio

import java.lang.foreign.Arena
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
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
     * Whether the tests that render audio can run. The last test here is pure arithmetic and
     * deliberately has no such guard, so the handedness of the transform stays covered on a
     * checkout that has never fetched the binaries.
     */
    private fun available(): Boolean {
        if (!SteamAudioNatives.isAvailable) {
            println("SKIPPED World2D handedness renders: ${SteamAudioNatives.describe()}")
            return false
        }
        return true
    }

    private val world = World2D(floorHeight = 0f, ceilingHeight = 4f, earHeight = 1.6f)
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
            // Clockwise from north, so 90 degrees is due east -- the listener's right.
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
            // The transform MovableWall.moveTo actually applies, not a copy of it.
            val transform = world.segmentTransform(x0, y0, x1, y1, quadLength = length)

            val startWorld = transform.transformPoint(Vector3(-half, 0f, 0f))
            val endWorld = transform.transformPoint(Vector3(half, 0f, 0f))

            // Game (x, y) maps to world (x, _, -y) -- see World2D.point for why the sign is
            // not free to choose.
            assertTrue(
                abs(startWorld.x - x0) < 1e-3 && abs(startWorld.z + y0) < 1e-3,
                "segment start ($x0,$y0) mapped to (${startWorld.x},${startWorld.z})",
            )
            assertTrue(
                abs(endWorld.x - x1) < 1e-3 && abs(endWorld.z + y1) < 1e-3,
                "segment end ($x1,$y1) mapped to (${endWorld.x},${endWorld.z})",
            )
        }
    }
}

private operator fun List<Float>.component4(): Float = this[3]
