@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.fabricated

import afield.four.modules.CompressedSplatPly
import java.io.File

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
     * Reads the whole sequence, so call it once at setup. It is the true peak over the windows that
     * can actually occur rather than the sum of the [window] largest frames anywhere — that
     * over-allocates, harmlessly, and is what to use if reading everything up front is too slow.
     */
    fun peakWindowSplats(window: Int): Int {
        require(window in 1..frameCount) { "window $window outside 1..$frameCount" }
        val counts = IntArray(frameCount) { raw(it).count }
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
) {
    init {
        require(frameCount >= 1) { "frameCount must be at least 1" }
    }

    var playing: Boolean = true

    /** 1.0 is forward at [fps]; negative runs backwards; 0.0 is a pause that keeps [playing] true. */
    var speed: Double = 1.0

    /** Wrap at the ends. When false the play head clamps and [playing] is left alone. */
    var loop: Boolean = true

    /** Fractional play head. Assign to scrub; [frame] is what the renderer should show. */
    var position: Double = 0.0
        set(value) {
            field = normalise(value)
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
        val elapsed = (nowNanos - lastNanos) / 1e9
        lastNanos = nowNanos
        if (playing && elapsed > 0) {
            position += elapsed.coerceAtMost(maxCatchUpSeconds) * fps * speed
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
}
