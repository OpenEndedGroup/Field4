@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.Box
import afield.RunsAt
import afield.ReturnValueRegistry
import afield.four.audio.steamaudio.AudioBuffer
import afield.four.audio.steamaudio.AudioSettings
import afield.four.audio.steamaudio.BinauralEffect
import afield.four.audio.steamaudio.BinauralEffectParams
import afield.four.audio.steamaudio.DirectEffect
import afield.four.audio.steamaudio.DirectEffectFlags
import afield.four.audio.steamaudio.DirectEffectParams
import afield.four.audio.steamaudio.Hrtf
import afield.four.audio.steamaudio.HrtfInterpolation
import afield.four.audio.steamaudio.SteamAudio
import afield.four.audio.steamaudio.SteamAudioContext
import afield.inMain
import afield.registerLaunchJob
import afield.returnValueRegistration
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import java.lang.foreign.Arena
import java.nio.file.Path
import java.nio.file.Paths
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A pattern placed in space: `pat(...)` -> [DoughEngine] -> Steam Audio HRTF, in realtime.
 *
 * Three subsystems that do not know about each other, joined by two coercions and one thread:
 *
 * | | what it is | what this file asks of it |
 * | --- | --- | --- |
 * | [PatPattern] | Tidal-style pattern language, polyphonic by construction | query a cycle, read the annotations |
 * | [DoughEngine] | the Strudel/superdough wasm synth, 32 voices | one engine per spatial source |
 * | `afield.four.audio.steamaudio` | Steam Audio 4.8.1 over FFM | direct path plus HRTF, per source, per block |
 *
 * # The tag is the spatial parameter
 *
 * A pattern's named parameters ride on [PatEvent.annotations], and [with] writes any key at all into
 * that map, so the pattern language needed no extension to describe where a sound is. Six keys are
 * read here and every other key is forwarded to Dough untouched:
 *
 * | tag | means | default |
 * | --- | --- | --- |
 * | `spat` | which spatial source -- i.e. which Dough engine -- the event plays on | `"0"` |
 * | `az` | azimuth in degrees; 0 ahead, +90 to the right | 0 |
 * | `elev` | elevation in degrees; +90 overhead | 0 |
 * | `dist` | distance in metres | 1.5 |
 * | `occl` | occlusion, 0 clear to 1 fully blocked | 0 |
 * | `air` | 1 to apply air absorption over distance, 0 for none | 1 |
 *
 * ```kotlin
 * patSpatial(
 *     pat("bd ~ sd ~, hh*8").with(
 *         paramOf("spat", pat("<kit hats>")),
 *         param("az", sine().range(-70.0, 70.0)),
 *         param("dist", "1 3 1 6"),
 *     ),
 *     cps = 2.0,
 * )
 * ```
 *
 * Because `az` can take a *continuous* pattern, `sine()` sampled per event is a source that moves
 * between notes; and because the placement is held on the source rather than the note, the same
 * pattern's next event on that `spat` group moves the whole group. The tags do not decorate the
 * sound, they aim it.
 *
 * # Why one Dough engine per source, and what it costs the pattern
 *
 * Steam Audio spatialises a **mono** signal -- a direction is a property of a whole buffer.
 * [DoughEngine] mixes its 32 voices to stereo and `MAX_ORBITS` is 1, so there is no way to pull two
 * separable signals out of one engine. Hence one engine per `spat` group, which is also why `spat`
 * exists at all rather than every event getting its own position.
 *
 * Two consequences worth stating plainly:
 *
 * - **A pattern's `pan` is overridden.** Every event is sent with `pan/0`, which puts all of that
 *   engine's energy in its left channel, and that channel *is* the mono source signal. Panning is
 *   lossless this way (no `0.5*(L+R)` summing) but `pan` as a musical parameter is gone: `az`
 *   replaces it, and it is a better parameter.
 * - **Note onsets quantise to a Dough block**, 128 frames or 2.9 ms at 44.1 kHz. Dough takes an
 *   event through `evaluate()` and applies it at the next `dsp()`, so unlike [PatSynthBank] -- which
 *   is sample-exact, and proves it by rendering bit-identically at four buffer sizes -- this path
 *   cannot place a note inside a block. That is a property of Dough's event API, not of the
 *   scheduler.
 *
 * # Why Dough is not on the audio thread
 *
 * [PatSynth] puts the pattern *query* on a feeder thread because querying allocates, and keeps
 * synthesis on the audio thread because Field's own voices are a fixed amount of float arithmetic.
 * Neither half of that argument survives here. [DoughEngine] is GraalWasm: every block enters a
 * polyglot context, and the JIT and the collector get a say in how long that takes.
 *
 * [PatSpatialBudgetProbe] measures it: four sources, 128 frames, 44.1 kHz, a 2.902 ms budget, 8000
 * blocks with the first tenth dropped as warmup. Two runs on the same machine, the second while
 * other work was loading it:
 *
 * | | idle | under load |
 * | --- | --- | --- |
 * | median block | 0.120 ms | 0.117 ms |
 * | p99 | 1.170 ms | 2.951 ms |
 * | p99.9 | 2.405 ms | 9.522 ms |
 * | worst block | 12.705 ms | **36.631 ms** |
 * | blocks over budget | 3 of 7200 | 74 of 7200 |
 *
 * Read the two columns together, because the pair is the finding. The **median is flat** -- the
 * arithmetic of four wasm engines and eight Steam Audio effects is 4% of a block either way, so
 * nothing here needs optimising. The **tail moves by a factor of three** with load the audio thread
 * has no say over. A path whose median fits twenty-five times over and which still misses one block
 * in a hundred is not a path to shave down; it is one to decouple.
 *
 * So [PatSpatialRoom] renders on its own thread into [PatSpatialRing], and the audio thread's entire
 * job is a bounded copy out of that ring. A ring holding more milliseconds than the worst stall
 * turns a dropout into a dip in [PatSpatialRing.fill], and [PatSpatialRing.underruns] is the number
 * that says whether that held.
 *
 * The one stall the ring cannot absorb is booting a [DoughEngine], at 130-500 ms. That is what
 * [PatSpatialRoom.prescanCycles] exists for, and what [PatSpatialRoom.lateSourceBoots] counts when
 * a `spat` tag turns up too late to have been prescanned.
 *
 * # What actually limits this, measured
 *
 * Not the arithmetic. On a ten-core machine at load average 33 -- twenty-odd agent sessions and a
 * Gradle daemon at 180% CPU -- [PatSpatialEngineSmoke] recorded the render thread producing 1274 of
 * the 1500 blocks a four-second window needed, while being **busy only 1399 ms of those 4000**. A
 * thread that is idle two thirds of the time and still misses its deadline is not a thread that
 * needs faster code; it is a thread that is not being scheduled. Per-block Dough cost came out 4.5x
 * its idle figure in the same run.
 *
 * Two things follow, and both are in the code rather than in advice:
 *
 * - [PatSpatialRoom.busyMsTotal] against elapsed time separates the two ways of falling behind.
 *   Near 100% busy with underruns means the work does not fit and this design is wrong. Far below
 *   it means the machine is oversubscribed, and [PatSpatialEngineSmoke] says so instead of failing.
 * - The ring is sized for the scheduler's stalls, not for this code's, which is why it defaults to
 *   341 ms rather than the ~40 ms the arithmetic alone would justify.
 *
 * Raising the render thread's priority was tried and reverted; the comment in
 * [PatSpatialRoom.start] records what it did instead of helping.
 */

