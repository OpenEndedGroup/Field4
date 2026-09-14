package afield.four.audio.steamaudio

import afield.examples.steamaudio.PROBE_FRAME_SIZE
import afield.examples.steamaudio.PROBE_SAMPLING_RATE
import afield.examples.steamaudio.Rig
import java.lang.foreign.Arena
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Locks down the handedness of the *reflected* field, the way `World2DHandednessTest` does for
 * the direct one.
 *
 * This exists because of a specific gap. `iplCalculateRelativeDirection` takes only `ahead` and
 * `up`, so `World2DHandednessTest` -- which measures real impulses and is otherwise the
 * strongest guard here -- never reads `CoordinateSpace3.right` at all. A change to how `right`
 * is derived therefore passed the whole suite green with nothing measuring it, and the only
 * evidence either way was somebody listening to `cave.wav`. Reasoning about Steam Audio's
 * documented axes is not a substitute: the argument can be run convincingly in both directions,
 * and was, in the wrong one.
 *
 * Measured, not assumed: one reflector, nothing else in the scene, and only the reflection bus
 * -- the direct path is never mixed in, so nothing dilutes the result. The reflector's side and
 * the ear it arrives at must agree.
 *
 * The rig is shared with `ReflectionHandednessProbe` rather than rebuilt here. A test that
 * asserts its own copy of the code under test proves nothing about the code that runs, which is
 * the mistake `movableWallTransformPlacesSegmentEndpointsCorrectly` made until it was pointed at
 * `World2D.segmentTransform`.
 */
class ReflectionHandednessTest {

    private val settings = AudioSettings(
        samplingRate = PROBE_SAMPLING_RATE,
        frameSize = PROBE_FRAME_SIZE,
    )

    private fun available(): Boolean {
        if (!SteamAudioNatives.isAvailable) {
            println("SKIPPED reflected-field handedness: ${SteamAudioNatives.describe()}")
            return false
        }
        return true
    }

    /**
     * Interaural level difference of the reflected field, in dB, positive for the right ear.
     *
     * Measured values sit around 5-7 dB, so the 2 dB threshold the assertions use is a sign
     * test with room to spare rather than a tolerance on a magnitude.
     */
    private fun ildFor(instanced: Boolean, placeAt: Float): Double =
        Arena.ofShared().use { arena ->
            SteamAudioContext().use { context ->
                Rig(context, settings, arena, instanced = instanced, placeAt = placeAt)
                    .use { it.measure() }
            }
        }

    @Test
    fun aStaticReflectorOnTheRightIsHeardOnTheRight() {
        if (!available()) return
        val ild = ildFor(instanced = false, placeAt = 4f)
        assertTrue(ild > 2.0, "reflector 4 m to the right, expected the right ear, got ${fmt(ild)} dB")
    }

    @Test
    fun aStaticReflectorOnTheLeftIsHeardOnTheLeft() {
        if (!available()) return
        val ild = ildFor(instanced = false, placeAt = -4f)
        assertTrue(ild < -2.0, "reflector 4 m to the left, expected the left ear, got ${fmt(ild)} dB")
    }

    /**
     * An instanced reflector placed by `updateTransform` must land where a static one does.
     *
     * Separate from the two above because it fails differently: a transform bug mirrors moving
     * geometry while leaving everything baked into the scene correct, which is precisely the
     * combination that is hard to hear and easy to misattribute to the acoustics.
     */
    @Test
    fun anInstancedReflectorAgreesWithAStaticOne() {
        if (!available()) return
        val right = ildFor(instanced = true, placeAt = 4f)
        val left = ildFor(instanced = true, placeAt = -4f)
        assertTrue(right > 2.0, "instanced reflector on the right, got ${fmt(right)} dB")
        assertTrue(left < -2.0, "instanced reflector on the left, got ${fmt(left)} dB")
    }

    /**
     * Sweeping a reflector across the front must sweep the ear balance the same way.
     *
     * The single-position tests would still pass if the mapping were mirrored *and* the two
     * positions happened to swap; requiring the whole sweep to be monotonic does not.
     */
    @Test
    fun sweepingAReflectorSweepsTheEarBalance() {
        if (!available()) return
        val positions = listOf(-6f, -3f, 0f, 3f, 6f)
        val measured = Arena.ofShared().use { arena ->
            SteamAudioContext().use { context ->
                Rig(context, settings, arena, instanced = true, placeAt = positions.first())
                    .use { rig ->
                        positions.map { x ->
                            rig.moveTo(x)
                            rig.measure()
                        }
                    }
            }
        }

        val failures = mutableListOf<String>()
        for (index in 1 until measured.size) {
            if (measured[index] <= measured[index - 1]) {
                failures += "x=${positions[index - 1]} gave ${fmt(measured[index - 1])} dB but " +
                    "x=${positions[index]} gave ${fmt(measured[index])} dB"
            }
        }
        assertTrue(
            failures.isEmpty(),
            "ear balance must rise as the reflector moves left to right: " +
                failures.joinToString("; "),
        )
        assertTrue(
            measured.first() < -2.0 && measured.last() > 2.0,
            "the sweep must cross from left to right, got " +
                measured.joinToString(", ") { fmt(it) } + " dB",
        )
    }

    private fun fmt(value: Double): String = "%.2f".format(value)
}
