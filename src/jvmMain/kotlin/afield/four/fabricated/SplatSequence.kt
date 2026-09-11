@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.fabricated

import afield.Box
import afield.ToHTML
import afield.controlGroup
import afield.four.modules.CompressedSplatPly
import java.io.File
import java.util.Locale

/**
 * Playing a splat capture over time: the frame reader, the play head, and the box controls that
 * drive it.
 *
 * A splat sequence is a directory of per-frame `.compressed.ply` files, and the reason this file
 * exists separately from `GaussianSplatRenderer.kt` is that the interesting problem is on the disk
 * side rather than the GPU side. `SplatSequence` reads frames on demand into a bounded
 * most-recently-used cache, sized in frames rather than bytes, because a working session scrubs:
 * a 124-frame capture is 44 MB and would fit whole, a five-minute one will not, and a
 * forward-only reader would make the short case pleasant and the long case impossible. Nothing
 * prefetches, which is a decision and not an omission — read-ahead only pays during playback, and
 * doing it properly costs a thread and a drop policy.
 *
 * `SplatTransport` is the play head and holds no data at all: a frame index, a rate, and the
 * playing/looping state. Keeping it separate from the reader is what lets the same transport drive
 * a sequence on disk or a frame set already resident, and its source `fps` is independent of the
 * display rate, so a 12 fps capture shown at 60 does not become a 60 fps capture.
 * `Box.transportControls` wires it to a control group, and the `ToHTML` rendering puts the
 * play head in a box's output where it can be read at a glance.
 */

/**
 * A directory of `.compressed.ply` frames, readable in any order.
 *
 * Random access is the point: scrubbing jumps around, so a sequence that could only be read forwards
 * would be useless for working. Frames are read on demand and held in a bounded most-recently-used
 * cache, which is the shape that survives a shot longer than memory — a 124-frame capture is 44 MB
 * and would fit entirely, but a five-minute one will not.
 *
 * Nothing here prefetches. A scrub is a random jump, so read-ahead only helps during playback, and
 * doing it properly means a thread and a policy for what to drop; [prime] is the blunt version for
 * when you know the shot is small.
 */
class SplatSequence(
    val files: List<File>,
    /** How many decoded frames to keep. Each is roughly the file's size again as ints. */
    val cacheFrames: Int = 96
) {
    init {
        require(files.isNotEmpty()) { "a sequence needs at least one frame" }
        require(cacheFrames >= 1) { "cacheFrames must be at least 1" }
    }

    val frameCount: Int get() = files.size

    private val cache = LinkedHashMap<Int, CompressedSplatPly.RawSplats>()
    private var hits = 0
    private var misses = 0

    /** Cache hits and misses since construction, for deciding whether [cacheFrames] is right. */
    val stats: String get() = "$hits hit / $misses miss over $frameCount frames, ${cache.size} cached"

    /**
     * The payload for [frame], read if it is not already cached. Indices outside the sequence wrap,
     * so a caller doing arithmetic on slot offsets does not have to.
     */
    fun raw(frame: Int): CompressedSplatPly.RawSplats {
        val index = Math.floorMod(frame, files.size)
        cache[index]?.let {
            // Re-insert to make this the most recently used.
            cache.remove(index)
            cache[index] = it
            hits++
            return it
        }
        misses++
        val loaded = CompressedSplatPly.readRaw(files[index].absolutePath)
        cache[index] = loaded
        while (cache.size > cacheFrames) {
            val oldest = cache.keys.first()
            cache.remove(oldest)
        }
        return loaded
    }

    /** Read every frame now. Only sensible when the whole sequence fits in [cacheFrames]. */
    fun prime(): SplatSequence {
        require(frameCount <= cacheFrames) {
            "priming $frameCount frames into a $cacheFrames-frame cache would evict as it goes"
        }
        for (frame in 0 until frameCount) raw(frame)
        return this
    }

    /**
     * The largest total splat count any [window] consecutive frames can reach, which is what a
     * [GaussianSplatRenderer.streaming] cloud's capacity has to be.
     *
     * Reads only each compressed PLY header, so it does not evict useful decoded frames from the
     * playback cache. It is the true peak over the windows that can actually occur rather than the
     * sum of the [window] largest frames anywhere.
     */
    fun peakWindowSplats(window: Int): Int {
        require(window in 1..frameCount) { "window $window outside 1..$frameCount" }
        val counts = IntArray(frameCount) { CompressedSplatPly.rawCount(files[it].absolutePath) }
        var running = 0
        for (i in 0 until window) running += counts[i]
        var peak = running
        for (start in 1 until frameCount) {
            running -= counts[start - 1]
            running += counts[(start + window - 1) % frameCount]
            if (running > peak) peak = running
        }
        return peak
    }

    companion object {
        /** Every `.compressed.ply` in [directory], ordered by filename. */
        fun fromDirectory(directory: File, cacheFrames: Int = 96): SplatSequence {
            val found = (directory.listFiles { f: File -> f.name.endsWith(".compressed.ply") }
                ?: arrayOf()).sortedBy { it.name }
            require(found.isNotEmpty()) { "no .compressed.ply files under $directory" }
            return SplatSequence(found, cacheFrames)
        }
    }
}

