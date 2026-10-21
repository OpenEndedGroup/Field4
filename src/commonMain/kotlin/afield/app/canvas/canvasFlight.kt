package afield.app.canvas

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A canvas flight: the canvas camera flown through a list of bookmarks, for filming.
 *
 * The flight opens on the first stop and holds there, then flies a leg to each next stop and holds
 * at it. Each leg eases in and out; along it the zoom moves in log space (equal steps feel like
 * equal steps whether you are at 20% or at 300%) and the point at the centre of the window moves in
 * a straight line in world coordinates, so the pan does not swing when the zoom changes under it.
 *
 * When two stops are further apart than either view shows and the straight line between them crosses
 * empty canvas, the film would show nothing for a while, so by default such a leg pulls back on the
 * way: the log zoom dips mid-leg, just far enough that both stops are in the window halfway
 * ([CanvasFlightSpec.pullBack]). A leg over boxes the whole way pans straight across them.
 *
 * This file is the arithmetic, shared by the browser that flies ([CanvasFlightTimeline.at]) and the
 * server that films ([CanvasFlightTimeline.duration], [CanvasFlightTimeline.arrivals]). The flight
 * itself is `src/jsMain/kotlin/afield/app/canvas/canvasFlightClient.kt`; box code calls `canvasFlight`
 * (`afield.ui.film`).
 */

/**
 * One stop: the [bookmark] it flies to (its name, or its slot as `"3"`), how long the leg to it
 * takes, and how long the camera then holds there. Null takes the flight's own value. The first
 * stop's leg is ignored: the flight starts there.
 */
@Serializable
data class CanvasFlightStop(
    val bookmark: String,
    val legSeconds: Double? = null,
    val holdSeconds: Double? = null
)

/**
 * A flight through [stops]. [orbitDegreesPerSecond] turns every visible three.js view about its own
 * target while the flight runs (0 leaves them still; positive turns the model anticlockwise seen
 * from above, as `turntable` does). [pullBack] lets a leg between far-apart stops zoom out on the way.
 */
@Serializable
data class CanvasFlightSpec(
    val stops: List<CanvasFlightStop>,
    val legSeconds: Double = 6.0,
    val holdSeconds: Double = 2.0,
    val orbitDegreesPerSecond: Double = 8.0,
    val pullBack: Boolean = true
) {
    /** The first problem with this spec, or null. */
    fun problem(): String? {
        if (stops.isEmpty()) return "a canvas flight needs at least one bookmark"
        fun legOk(s: Double) = s.isFinite() && s >= 0.1 && s <= 600.0
        fun holdOk(s: Double) = s.isFinite() && s >= 0.0 && s <= 600.0
        if (!legOk(legSeconds)) return "legSeconds must be between 0.1 and 600, got $legSeconds"
        if (!holdOk(holdSeconds)) return "holdSeconds must be between 0 and 600, got $holdSeconds"
        stops.forEach { stop ->
            stop.legSeconds?.let { if (!legOk(it)) return "the leg to \"${stop.bookmark}\" must take 0.1 to 600 seconds, got $it" }
            stop.holdSeconds?.let { if (!holdOk(it)) return "the hold at \"${stop.bookmark}\" must be 0 to 600 seconds, got $it" }
        }
        if (!orbitDegreesPerSecond.isFinite() || abs(orbitDegreesPerSecond) > 90.0)
            return "orbitDegreesPerSecond must be between -90 and 90, got $orbitDegreesPerSecond"
        if (CanvasFlightTimeline(this).duration > 3600.0) return "a canvas flight lasts at most an hour"
        return null
    }
}

/** Where the camera looks: the world point at the centre of the window, and the zoom. */
@Serializable
data class CanvasFlightView(val x: Double, val y: Double, val zoom: Double)

/** Ease in and out: still at both ends, fastest halfway, and smooth throughout (no jerk at the middle). */
fun canvasFlightEase(u: Double): Double = 0.5 - 0.5 * cos(PI * u.coerceIn(0.0, 1.0))

/** The flight's clock: which stops a moment falls between, and how far along. */
class CanvasFlightTimeline(private val spec: CanvasFlightSpec) {
    private val legs = spec.stops.mapIndexed { i, s -> if (i == 0) 0.0 else s.legSeconds ?: spec.legSeconds }
    private val holds = spec.stops.map { it.holdSeconds ?: spec.holdSeconds }

    /** When the camera first stands at each stop: 0 for the first. */
    val arrivals: List<Double> = buildList {
        var t = 0.0
        legs.indices.forEach { i ->
            t += legs[i]
            add(t)
            t += holds[i]
        }
    }

