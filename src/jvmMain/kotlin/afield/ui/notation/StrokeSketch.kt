package afield.ui.notation

import afield.app.protocol.Handled
import afield.app.protocol.Message
import afield.app.protocol._send
import afield.app.protocol.handlers
import afield.app.protocol.payload
import afield.app.server.SessionInformation
import afield.app.server.thisSession
import afield.app.server.thisSessionInfo
import afield.audio.synth.DoughLive
import afield.audio.synth.PatParam
import afield.audio.synth.PatPattern
import afield.audio.synth.param
import afield.audio.synth.paramOf
import afield.audio.synth.pure
import afield.audio.synth.sig
import afield.document.graph.Box
import afield.document.graph.registerStopHook
import afield.geometry.path.FLine
import afield.math.linalg.Vec2
import afield.math.linalg.vec
import afield.runtime.lifecycle.AnimationClock
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.inMain
import afield.runtime.lifecycle.plugins
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Living strokes: each stroke drawn by hand on the drawing layer becomes an animated, sounding object
 * running code of its own, after the madp line demo.
 *
 * A box takes the pen with [eachStroke]. Each stroke then drawn with the drawing layer's pencil is
 * taken off the scene and becomes a [Stroke]: physics points that start where the hand went, the
 * block of the box that held the pen when it was drawn (later edits do not reach it), a `draw { t -> }`
 * called sixty times a second, and an optional pattern playing through dough. What the strokes draw
 * is shown on the **stroke layer**, a canvas in world coordinates between Field's background and its
 * boxes, which fades towards transparent for trails.
 *
 * One [StrokeSketch] per document keeps the strokes, the pen and the clock. Its frames go to each
 * window showing the document as `stroke-layer-frame`, one at a time: the next is sent once the window
 * answers `stroke-layer-ack`, so a slow window is never flooded.
 */

/** One physics point of a living stroke. Set [ax] and [ay] in `draw { }`; the sketch integrates them. */
class StrokePoint(var x: Double, var y: Double) {
    var vx = 0.0
    var vy = 0.0
    var ax = 0.0
    var ay = 0.0

    /** Where the point is now. */
    val at: Vec2 get() = vec(x, y)

    override fun toString() = "StrokePoint(%.1f, %.1f)".format(x, y)
}

/** A point another stroke has, found by [Stroke.closestPoint] or [Stroke.closestOutput]. */
data class StrokeHit(val x: Double, val y: Double, val dist: Double, val strokeIndex: Int, val pointIndex: Int)

/** What [eachStroke] made: the block a box gives to every stroke drawn while it holds the pen. */
class StrokePen internal constructor(
    val box: Box,
    /** How much of the last frame each frame keeps, 0..1: 0 clears, 0.9 leaves long trails. */
    val trail: Double,
    /** Width of a line that does not set its own `thickness`. */
    val lineWidth: Double,
    internal val body: Stroke.() -> Unit,
) {
    internal val sketch: StrokeSketch? get() = if (box.isInsideInitialized()) StrokeSketch.of(box.inside) else null

    /** Whether strokes drawn now come to this pen. */
    val hasPen: Boolean get() = sketch?.pen === this

    override fun toString() = "pen of '${box.name}' (${if (hasPen) "holding it" else "put down"}, ${sketch?.strokes?.size ?: 0} strokes on the page)"
}

/**
 * A stroke that has come alive: its [points], its place in the drawing ([index]), and the code it was
 * drawn with. The receiver of the [eachStroke] block, which runs once when the stroke is drawn (the
 * demo's `onStart`) and sets up the rest with [draw] (its `manipulatePoints`) and [sound].
 */