/** Where a spatial source is, in Steam Audio's listener-relative frame. Metres and degrees. */
data class PatSpatialPlacement(
    /** Azimuth in degrees: 0 ahead, +90 to the listener's right. */
    val azimuthDeg: Double = 0.0,
    /** Elevation in degrees: 0 at ear height, +90 overhead. */
    val elevationDeg: Double = 0.0,
    /** Distance in metres. Clamped to [MIN_DISTANCE] so a source cannot land inside the head. */
    val distance: Double = 1.5,
    /** Occlusion, 0 clear to 1 fully blocked. */
    val occlusion: Double = 0.0,
    /** Whether the direct path loses high frequencies over distance. */
    val airAbsorption: Boolean = true,
) {
    /** Unit direction in Steam Audio's convention: -Z ahead, +X right, +Y up. */
    fun direction(): FloatArray {
        val az = Math.toRadians(azimuthDeg)
        val el = Math.toRadians(elevationDeg)
        val ce = cos(el)
        return floatArrayOf(
            (sin(az) * ce).toFloat(),
            sin(el).toFloat(),
            (-cos(az) * ce).toFloat(),
        )
    }

    companion object {
        /** Steam Audio's inverse-distance curve stops steepening here, so nothing blows up at zero. */
        const val MIN_DISTANCE = 0.4
    }
}

/**
 * One pattern event, resolved into a Dough event and a placement, stamped with its start frame.
 *
 * Everything the render thread needs is decided before it arrives: which engine, what to say to it,
 * and where the engine now is. No map lookups happen per block.
 */
data class PatSpatialEvent(
    /** Absolute frame on [PatSpatialRoom.framesRendered]'s clock. */
    val startFrame: Long,
    /** The `spat` tag: which source this plays on. */
    val sourceKey: String,
    /** The Dough event, already carrying `pan/0`. */
    val doughEvent: Map<String, Any?>,
    /** Where the source moves to when this event starts. */
    val placement: PatSpatialPlacement,
)

/** Keys this file consumes itself rather than forwarding to Dough. */
private val SPATIAL_TAGS = setOf("spat", "az", "elev", "dist", "occl", "air")

/**
 * The events of [pattern] over [arc], as Dough events with placements.
 *
 * Runs on the render thread, ahead of the audio. The `s` default is a sawtooth because an untagged
 * pattern should make an audible noise rather than nothing; give events an `s` to choose.
 */
fun patSpatialEventsForArc(
    pattern: PatPattern<String>,
    arc: PatArc,
    cps: Double,
    sampleRate: Int,
    gain: Double = 1.0,
    originFrame: Long = 0L,
    defaultSound: String = "saw",
): List<PatSpatialEvent> {
    require(cps > 0.0) { "cps must be > 0, got $cps" }
    val secondsPerCycle = 1.0 / cps
    return pattern.events(arc)
        .sortedWith(compareBy({ it.arc.start }, { it.arc.end }))
        .map { event ->
            val startSec = event.arc.start.toDouble() * secondsPerCycle
            val arcSec = (event.arc.end - event.arc.start).toDouble() * secondsPerCycle
            val ann = event.annotations

            val dough = linkedMapOf<String, Any?>("dough" to "play")
            // The atom itself names a sound or a note, the way the rest of the pattern language
            // reads it: a number is a MIDI note, a word is a sound.
            val atom = event.value.trim()
            val atomNote = atom.toDoubleOrNull()
            if (atomNote != null) {
                dough["s"] = ann["s"] ?: ann["sound"] ?: defaultSound
                dough["note"] = patFormatNumber(atomNote)
            } else {
                dough["s"] = if (atom.isEmpty() || atom == "~") (ann["s"] ?: defaultSound) else atom
            }
            // Explicit annotations win over anything inferred from the atom.
            ann["note"]?.let { dough["note"] = it }
            ann["n"]?.let { dough["note"] = it }
            if (dough["note"] == null) dough["note"] = "60"

            // Dough's own amplitude parameter. The event's `gain` is folded in here so that a
            // pattern written for patSynth sounds the same shape through this path.
            val vel = (ann["velocity"]?.toDoubleOrNull() ?: ann["gain"]?.toDoubleOrNull() ?: 0.8) * gain
            dough["velocity"] = patFormatNumber(vel.coerceIn(0.0, 4.0))
            // The note's length in seconds, so a pattern's rhythm reaches the synth's envelope.
            dough["duration"] = patFormatNumber(arcSec * (ann["sustain"]?.toDoubleOrNull() ?: 1.0))

            // Everything not claimed as a spatial tag, and not already set, goes to Dough verbatim.
            // That is what makes `lpf`, `room`, `crush`, `fmi` and the rest available for free.
            for ((k, v) in ann) {
                if (k in SPATIAL_TAGS || k in dough || k == "gain" || k == "sustain") continue
                dough[k] = v
            }
            // Last, and not negotiable: the engine must hand us a mono signal.
            dough["pan"] = "0"

            PatSpatialEvent(
                startFrame = originFrame + Math.round(startSec * sampleRate),
                sourceKey = ann["spat"]?.trim()?.ifEmpty { null } ?: "0",
                doughEvent = dough,
                placement = PatSpatialPlacement(
                    azimuthDeg = ann["az"]?.toDoubleOrNull() ?: 0.0,
                    elevationDeg = (ann["elev"]?.toDoubleOrNull() ?: 0.0).coerceIn(-90.0, 90.0),
                    distance = max(ann["dist"]?.toDoubleOrNull() ?: 1.5, PatSpatialPlacement.MIN_DISTANCE),
                    occlusion = (ann["occl"]?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 1.0),
                    airAbsorption = (ann["air"]?.toDoubleOrNull() ?: 1.0) > 0.5,
                ),
            )
        }
}

