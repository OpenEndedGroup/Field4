@file:afield.runtime.completion.CompletionCategory("Simulation")

package afield.animation.motion

import afield.app.protocol.Rect
import afield.audio.synth.sound
import afield.audio.synth.thisRMS
import afield.document.graph.Box
import afield.document.graph.registerStopHook
import afield.runtime.execution.begin
import afield.runtime.lifecycle.AnimationClock
import afield.runtime.lifecycle.Frames
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.loop
import afield.ui.output.displayOpen
import afield.ui.output.outputRequests
import kotlinx.serialization.json.JsonPrimitive
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The canvas as a score: boxes are the events, a playhead box sweeps across them, and each box's
 * `score { }` runs every frame the playhead is over it.
 *
 * A box arms itself with [score]; the lead box starts the clock with [playScore], which moves the
 * box named `playhead` across a horizontal span of the canvas once per `loop` seconds and calls every
 * armed box whose frame the playhead crosses. Inside `score { }` the receiver is a [ScoreFrame]: how
 * far through the box the playhead is ([ScoreFrame.localProgress], [ScoreFrame.localT]), whether it
 * just arrived ([ScoreFrame.justEntered]), a [ScoreFrame.draw] list that the lead box's output panel
 * shows as a canvas, dough sound ([ScoreFrame.sound]) and the box's measured level
 * ([ScoreFrame.thisRMS]), and the box's own frame ([ScoreFrame.box]), which it may move.
 *
 * This is Field's time slider (`templates/timeSlider`: a line box that starts the boxes it passes
 * over) with a clock and per-frame calls instead of launches, so a box's code is compiled once, when
 * it is run, rather than every time the playhead reaches it. The clock is [AnimationClock], so a
 * video recording of a score plays at the right speed.
 */

private val armed = WeakHashMap<Box, ScoreBehaviour>()

/** What [score] arms: [body], called with a fresh [ScoreFrame] each frame the playhead is over [box]. */
class ScoreBehaviour internal constructor(val box: Box, internal val body: ScoreFrame.() -> Unit) {
    internal var wasActive = false
    internal val subdivisions = HashMap<String, Long>()
    internal var lastError: String? = null

    /** False once the box has been stopped or has armed a newer behaviour. */
    val isArmed: Boolean get() = synchronized(armed) { armed[box] === this }

    override fun toString() = "score behaviour of '${box.name}' (${if (isArmed) "armed" else "disarmed"})"
}

/**
 * Arm this box: [body] runs every frame that a running [playScore] has its playhead over this box's
 * frame.
 *
 * ```
 * score {
 *     val c = boxPoint(0.5, 0.5)
 *     draw.ring(c, 12 + thisRMS * 18 + tri01(localT * 2) * 8, color = "rgba(255,255,255,0.9)", width = 2.0)
 *     if (justEntered) sound("/dough/play/note/60/duration/0.08/s/sine/gain/0.2/lpf/1200/release/0.05")
 * }
 * ```
 *
 * Running the box again replaces the behaviour; stopping the box disarms it. The body is called
 * on the main loop from the lead box's animation, with this box as the box in context, so `print`
 * and `display` inside it go to this box. Anything declared above `score { }` lives as long as the
 * behaviour, which is where state that must last between frames belongs (the demo's `state`). An
 * exception is shown as an error on this box, once per distinct message, and the score carries on.
 */
fun Box.score(body: ScoreFrame.() -> Unit): ScoreBehaviour {
    val behaviour = ScoreBehaviour(this, body)
    synchronized(armed) { armed[this] = behaviour }
    registerStopHook(Runnable {
        synchronized(armed) { if (armed[this] === behaviour) armed.remove(this) }
        if (behaviour.wasActive) tint = ""
    })
    return behaviour
}

/** Every armed box in [owner]'s document. */
private fun armedIn(owner: Box): List<ScoreBehaviour> = synchronized(armed) {
    armed.values.filter { it.box.isInsideInitialized() && owner.isInsideInitialized() && it.box.inside === owner.inside }
}

/**
 * The clock of a running score: where the playhead is, and the span and loop it sweeps.
 *
 * Made by [playScore]. [paused] holds the playhead where it is (boxes under it keep being called,
 * with the clock stopped), and [loopDuration] may be changed while it runs.
 */