class Stroke internal constructor(
    internal val sketch: StrokeSketch,
    val pen: StrokePen,
    /** 0 for the first stroke on the page, then 1, 2, ... */
    val index: Int,
    /** The stroke's physics points, where the hand went, about 2 units apart. */
    val points: List<StrokePoint>,
) : StrokeVerbs() {
    /** The box that held the pen when this stroke was drawn. */
    val box: Box get() = pen.box

    /** Seconds since this stroke was drawn. */
    var t: Double = 0.0
        internal set

    internal val born = AnimationClock.nowSeconds()
    internal var drawer: ((Double) -> List<FLine>)? = null
    internal var output: List<FLine> = emptyList()
    internal var indexed: FloatArray = FloatArray(0)
    internal var voice: DoughLive.PatternVoice? = null
    internal var lastError: String? = null

    /** The points' current positions, as a curve. */
    val curve: List<Vec2> get() = points.map { it.at }

    /**
     * What this stroke draws, called 60 times a second with [t], seconds since it was drawn. Return
     * the lines to show; set `ax`/`ay` on [points] to push them, and the sketch moves them after the
     * call (damping 0.98 a step). Without a `draw`, the stroke is shown as it was drawn.
     */
    fun draw(block: (t: Double) -> List<FLine>) {
        drawer = block
    }

    /**
     * Play [pattern] while this stroke lives, on the sketch's clock (0.5 cycles a second, shared by
     * every stroke, so patterns stay in step). The pattern language's own atoms and params, with the
     * Strudel names [s], [lpf] and [signal]: `pat("c4 e4 g4") with s("sawtooth") with gain(signal { level })`.
     * Note names are Tidal's, so `c5` is middle C. Gains are Strudel's: the pattern plays at a quarter
     * of dough's level, as quiet as the same gain is in the demo.
     */
    fun sound(pattern: PatPattern<String>) {
        voice?.stop()
        voice = sketch.play(this, pattern)
    }

    /** The sketch's sound clock, in cycles: `cycle % 1` is how far through the bar it is now. */
    val cycle: Double get() = sketch.cycle

    /** The nearest physics point of any other stroke to ([x], [y]), or null when this is the only one. */
    fun closestPoint(x: Double, y: Double): StrokeHit? {
        var best: StrokeHit? = null
        for (other in sketch.strokes) {
            if (other === this) continue
            other.points.forEachIndexed { j, p ->
                val d = hypot(x - p.x, y - p.y)
                if (best == null || d < best!!.dist) best = StrokeHit(p.x, p.y, d, other.index, j)
            }
        }
        return best
    }

    /**
     * The nearest point of what any other stroke drew last frame to ([x], [y]): every node of its lines,
     * except lines marked [FLine.ignore]. Last frame's, because this frame's is still being drawn.
     */
    fun closestOutput(x: Double, y: Double): StrokeHit? {
        var best: StrokeHit? = null
        for (other in sketch.strokes) {
            if (other === this) continue
            val a = other.indexed
            var j = 0
            while (j < a.size) {
                val px = a[j].toDouble()
                val py = a[j + 1].toDouble()
                val d = hypot(x - px, y - py)
                if (best == null || d < best!!.dist) best = StrokeHit(px, py, d, other.index, j / 2)
                j += 2
            }
        }
        return best
    }

    /** How loud everything the strokes play is right now, measured from dough's voices: about 0.7 for a sine at gain 1. */
    fun rms(): Double = (DoughLive.sharedOrNull()?.mixLevel ?: 0.0) * 0.7071

    /** Strudel's `.sound(name)` / `s(name)`: the dough sound a note plays on (`sawtooth`, `triangle`, `square`, `sine`). */
    fun s(name: String): PatParam = paramOf("s", pure(name))

    /** Strudel's `.lpf(hz)`: dough's low-pass cutoff. */
    fun lpf(values: PatPattern<Double>): PatParam = param("lpf", values)

    /** [lpf] at a fixed cutoff. */
    fun lpf(hz: Number): PatParam = param("lpf", hz)

    /** Strudel's `signal(() => …)`: a continuous pattern whose value is [read] when each event is scheduled. */
    fun signal(read: () -> Double): PatPattern<Double> = sig { read() }

    override fun toString() = "stroke $index of '${box.name}' (${points.size} points, %.1fs old)".format(t)
}

/**
 * Take the pen: every stroke drawn by hand from now on (Alt+D, the pencil) becomes a living [Stroke]
 * running [body].
 *
 * ```
 * eachStroke(trail = 0.18) {
 *     var energy = 0.0
 *     sound(pat("c4 e4 g4") with s("triangle") with gain(signal { 0.03 + energy * 0.12 }))
 *     draw { t ->
 *         val base = resample(points, 48)
 *         val wave = base.indices.map { i ->
 *             val n = normalAt(base, i)
 *             val w = driftNoise(i * 0.2, t, 1.0) * 18
 *             vec(base[i].x + n.x * w, base[i].y + n.y * w)
 *         }
 *         energy = clamp01(t / 10)
 *         listOf(smooth(wave).also { it.color = cssHsla(200 + t * 20, 0.9, 0.68, 0.88) })
 *     }
 * }
 * ```
 *
 * [body] is the stroke's own program: it runs once when the stroke is drawn, and what it keeps in
 * local variables is that stroke's alone and lives as long as the stroke, shared by its drawing and
 * its sound (the demo's `ctrl`). Running this box again changes what *new* strokes do; strokes already
 * drawn keep the code they were drawn with. Running another `eachStroke` box gives that box the pen;
 * stopping this one puts it down. [trail] (0..1) is how much of each frame the next keeps, and
 * [lineWidth] is the width of lines that set no `thickness`. [clearStrokes] removes every stroke.
 *
 * The hand-drawn mark is removed from the drawing as the stroke comes alive, unless [keepMarks].
 */
