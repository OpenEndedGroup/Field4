@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import afield.document.graph.Box
import afield.runtime.lifecycle.AnimationClock
import afield.app.diagnostics.logging.lprintln
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Box-level dough: `sound("/dough/play/note/60/s/sine/...")` from any box, and `thisRMS`, the level
 * that box's own sounds are making right now.
 *
 * The paths are dough's, unchanged, so a path copied from a browser demo running `dough.wasm` plays
 * the same here: [DoughEngine] runs the same module. One [DoughLive] serves the whole process. It
 * builds its engine on its own render thread (a [DoughEngine] admits only the thread that built it)
 * and passes audio to [FieldAudioEngine] through a short [PatSpatialRing], so nothing dough does,
 * booting included, happens on the audio thread.
 *
 * Every box gets a dough `group` number, and [Box.sound] tags the box's events with it. After every
 * block the render thread reads each sounding voice's group and output sample out of the module's
 * memory ([DoughEngine.voiceFrames]) and keeps a running mean square per group, so [Box.thisRMS] is
 * measured from what the box's voices actually produced, not estimated from the numbers in its paths.
 */

/**
 * The process-wide realtime dough engine behind [Box.sound] and [Box.thisRMS].
 *
 * [play] queues an event from any thread; the render thread applies it before its next block, so a
 * sound starts within one block plus the ring (about 25 ms at the default fill) plus the audio
 * engine's own output queue. [level] reads the published measurement for a `group`.
 *
 * If the audio device stops asking for audio (no output device, or a contended one), the ring fills
 * and the render thread keeps rendering at wall-clock pace and drops the blocks, so levels keep
 * moving and a capture with no speakers still shows a box reacting to its sound.
 */