class ScoreTransport internal constructor(
    val owner: Box,
    var loopDuration: Double,
    /** The canvas x span the playhead crosses once per loop. */
    val x: ClosedFloatingPointRange<Double>,
    /** The canvas y span the output panel shows, and the height the playhead box covers. */
    val y: ClosedFloatingPointRange<Double>,
    /** Output canvas width in pixels. */
    val width: Int,
    /** Output canvas height in pixels. x and y scale separately, as in a score whose time axis is long. */
    val height: Int,
    private val playheadName: String?,
    /** Whether a box is tinted ([Box.tint]) while the playhead is over it. */
    val tintActive: Boolean = true,
) {
    /** Hold the clock. */
    var paused = false

    /** Seconds of score played, across loops. */
    var transportT = 0.0
        private set

    /** Frames stepped. */
    var frame = 0
        private set

    /** The playhead's canvas x. */
    var playheadX = x.start
        private set

    internal var lastFrameJson = "{\"w\":$width,\"h\":$height,\"calls\":[]}"
    private var lastNow = Double.NaN

    private val scoreWidth get() = x.endInclusive - x.start

    internal fun step() {
        val now = AnimationClock.nowSeconds()
        val dt = if (lastNow.isNaN()) 0.0 else min(0.05, max(0.0, now - lastNow))
        lastNow = now
        frame++
        if (!paused) transportT += dt
        val loop = max(1e-3, loopDuration)
        val loopIndex = floor(transportT / loop).toLong()
        val t = transportT - loopIndex * loop
        playheadX = x.start + scoreWidth * (t / loop)
        movePlayhead()

        val draw = ScoreDraw()
        for (b in armedIn(owner)) {
            val bounds = b.box.frame
            val active = playheadX >= bounds.x && playheadX <= bounds.x + bounds.w
            val entered = active && !b.wasActive
            val exited = !active && b.wasActive
            b.wasActive = active
            if (tintActive && entered) b.box.tint = playingTint(b.box)
            if (tintActive && exited) b.box.tint = ""
            if (!active && !exited) continue
            val localProgress = ((playheadX - bounds.x) / max(1.0, bounds.w)).coerceIn(0.0, 1.0)
            val f = ScoreFrame(
                this, b, draw, t = t, dt = dt, transportT = transportT, loopIndex = loopIndex,
                bounds = bounds, localProgress = localProgress,
                boxDuration = bounds.w / max(1.0, scoreWidth) * loop,
                justEntered = entered, justExited = exited,
            )
            try {
                Box.withBoxContextNow(b.box) { b.body(f) }
                b.lastError = null
            } catch (t: Throwable) {
                val message = t.message ?: t.toString()
                if (message != b.lastError) {
                    b.lastError = message
                    b.box.displayError("score { } threw: $message")
                }
            }
            if (exited) b.subdivisions.clear()
        }
        lastFrameJson = draw.toJson(width, height, t, loop)
    }

    /** Untint every box this score lit, as it stops. */
    internal fun clearTints() {
        for (b in armedIn(owner)) if (b.wasActive) {
            b.box.tint = ""
            b.wasActive = false
        }
    }

    private fun movePlayhead() {
        val name = playheadName ?: return
        val playhead = owner.root.children.firstOrNull { it.name == name } ?: return
        val f = playhead.frame
        val target = Rect(playheadX, y.start, f.w, y.endInclusive - y.start)
        if (abs(f.x - target.x) > 0.25 || f.y != target.y || f.h != target.h) playhead.frame = target
    }

    override fun toString() = "score: %.2fs of %.2fs, playhead at x %.0f".format(transportT % loopDuration, loopDuration, playheadX)
}

/**
 * Start the score: sweep the box named [playhead] across [x] once every [loop] seconds, call every
 * armed box ([score]) it crosses, and show what they draw in this box's output [channel].
 *
 * ```
 * playScore(loop = 6.0, x = 0.0..1600.0, y = 100.0..700.0)
 * ```
 *
 * Make it the box's last expression: it returns the animation, and stopping the box stops the score.
 * The output is a dark [width] by [height] canvas showing the canvas region [x] by [y], so
 * [ScoreFrame.boxPoint] lands a box's drawing where the box sits in the score. Every box whose frame
 * lies in the score and that has not been run yet is run once at the start, the way Field's time
 * slider runs the boxes it crosses, so running this one box plays the whole score. The playhead box,
 * if there is one, is styled as an orange line and moved every frame; without one the score still
 * plays. While the playhead is over a box the box is tinted ([Box.tint]) in a hue of its own, as the
 * demo lights its active boxes; pass `tint = false` to leave boxes as they are.
 */