/**
 * A single-producer single-consumer ring of interleaved stereo frames.
 *
 * The whole point of this file's threading, so it is worth being precise about: the render thread
 * writes, the audio thread reads, and neither ever blocks or allocates. Both indices are monotonic
 * [AtomicLong]s and the capacity is a power of two, so a position becomes an offset with a mask and
 * there is no wraparound arithmetic to get wrong.
 *
 * An [underruns] count is not a diagnostic afterthought -- it is the one number that says whether
 * this design is working. Zero means the render thread stayed ahead.
 */
class PatSpatialRing(requestedFrames: Int) {

    /** Frames of headroom. Rounded up to a power of two. */
    val capacityFrames: Int = Integer.highestOneBit(requestedFrames.coerceAtLeast(1024) * 2 - 1)

    private val buffer = FloatArray(capacityFrames * 2)
    private val mask = (capacityFrames - 1).toLong()
    private val writePos = AtomicLong(0L)
    private val readPos = AtomicLong(0L)

    /** Frames written but not yet consumed. */
    val fill: Int get() = (writePos.get() - readPos.get()).toInt()

    /** Frames the writer may add right now. */
    val room: Int get() = capacityFrames - fill

    /** Frames the audio thread asked for and the ring could not supply. */
    @Volatile
    var underruns: Long = 0L
        private set

    /** Lowest fill the audio thread has ever seen, in frames. How close this came to a dropout. */
    @Volatile
    var minFill: Int = Int.MAX_VALUE
        private set

    /**
     * Forget the underrun and fill history.
     *
     * Called once, by the render thread, at the moment the ring first reaches its target fill. Up to
     * that point nothing is consuming and the render thread has no deadline, so warmup figures
     * describe a period in which an underrun was neither possible nor meaningful.
     */
    internal fun resetStatistics() {
        underruns = 0L
        minFill = Int.MAX_VALUE
    }

    /** Write [n] frames from [l] and [r]. Called only by the render thread. */
    fun write(l: FloatArray, r: FloatArray, n: Int) {
        val w = writePos.get()
        for (i in 0 until n) {
            val slot = (((w + i) and mask) * 2L).toInt()
            buffer[slot] = l[i]
            buffer[slot + 1] = r[i]
        }
        writePos.set(w + n)
    }

    /**
     * Add [n] frames into [outL] and [outR]. Called only by the audio thread.
     *
     * Additive, because that is what a [StereoNode] contract is, and short reads leave the tail of
     * the block untouched rather than writing silence over whatever else is in the mix.
     */
    fun readInto(outL: FloatArray, outR: FloatArray, n: Int) {
        val available = fill
        if (available < minFill) minFill = available
        val take = if (available < n) {
            underruns += (n - available).toLong()
            available
        } else {
            n
        }
        val rd = readPos.get()
        for (i in 0 until take) {
            val slot = (((rd + i) and mask) * 2L).toInt()
            outL[i] += buffer[slot]
            outR[i] += buffer[slot + 1]
        }
        readPos.set(rd + take)
    }
}

/**
 * One spatial source: a Dough engine, and the Steam Audio effects that place its output.
 *
 * Owned entirely by the render thread -- created there and closed there -- because both a
 * [DoughEngine]'s polyglot context and Steam Audio's effect state are cheapest when exactly one
 * thread ever touches them.
 *
 * [target] is where the pattern last said to be and [current] is where the render has got to.
 * Interpolating between them is not cosmetic: a note that jumps 140 degrees of azimuth between one
 * block and the next steps the HRTF filter discontinuously, and that is audible as a click on every
 * note. One pole per block, so a placement change lands over a few milliseconds.
 */
