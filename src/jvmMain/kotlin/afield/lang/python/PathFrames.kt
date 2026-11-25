/**
 * Baked animations of styled cubic Bézier paths, written by a Python box and drawn by Field.
 *
 * `fieldmanim` (in `python/`) runs a Manim scene, records every frame's geometry instead of
 * rendering it, and publishes it here; a Kotlin box turns any moment of it into [FLine]s. Covered
 * by `PathFramesTest`.
 */

package afield.lang.python

import afield.document.graph.Box
import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.math.linalg.vec
import afield.runtime.lifecycle.AnimationClock
import afield.ui.visualization.LinesFraming
import afield.ui.visualization.lines
import kotlin.math.floor

/** How often [PathFrames.draw] sends a frame that has not changed, in case the output was cleared. */
private const val REDRAW_SECONDS = 0.5

/**
 * An animation as a sequence of frames, each a list of filled or stroked cubic Bézier paths, baked
 * by Python and drawn by Kotlin at whatever moment Kotlin asks for.
 *
 * The Python side, `fieldmanim.bake`, plays a Manim scene without rendering it: at each frame it
 * records the Bézier control points and colours that Manim's Cairo camera would have painted, and
 * [publish]es the lot. Field then owns time. A box can scrub, loop or hold the animation, and draw
 * it next to anything else Field draws, without calling back into Python.
 *
 * ```kotlin
 * val scene = NumpyShared.pathFrames("intro")
 * val t0 = AnimationClock.nowSeconds()
 * loop { scene.draw(thisBox, AnimationClock.nowSeconds() - t0, loop = true) }
 * ```
 *
 * Coordinates are Manim's: the origin at the centre of a frame [unitWidth] x [unitHeight] units,
 * y up. [frame] and [at] map that onto `width` x `height` pixels, y down, and scale stroke widths
 * the way Manim's Cairo camera does (a `stroke_width` of 4 is 0.04 units).
 *
 * Frames that look the same, such as every frame of a `wait()`, share one *snapshot*, so a long
 * pause costs one entry in [publish]'s `frameSnapshots` per frame and nothing more.
 *
 * Each `play()` or `wait()` in the scene is a [Beat]. A [player] steps through them, which is how a
 * slide's cues play a scene one animation at a time.
 */
class PathFrames internal constructor(val name: String) {
    private val lock = Any()

    @Volatile
    private var baked: Baked = Baked.EMPTY

    /** Bumped by every [publish]. */
    @Volatile
    var version: Long = 0
        private set