class DoughLive internal constructor(
    private val wasmPath: Path,
    val sampleRate: Int,
    /** Frames the render thread keeps in the ring: the latency it adds. */
    val targetFillFrames: Int = 1024,
    /** Time constant of the level meter, in seconds. */
    val levelTimeConstant: Double = 0.04,
) : AutoCloseable {

    companion object {
        /**
         * What [level] divides by: the RMS of one voice's output for `/dough/play/s/sine/gain/1`,
         * centre-panned, as [DoughVoiceMeter] measures it. So a sine at gain 1 reads 1, and a gain of
         * 0.2 reads about 0.2, which is the scale the madp demos' `thisRMS * 18` was written for.
         * Pinned by `DoughLiveTest`.
         */
        const val SINE_GAIN_ONE_RMS = 0.139

        @Volatile
        private var shared: DoughLive? = null

        /** The process's [DoughLive] if something has already started it, without starting one. */
        fun sharedOrNull(): DoughLive? = shared

        /** The process's [DoughLive], started on first use and registered with [audioEngine]. */
        @Synchronized
        fun shared(): DoughLive = shared ?: run {
            val engine = audioEngine()
            DoughLive(doughWasmPath(), engine.sampleRate).also { live ->
                live.start()
                val bank = PatSpatialBank(live.ring, live.sampleRate)
                bank.isReady = { live.ready }
                engine.register(audio(bank, gain = 1f))
                shared = live
            }
        }

        private val groups = WeakHashMap<Box, Int>()
        private val nextGroup = AtomicInteger(1)

        /** The dough `group` this box's sounds carry. Stable for the life of the box in this process. */
        @Synchronized
        fun groupOf(box: Box): Int = groups.getOrPut(box) { nextGroup.getAndIncrement() }
    }

    /** The ring the render thread fills and the audio thread drains. */
    val ring = PatSpatialRing(targetFillFrames * 2)

    /** True once the engine has booted on the render thread. */
    @Volatile
    var ready: Boolean = false
        private set

    /** The last error the render thread hit, from booting or from an event the module refused. */
    @Volatile
    var lastError: Throwable? = null
        private set

    /** Blocks rendered and dropped because nothing was draining the ring. */
    @Volatile
    var droppedBlocks: Long = 0L
        private set

    private val meter = DoughVoiceMeter(sampleRate, levelTimeConstant)
    private val pending = ConcurrentLinkedQueue<Map<String, Any?>>()
    private val patterns = CopyOnWriteArrayList<PatternVoice>()

    /** Frames the render thread has rendered (or, with no device draining it, dropped) since it started. */
    @Volatile
    var framesRendered: Long = 0L
        private set

    /** The frame that is being heard now: [framesRendered] less what is still waiting in the ring. */
    val heardFrame: Long get() = max(0L, framesRendered - ring.fill)

    /**
     * A pattern [play] is playing. [stop] ends it after the block in progress; notes already started
     * finish their own envelopes.
     */
    class PatternVoice internal constructor(
        private val live: DoughLive,
        val pattern: PatPattern<String>,
        /** Cycles per second. */
        val cps: Double,
        /** The frame on [DoughLive.framesRendered]'s clock at which cycle 0 begins. */
        val originFrame: Long,
        /** The dough `group` its events carry, or null for none. */
        val group: Int?,
    ) {
        @Volatile
        var stopped = false
            private set

        /** The last exception a query of [pattern] threw (a `signal { }` that failed, say), or null. */
        @Volatile
        var lastError: Throwable? = null
            internal set

        /** Where this pattern is, in cycles, as heard now. */
        val cycle: Double get() = max(0.0, (live.heardFrame - originFrame).toDouble() / live.sampleRate * cps)

        fun stop() {
            stopped = true
            live.patterns.remove(this)
        }

        private val cpsQ = PatQ.of((cps * 1_000_000).roundToLong(), 1_000_000L)

        internal fun eventsInBlock(firstFrame: Long, frames: Int): List<Map<String, Any?>> {
            val a = firstFrame - originFrame
            val b = a + frames
            if (b <= 0) return emptyList()
            val arc = PatArc(PatQ.of(max(0L, a), live.sampleRate.toLong()) * cpsQ, PatQ.of(b, live.sampleRate.toLong()) * cpsQ)
            // A continuous pattern (no `whole`) has no notes to strike, so only events with a start play.
            return pattern.events(arc).filter { it.whole != null && it.hasOnset }.map { event ->
                val e = doughPatternEvent(event, cps)
                if (group == null || "group" in e) e else e + ("group" to group)
            }
        }
    }

    /**
     * Play [pattern] at [cps] cycles per second until [PatternVoice.stop], from cycle 0 at [originFrame]
     * (now, by default; pass another voice's `originFrame` to play in step with it).
     *
     * The pattern is queried on the render thread one 128-frame block at a time, so onsets land on a
     * block (2.7 ms at 48 kHz) and a continuous parameter, `gain(sig { level })` for instance, is read
     * as each event is scheduled. Each event becomes a dough event by [doughPatternEvent]. A query
     * that throws is recorded in [PatternVoice.lastError] and that block of the pattern is skipped.
     */
    fun play(pattern: PatPattern<String>, cps: Double = 0.5, group: Int? = null, originFrame: Long = framesRendered): PatternVoice {
        require(cps > 0.0) { "cps must be > 0, got $cps" }
        return PatternVoice(this, pattern, cps, originFrame, group).also { patterns.add(it) }
    }
    @Volatile
    private var stopped = false
    private var thread: Thread? = null

    /**
     * Queue one dough path, tagged with [group] when it is not null.
     *
     * The path is parsed here, on the caller's thread, so a malformed one fails in the box that wrote
     * it instead of on the render thread.
     */
    fun play(path: String, group: Int? = null) {
        val event: Map<String, Any?> = doughPathEvent(path)
        pending.add(if (group == null || "group" in event) event else event + ("group" to group))
    }

    /** The measured level of [group]'s voices, scaled so a sine at gain 1 reads about 1. */
    fun level(group: Int): Double = sqrt(meter.published[group] ?: 0.0) / SINE_GAIN_ONE_RMS

    /** The measured level of every voice together, on the same scale as [level]. */
    val mixLevel: Double get() = sqrt(meter.publishedTotal) / SINE_GAIN_ONE_RMS

    internal fun start() {
        thread = Thread({ renderLoop() }, "DoughLive").apply {
            isDaemon = true
            start()
        }
    }

    private fun renderLoop() {
        val engine = try {
            DoughEngine(wasmPath, sampleRate)
        } catch (t: Throwable) {
            lastError = t
            lprintln("error,audio", "DoughLive could not boot dough.wasm: $t")
            return
        }
        try {
            ready = true
            val l = FloatArray(DoughEngine.BLOCK_SIZE)
            val r = FloatArray(DoughEngine.BLOCK_SIZE)
            val blockSeconds = DoughEngine.BLOCK_SIZE.toDouble() / sampleRate
            var lastProgress = System.nanoTime()
            var stallClock = 0.0
            while (!stopped) {
                drain(engine)
                if (ring.fill < targetFillFrames && ring.room >= DoughEngine.BLOCK_SIZE) {
                    schedulePatterns(engine)
                    engine.renderBlock(l, r)
                    framesRendered += DoughEngine.BLOCK_SIZE
                    meter.afterBlock(engine)
                    ring.write(l, r, DoughEngine.BLOCK_SIZE)
                    lastProgress = System.nanoTime()
                    stallClock = 0.0
                    continue
                }
                val now = System.nanoTime()
                val stalledFor = (now - lastProgress) / 1e9
                if (stalledFor > 0.25) {
                    // Nobody is draining the ring. Keep the engine's clock and the meters moving at
                    // the pace a device would have, and drop what it renders.
                    while (stallClock < stalledFor - 0.25) {
                        schedulePatterns(engine)
                        engine.renderBlock(l, r)
                        framesRendered += DoughEngine.BLOCK_SIZE
                        meter.afterBlock(engine)
                        droppedBlocks++
                        stallClock += blockSeconds
                    }
                }
                try {
                    Thread.sleep(1L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } finally {
            ready = false
            engine.close()
        }
    }

    private fun schedulePatterns(engine: DoughEngine) {
        for (voice in patterns) {
            if (voice.stopped) continue
            val events = try {
                voice.eventsInBlock(framesRendered, DoughEngine.BLOCK_SIZE)
            } catch (t: Throwable) {
                voice.lastError = t
                continue
            }
            for (event in events) {
                try {
                    engine.evaluate(event)
                } catch (t: Throwable) {
                    lastError = t
                    lprintln("warning,audio", "dough refused $event: $t")
                }
            }
        }
    }

    private fun drain(engine: DoughEngine) {
        while (true) {
            val event = pending.poll() ?: return
            try {
                engine.evaluate(event)
            } catch (t: Throwable) {
                lastError = t
                lprintln("warning,audio", "dough refused $event: $t")
            }
        }
    }

    override fun close() {
        stopped = true
        thread?.join(2_000)
    }
}

/**
 * Per-group running mean square of a [DoughEngine]'s voices, one sample per voice per block.
 *
 * [afterBlock] belongs to the thread that renders the engine. It sums each group's voices' squared
 * output samples, folds that into a one-pole average with time constant [timeConstant], and every
 * few blocks publishes a snapshot that any thread may read through [published] and [publishedTotal].
 * A group whose level has decayed to nothing is forgotten. Reverb and delay are mixed after the
 * voices in dough, so they are not part of any group's level.
 */
class DoughVoiceMeter(sampleRate: Int, val timeConstant: Double = 0.04, private val publishEvery: Int = 4) {
    private val alpha = 1.0 - exp(-DoughEngine.BLOCK_SIZE / (sampleRate * timeConstant))
    private val frames = FloatArray(DoughEngine.MAX_VOICES * 3)
    private val meanSquare = HashMap<Int, Double>()
    private val thisBlock = HashMap<Int, Double>()
    private var total = 0.0
    private var blocks = 0

    /** Mean square per group, as of the last publish. */
    @Volatile
    var published: Map<Int, Double> = emptyMap()
        private set

    /** Mean square of every voice together, as of the last publish. */
    @Volatile
    var publishedTotal: Double = 0.0
        private set

    /** Fold the engine's current voices in. Call once after every [DoughEngine.renderBlock]. */
    fun afterBlock(engine: DoughEngine) {
        val n = engine.voiceFrames(frames)
        thisBlock.clear()
        var sum = 0.0
        for (v in 0 until n) {
            val group = frames[v * 3].toInt()
            val left = frames[v * 3 + 1].toDouble()
            val right = frames[v * 3 + 2].toDouble()
            val square = 0.5 * (left * left + right * right)
            if (!square.isFinite()) continue
            thisBlock[group] = (thisBlock[group] ?: 0.0) + square
            sum += square
        }
        for (group in thisBlock.keys) meanSquare.putIfAbsent(group, 0.0)
        val each = meanSquare.entries.iterator()
        while (each.hasNext()) {
            val entry = each.next()
            val next = entry.value + alpha * ((thisBlock[entry.key] ?: 0.0) - entry.value)
            if (next < 1e-12 && entry.key !in thisBlock) each.remove() else entry.setValue(next)
        }
        total += alpha * (sum - total)
        if (++blocks % publishEvery == 0) {
            published = HashMap(meanSquare)
            publishedTotal = total
        }
    }
}

/**
 * A dough path as the key/value event the module reads: `"/dough/play/s/saw/n/60"` is
 * `{dough=play, s=saw, n=60}`.
 *
 * Line breaks are allowed and anything after `//` on a line is dropped. Fails on an odd number of
 * segments rather than guessing which value is missing.
 */
fun doughPathEvent(path: String): Map<String, String> {
    val chunks = path
        .trim()
        .lines()
        .map { it.substringBefore("//") }
        .joinToString("")
        .split("/")
        .filter { it.isNotBlank() }

    require(chunks.size % 2 == 0) { "Expected key/value pairs in path: $path" }

    val out = linkedMapOf<String, String>()
    var i = 0
    while (i < chunks.size) {
        out[chunks[i].trim()] = chunks[i + 1].trim()
        i += 2
    }
    return out
}

/**
 * A pattern event as the dough event that plays it, for patterns written the Tidal/Strudel way:
 * the event's value names a note or a sound, and its annotations are dough parameters.
 *
 * A value that reads as a note ([patNoteNumber]: `60`, `c5`, `ef4`) becomes `note`, played on the
 * event's `s` (or `sound`), triangle by default as in Strudel; any other word is the sound itself, so
 * `pat("bd hh cp")` plays dough's own drums. `note`/`n` annotations win over the value and may be
 * note names too. `duration` is the event's length in seconds at [cps], times `sustain` if given.
 * Every other annotation (`gain`, `pan` from 0 left to 1 right, `lpf`, `room`, `speed`, ...) goes to
 * dough verbatim.
 */
fun doughPatternEvent(event: PatEvent<String>, cps: Double): Map<String, Any?> {
    val ann = event.annotations
    val out = linkedMapOf<String, Any?>("dough" to "play")
    val atom = event.value.trim()
    val atomNote = patNoteNumber(atom)
    if (atomNote != null) {
        out["s"] = ann["s"] ?: ann["sound"] ?: "triangle"
        out["note"] = atomNote.toString()
    } else {
        out["s"] = atom.ifEmpty { ann["s"] ?: ann["sound"] ?: "triangle" }
    }
    (ann["note"] ?: ann["n"])?.let { v -> out["note"] = patNoteNumber(v)?.toString() ?: v }
    val whole = event.extent
    val seconds = (whole.end - whole.start).toDouble() / cps
    out["duration"] = patFormatNumber(seconds * (ann["sustain"]?.toDoubleOrNull() ?: 1.0))
    for ((k, v) in ann) {
        if (k in out || k == "sound" || k == "n" || k == "sustain") continue
        out[k] = v
    }
    return out
}

/**
 * Where `dough.wasm` is: the checkout's copy when Field runs from the repository root, otherwise the
 * classpath resource, copied out to a temporary file once.
 */
fun doughWasmPath(): Path {
    val inTree = Paths.get("./src/jvmMain/resources/dough.wasm")
    if (Files.isRegularFile(inTree)) return inTree
    val resource = DoughEngine::class.java.getResourceAsStream("/dough.wasm")
        ?: error("dough.wasm is neither at $inTree nor on the classpath")
    val copy = Files.createTempFile("dough", ".wasm")
    resource.use { Files.copy(it, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    copy.toFile().deleteOnExit()
    return copy
}

private class BoxSoundState {
    val lastPlayed = HashMap<String, Double>()
}

private val soundStates = WeakHashMap<Box, BoxSoundState>()

/**
 * Play a dough path from this box, unless the same [key] played less than [minInterval] seconds ago.
 *
 * ```
 * if (justEntered) sound("/dough/play/note/60/duration/0.08/s/sine/gain/0.2/lpf/1200/release/0.05")
 * ```
 *
 * The path is dough's own (`note`/`n`, `s`/`sound`, `gain`, `lpf`, `release`, `verb`, `pan` where 0
 * is hard left, ...), so a path that plays in a browser running `dough.wasm` plays the same here.
 * [key] defaults to the path itself, so calling this every frame with the same path sounds at most
 * once per [minInterval]; give two different calls the same key to make them share the limit.
 * [force] ignores the limit. Returns whether it played.
 *
 * The event is tagged with this box's dough `group`, which is what lets [thisRMS] measure the box's
 * own voices; a path that sets its own `group` keeps it, and then is not counted in [thisRMS].
 * The first call in a session boots the engine, which takes a few hundred milliseconds on its own
 * thread.
 */
fun Box.sound(path: String, key: String = path, minInterval: Double = 0.125, force: Boolean = false): Boolean {
    val now = AnimationClock.nowSeconds()
    val state = synchronized(soundStates) { soundStates.getOrPut(this) { BoxSoundState() } }
    if (!force) {
        val previous = state.lastPlayed[key]
        if (previous != null && now - previous < minInterval) return false
    }
    DoughLive.shared().play(path, DoughLive.groupOf(this))
    state.lastPlayed[key] = now
    return true
}

/** Play a dough path from this box now, whatever played before: [sound] with `force = true`. */
fun Box.soundNow(path: String): Boolean = sound(path, force = true, minInterval = 0.0)

/**
 * How loud this box's own dough voices are right now, measured: about 1 for a sine at `gain/1`,
 * 0.2 for one at `gain/0.2`, 0 when nothing this box played is still sounding.
 *
 * A running RMS over roughly the last 40 ms of the voices [sound] started from this box, sampled
 * once per 128-frame block, so it rises within a few milliseconds of a note and falls with its
 * release. Excludes the shared reverb and delay. Reads 0 before this box has played anything.
 */
val Box.thisRMS: Double
    get() = DoughLive.sharedOrNull()?.level(DoughLive.groupOf(this)) ?: 0.0