class PatSpatialSource internal constructor(
    val key: String,
    internal val engine: DoughEngine,
    internal val direct: DirectEffect,
    internal val binaural: BinauralEffect,
    internal val directParams: DirectEffectParams,
    internal val binauralParams: BinauralEffectParams,
    internal val dry: AudioBuffer,
    internal val shaped: AudioBuffer,
    internal val stereo: AudioBuffer,
) : AutoCloseable {

    internal var target = PatSpatialPlacement()
    internal var current = PatSpatialPlacement()
    internal val mono = FloatArray(DoughEngine.BLOCK_SIZE)
    internal val blockL = FloatArray(DoughEngine.BLOCK_SIZE)
    internal val blockR = FloatArray(DoughEngine.BLOCK_SIZE)
    internal val airGains = FloatArray(SteamAudio.NUM_BANDS)

    /** Notes started on this source. */
    @Volatile
    var notesPlayed: Long = 0L
        internal set

    /** Where this source is right now, as the render thread last placed it. */
    val placement: PatSpatialPlacement get() = current

    /**
     * Voices sounding in this source's engine, as the render thread last published them.
     *
     * Not a live read, and it cannot be one: a GraalVM polyglot context may only be entered by the
     * thread that built it, so `DoughEngine.activeVoices()` throws "Multi threaded access requested
     * ... but is not allowed for language(s) js" from anywhere but the render thread. That is also
     * the deeper reason the engines are created inside [PatSpatialRoom.renderLoop] -- a Dough engine
     * is not merely *best* owned by one thread, it is owned by one thread permanently.
     */
    @Volatile
    var activeVoices: Int = 0
        internal set

    /** The most voices ever seen sounding at once on this source. A sampled chord shows up here. */
    @Volatile
    var peakVoices: Int = 0
        internal set

    override fun close() {
        binaural.close()
        direct.close()
        engine.close()
    }

    override fun toString(): String = synthPrettyObject(
        this, "key" to key, "az" to current.azimuthDeg, "dist" to current.distance,
        "notes" to notesPlayed,
    )
}

/**
 * The render thread: pattern in, spatialised stereo into a [PatSpatialRing].
 *
 * It does three jobs in one loop, in this order, and the order is the design:
 *
 *  1. **Query ahead.** Ask [pattern] for whole cycles until it has covered the ring's horizon.
 *  2. **Start what is due.** Hand each event to its source's engine and move that source's target.
 *  3. **Render one block per source**, spatialise it, sum, and push.
 *
 * Sources are created lazily, on the first event that names them, because booting a [DoughEngine]
 * costs a few hundred milliseconds and 200 MB of wasm memory -- a `spat` tag nobody uses should not
 * pay for that. They are created here on the render thread, never on the caller's.
 */
