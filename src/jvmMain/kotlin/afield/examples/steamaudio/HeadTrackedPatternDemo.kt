package afield.examples.steamaudio

import afield.audio.synth.HeadPose
import afield.audio.synth.SynthContext
import afield.audio.synth.VirtualHead
import afield.audio.synth.pat
import afield.audio.synth.param
import afield.audio.synth.patSpatial
import afield.audio.synth.stack
import afield.audio.synth.with
import afield.math.linalg.Vec3
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A rhythm placed in a room, heard from a head that moves: OpenXR's pose driving Steam Audio's HRTF.
 *
 * Listen on **headphones**. HRTF rendering assumes each ear hears only its own channel, and over
 * speakers the whole effect collapses.
 *
 * The render is in four movements, which are arranged to separate the one thing that is new here
 * from the three things that were already true:
 *
 *  1. **Head still, sounds placed.** A kick two metres ahead, a hat hard left, a snare hard right.
 *     Nothing is tracking yet -- this is the sound field the pattern's own `az` and `dist` tags
 *     describe, and the baseline the next three movements move against.
 *  2. **Head turning, sounds still.** The same pattern, unchanged, while the head yaws a full turn
 *     to the right. The room stays where it is and you rotate inside it: the hat starts on your
 *     left, passes behind you, and arrives on your right.
 *  3. **Head walking, sounds still.** The head looks straight ahead the whole time and only steps
 *     sideways, two metres each way. This is the movement an azimuth-only listener could not
 *     produce at all -- the head's *position* is what makes the kick sweep past.
 *  4. **Head leaning in and out.** Straight toward the kick and back, 1.6 m each way. Distance is
 *     now a property of where the head is -- and it is not the same change for every source, which
 *     is the part worth listening for. Walking in brings the kick from 2 m to 0.4 m and at the same
 *     time carries the hat and snare, which are off to the sides at 1.2 m, out to 2 m and behind.
 *     Measured over the movement, level swings 2:1 half-second to half-second where movement 1 is
 *     flat to within 5%, but it is not a single swell: the sources move apart, not together.
 *
 * Movements 2, 3 and 4 differ from movement 1 in the seven numbers of a head pose and in nothing
 * else: same pattern, same tags, same Dough engines, same HRTF. On a machine with a headset those
 * seven numbers come from `OpenXRHmdTarget.headPose`; here they come from a [VirtualHead], because
 * macOS has no OpenXR runtime. The code between the pose and the ear is identical either way, which
 * is the point of the demo.
 *
 * ```bash
 * ./gradlew headTrackedDemo
 * ```
 *
 * Nothing about the render is realtime. `patSpatial`'s render thread sleeps only when its ring is
 * at target fill, so a file render that drains the ring as fast as blocks appear free-runs: the
 * `vs rt` column reports 7x to 30x. The head is therefore a function of the **render frame**,
 * handed to [patSpatial] as `listenerPoseAt` and evaluated by the render thread at the top of each
 * block, so the motion and the music share one timeline and two runs write the same file.
 * `pat_spatial.md` publishes this render through `PatSpatialSoundDocs`, at five seconds a movement.
 */
private const val SAMPLING_RATE = 44_100

/** The block the WAV is pulled in. Unrelated to the render thread's own 128-frame block. */
private const val BLOCK = 512

/** Seconds per movement in the Gradle demo. */
private const val MOVEMENT_SECONDS = 8.0

/** Cycles per second. Two is a brisk walking pulse, so an eight-second movement is sixteen bars. */
private const val CPS = 2.0

/** The four movements, in order, as the readout labels them. */
private val MOVEMENTS = listOf(
    "1. head still",
    "2. head turning right, one turn",
    "3. head stepping side to side",
    "4. head leaning in and out",
)

/** A finished render: interleaved stereo at [sampleRate], plus a readout line per movement. */
class HeadTrackedRender(val interleaved: FloatArray, val sampleRate: Int, val readout: List<String>)

/**
 * Where the head is at render frame [frame], for movements of [movementFrames] each.
 *
 *  1. No motion: the room's origin, level and facing -Z, which is what an untracked patSpatial does.
 *  2. A full turn to the right over the movement.
 *  3. Two metres each way along the room's +X, looking straight ahead throughout.
 *  4. Straight in toward the kick and back out. -Z is forward and the kick is at 2 m, so 1.6 m of
 *     approach closes most of the distance without arriving inside the source.
 *
 * Each movement starts from its own t = 0, so 2, 3 and 4 all begin at the identity pose.
 */
fun headTrackedPose(frame: Long, movementFrames: Long, sampleRate: Int): HeadPose {
    val movement = (frame / movementFrames).toInt()
    val seconds = (frame - movement * movementFrames) / sampleRate.toDouble()
    val period = movementFrames / sampleRate.toDouble()
    return when (movement) {
        1 -> VirtualHead.turning(degreesPerSecond = 360.0 / period).pose(seconds)
        2 -> VirtualHead.walking(Vec3(1f, 0f, 0f), metres = 2.0, periodSeconds = period).pose(seconds)
        3 -> VirtualHead.walking(Vec3(0f, 0f, -1f), metres = 1.6, periodSeconds = period).pose(seconds)
        else -> HeadPose.IDENTITY
    }
}