fun Box.playScore(
    loop: Double = 4.0,
    x: ClosedFloatingPointRange<Double> = 0.0..1600.0,
    y: ClosedFloatingPointRange<Double> = 0.0..600.0,
    width: Int = 520,
    height: Int = 360,
    playhead: String? = "playhead",
    channel: String = "score",
    tint: Boolean = true,
): Frames {
    require(x.endInclusive > x.start && y.endInclusive > y.start) { "playScore needs a non-empty x and y span" }
    val transport = ScoreTransport(this, loop, x, y, width, height, playhead, tint)
    val binding = outputRequests("score-$channel") { JsonPrimitive(transport.lastFrameJson) }
    display(
        channel,
        """<iframe src="/score_view.html?binding=${binding.id}" """ +
            """style="border:0;display:block;background:#0a0a0a;width:${width}px;height:${height}px"></iframe>""",
    )
    displayOpen(channel)

    playhead?.let { name ->
        root.children.firstOrNull { it.name == name }?.let { p ->
            if (p.getPropertyRaw("customization") != PLAYHEAD_CUSTOMIZATION) p.setPropertyRaw("customization", PLAYHEAD_CUSTOMIZATION)
            p.frame = Rect(x.start, y.start, 12.0, y.endInclusive - y.start)
        }
    }
    // Like the time slider, which runs whatever it crosses: every box in the score that has not been
    // run yet is run once now, so opening a score and running this box plays all of it.
    val span = Rect(x.start, y.start, x.endInclusive - x.start, y.endInclusive - y.start)
    val waiting = synchronized(armed) {
        root.children.filter { it !== this && it.name != playhead && armed[it] == null && it.frame.intersects(span) }
    }
    waiting.forEach { it.begin() }

    return loop { transport.step() }.end { transport.clearTints() }
}

/** A box's playing tint: a hue fixed by its id, as the madp score demo gives each box its own. */
internal fun playingTint(box: Box): String = "hsla(${Math.floorMod(box.id.hashCode() * 57, 360)}, 80%, 56%, 0.22)"

private val PLAYHEAD_CUSTOMIZATION = """
this.style.border = "0px solid transparent"
this.style.borderLeft = "2px solid #ffb86c"
this.style.background = "linear-gradient(90deg, rgba(255,184,108,0.10), transparent)"
this.style.setProperty("--box-border", "0")
this.style.color = "#ffb86c"
this.lock_w = true
this.lock_h = true
""".trimIndent()

/**
 * One frame of one armed box: the receiver of `score { }`.
 *
 * Positions are canvas coordinates, the same numbers as a box's `frame`; [fromScore] and [boxPoint]
 * turn them into output-canvas pixels for [draw]. Times are in seconds.
 */