    /** The whole flight, the last stop's hold included. */
    val duration: Double = (arrivals.lastOrNull() ?: 0.0) + (holds.lastOrNull() ?: 0.0)

    /**
     * At [seconds]: the stop flown from, the stop flown to (the same while holding) and the eased
     * fraction of the leg between them.
     */
    fun locate(seconds: Double): Triple<Int, Int, Double> {
        val t = seconds.coerceIn(0.0, duration)
        for (i in arrivals.indices.reversed()) {
            if (t >= arrivals[i]) return Triple(i, i, 0.0)
            val start = arrivals[i] - legs[i]
            if (t >= start) return Triple(i - 1, i, canvasFlightEase((t - start) / legs[i]))
        }
        return Triple(0, 0, 0.0)
    }

    /**
     * The view at [seconds], between the stops' [views] (one per stop, in order), in a pane of
     * [paneW] by [paneH] CSS px. The pane's size decides how far a leg pulls back; without it (0)
     * no leg does. [crossesEmpty] says whether the leg to stop `i`, flown straight, crosses canvas with
     * nothing on it; only such a leg pulls back (the browser knows where the boxes are; this file does not).
     */
    fun at(
        seconds: Double, views: List<CanvasFlightView>, paneW: Double = 0.0, paneH: Double = 0.0,
        crossesEmpty: (Int) -> Boolean = { true }
    ): CanvasFlightView {
        val (from, to, e) = locate(seconds)
        val a = views[from]
        val b = views[to]
        if (from == to || e <= 0.0) return a
        if (e >= 1.0) return b
        val dip = if (spec.pullBack && crossesEmpty(to)) canvasFlightPullBack(a, b, paneW, paneH) else 0.0
        return CanvasFlightView(
            x = a.x + (b.x - a.x) * e,
            y = a.y + (b.y - a.y) * e,
            zoom = a.zoom * (b.zoom / a.zoom).pow(e) * exp(-dip * 4.0 * e * (1.0 - e))
        )
    }
}

/**
 * How far, in natural log of zoom, the leg from [a] to [b] dips halfway in a [paneW] by [paneH] pane:
 * enough that both stops' centres, each with the middle half of the closer view around it, are in the
 * window at the leg's midpoint, and zero when the plain log-space zoom already shows that.
 */
fun canvasFlightPullBack(a: CanvasFlightView, b: CanvasFlightView, paneW: Double, paneH: Double): Double {
    if (paneW <= 0.0 || paneH <= 0.0) return 0.0
    val midX = (a.x + b.x) / 2.0
    val midY = (a.y + b.y) / 2.0
    // The subject is the closer view's middle half; a wide stop already shows the close one.
    val close = maxOf(a.zoom, b.zoom)
    fun needX(v: CanvasFlightView) = abs(v.x - midX) + paneW / (4.0 * close)
    fun needY(v: CanvasFlightView) = abs(v.y - midY) + paneH / (4.0 * close)
    val fit = min(paneW / 2.0 / maxOf(needX(a), needX(b)), paneH / 2.0 / maxOf(needY(a), needY(b)))
    val plain = sqrt(a.zoom * b.zoom)
    return if (fit < plain) ln(plain / fit) else 0.0
}

/**
 * The view this bookmark shows in a main pane at ([paneX], [paneY]) of [paneW] by [paneH] CSS px:
 * the world point at the centre of the window it was saved in, and its zoom, scaled down (or up) so
 * the part of the canvas it showed fits this pane. A bookmark from before saved panes recorded their
 * size is taken as saved in this pane, which is exactly what restoring it shows.
 */
fun ScrollPosition.flightView(paneX: Double, paneY: Double, paneW: Double, paneH: Double, currentZoom: Double): CanvasFlightView {
    val z = zoom ?: currentZoom
    val savedW = viewW?.takeIf { it > 0.0 }
    val savedH = viewH?.takeIf { it > 0.0 }
    val sized = savedW != null && savedH != null
    val rx = if (sized) viewX ?: 0.0 else paneX
    val ry = if (sized) viewY ?: 0.0 else paneY
    val rw = savedW ?: paneW
    val rh = savedH ?: paneH
    val fit = if (sized) min(paneW / rw, paneH / rh) else 1.0
    return CanvasFlightView((rx + rw / 2.0 - x) / z, (ry + rh / 2.0 - y) / z, z * fit)
}

/** The canvas translation that puts [view] at the centre of the pane, at [view]'s zoom. */
fun CanvasFlightView.translation(paneX: Double, paneY: Double, paneW: Double, paneH: Double): Pair<Double, Double> =
    Pair(paneX + paneW / 2.0 - x * zoom, paneY + paneH / 2.0 - y * zoom)