    private val cache = object : LinkedHashMap<CacheKey, List<FLine>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, List<FLine>>?) = size > 512
    }

    /** Frames per second the animation was baked at. */
    val fps: Double get() = baked.fps

    /** The number of frames. */
    val frameCount: Int get() = baked.frameSnapshots.size

    /** The number of distinct snapshots that the frames refer to. */
    val snapshotCount: Int get() = baked.snapshotPaths.size

    /** Length in seconds: [frameCount] / [fps]. */
    val duration: Double get() = if (baked.fps > 0) frameCount / baked.fps else 0.0

    /** The width of the frame in the animation's own units (Manim's `frame_width`, 14.22 by default). */
    val unitWidth: Double get() = baked.unitWidth

    /** The height of the frame in the animation's own units (Manim's `frame_height`, 8 by default). */
    val unitHeight: Double get() = baked.unitHeight

    /** One `play()` or `wait()` of the scene, in order, with when it starts and ends in seconds. */
    data class Beat(val index: Int, val name: String, val start: Double, val end: Double)

    /** The scene's `play()` and `wait()` calls, in order. Empty for a scene that only adds things. */
    val beats: List<Beat> get() = baked.beats

    /** When the first [n] beats have played: `0.0` for none, the [duration] for all of them. */
    fun beatEnd(n: Int): Double = if (n <= 0) 0.0 else beats.getOrNull(n - 1)?.end ?: duration

    /** A [PathFramesPlayer] for this animation, starting before its first beat. */
    fun player(): PathFramesPlayer = PathFramesPlayer(this)

    /**
     * Replace the animation. Python calls this once per bake, with numpy arrays that JEP passes as
     * Java arrays.
     *
     * @param fps frames per second
     * @param unitWidth width of the frame in the animation's units, centred on the origin
     * @param unitHeight height of the frame in the animation's units, centred on the origin
     * @param background RGBA in `0..1` of the frame behind everything; alpha 0 draws no background
     * @param points every control point, `x, y` pairs: per subpath a start point, then three per cubic
     * @param subpaths per subpath, `pointCount, closed` pairs, `closed` 1 or 0
     * @param styles per drawn path, `r, g, b, a, strokeWidth, filled`; `filled` 1 fills it, 0 strokes
     *   it at `strokeWidth` (in Manim's stroke units)
     * @param pathSubpaths per drawn path, how many of the next [subpaths] belong to it
     * @param snapshotPaths per snapshot, how many of the next drawn paths belong to it
     * @param frameSnapshots per frame, the snapshot it shows
     * @param beatStarts per beat (a `play()` or `wait()`), the frame it starts on
     * @param beatNames the beats' names, one per line
     */
    @JvmOverloads
    fun publish(
        fps: Double,
        unitWidth: Double,
        unitHeight: Double,
        background: FloatArray,
        points: FloatArray,
        subpaths: IntArray,
        styles: FloatArray,
        pathSubpaths: IntArray,
        snapshotPaths: IntArray,
        frameSnapshots: IntArray,
        beatStarts: IntArray = IntArray(0),
        beatNames: String = "",
    ) {
        val b = Baked(fps, unitWidth, unitHeight, background, points, subpaths, styles, pathSubpaths, snapshotPaths, frameSnapshots, beatStarts, beatNames)
        synchronized(lock) {
            baked = b
            cache.clear()
            version++
        }
    }

    /**
     * Frame [index] (clamped to the animation) as [FLine]s on a [width] x [height] pixel canvas,
     * background first when it has one. The list is cached and shared, so don't mutate it.
     */
    @JvmOverloads
    fun frame(index: Int, width: Double = 960.0, height: Double = 540.0, background: Boolean = true): List<FLine> {
        val b = baked
        if (b.frameSnapshots.isEmpty()) return emptyList()
        val snapshot = b.frameSnapshots[index.coerceIn(0, b.frameSnapshots.size - 1)]
        val key = CacheKey(snapshot, width, height, background)
        synchronized(lock) {
            if (b === baked) cache[key]?.let { return it }
        }
        val built = b.build(snapshot, width, height, background)
        synchronized(lock) {
            if (b === baked) cache[key] = built
        }
        return built
    }

    /**
     * The frame showing at [seconds] from the start, held at the last frame after the end, or
     * wrapped round when [loop] is true. See [frame].
     */
    @JvmOverloads
    fun at(seconds: Double, width: Double = 960.0, height: Double = 540.0, background: Boolean = true, loop: Boolean = false): List<FLine> {
        val n = frameCount
        if (n == 0) return emptyList()
        var i = floor(seconds * fps).toInt()
        if (loop) i = Math.floorMod(i, n)
        return frame(i, width, height, background)
    }

    /**
     * Draw the frame at [seconds] (the last one by default; wrapped round when [loop] is true) in
     * [box]'s output on [channel], as SVG on a [width] x [height] canvas, framed to that canvas.
     *
     * Called every frame from a `loop { }`, it sends a drawing only when the frame has changed, and
     * otherwise twice a second, so a scene that is holding still costs the connection nothing and
     * two figures on one slide do not crowd each other out. `fieldmanim.Baked.show` calls this from
     * Python.
     */
    @JvmOverloads
    fun draw(box: Box, seconds: Double = duration, channel: String = "manim", width: Double = 960.0, height: Double = 540.0, loop: Boolean = false) {
        val shown = at(seconds, width, height, loop = loop)
        val now = System.nanoTime() / 1e9
        synchronized(drawn) {
            val byChannel = drawn.getOrPut(box) { HashMap() }
            val last = byChannel[channel]
            if (last != null && last.first === shown && now - last.second < REDRAW_SECONDS) return
            byChannel[channel] = shown to now
        }
        box.lines(shown, channel = channel, w = width, h = height, framing = framing(width, height))
    }

    /** What [draw] last sent to each box and channel, and when; frames are cached, so `===` means unchanged. */
    private val drawn = java.util.WeakHashMap<Box, MutableMap<String, Pair<List<FLine>, Double>>>()

    /** A framing that keeps the whole [width] x [height] canvas in view, for `lines(..., framing = )`. */
    fun framing(width: Double = 960.0, height: Double = 540.0): LinesFraming = LinesFraming.Fixed(0.0, 0.0, width, height)

    override fun toString() =
        "PathFrames($name, $frameCount frames at ${fps.toInt()} fps, ${beats.size} beats, $snapshotCount snapshots, version $version)"

    private data class CacheKey(val snapshot: Int, val width: Double, val height: Double, val background: Boolean)

    private class Baked(
        val fps: Double,
        val unitWidth: Double,
        val unitHeight: Double,
        val background: FloatArray,
        val points: FloatArray,
        val subpaths: IntArray,
        val styles: FloatArray,
        val pathSubpaths: IntArray,
        val snapshotPaths: IntArray,
        val frameSnapshots: IntArray,
        beatStarts: IntArray,
        beatNames: String,
    ) {
        val beats: List<Beat>

        /** Index of each subpath's first point, each path's first subpath and each snapshot's first path. */
        val subpathPointStart: IntArray
        val pathSubpathStart: IntArray
        val snapshotPathStart: IntArray

        init {
            require(fps > 0 || frameSnapshots.isEmpty()) { "fps must be positive, not $fps" }
            require(background.size == 4) { "background is RGBA, 4 floats, not ${background.size}" }
            require(points.size % 2 == 0) { "points are x, y pairs; got ${points.size} floats" }
            require(subpaths.size % 2 == 0) { "subpaths are (pointCount, closed) pairs; got ${subpaths.size} ints" }
            val nSub = subpaths.size / 2
            require(styles.size == pathSubpaths.size * STYLE) {
                "styles has ${styles.size} floats for ${pathSubpaths.size} paths; expected $STYLE per path"
            }
            subpathPointStart = prefix(IntArray(nSub) { subpaths[2 * it] })
            pathSubpathStart = prefix(pathSubpaths)
            snapshotPathStart = prefix(snapshotPaths)
            val totalPoints = if (nSub == 0) 0 else subpathPointStart[nSub - 1] + subpaths[2 * (nSub - 1)]
            require(totalPoints == points.size / 2) { "subpaths account for $totalPoints points, but ${points.size / 2} were given" }
            for (i in 0 until nSub) {
                val n = subpaths[2 * i]
                require(n >= 1 && (n - 1) % 3 == 0) { "subpath $i has $n points; a subpath is a start point and 3 per cubic" }
            }
            val usedSub = pathSubpaths.sum()
            require(usedSub == nSub) { "paths account for $usedSub subpaths, but $nSub were given" }
            val usedPaths = snapshotPaths.sum()
            require(usedPaths == pathSubpaths.size) { "snapshots account for $usedPaths paths, but ${pathSubpaths.size} were given" }
            for (s in frameSnapshots) require(s in snapshotPaths.indices) { "frame refers to snapshot $s of ${snapshotPaths.size}" }
            val names = if (beatStarts.isEmpty()) emptyList() else beatNames.split('\n')
            require(names.size == beatStarts.size) { "${beatStarts.size} beats but ${names.size} beat names" }
            for (i in beatStarts.indices) {
                require(beatStarts[i] in 0..frameSnapshots.size && (i == 0 || beatStarts[i] >= beatStarts[i - 1])) {
                    "beat $i starts at frame ${beatStarts[i]}; beats start in order, within the ${frameSnapshots.size} frames"
                }
            }
            beats = beatStarts.indices.map { i ->
                val end = if (i + 1 < beatStarts.size) beatStarts[i + 1] else frameSnapshots.size
                Beat(i, names[i], beatStarts[i] / fps, end / fps)
            }
        }

        fun build(snapshot: Int, width: Double, height: Double, withBackground: Boolean): List<FLine> {
            val sx = width / unitWidth
            val sy = height / unitHeight
            val out = ArrayList<FLine>(snapshotPaths[snapshot] + 1)
            if (withBackground && background[3] > 0f) {
                out += FLine().moveTo(0, 0).lineTo(width, 0).lineTo(width, height).lineTo(0, height).close().also {
                    it.color = vec(background[0], background[1], background[2], background[3])
                    it.filled = true
                    it.stroked = false
                }
            }
            val firstPath = snapshotPathStart[snapshot]
            for (p in firstPath until firstPath + snapshotPaths[snapshot]) {
                val f = FLine()
                val firstSub = pathSubpathStart[p]
                for (s in firstSub until firstSub + pathSubpaths[p]) {
                    var k = subpathPointStart[s]
                    val n = subpaths[2 * s]
                    fun x(i: Int) = (points[2 * i] + unitWidth / 2) * sx
                    fun y(i: Int) = (unitHeight / 2 - points[2 * i + 1]) * sy
                    f.moveTo(x(k), y(k))
                    repeat((n - 1) / 3) {
                        f.cubicTo(x(k + 1), y(k + 1), 0, x(k + 2), y(k + 2), 0, x(k + 3), y(k + 3), 0)
                        k += 3
                    }
                    if (subpaths[2 * s + 1] != 0) f.close()
                }
                val st = STYLE * p
                f.color = vec(styles[st], styles[st + 1], styles[st + 2], styles[st + 3])
                if (styles[st + 5] != 0f) {
                    f.filled = true
                    f.stroked = false
                } else {
                    f.thickness = styles[st + 4] * MANIM_LINE_WIDTH * sx
                }
                out += f
            }
            return out
        }

        companion object {
            const val STYLE = 6

            /** Manim's Cairo camera draws `stroke_width` w at w * 0.01 frame units (`cairo_line_width_multiple`). */
            const val MANIM_LINE_WIDTH = 0.01

            val EMPTY = Baked(30.0, 14.222222, 8.0, FloatArray(4), FloatArray(0), IntArray(0), FloatArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0), "")

            private fun prefix(counts: IntArray): IntArray {
                val out = IntArray(counts.size)
                var acc = 0
                for (i in counts.indices) {
                    out[i] = acc
                    acc += counts[i]
                }
                return out
            }
        }
    }
}