fun Box.eachStroke(trail: Double = 0.0, lineWidth: Double = 2.0, keepMarks: Boolean = false, body: Stroke.() -> Unit): StrokePen {
    val pen = StrokePen(this, trail.coerceIn(0.0, 1.0), lineWidth, body)
    val sketch = pen.sketch ?: throw IllegalStateException("eachStroke needs a box in a document")
    sketch.pen = pen
    sketch.trail = pen.trail
    notation.onStroke("eachStroke") { mark -> if (sketch.pen === pen) sketch.take(pen, mark, keepMarks) }
    registerStopHook(Runnable { if (sketch.pen === pen) sketch.pen = null })
    sketch.ensureRunning()
    return pen
}

/** Remove every living stroke in this document, and silence them. The pen stays where it is. */
fun Box.clearStrokes() {
    if (isInsideInitialized()) StrokeSketch.of(inside).clear()
}

/** The living strokes of this document, oldest first. */
val Box.livingStrokes: List<Stroke>
    get() = if (isInsideInitialized()) StrokeSketch.of(inside).strokes.toList() else emptyList()

/**
 * The server end of the stroke layer: a window answers each `stroke-layer-frame` with
 * `stroke-layer-ack` once it has drawn it, and only then is it sent the next.
 */
@RunsAt(plugins)
fun installStrokeLayerHandlers() {
    handlers["stroke-layer-ack"] = mutableSetOf({ _, _ ->
        val info = thisSessionInfo.get()
        val session = thisSession.get()
        if (info != null && session != null) StrokeSketch.existing(info)?.acked(session)
        Handled.yes
    })
}

/**
 * The strokes of one document, the pen that new strokes go to, and the clock that steps them.
 *
 * Stepping runs on the main loop at a fixed 60 steps a second (at most two per loop turn, so a stall
 * does not become a burst), which is the rate the demo's physics constants were written for.
 */
class StrokeSketch internal constructor(val info: SessionInformation) {
    companion object {
        private val sketches = ConcurrentHashMap<SessionInformation, StrokeSketch>()

        /** The sketch of the document [info] is. */
        fun of(info: SessionInformation): StrokeSketch = sketches.computeIfAbsent(info) { StrokeSketch(it) }

        internal const val STEP = 1.0 / 60
        internal const val DAMPING = 0.98
        internal const val CPS = 0.5

        /**
         * The level stroke patterns play at, relative to dough at the same `gain`: Strudel's synths
         * are that much quieter than dough's, so the demo's gains sound as loud here as there, and its
         * `rms()`-driven drawing moves as much. Measured on the prism choir with the same three
         * strokes: the demo's `rms()` read 0.046 in Chrome, this sketch's 0.19 at gain 1.
         */
        internal const val STRUDEL_LEVEL = 0.25

        internal fun existing(info: SessionInformation): StrokeSketch? = sketches[info]
    }

    /** The strokes, oldest first. */
    val strokes = CopyOnWriteArrayList<Stroke>()

    /** Where new strokes go, or null when no box holds the pen. */
    @Volatile
    var pen: StrokePen? = null

    /** The trail of the most recent pen: how much of each frame the next keeps. */
    @Volatile
    var trail: Double = 0.0

    private val incoming = ConcurrentLinkedQueue<() -> Unit>()
    private val inFlight = ConcurrentHashMap<WebSocketSession, Double>()
    private var originFrame: Long? = null
    @Volatile
    private var running = false
    private var cleared = true

    /** The sound clock, in cycles since the first stroke played; 0 before any did. */
    val cycle: Double
        get() {
            val origin = originFrame ?: return 0.0
            val live = DoughLive.sharedOrNull() ?: return 0.0
            return max(0.0, (live.heardFrame - origin).toDouble() / live.sampleRate * CPS)
        }