/**
 * A play head over [frameCount] frames: play, pause, scrub, loop, and run backwards.
 *
 * Deliberately clock-driven rather than tick-counting, so it keeps real time whatever the display
 * rate does, and deliberately *fractional* internally, so scrubbing has sub-frame resolution while
 * [frame] stays integral.
 *
 * The property that makes it worth having a class: [advance] reports whether the resolved [frame]
 * actually **changed**. A scrub that moves the play head a third of a frame, or a display running at
 * 90 Hz over a 12 fps source, both leave [frame] alone most of the time — and re-decoding fifteen
 * files to write identical bytes is the single easiest way to waste this engine's budget.
 */
class SplatTransport(
    val frameCount: Int,
    /** Source frames per second. Independent of the display rate. */
    var fps: Double = 12.0
) : ToHTML {
    init {
        require(frameCount >= 1) { "frameCount must be at least 1" }
    }

    var playing: Boolean = true

    /**
     * When true, [advance] only services clock bookkeeping; a frame-set presenter moves time with
     * [commitPresented]. This keeps the public playhead identical to the complete asset set actually
     * shown instead of letting it run several frames ahead during an I/O underrun.
     */
    var presentationPaced: Boolean = false

    /** 1.0 is forward at [fps]; negative runs backwards; 0.0 is a pause that keeps [playing] true. */
    var speed: Double = 1.0

    /** Wrap at the ends. When false the play head clamps and [playing] is left alone. */
    var loop: Boolean = true

    /** Fractional play head. Assign to scrub; [frame] is what the renderer should show. */
    private var clockPosition = 0.0
    /** Increases on an explicit scrub/seek, not on ordinary wall-clock advancement. */
    var seekGeneration: Long = 0
        private set

    var position: Double
        get() = clockPosition
        set(value) {
            clockPosition = normalise(value)
            seekGeneration++
        }

    /** The frame [position] resolves to. */
    val frame: Int get() = Math.floorMod(position.toInt(), frameCount)

    /** [position] as 0..1 over the sequence, for a slider. */
    var normalized: Double
        get() = if (frameCount <= 1) 0.0 else position / frameCount
        set(value) { position = value * frameCount }

    private var lastNanos: Long = Long.MIN_VALUE
    private var lastFrame: Int = -1

    /**
     * Advance the play head to [nowNanos] and report whether [frame] changed.
     *
     * Call once per display frame and reupload only when it returns true. The first call establishes
     * the clock and reports true, so the initial frame gets uploaded.
     *
     * A gap longer than [maxCatchUpSeconds] is treated as a stall rather than as elapsed time — a
     * breakpoint, a shader recompile or a garbage-collection pause would otherwise fling the play
     * head forwards by however long you were stopped.
     */
    fun advance(nowNanos: Long, maxCatchUpSeconds: Double = 0.25): Boolean {
        if (lastNanos == Long.MIN_VALUE) {
            lastNanos = nowNanos
            lastFrame = frame
            return true
        }
        if (presentationPaced) {
            lastNanos = nowNanos
            return false
        }
        val elapsed = (nowNanos - lastNanos) / 1e9
        lastNanos = nowNanos
        if (playing && elapsed > 0) {
            clockPosition = normalise(clockPosition + elapsed.coerceAtMost(maxCatchUpSeconds) * fps * speed)
        }
        val now = frame
        if (now == lastFrame) return false
        lastFrame = now
        return true
    }

    /** Jump to [target] frames, fractional. Reports whether [frame] changed. */
    fun seek(target: Double): Boolean {
        position = target
        val now = frame
        if (now == lastFrame) return false
        lastFrame = now
        return true
    }

    /** Jump by [delta] frames, fractional — the scrub-by-a-drag case. */
    fun scrubBy(delta: Double): Boolean = seek(position + delta)

    /** Force the next [advance] to report a change, after something else invalidated the upload. */
    fun invalidate() {
        lastFrame = -1
    }

    /** Move the playhead after one complete externally-paced frame set has committed. Not a seek. */
    fun commitPresented(frame: Int, nowNanos: Long) {
        require(frame in 0 until frameCount)
        clockPosition = frame.toDouble()
        lastFrame = frame
        lastNanos = nowNanos
    }

    /** Drop the clock, so the next [advance] does not count the time since the last one. */
    fun resetClock() {
        lastNanos = Long.MIN_VALUE
    }

    private fun normalise(value: Double): Double {
        if (!value.isFinite()) return 0.0
        if (loop) {
            val span = frameCount.toDouble()
            var wrapped = value % span
            if (wrapped < 0) wrapped += span
            return wrapped
        }
        return value.coerceIn(0.0, (frameCount - 1).toDouble())
    }

    override fun toString(): String =
        "SplatTransport(frame=$frame/${frameCount}, position=%.2f, fps=$fps, speed=$speed, ".format(position) +
            "playing=$playing, loop=$loop)"

    /**
     * A play head you can read at a glance: the track, where the head sits on it, the frame count,
     * and the state that decides what happens next.
     *
     * This is what `transport.h` returns and what the value visualizer shows when you inspect the
     * variable. It is a *snapshot* — nothing here polls. For a readout that follows the head while
     * it plays, use [Box.transportControls], whose meter is sampled on Field's own meter thread.
     *
     * Palette and idiom follow the other visualizers: inline SVG, `#ccc` on `#666`, monospace, no
     * stylesheet to install and nothing that breaks if the output panel is resized.
     */
    override fun toHTML(): String {
        val width = 330
        val frames = frameCount.coerceAtLeast(1)
        val fraction = if (frames <= 1) 0.0 else (position / frames).coerceIn(0.0, 1.0)
        val left = 2.0
        val right = width - 2.0
        val head = left + fraction * (right - left)
        val moving = playing && speed != 0.0

        // Green while it is actually advancing, grey when parked: the two states a glance should
        // separate. Direction gets its own glyph rather than a minus sign on the speed.
        val fill = if (moving) "#4a9d7f" else "#5a5a5a"
        val state = when {
            !playing -> "paused"
            speed == 0.0 -> "held"
            speed < 0 -> "reverse"
            else -> "playing"
        }
        val glyph = when {
            !playing || speed == 0.0 -> "&#9633;"      // hollow square
            speed < 0 -> "&#9664;"                      // left triangle
            else -> "&#9654;"                           // right triangle
        }
        fun n(value: Double) = String.format(Locale.ROOT, "%.1f", value)
        // Java's %g keeps trailing zeros, so 12.0 came out "12.0fps". An integral rate should read
        // as "12fps" -- this is a readout, and the ".0" is noise on the common case.
        fun rate() =
            if (fps == Math.floor(fps) && fps.isFinite()) fps.toLong().toString()
            else String.format(Locale.ROOT, "%.2f", fps)
        fun rateSpeed() = String.format(Locale.ROOT, "%+.2f", speed)

        val ticks = StringBuilder()
        // A tick every tenth, so a glance reads position as a proportion and not just a pixel.
        for (i in 1 until 10) {
            val x = left + (right - left) * i / 10.0
            ticks.append("<line x1='${n(x)}' y1='20' x2='${n(x)}' y2='24' stroke='#3a3a3a' stroke-width='1'/>")
        }

        return buildString {
            append("<div style=\"font-family: 'Iosevka Web', monospace; font-size: 12px; ")
            append("color: #ccc; line-height: 1.4; display: inline-block;\">")
            append("<svg width='$width' height='34' viewBox='0 0 $width 34' ")
            append("xmlns='http://www.w3.org/2000/svg'>")
            // Row one: which frame, and what it is doing.
            append("<text x='2' y='11' fill='#ccc' font-size='11' font-family='monospace'>")
            append("frame $frame / $frameCount</text>")
            append("<text x='${width - 2}' y='11' fill='#666' font-size='11' font-family='monospace' ")
            append("text-anchor='end'>$glyph $state &#183; ${rate()}fps &#183; ${rateSpeed()}x")
            append(if (loop) " &#183; loop" else " &#183; clamp")
            append("</text>")
            // Row two: the track itself.
            append("<line x1='${n(left)}' y1='22' x2='${n(right)}' y2='22' stroke='#333' ")
            append("stroke-width='4' stroke-linecap='round'/>")
            append(ticks)
            if (head > left) {
                append("<line x1='${n(left)}' y1='22' x2='${n(head)}' y2='22' stroke='$fill' ")
                append("stroke-width='4' stroke-linecap='round'/>")
            }
            append("<circle cx='${n(head)}' cy='22' r='4.5' fill='#1b1b1b' stroke='#ccc' ")
            append("stroke-width='1.5'/>")
            append("<text x='2' y='33' fill='#555' font-size='8' font-family='monospace'>")
            append("position ${n(position)}</text>")
            append("</svg></div>")
        }
    }
}