/**
 * Renders the four movements, [movementSeconds] each, and returns the audio.
 *
 * Three sounds at three fixed places in the room, written as three layers rather than as one
 * pattern with placement patterns over it. `spat` is what makes them three sources -- Steam Audio
 * aims a whole mono buffer, so a separately placed sound needs its own Dough engine -- and stacking
 * the layers keeps each group's `az` a constant that can be read back against the readout.
 *
 * The ring hands blocks over in the order they were rendered, starting from frame 0, so frame n of
 * the file is frame n of [headTrackedPose]'s timeline. Needs Steam Audio's `libphonon`
 * (`./gradlew fetchSteamAudioNatives`).
 */
fun renderHeadTracked(movementSeconds: Double = MOVEMENT_SECONDS, sampleRate: Int = SAMPLING_RATE): HeadTrackedRender {
    val kick = pat("bd ~ ~ ~")
        .with(param("spat", "kick"), param("az", 0), param("dist", 2.0))
    val hats = pat("hh*8")
        .with(param("spat", "hat"), param("az", -85), param("dist", 1.2))
    val snare = pat("~ ~ sd ~")
        .with(param("spat", "snare"), param("az", 85), param("dist", 1.2))

    val movementFrames = (movementSeconds * sampleRate).toLong()
    val total = (movementFrames * MOVEMENTS.size).toInt()
    val out = FloatArray(total * 2)
    val readout = mutableListOf<String>()

    val launch = patSpatial(
        stack(kick, hats, snare),
        cps = CPS,
        sampleRate = sampleRate,
        maxSources = 3,
        listenerPoseAt = { frame -> headTrackedPose(frame, movementFrames, sampleRate) },
    )
    try {
        check(launch.awaitReady()) { "the spatial room never became ready" }
        val ctx = SynthContext(sampleRate.toFloat(), BLOCK)
        val l = FloatArray(BLOCK)
        val r = FloatArray(BLOCK)
        var at = 0
        for ((index, label) in MOVEMENTS.withIndex()) {
            val end = ((index + 1) * movementFrames).toInt()
            val startedAt = System.nanoTime()
            var sumL = 0.0
            var sumR = 0.0
            val first = at
            while (at < end) {
                // The block does not exist until the render thread has made it. Waiting here is
                // not an underrun, and the ring's own counter is what would say otherwise.
                while (launch.room.ring.fill < BLOCK) {
                    launch.room.failure?.let { throw IllegalStateException("render thread failed", it) }
                    Thread.sleep(1L)
                }
                java.util.Arrays.fill(l, 0f)
                java.util.Arrays.fill(r, 0f)
                // A fresh cycle id per pull: StereoNode memoizes per id, so a repeat would be
                // answered from its cache instead of from the ring.
                ctx.cycleId = at.toLong() / BLOCK + 1
                ctx.time = at.toFloat() / sampleRate
                launch.bank.render(l, r, BLOCK, ctx)
                val n = minOf(BLOCK, total - at)
                for (i in 0 until n) {
                    out[2 * (at + i)] = l[i]
                    out[2 * (at + i) + 1] = r[i]
                    if (at + i < end) {
                        sumL += l[i].toDouble() * l[i]
                        sumR += r[i].toDouble() * r[i]
                    }
                }
                at += BLOCK
            }
            val counted = (minOf(at, end) - first).coerceAtLeast(1)
            val rmsL = sqrt(sumL / counted)
            val rmsR = sqrt(sumR / counted)
            val wallSeconds = (System.nanoTime() - startedAt) / 1e9
            readout += "  %-34s %8.5f %8.5f %6.2f %7.1fx".format(
                label, rmsL, rmsR, rmsL / rmsR.coerceAtLeast(1e-9), movementSeconds / wallSeconds.coerceAtLeast(1e-9),
            )
            // Movements 2 and 3 sweep symmetrically, so their L/R averages back toward 1 -- the
            // moving image is in the file, not in this number. Movement 4's levels are the ones
            // to read.
            if (abs(rmsL) < 1e-6 && abs(rmsR) < 1e-6) readout += "      (silent -- something is wrong)"
        }
        val underruns = launch.room.ring.underruns
        check(underruns == 0L) { "$underruns frames of underrun: the file would not be the render" }
    } finally {
        launch.close()
    }
    return HeadTrackedRender(out, sampleRate, readout)
}

fun main() {
    val output = Path.of("build/demo/head-tracked.wav")
    Files.createDirectories(output.parent)

    println("OpenXR head pose -> pat -> Dough -> Steam Audio HRTF")
    println("  $SAMPLING_RATE Hz, ${MOVEMENT_SECONDS.toInt()} s of audio per movement")
    println()
    println("  %-34s %8s %8s %6s %8s".format("movement", "rmsL", "rmsR", "L/R", "vs rt"))

    val render = renderHeadTracked()
    render.readout.forEach(::println)

    WavWriter.write(output, render.interleaved, channelCount = 2, samplingRate = render.sampleRate)
    println()
    println("  wrote ${render.interleaved.size / 2} frames to $output -- headphones, please")
}