class PatSpatialRoom internal constructor(
    val pattern: PatPattern<String>,
    val cps: Double,
    val sampleRate: Int,
    val gain: Double,
    val ring: PatSpatialRing,
    val wasmPath: Path,
    val maxSources: Int,
    /** Frames the render thread tries to keep in the ring. Its tolerance for its own stalls. */
    val targetFillFrames: Int,
    val hrtfInterpolation: HrtfInterpolation,
    val defaultSound: String,
    /** Cycles scanned up front for `spat` tags, so their engines boot before any audio is due. */
    val prescanCycles: Int,
) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private val frames = AtomicLong(0L)
    private val sourcesByKey = LinkedHashMap<String, PatSpatialSource>()
    private val pending = PriorityQueue<PatSpatialEvent>(64, compareBy { it.startFrame })
    private val listenerRequests = ConcurrentLinkedQueue<Double>()

    /** Frames the render thread has produced. The clock a [PatSpatialEvent] is stamped on. */
    val framesRendered: Long get() = frames.get()

    /** Cycles of [pattern] queried so far. */
    @Volatile
    var cyclesQueried: Long = 0L
        private set

    /**
     * True once Steam Audio is up, every prescanned engine has booted, and the ring has filled.
     *
     * Not "the thread started". This is the point at which attaching a realtime consumer is safe,
     * which is why [PatSpatialLaunch.awaitReady] waits for it.
     */
    @Volatile
    var ready: Boolean = false
        private set

    /** Non-null if the render thread died; the reason it died. */
    @Volatile
    var failure: Throwable? = null
        private set

    /**
     * Engines booted after the ring was already feeding an audio thread.
     *
     * Each one is a likely dropout, and this is the honest way to say so. Booting a [DoughEngine]
     * takes 100-400 ms inside the render loop, which is longer than the ring holds, so a `spat` tag
     * that first appears in cycle 40 stalls the render thread past the audio thread's supply.
     * [prescanCycles] is what keeps this at zero for the ordinary case -- a pattern whose tags are
     * all visible in its first few cycles has every engine booted before a sample is produced.
     */
    @Volatile
    var lateSourceBoots: Int = 0
        private set

    /** Longest single block the render thread has taken, in milliseconds. */
    @Volatile
    var worstBlockMs: Double = 0.0
        private set

    /** Blocks rendered since the ring first filled. */
    @Volatile
    var blocksRendered: Long = 0L
        private set

    /** Blocks that took longer than 5 ms -- longer than the audio they produced. */
    @Volatile
    var slowBlocks: Long = 0L
        private set

    /** Blocks that took longer than 50 ms, which no reasonable ring depth absorbs repeatedly. */
    @Volatile
    var stallBlocks: Long = 0L
        private set

    /** Total milliseconds spent in blocks over 5 ms. How much lateness there is to absorb. */
    @Volatile
    var slowBlockMsTotal: Double = 0.0
        private set

    /**
     * Milliseconds this thread spent doing its own work since the ring filled.
     *
     * Compare it with elapsed wall time to tell the two ways of falling behind apart. Near 100%
     * means the work genuinely does not fit and the design is wrong. Far below it, with underruns,
     * means the thread was not scheduled -- the machine is oversubscribed and no amount of making
     * this loop faster will help.
     */
    val busyMsTotal: Double get() = queryMsTotal + eventMsTotal + synthMsTotal + spatialiseMsTotal

    /** Where the render thread spends its time, split by stage, in milliseconds. */
    @Volatile
    var queryMsTotal: Double = 0.0
        private set

    @Volatile
    var eventMsTotal: Double = 0.0
        private set

    @Volatile
    var synthMsTotal: Double = 0.0
        private set

    @Volatile
    var spatialiseMsTotal: Double = 0.0
        private set

    /** Sources that exist, in the order their `spat` tags were first seen. */
    val sources: List<PatSpatialSource> get() = synchronized(sourcesByKey) { sourcesByKey.values.toList() }

    /**
     * Turn the listener's head to [azimuthDeg], from any thread.
     *
     * Positive is a turn to the right, and a source dead ahead moves to the listener's left as the
     * head turns right. The render thread applies it at the top of a block.
     */
    fun turnListener(azimuthDeg: Double) {
        listenerRequests.add(azimuthDeg)
    }

    /** The listener's heading in degrees, as the render thread last applied it. */
    @Volatile
    var listenerAzimuthDeg: Double = 0.0
        private set

    internal fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = kotlin.concurrent.thread(isDaemon = true, name = "pat-spatial-room") {
            // Deliberately left at the default priority. Raising it to MAX_PRIORITY was tried and
            // reverted: once the ring is full this thread polls on a 2 ms sleep, and as a
            // high-priority poller on a loaded machine it preempted the audio engine's own 4 ms
            // loop badly enough that OpenAL stopped reporting processed buffers at all -- the
            // output went silent while this thread sat on a full ring, which looks nothing like a
            // priority problem and cost an hour to pin on the one line that caused it.
            try {
                renderLoop()
            } catch (t: Throwable) {
                failure = t
            } finally {
                ready = false
                synchronized(sourcesByKey) {
                    sourcesByKey.values.forEach { runCatching { it.close() } }
                    sourcesByKey.clear()
                }
            }
        }
    }

    private fun renderLoop() {
        val settings = AudioSettings(samplingRate = sampleRate, frameSize = DoughEngine.BLOCK_SIZE)
        SteamAudioContext().use { context ->
            Hrtf.default(context, settings, volume = 1.0f).use { hrtf ->
                Arena.ofShared().use { arena ->
                    val mixL = FloatArray(DoughEngine.BLOCK_SIZE)
                    val mixR = FloatArray(DoughEngine.BLOCK_SIZE)
                    val lane = FloatArray(DoughEngine.BLOCK_SIZE * 2)
                    var cycle = 0L

                    // Boot every engine the pattern's first cycles ask for, before producing a
                    // sample. The alternative -- booting on the first event that names a tag --
                    // puts a few hundred milliseconds of wasm startup inside a block that owes the
                    // audio thread 2.9 ms of audio, and the ring is not deep enough to hide it.
                    for (key in prescanSourceKeys()) {
                        if (!running.get()) return
                        sourceFor(context, settings, hrtf, arena, key, late = false)
                    }

                    while (running.get()) {
                        listenerRequests.poll()?.let { requested ->
                            var latest = requested
                            while (true) latest = listenerRequests.poll() ?: break
                            listenerAzimuthDeg = latest
                        }

                        // 1. Query ahead far enough to cover everything the ring will hold.
                        val framesPerCycle = sampleRate / cps
                        val horizon = frames.get() + ring.capacityFrames + framesPerCycle
                        var queried = 0
                        val queryStart = System.nanoTime()
                        while (running.get() && cycle * framesPerCycle <= horizon && queried < 64) {
                            pending.addAll(
                                patSpatialEventsForArc(
                                    pattern = pattern,
                                    arc = PatArc(PatQ.of(cycle), PatQ.of(cycle + 1)),
                                    cps = cps,
                                    sampleRate = sampleRate,
                                    gain = gain,
                                    originFrame = 0L,
                                    defaultSound = defaultSound,
                                ),
                            )
                            cycle++
                            cyclesQueried = cycle
                            queried++
                        }
                        if (ready) queryMsTotal += (System.nanoTime() - queryStart) / 1e6

                        if (ring.fill >= targetFillFrames || ring.room < DoughEngine.BLOCK_SIZE) {
                            // The ring is full for the first time, so the prefill is done. Until
                            // now this thread has had no deadline, and that is exactly what it
                            // needed: the first blocks through GraalWasm and Steam Audio run
                            // interpreted, and one of them was measured at 365 ms. Declaring
                            // readiness after a single block instead -- which is what this used to
                            // do -- hands the audio thread a nearly empty ring and a render loop
                            // that has not been compiled yet, and it underran 0.78 s out of 4.
                            if (!ready) {
                                // The warmup's own numbers would misdescribe steady-state play.
                                worstBlockMs = 0.0
                                ring.resetStatistics()
                                ready = true
                            }
                            // Ahead of the audio thread. Sleeping here is the whole margin.
                            runCatching { Thread.sleep(2L) }.onFailure { return }
                            continue
                        }

                        val started = System.nanoTime()
                        val blockStart = frames.get()
                        val blockEnd = blockStart + DoughEngine.BLOCK_SIZE

                        // 2. Start everything due in this block. Dough applies an event at its next
                        //    dsp(), so "due in this block" is as fine as the granularity gets.
                        while (pending.isNotEmpty() && pending.peek().startFrame < blockEnd) {
                            val event = pending.poll()
                            val source = sourceFor(context, settings, hrtf, arena, event.sourceKey, late = true)
                            if (source != null) {
                                source.engine.evaluate(event.doughEvent)
                                source.target = event.placement
                                source.notesPlayed++
                            }
                        }
                        val afterEvents = System.nanoTime()

                        // 3. One block per source, spatialised and summed.
                        java.util.Arrays.fill(mixL, 0f)
                        java.util.Arrays.fill(mixR, 0f)
                        val live = synchronized(sourcesByKey) { sourcesByKey.values.toList() }
                        for (source in live) {
                            val t0 = System.nanoTime()
                            source.engine.renderBlock(source.blockL, source.blockR)
                            val t1 = System.nanoTime()
                            spatialiseSource(context, source, mixL, mixR, lane)
                            val t2 = System.nanoTime()
                            if (ready) {
                                synthMsTotal += (t1 - t0) / 1e6
                                spatialiseMsTotal += (t2 - t1) / 1e6
                            }
                        }

                        // Publish the voice counts for anyone watching. Only this thread may ask
                        // the engine, and asking costs a polyglot context enter, so it is periodic
                        // rather than per block.
                        if (blockStart % (DoughEngine.BLOCK_SIZE * 4L) == 0L) {
                            for (source in live) {
                                val v = source.engine.activeVoices() ?: 0
                                source.activeVoices = v
                                if (v > source.peakVoices) source.peakVoices = v
                            }
                        }

                        ring.write(mixL, mixR, DoughEngine.BLOCK_SIZE)
                        frames.set(blockEnd)

                        val finished = System.nanoTime()
                        val ms = (finished - started) / 1e6
                        if (ready) {
                            if (ms > worstBlockMs) worstBlockMs = ms
                            blocksRendered++
                            eventMsTotal += (afterEvents - started) / 1e6
                            if (ms > 5.0) {
                                slowBlocks++
                                slowBlockMsTotal += ms
                                if (ms > 50.0) stallBlocks++
                            }
                        }
                    }
                }
            }
        }
    }

    private fun spatialiseSource(
        context: SteamAudioContext,
        source: PatSpatialSource,
        mixL: FloatArray,
        mixR: FloatArray,
        lane: FloatArray,
    ) {
        // pan/0 put every voice in the left channel, so this copy is the mono source signal and
        // not a downmix of one.
        System.arraycopy(source.blockL, 0, source.mono, 0, DoughEngine.BLOCK_SIZE)
        source.dry.writeChannel(0, source.mono)

        val at = approach(source.current, source.target)
        source.current = at

        // Steam Audio's own inverse-distance curve, evaluated here rather than through
        // DistanceAttenuationModel.calculate, which allocates a scratch Arena per call.
        val distance = max(at.distance, PatSpatialPlacement.MIN_DISTANCE)
        source.directParams.distanceAttenuation =
            (PatSpatialPlacement.MIN_DISTANCE / distance).toFloat()
        source.directParams.occlusion = (1.0 - at.occlusion).toFloat()

        var flags = DirectEffectFlags.APPLY_DISTANCE_ATTENUATION or DirectEffectFlags.APPLY_OCCLUSION
        if (at.airAbsorption) {
            // Steam Audio's default air-absorption curve: a per-band exponential in distance.
            for (band in source.airGains.indices) {
                val coeff = AIR_COEFFS[band.coerceAtMost(AIR_COEFFS.size - 1)]
                source.airGains[band] = Math.exp(-coeff * distance).toFloat()
            }
            source.directParams.setAirAbsorption(source.airGains)
            flags = flags or DirectEffectFlags.APPLY_AIR_ABSORPTION
        }
        source.directParams.flags = flags
        source.direct.apply(source.directParams, source.dry, source.shaped)

        // The listener's heading turns the world the other way, which is the whole content of a
        // head rotation: a source ahead of a head turned right is off to the left.
        val dir = at.copy(azimuthDeg = at.azimuthDeg - listenerAzimuthDeg).direction()
        source.binauralParams.setDirection(dir[0], dir[1], dir[2])
        source.binaural.apply(source.binauralParams, source.shaped, source.stereo)

        source.stereo.interleaveTo(context, lane)
        for (f in 0 until DoughEngine.BLOCK_SIZE) {
            mixL[f] += lane[f * 2]
            mixR[f] += lane[f * 2 + 1]
        }
    }

    /** One pole per block toward the target, so a placement jump does not step the HRTF filter. */
    private fun approach(from: PatSpatialPlacement, to: PatSpatialPlacement): PatSpatialPlacement {
        if (from == to) return to
        val k = SMOOTHING
        fun lerp(a: Double, b: Double) = a + (b - a) * k
        // Azimuth is circular: going from -170 to 170 is 20 degrees the short way, not 340.
        var delta = (to.azimuthDeg - from.azimuthDeg) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return PatSpatialPlacement(
            azimuthDeg = from.azimuthDeg + delta * k,
            elevationDeg = lerp(from.elevationDeg, to.elevationDeg),
            distance = lerp(from.distance, to.distance),
            occlusion = lerp(from.occlusion, to.occlusion),
            airAbsorption = to.airAbsorption,
        )
    }

    /**
     * The distinct `spat` tags in the pattern's first [prescanCycles] cycles, in first-seen order.
     *
     * Queries the pattern the same way the render loop does, so a tag that only exists under a
     * condition the first cycles do not reach is genuinely not knowable here -- that is the case
     * [lateSourceBoots] counts rather than pretends to have solved.
     */
    private fun prescanSourceKeys(): List<String> {
        val keys = LinkedHashSet<String>()
        for (c in 0 until prescanCycles) {
            if (keys.size >= maxSources) break
            val events = runCatching {
                patSpatialEventsForArc(
                    pattern = pattern, arc = PatArc(PatQ.of(c.toLong()), PatQ.of(c.toLong() + 1)),
                    cps = cps, sampleRate = sampleRate, gain = gain, defaultSound = defaultSound,
                )
            }.getOrElse { emptyList() }
            for (e in events) keys.add(e.sourceKey)
        }
        // A pattern with no events at all in its opening cycles still gets the default source, so
        // that a first note arriving later does not pay for a boot.
        if (keys.isEmpty()) keys.add("0")
        return keys.take(maxSources)
    }

    private fun sourceFor(
        context: SteamAudioContext,
        settings: AudioSettings,
        hrtf: Hrtf,
        arena: Arena,
        key: String,
        late: Boolean,
    ): PatSpatialSource? {
        synchronized(sourcesByKey) {
            sourcesByKey[key]?.let { return it }
            if (sourcesByKey.size >= maxSources) return null
        }
        if (late) lateSourceBoots++
        val source = PatSpatialSource(
            key = key,
            engine = DoughEngine(wasmPath = wasmPath, sampleRate = sampleRate),
            direct = DirectEffect(context, settings, channelCount = 1),
            binaural = BinauralEffect(context, settings, hrtf),
            directParams = DirectEffectParams(arena, flags = DirectEffectFlags.APPLY_DISTANCE_ATTENUATION),
            binauralParams = BinauralEffectParams(arena, hrtf = hrtf, interpolation = hrtfInterpolation),
            dry = AudioBuffer(arena, 1, DoughEngine.BLOCK_SIZE),
            shaped = AudioBuffer(arena, 1, DoughEngine.BLOCK_SIZE),
            stereo = AudioBuffer(arena, 2, DoughEngine.BLOCK_SIZE),
        )
        synchronized(sourcesByKey) { sourcesByKey[key] = source }
        return source
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
    }

    val isRunning: Boolean get() = running.get()

    override fun toString(): String = synthPrettyObject(
        this, "cps" to cps, "sources" to sources.size, "cycles" to cyclesQueried,
        "fill" to ring.fill, "underruns" to ring.underruns, "worstBlockMs" to worstBlockMs,
    )

    private companion object {
        /** Fraction of the remaining distance a block closes. 0.02 is about 5 ms at 128/44100. */
        const val SMOOTHING = 0.02

        /**
         * Air absorption per metre, per Steam Audio band (low, mid, high).
         *
         * These are the coefficients Steam Audio's own default model uses, and they are here rather
         * than behind `IPLAirAbsorptionModel` because evaluating that model allocates.
         */
        val AIR_COEFFS = doubleArrayOf(0.0002, 0.0017, 0.0182)
    }
}