/**
 * Plays a [PathFrames] a beat at a time, for a figure on a slide.
 *
 * [toBeat] with the next beat plays it at the scene's own speed; any other beat, earlier or
 * further on, is shown at once, as a slide that is gone back to shows its figure as it was. Drive it
 * from a slider in a `controlGroup`, and a talk's `control(figure, "beat", n)` and cues step
 * through the scene:
 *
 * ```kotlin
 * val scene = NumpyShared.pathFrames("intro")
 * val play = scene.player()
 * controlGroup {
 *     slider("beat", 0.0, scene.beats.size.toDouble(), step = 1.0, initialValue = 0.0) { play.toBeat(it.toInt()) }
 * }
 * loop { play.draw(thisBox) }
 * ```
 */
class PathFramesPlayer internal constructor(val frames: PathFrames) {
    @Volatile
    private var t = 0.0

    @Volatile
    private var goal = 0.0
    private var last = Double.NaN

    /** How many beats have played, or are playing: 0 before the first, `frames.beats.size` after the last. */
    @Volatile
    var beat: Int = 0
        private set

    /** Play the next beat, or show the scene after the first [n] beats when [n] is any other. */
    fun toBeat(n: Int) {
        val k = n.coerceIn(0, frames.beats.size)
        val end = frames.beatEnd(k)
        if (k == beat + 1 && t <= end) {
            goal = end
        } else {
            t = end
            goal = end
        }
        beat = k
    }

    /**
     * The scene time, advanced by the time since the last call toward the end of the current beat.
     * A gap of more than a quarter of a second, while the figure was off screen, counts as a quarter.
     */
    fun now(): Double {
        val clock = AnimationClock.nowSeconds()
        if (!last.isNaN() && t < goal) t = minOf(goal, t + (clock - last).coerceIn(0.0, 0.25))
        last = clock
        return t
    }

    /** Draw the frame at [now] in [box]'s output, as [PathFrames.draw] does. */
    @JvmOverloads
    fun draw(box: Box, channel: String = "manim", width: Double = 960.0, height: Double = 540.0) =
        frames.draw(box, now(), channel, width, height)

    /** The frame at [now], as [PathFrames.at] draws it. */
    @JvmOverloads
    fun lines(width: Double = 960.0, height: Double = 540.0, background: Boolean = true): List<FLine> =
        frames.at(now(), width, height, background)

    override fun toString() = "PathFramesPlayer(${frames.name}, beat $beat of ${frames.beats.size}, ${"%.2f".format(t)} s)"
}