class ScoreFrame internal constructor(
    private val transport: ScoreTransport,
    private val behaviour: ScoreBehaviour,
    /** The draw list every box under the playhead adds to this frame. */
    val draw: ScoreDraw,
    /** Seconds into the current loop. */
    val t: Double,
    /** Seconds since the last frame, at most 0.05. */
    val dt: Double,
    /** Seconds played, across loops. */
    val transportT: Double,
    /** Which loop this is, from 0. */
    val loopIndex: Long,
    /** This box's frame, in canvas coordinates. */
    val bounds: Rect,
    /** How far across this box the playhead is, 0 at its left edge and 1 at its right. */
    val localProgress: Double,
    /** Seconds the playhead takes to cross this box. */
    val boxDuration: Double,
    /** True on the first frame the playhead is over this box. */
    val justEntered: Boolean,
    /** True on the one extra call made the frame after the playhead leaves this box. */
    val justExited: Boolean,
) {
    private val owner: Box get() = behaviour.box

    /** Seconds since the playhead reached this box: [localProgress] times [boxDuration]. */
    val localT: Double get() = localProgress * boxDuration

    /** Length of a loop, in seconds. */
    val loopDuration: Double get() = transport.loopDuration

    /** How far through the loop the playhead is, 0 to 1. */
    val progress: Double get() = t / loopDuration

    /** Frames the score has stepped. */
    val frame: Int get() = transport.frame

    /** The playhead's canvas x. */
    val playheadX: Double get() = transport.playheadX

    /** The score's canvas span. */
    val scoreLeft: Double get() = transport.x.start
    val scoreTop: Double get() = transport.y.start
    val scoreRight: Double get() = transport.x.endInclusive
    val scoreBottom: Double get() = transport.y.endInclusive
    val scoreWidth: Double get() = scoreRight - scoreLeft
    val scoreHeight: Double get() = scoreBottom - scoreTop

    /** The output canvas, in pixels. */
    val width: Double get() = transport.width.toDouble()
    val height: Double get() = transport.height.toDouble()

    /** Output pixels per canvas unit. */
    val scaleX: Double get() = width / scoreWidth
    val scaleY: Double get() = height / scoreHeight

    val left: Double get() = bounds.x
    val top: Double get() = bounds.y
    val right: Double get() = bounds.x + bounds.w
    val bottom: Double get() = bounds.y + bounds.h
    val cx: Double get() = bounds.x + bounds.w / 2
    val cy: Double get() = bounds.y + bounds.h / 2

    /** The output-canvas point for canvas coordinates ([x], [y]). */
    fun fromScore(x: Double, y: Double) = ScorePoint((x - scoreLeft) * scaleX, (y - scoreTop) * scaleY)

    /** The output-canvas point at ([u], [v]) across this box, 0..1 each way, (0, 0) its top left. */
    fun boxPoint(u: Double, v: Double) = fromScore(bounds.x + bounds.w * u, bounds.y + bounds.h * v)

    /** An output-canvas point, for [ScoreDraw.polyline]. */
    fun pt(x: Double, y: Double) = ScorePoint(x, y)

    /** This box's measured dough level: [afield.audio.synth.thisRMS]. */
    val thisRMS: Double get() = owner.thisRMS

    /** Play a dough path from this box; [afield.audio.synth.sound]. */
    fun sound(path: String, key: String = path, minInterval: Double = 0.125, force: Boolean = false) =
        owner.sound(path, key, minInterval, force)

    /** Play a dough path from this box regardless of the last time it played. */
    fun soundNow(path: String) = owner.sound(path, force = true, minInterval = 0.0)

    /**
     * True once each time the playhead crosses into another of [divisions] equal slices of this box,
     * the first slice included: a pulse of [divisions] per pass. Keep a separate count per [key].
     */
    fun onSubdivision(divisions: Int, key: String = "div:$divisions"): Boolean {
        val n = max(1, divisions)
        val step = floor(localProgress.coerceIn(0.0, 0.999999) * n).toLong()
        val token = loopIndex * 1_000_003L + step
        if (behaviour.subdivisions[key] == token) return false
        behaviour.subdivisions[key] = token
        return true
    }

    /** This box's frame on the canvas, which the box may move while it plays. */
    val box: ScoreBox get() = ScoreBox(owner)

    fun clamp(v: Double, lo: Double, hi: Double) = max(lo, min(hi, v))
    fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    /** A triangle wave of period 1 between -1 and 1. */
    fun triWave(x: Double): Double {
        val p = x - floor(x)
        return 1 - 4 * abs(p - 0.5)
    }

    /** A triangle wave of period 1 between 0 and 1, 0 at whole numbers. */
    fun tri01(x: Double) = 1 - abs((x - floor(x)) * 2 - 1)

    /** A uniformly random double in [lo, hi). */
    fun rand(lo: Double, hi: Double) = lo + Math.random() * (hi - lo)

    /** A uniformly random int in lo..hi. */
    fun randInt(lo: Int, hi: Int) = lo + (Math.random() * (hi - lo + 1)).toInt()
}

/** A point on the output canvas, in pixels: what [ScoreFrame.boxPoint] and [ScoreFrame.fromScore] return. */
data class ScorePoint(val x: Double, val y: Double)

/** A box's canvas frame, as `score { }` sees it: read it, set it, move it, resize it. */
class ScoreBox internal constructor(private val box: Box) {
    fun get(): Rect = box.frame
    fun set(x: Double = box.frame.x, y: Double = box.frame.y, w: Double = box.frame.w, h: Double = box.frame.h) {
        box.frame = Rect(x, y, max(1.0, w), max(1.0, h))
    }
    fun set(r: Rect) = set(r.x, r.y, r.w, r.h)
    fun move(dx: Double, dy: Double) = set(x = box.frame.x + dx, y = box.frame.y + dy)
    fun resize(dw: Double, dh: Double) = set(w = box.frame.w + dw, h = box.frame.h + dh)
}