/**
 * The audio thread's end of the chain: drain a [PatSpatialRing] into the output.
 *
 * There is nothing else in here on purpose. No synthesis, no spatialisation, no allocation, no
 * locks -- a bounded copy and two monotonic counters. Everything expensive is a thread away.
 */
class PatSpatialBank internal constructor(
    val ring: PatSpatialRing,
    val sampleRate: Int,
    val masterGain: Double = 1.0,
) : StereoNode() {

    private val frameCounter = AtomicLong(0L)

    /** Frames the audio thread has pulled. */
    val frame: Long get() = frameCounter.get()

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        ring.readInto(outL, outR, numFrames)
        if (masterGain != 1.0) {
            val g = masterGain.toFloat()
            for (i in 0 until numFrames) {
                outL[i] *= g
                outR[i] *= g
            }
        }
        frameCounter.set(frameCounter.get() + numFrames)
    }

    override fun toString(): String = synthPrettyObject(
        this, "frame" to frame, "fill" to ring.fill, "underruns" to ring.underruns,
    )
}

/**
 * A spatialised pattern, as one thing a box can return.
 *
 * Returning it from a box ties the sound to the box, the way [PatSynthLaunch] does: the box stays
 * lit while it plays, and stopping it stops the render thread and closes every Dough engine and
 * Steam Audio handle with it.
 *
 * [tap] sits in the signal path and passes audio through unchanged, so `launch.tap.snapshot()` is a
 * copy of what the audio thread last produced rather than a second rendering.
 */
