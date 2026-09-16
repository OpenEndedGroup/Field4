package afield.examples.steamaudio

import afield.four.synth.HeadPose
import afield.four.synth.PatSpatialLaunch
import afield.four.synth.SynthContext
import afield.four.synth.VirtualHead
import afield.four.synth.pat
import afield.four.synth.param
import afield.four.synth.patSpatial
import afield.four.synth.stack
import afield.four.synth.with
import afield.stdlib.Vec3
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
 * `patSpatial` is documented as a realtime path, and it is one -- but "realtime" there means *paced
 * by whatever drains its ring*, which is normally an audio device. Nothing in the render thread
 * limits it to wall clock: it sleeps only when the ring is at target fill. A file render like this
 * one drains the ring as fast as the blocks appear, so it free-runs -- the `vs rt` column below
 * reports 9.7x on the first movement and 35x to 42x once the JIT is hot. That is why the heads below
 * are driven from the render thread's frame counter rather than from the clock on the wall; see
 * [renderOrigin]. The per-movement readout is printed as it goes.
 */
private const val SAMPLING_RATE = 44_100

/** The block the WAV is pulled in. Unrelated to the render thread's own 128-frame block. */
private const val BLOCK = 512

/** Seconds per movement. */
private const val MOVEMENT_SECONDS = 8.0

/** Cycles per second. Two is a brisk walking pulse, so a movement is sixteen bars. */
private const val CPS = 2.0

fun main() {
    val output = Path.of("build/demo/head-tracked.wav")
    Files.createDirectories(output.parent)

    println("OpenXR head pose -> pat -> Dough -> Steam Audio HRTF")
    println("  $SAMPLING_RATE Hz, ${MOVEMENT_SECONDS.toInt()} s of audio per movement")
    println()

    // Three sounds at three fixed places in the room, written as three layers rather than as one
    // pattern with placement patterns over it. `spat` is what makes them three sources -- Steam
    // Audio aims a whole mono buffer, so a separately placed sound needs its own Dough engine --
    // and stacking the layers is what keeps each group's `az` a constant that can be read back
    // against the report rather than an alignment to reason about.
    val kick = pat("bd ~ ~ ~")
        .with(param("spat", "kick"), param("az", 0), param("dist", 2.0))
    val hats = pat("hh*8")
        .with(param("spat", "hat"), param("az", -85), param("dist", 1.2))
    val snare = pat("~ ~ sd ~")
        .with(param("spat", "snare"), param("az", 85), param("dist", 1.2))
    val placed = stack(kick, hats, snare)

    val samples = ArrayList<Float>(((MOVEMENT_SECONDS * 4 + 1) * SAMPLING_RATE).toInt() * 2)

    val launch = patSpatial(
        placed,
        cps = CPS,
        sampleRate = SAMPLING_RATE,
        maxSources = 3,
    )

    try {
        print("  booting three Dough engines and Steam Audio ... ")
        System.out.flush()
        check(launch.awaitReady()) { "the spatial room never became ready" }
        println("ready")
        println()
        println("  %-34s %8s %8s %6s %8s".format("movement", "rmsL", "rmsR", "L/R", "vs rt"))

        // Every moving head below reads this, not the wall clock: it is how far the render thread
        // has got, which is the timeline of the blocks being spatialised. See [renderOrigin].
        val audioClock = { (launch.room.framesRendered - renderOrigin) / SAMPLING_RATE.toDouble() }

        // 1. No pose source at all: the listener sits at the room's origin, level and facing -Z,
        //    which is exactly what an untracked patSpatial has always done.
        record(launch, samples, "1. head still", MOVEMENT_SECONDS, driver = null)

        // 2. A full turn to the right over the movement. 360/8 = 45 degrees a second.
        record(
            launch, samples, "2. head turning right, one turn", MOVEMENT_SECONDS,
            driver = VirtualHead.turning(degreesPerSecond = 360.0 / MOVEMENT_SECONDS, clock = audioClock),
        )

        // 3. Two metres each way along the room's +X, looking straight ahead throughout.
        record(
            launch, samples, "3. head stepping side to side", MOVEMENT_SECONDS,
            driver = VirtualHead.walking(
                Vec3(1f, 0f, 0f), metres = 2.0, periodSeconds = MOVEMENT_SECONDS, clock = audioClock,
            ),
        )

        // 4. Straight in toward the kick and back out. -Z is forward, and the kick is at 2 m, so
        //    1.6 m of approach closes most of the distance without arriving inside the source.
        record(
            launch, samples, "4. head leaning in and out", MOVEMENT_SECONDS,
            driver = VirtualHead.walking(
                Vec3(0f, 0f, -1f), metres = 1.6, periodSeconds = MOVEMENT_SECONDS, clock = audioClock,
            ),
        )
    } finally {
        launch.close()
    }

    val interleaved = FloatArray(samples.size) { samples[it] }
    WavWriter.write(output, interleaved, channelCount = 2, samplingRate = SAMPLING_RATE)
    println()
    println("  wrote ${interleaved.size / 2} frames to $output -- headphones, please")
}