/**
 * The shapes the boxes under the playhead draw this frame, in output-canvas pixels.
 *
 * Colours are CSS strings (`"rgba(255, 238, 205, 0.96)"`, `"hsla(28, 90%, 68%, 0.84)"`, `"#ffb86c"`).
 * A line uses [stroke], else `color`, else a soft white; a filled shape uses `fill`, else `color`,
 * and is outlined only when [stroke] is given. [ring] is an outlined circle.
 */
class ScoreDraw internal constructor() {
    private val calls = StringBuilder()
    private var count = 0

    /** Shapes drawn so far this frame. */
    val size: Int get() = count

    private fun begin(kind: String) {
        if (count++ > 0) calls.append(',')
        calls.append("[\"").append(kind).append('"')
    }

    private fun n(v: Double) {
        calls.append(',')
        if (!v.isFinite()) calls.append('0')
        else calls.append(Math.round(v * 10) / 10.0)
    }

    private fun s(v: String?) {
        calls.append(',')
        if (v == null) calls.append("null")
        else calls.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
    }

    private fun style(fill: String?, stroke: String?, width: Double, alpha: Double) {
        s(fill); s(stroke); n(width); n(alpha); calls.append(']')
    }

    fun line(x0: Double, y0: Double, x1: Double, y1: Double, stroke: String? = null, color: String? = null, width: Double = 1.0, alpha: Double = 1.0) {
        begin("l"); n(x0); n(y0); n(x1); n(y1)
        style(null, stroke ?: color ?: "rgba(255,255,255,0.88)", width, alpha)
    }

    fun line(a: ScorePoint, b: ScorePoint, stroke: String? = null, color: String? = null, width: Double = 1.0, alpha: Double = 1.0) =
        line(a.x, a.y, b.x, b.y, stroke, color, width, alpha)

    fun circle(x: Double, y: Double, r: Double, fill: String? = null, stroke: String? = null, color: String? = null, width: Double = 1.0, alpha: Double = 1.0) {
        begin("c"); n(x); n(y); n(max(0.0, r))
        style(fill ?: color, stroke, width, alpha)
    }

    fun circle(p: ScorePoint, r: Double, fill: String? = null, stroke: String? = null, color: String? = null, width: Double = 1.0, alpha: Double = 1.0) =
        circle(p.x, p.y, r, fill, stroke, color, width, alpha)

    fun ring(x: Double, y: Double, r: Double, stroke: String? = null, color: String? = null, width: Double = 1.0, alpha: Double = 1.0) {
        begin("c"); n(x); n(y); n(max(0.0, r))
        style(null, stroke ?: color ?: "rgba(255,255,255,0.88)", width, alpha)
    }

    fun ring(p: ScorePoint, r: Double, stroke: String? = null, color: String? = null, width: Double = 1.0, alpha: Double = 1.0) =
        ring(p.x, p.y, r, stroke, color, width, alpha)

    fun rect(x: Double, y: Double, w: Double, h: Double, fill: String? = null, stroke: String? = null, color: String? = null, width: Double = 1.0, alpha: Double = 1.0) {
        begin("r"); n(x); n(y); n(w); n(h)
        style(fill ?: color, stroke, width, alpha)
    }

    fun polyline(points: List<ScorePoint>, fill: String? = null, stroke: String? = null, color: String? = null, width: Double = 1.0, alpha: Double = 1.0, closed: Boolean = false) {
        begin("p")
        calls.append(",[")
        points.forEachIndexed { i, p ->
            if (i > 0) calls.append(',')
            calls.append(Math.round(p.x * 10) / 10.0).append(',').append(Math.round(p.y * 10) / 10.0)
        }
        calls.append("],").append(closed)
        style(fill ?: color, stroke, width, alpha)
    }

    internal fun toJson(width: Int, height: Int, t: Double, loop: Double) =
        "{\"w\":$width,\"h\":$height,\"t\":${Math.round(t * 100) / 100.0},\"loop\":$loop,\"calls\":[$calls]}"
}