class PatSpatialLaunch internal constructor(
    val bank: PatSpatialBank,
    val room: PatSpatialRoom,
) : StereoNode(), AutoCloseable {

    /** A tap on this launch's own output, fed from the audio thread. */
    val tap: StereoScope = StereoScope(bank)

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        tap.render(outL, outR, numFrames, ctx)
    }

    /** The spatial sources the pattern's `spat` tags have brought into being. */
    val sources: List<PatSpatialSource> get() = room.sources

    /** Turn the listener's head. Positive is to the right. */
    fun turnListener(azimuthDeg: Double) = room.turnListener(azimuthDeg)

    /** Frames the audio thread asked for and the ring could not supply. Zero is the whole point. */
    val underruns: Long get() = room.ring.underruns

    /**
     * Block until the render thread has Steam Audio up and has filled the ring, or [timeoutMs].
     *
     * A launch is deliberately asynchronous -- booting one Dough engine per source takes a few
     * hundred milliseconds each and a box should not sit on the main thread for it. A test, or a
     * capture, does want to wait.
     *
     * **Wait for this before handing the launch to an audio engine.** Ready means the ring has
     * filled to [PatSpatialRoom.targetFillFrames] once, which is both the prefill and the render
     * loop's warmup; a consumer attached earlier drains a ring that the still-interpreted render
     * path cannot keep up with.
     */
    fun awaitReady(timeoutMs: Long = 20_000L): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            room.failure?.let { throw IllegalStateException("spatial render thread failed", it) }
            if (room.ready) return true
            Thread.sleep(5L)
        }
        return false
    }

    /**
     * Pull [frames] through the bank and into [tap], for when nothing else is pulling it.
     *
     * The same situation [PatSynthLaunch.advanceOffline] answers: a machine with no output device
     * has no audio thread, so nobody drains the ring and the tap stays empty. The difference is
     * that here the render thread is running regardless, so this does not advance the synthesis --
     * it only consumes what the render thread has already produced, in real time. Waiting is
     * therefore part of the contract, and a caller asking for a second of audio waits a second.
     */
    fun advanceOffline(frames: Int) {
        require(frames >= 0) { "frames must be >= 0, got $frames" }
        var left = frames
        while (left > 0) {
            val n = minOf(OFFLINE_BLOCK, left)
            while (room.ring.fill < n && room.isRunning) {
                room.failure?.let { throw IllegalStateException("spatial render thread failed", it) }
                Thread.sleep(1L)
            }
            java.util.Arrays.fill(offlineL, 0f)
            java.util.Arrays.fill(offlineR, 0f)
            offlineContext.cycleId = ++offlineCycle
            offlineContext.time = bank.frame.toFloat() / bank.sampleRate
            tap.render(offlineL, offlineR, n, offlineContext)
            left -= n
        }
    }

    private val offlineContext = SynthContext(bank.sampleRate.toFloat(), OFFLINE_BLOCK)
    private val offlineL = FloatArray(OFFLINE_BLOCK)
    private val offlineR = FloatArray(OFFLINE_BLOCK)
    private var offlineCycle = 0L

    override fun close() {
        room.stop()
    }

    override fun toString(): String = synthPrettyObject(this, "room" to room, "bank" to bank)

    private companion object {
        const val OFFLINE_BLOCK = 2048
    }
}