/**
 * A transport you can drive from an output area: scrub, speed, play/pause, loop and single steps.
 *
 * The split between the meter and the scrub slider is not redundancy, it is the shape the plumbing
 * forces and the shape you want anyway. Field's control callbacks run **browser to JVM** only, so a
 * slider cannot follow a play head that the JVM is advancing — drag it and it seeks, but leave it
 * and it stays where you left it. A [ControlGroupBuilder.meter] takes a `source` lambda that
 * Field's own `field-meter-sampler` thread polls, so the meter *does* follow. Read the meter, drive
 * the slider.
 *
 * Everything is live against [transport] the moment it moves; nothing here needs the box to re-run.
 * The one thing to know: the group is rebuilt when the box runs, and control values are cached by
 * label, so re-running restores the slider positions rather than resetting them.
 *
 * @param transport the play head to drive. Held by reference, so a later `transport.fps = …` is seen.
 * @param channel the output disclosure that receives the controls.
 */
fun Box.transportControls(transport: SplatTransport, channel: String = "transport") {
    val last = (transport.frameCount - 1).coerceAtLeast(1).toDouble()
    controlGroup(channel) {
        meter(
            "frame", min = 0.0, max = last,
            initialValue = transport.position.coerceIn(0.0, last),
            source = { transport.position.coerceIn(0.0, last) }
        )
        slider("scrub", min = 0.0, max = last, step = 1.0,
            initialValue = transport.position.coerceIn(0.0, last)) { transport.seek(it) }
        slider("speed", min = -2.0, max = 2.0, step = 0.05, initialValue = transport.speed) {
            transport.speed = it
        }
        checkbox("playing", transport.playing) { transport.playing = it }
        checkbox("loop", transport.loop) { transport.loop = it }
        button("step back") { if (it) transport.scrubBy(-1.0) }
        button("step forward") { if (it) transport.scrubBy(1.0) }
    }
}