    internal fun play(stroke: Stroke, pattern: PatPattern<String>): DoughLive.PatternVoice {
        val live = DoughLive.shared()
        val origin = originFrame ?: live.framesRendered.also { originFrame = it }
        return live.play(pattern, CPS, DoughLive.groupOf(stroke.box), origin, gain = STRUDEL_LEVEL)
    }

    internal fun take(pen: StrokePen, mark: Mark, keepMarks: Boolean) {
        val raw = mark.points
        incoming += {
            val pts = ArrayList<StrokePoint>()
            for (p in raw) {
                val last = pts.lastOrNull()
                if (last == null || hypot(p.x - last.x, p.y - last.y) >= 2.0) pts += StrokePoint(p.x.toDouble(), p.y.toDouble())
            }
            if (pts.size >= 2) {
                if (!keepMarks) pen.box.notation.delete(mark)
                val stroke = Stroke(this, pen, strokes.size, pts)
                runUser(stroke) { stroke.(pen.body)() }
                strokes += stroke
            }
        }
        ensureRunning()
    }

    internal fun clear() {
        incoming += {
            strokes.forEach { it.voice?.stop() }
            strokes.clear()
            originFrame = null
        }
        ensureRunning()
    }

    internal fun acked(session: WebSocketSession) {
        inFlight.remove(session)
    }

    @Synchronized
    internal fun ensureRunning() {
        if (running) return
        running = true
        inMain {
            var last = AnimationClock.nowSeconds()
            var owed = 0.0
            while (true) {
                while (true) (incoming.poll() ?: break).invoke()
                val now = AnimationClock.nowSeconds()
                owed = min(2 * STEP, owed + max(0.0, now - last))
                last = now
                var stepped = false
                while (owed >= STEP) {
                    step()
                    owed -= STEP
                    stepped = true
                }
                if (stepped) publish()
                if (strokes.isEmpty() && pen == null && incoming.isEmpty()) {
                    synchronized(this@StrokeSketch) { running = false }
                    publish()
                    return@inMain
                }
                delay(4)
            }
        }
    }

    private fun step() {
        val now = AnimationClock.nowSeconds()
        val all = strokes.toList()
        for (s in all) for (p in s.points) {
            p.ax = 0.0
            p.ay = 0.0
        }
        val next = arrayOfNulls<FloatArray>(all.size)
        all.forEachIndexed { i, s ->
            s.t = now - s.born
            val drawer = s.drawer
            s.output = if (drawer == null) listOf(polylineOf(s)) else runUser(s) { drawer(s.t) } ?: s.output
            next[i] = StrokeLayerFrames.indexedPositions(s.output)
        }
        all.forEachIndexed { i, s ->
            s.indexed = next[i]!!
            for (p in s.points) {
                p.vx = (p.vx + p.ax * STEP) * DAMPING
                p.vy = (p.vy + p.ay * STEP) * DAMPING
                p.x += p.vx * STEP
                p.y += p.vy * STEP
            }
        }
    }

    private fun publish() {
        val sessions = info.sessions
        if (sessions.isEmpty()) return
        if (strokes.isEmpty() && cleared) return
        val frame = StrokeLayerFrames.encode(trail, strokes.map { it.output to (it.pen.lineWidth) })
        cleared = strokes.isEmpty()
        val now = AnimationClock.nowSeconds()
        val message = Message("stroke-layer-frame").payload(JsonPrimitive(frame))
        for (session in sessions) {
            val sentAt = inFlight[session]
            // A window that has not answered in a quarter of a second (hidden, or gone) gets frames at
            // that rate rather than none.
            if (sentAt != null && now - sentAt < 0.25 && !cleared) continue
            inFlight[session] = now
            _send(session, message)
        }
    }

    private fun polylineOf(s: Stroke): FLine = FLine().also { f ->
        s.points.forEachIndexed { i, p -> if (i == 0) f.moveTo(p.x, p.y) else f.lineTo(p.x, p.y) }
    }

    private fun <T> runUser(stroke: Stroke, block: () -> T): T? = try {
        Box.withBoxContextNow(stroke.box) { block() }.also { stroke.lastError = null }
    } catch (t: Throwable) {
        val message = t.message ?: t.toString()
        if (message != stroke.lastError) {
            stroke.lastError = message
            stroke.box.error("stroke ${stroke.index} threw: $message")
        }
        null
    }

    override fun toString() = "stroke sketch of ${info.documentName}: ${strokes.size} strokes, pen ${pen?.box?.name ?: "down"}"
}