/**
 * Play [pattern] through Dough, placed in space by Steam Audio, in realtime.
 *
 * ```kotlin
 * audio {
 *     patSpatial(
 *         pat("c4 e4 g4 b4").with(
 *             paramOf("spat", pat("<near far>")),
 *             param("az", saw().range(-80.0, 80.0)),
 *             param("dist", "1 4"),
 *         ),
 *         cps = 1.5,
 *     )
 * }
 * ```
 *
 * Headphones. HRTF rendering assumes each ear hears only its own channel; over speakers it
 * collapses to something close to ordinary panning.
 *
 * @param cps cycles per second.
 * @param gain a multiplier on top of each event's own `velocity`.
 * @param sampleRate **must match the engine that will play this**, and the default matches
 *   [FieldAudioEngine.shared]. The ring has a producer on one clock and a consumer on the other, so
 *   a mismatch is not a resampling -- it is a steady drift that empties or overflows the ring and
 *   transposes everything on the way. [registerPatSpatialReturnValue] refuses a mismatch rather
 *   than letting it be heard as a tuning problem.
 * @param maxSources how many distinct `spat` tags get an engine. Each costs a Dough boot and about
 *   200 MB of wasm memory, so this is a real ceiling rather than a formality.
 * @param prescanCycles how many opening cycles are searched for `spat` tags, so that their Dough
 *   engines are booted before the first sample rather than inside a block that owes the audio
 *   thread 2.9 ms. See [PatSpatialRoom.lateSourceBoots] for what happens to a tag that appears
 *   later than this.
 * @param ringFrames headroom between the render thread and the audio thread, and therefore also
 *   this path's added output latency. The default holds 341 ms at 48 kHz and the render thread aims
 *   to keep 256 ms of it filled. That is far more than the 12.7 ms worst block measured on an idle
 *   machine, and deliberately so: the stalls that matter are not this code's arithmetic but the
 *   scheduler's, and on a machine at three times its core count they ran to 115 ms. Latency here
 *   costs nothing musically -- the pattern is queried ahead, so notes stay in time with each other;
 *   what it delays is only how soon a start or a stop is heard.
 */
fun patSpatial(
    pattern: PatPattern<String>,
    cps: Double = 1.0,
    gain: Double = 1.0,
    sampleRate: Int = 48_000,
    maxSources: Int = 4,
    ringFrames: Int = 16384,
    targetFillFrames: Int = 12288,
    masterGain: Double = 1.0,
    hrtfInterpolation: HrtfInterpolation = HrtfInterpolation.BILINEAR,
    defaultSound: String = "saw",
    prescanCycles: Int = 8,
    wasmPath: Path = Paths.get("./src/jvmMain/resources/dough.wasm"),
): PatSpatialLaunch {
    require(maxSources in 1..16) { "maxSources must be in 1..16, got $maxSources" }
    val ring = PatSpatialRing(ringFrames)
    require(targetFillFrames in DoughEngine.BLOCK_SIZE until ring.capacityFrames) {
        "targetFillFrames must be between one block and the ring capacity " +
            "(${DoughEngine.BLOCK_SIZE} until ${ring.capacityFrames}), got $targetFillFrames"
    }
    val bank = PatSpatialBank(ring = ring, sampleRate = sampleRate, masterGain = masterGain)
    val room = PatSpatialRoom(
        pattern = pattern,
        cps = cps,
        sampleRate = sampleRate,
        gain = gain,
        ring = ring,
        wasmPath = wasmPath,
        maxSources = maxSources,
        targetFillFrames = targetFillFrames,
        hrtfInterpolation = hrtfInterpolation,
        defaultSound = defaultSound,
        prescanCycles = prescanCycles,
    )
    room.start()
    return PatSpatialLaunch(bank, room)
}

/**
 * Teach Field that a [PatSpatialLaunch] is something a box can be running.
 *
 * [registerPatSynthReturnValue]'s shape exactly: register the patch, hold the box open while the run
 * generation is current, and close the launch in a `finally` so the render thread, the Dough engines
 * and the Steam Audio handles all go with it.
 */
@RunsAt(returnValueRegistration, priority = 10)
fun registerPatSpatialReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, launchEpoch, notify ->
        val launch = returnValue as? PatSpatialLaunch ?: return@handler false

        val engine = FieldAudioEngine.shared()
        // A ring whose producer and consumer disagree about the sample rate does not resample; it
        // drifts. At 44.1 against 48 the audio thread consumes 9% faster than the render thread
        // produces, so the ring empties, and what does arrive is transposed up a tone and a half.
        // Both symptoms read as a synth problem rather than a clock problem, so refuse it here.
        require(launch.bank.sampleRate == engine.sampleRate) {
            "patSpatial(sampleRate = ${launch.bank.sampleRate}) cannot play on a " +
                "FieldAudioEngine running at ${engine.sampleRate} Hz -- the ring would drift. " +
                "Pass sampleRate = ${engine.sampleRate}."
        }
        val handle = engine.register(audio(launch, gain = 1f))

        inMain {
            val job = kotlin.coroutines.coroutineContext[Job]!!
            if (!inside.registerLaunchJob(job, launchEpoch)) {
                handle.close()
                launch.close()
                notify(false, strand, generation)
                return@inMain
            }
            notify(true, strand, generation)
            try {
                while (inside.runGeneration[strand] == generation) {
                    yield()
                }
            } finally {
                handle.close()
                launch.close()
                inside.jobs.remove(job)
                notify(false, strand, generation)
            }
        }
        true
    }
}

/**
 * Play [pattern] in space and put a live waveform and spectrum in this box's output.
 *
 * Return the result as the box's last expression, as with [patSpatial]: the display is a side
 * effect and the launch is what ties the sound to the box.
 */
fun Box.showPatSpatial(
    pattern: PatPattern<String>,
    cps: Double = 1.0,
    channel: String = "main",
): PatSpatialLaunch {
    val launch = patSpatial(pattern, cps = cps)
    launch.tap.liveSummary(channel)
    return launch
}