/**
 * Frames the render thread had produced when the current movement began.
 *
 * Every moving head in this demo reads `framesRendered - renderOrigin`, and it is worth being
 * precise about why that clock and not one of the two more obvious ones:
 *
 *  - **Not the wall clock.** A `patSpatial` render is paced by whatever drains its ring. An audio
 *    device makes it realtime; a loop that waits only for the next block to exist makes it free-run,
 *    measured here between 9.7x and 42x. A head on the wall clock would therefore complete a few
 *    degrees of its turn over a movement's worth of audio, by a factor nothing can predict.
 *  - **Not the frames pulled into the file.** The block coming out of the ring was spatialised up to
 *    a ring's depth ago -- 0.37 s at the default size. A pose applied against the pulling end
 *    therefore aims a block that was rendered before it, and the head motion in the file trails the
 *    music by that much. The render thread's own counter is the timeline of the blocks actually
 *    being aimed, so there is nothing to correct for.
 */
private var renderOrigin: Long = 0L

/**
 * Ever-increasing cycle id for every pull in this file, discards included.
 *
 * [afield.four.synth.StereoNode] memoizes per cycle id and starts its cache at -1, so a pull that
 * reuses an id -- or passes -1 -- is answered from the cache instead of generating, and on the very
 * first call that cache is zero-length. One counter that never repeats is the whole guard.
 */
private var cycleClock: Long = 0L

/**
 * Pull [seconds] of audio out of [launch] into [into], with the listener following [driver].
 *
 * The head is pumped from this loop rather than from a timer thread, which is also the shape an
 * OpenXR consumer wants, for a different reason given under [afield.four.synth.HeadPoseSource]: the
 * headset's pose is coherent inside the frame that located it.
 *
 * The tracker is closed at the end of each movement rather than left running, so there is never a
 * pair of them writing to the room's one listener.
 */
private fun record(
    launch: PatSpatialLaunch,
    into: MutableList<Float>,
    label: String,
    seconds: Double,
    driver: VirtualHead?,
) {
    // Every movement starts from where the head would be at t = 0 for its own motion, including
    // movement 1, whose "motion" is sitting at the origin.
    launch.setListenerPose(HeadPose.IDENTITY)

    // Throw away what the ring is already holding. Those blocks were spatialised under the previous
    // movement's pose, and recording them would put a third of a second of the last movement at the
    // head of this one -- visible in the first run of this demo as a turn that finished at 350
    // degrees instead of 360, short by exactly the ring's depth. One pass is enough: the render
    // thread refills as this drains, but it cannot get more than a block or two ahead of it.
    val ctx = SynthContext(SAMPLING_RATE.toFloat(), BLOCK)
    val discard = FloatArray(BLOCK)
    while (launch.room.ring.fill >= BLOCK) {
        ctx.cycleId = ++cycleClock
        launch.bank.render(discard, discard, BLOCK, ctx)
    }

    renderOrigin = launch.room.framesRendered
    driver?.start()
    val tracker = driver?.let { launch.followHead(it, hz = 0.0) }
    val startedAt = System.nanoTime()

    val l = FloatArray(BLOCK)
    val r = FloatArray(BLOCK)
    var sumL = 0.0
    var sumR = 0.0
    var counted = 0
    var at = 0
    val total = (seconds * SAMPLING_RATE).toInt()
    try {
        while (at < total) {
            // The block does not exist until the render thread has made it. Waiting here is not
            // an underrun, and the ring's own counter is what would say otherwise.
            while (launch.room.ring.fill < BLOCK) {
                launch.room.failure?.let { throw IllegalStateException("render thread failed", it) }
                Thread.sleep(1L)
            }
            // Where the head is for the block about to be pulled, on the file's own clock.
            tracker?.pump()
            java.util.Arrays.fill(l, 0f)
            java.util.Arrays.fill(r, 0f)
            ctx.cycleId = ++cycleClock
            ctx.time = at.toFloat() / SAMPLING_RATE
            launch.bank.render(l, r, BLOCK, ctx)
            for (i in 0 until BLOCK) {
                into.add(l[i])
                into.add(r[i])
                sumL += l[i].toDouble() * l[i]
                sumR += r[i].toDouble() * r[i]
                counted++
            }
            at += BLOCK
        }
    } finally {
        tracker?.close()
    }

    val wallSeconds = (System.nanoTime() - startedAt) / 1e9
    val rmsL = sqrt(sumL / counted)
    val rmsR = sqrt(sumR / counted)
    println("  %-34s %8.5f %8.5f %6.2f %7.1fx".format(
        label, rmsL, rmsR, rmsL / rmsR.coerceAtLeast(1e-9), seconds / wallSeconds.coerceAtLeast(1e-9),
    ))
    if (tracker != null) {
        println("      %s".format(tracker))
    }
    // Movements 2 and 3 sweep symmetrically, so their windowed L/R averages back toward 1 -- the
    // moving image is in the file, not in this number. Movement 4's levels are the ones to read.
    if (abs(rmsL) < 1e-6 && abs(rmsR) < 1e-6) println("      (silent -- something is wrong)")
    val underruns = launch.room.ring.underruns
    if (underruns > 0L) {
        println("      $underruns frames of underrun so far; this machine is oversubscribed")
    }
}
