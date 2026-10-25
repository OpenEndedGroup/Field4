@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.graphics.paint

/**
 * A command-line entry point that replays a recorded pen gesture through a brush and writes the
 * painted PNG, so an agent can try a gesture without a browser.
 *
 * [GestureReplayMain] reads a [GestureReplayRequest] (raw samples, or keyframe lanes in
 * [GestureReplayLanes]) and prints a [GestureReplayReport] as JSON.
 */

import afield.animation.keyframes.Keyframe
import afield.animation.keyframes.KeyframeEvaluator
import afield.ui.visualization.PenSample
import afield.app.protocol.Rect
import afield.animation.gesture.measureGestureFit
import afield.animation.gesture.reduceTakeIndices
import afield.math.linalg.Vec4
import afield.animation.gesture.takeToLanes
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/**
 * The gesture -> brush replay for an agent, without a browser: a take (or its keyframe lanes) in a
 * JSON file, a painted PNG out. `field_backdoor.py --command 'run-jvm-main afield.graphics.paint.GestureReplayMain
 * --arg <request.json>'`, or the MCP tool `field_paint_gesture`.
 *
 * The request ([GestureReplayRequest]) holds either `samples` — rows `[t, x, y]` or `[t, x, y,
 * pressure, tiltX, tiltY, twist]`, as a pad records them — or `lanes` (`x`, `y`, optionally
 * `pressure`), keyframes as the keyframe editor stores them. With `bake` and `samples`, the take is
 * first baked into lanes at that tolerance (what `bakeGesture` does), the lanes written to
 * `lanesOut` for editing, and the replay made from the lanes. One JSON report goes to stdout.
 */
object GestureReplayMain {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @JvmStatic
    fun main(args: Array<String>) {
        val path = args.firstOrNull() ?: run {
            System.err.println("usage: afield.graphics.paint.GestureReplayMain <request.json>")
            exitProcess(2)
        }
        val report = try {
            run(json.decodeFromString<GestureReplayRequest>(File(path).readText()))
        } catch (e: Exception) {
            GestureReplayReport(ok = false, error = "${e::class.simpleName}: ${e.message}")
        }
        println(json.encodeToString(report))
        if (!report.ok) exitProcess(1)
    }

    fun run(req: GestureReplayRequest): GestureReplayReport {
        if (!PaintLibrary.isAvailable) return GestureReplayReport(ok = false, error = PaintLibrary.describe())
        val xr = req.xRange.let { it[0] to it[1] }
        val yr = req.yRange.let { it[0] to it[1] }
        val into = req.into?.let { Rect(it[0], it[1], it[2], it[3]) }
        var lanes = req.lanes
        var fit: String? = null
        if (lanes == null && req.bake != null) {
            val take = req.samples.flatMap { it.take(3) }.toDoubleArray()
            val baked = takeToLanes(take, req.bake)
            // a pen's dimensions baked as x and y are: pressure to the tolerance, degrees to 100 times it
            fun penLane(field: Int, tolerance: Double) = req.samples.takeIf { s -> s.all { it.size >= 7 } }?.let { s ->
                val flat = s.flatMap { listOf(it[0], it[field], it[field]) }.toDoubleArray()
                reduceTakeIndices(flat, tolerance).map { Keyframe(flat[it * 3], flat[it * 3 + 1], "linear") }
            }
            lanes = GestureReplayLanes(baked.x, baked.y, penLane(3, req.bake), penLane(4, 100 * req.bake), penLane(5, 100 * req.bake), penLane(6, 100 * req.bake))
            fit = measureGestureFit(take, lane(baked.x), lane(baked.y)).toString()
            req.lanesOut?.let { File(it).apply { parentFile?.mkdirs() }.writeText(json.encodeToString(lanes)) }
        }
        PaintCanvas(req.widthPx, aspect = req.aspect, groundColor = Vec4(0.97f, 0.96f, 0.92f, 1f), sizeMm = req.sizeMm, seed = req.seed).use { c ->
            val g = if (lanes != null) {
                c.gesture(lane(lanes.x), lane(lanes.y), xr, yr, into, lanes.pressure?.let(::lane), lanes.tiltX?.let(::lane),
                    lanes.tiltY?.let(::lane), lanes.twist?.let(::lane), req.pressure, req.pressureScale, req.tilt, timeScale = req.timeScale)
            } else {
                require(req.samples.isNotEmpty()) { "give samples or lanes" }
                c.gesture(req.samples.map { r ->
                    if (r.size >= 7) PenSample(r[0], r[1], r[2], r[3], r[4], r[5], r[6]) else PenSample(r[0], r[1], r[2])
                }, xr, yr, into, req.pressure, req.pressureScale, req.tilt, timeScale = req.timeScale)
            }
            val color = Vec4(req.color[0].toFloat(), req.color[1].toFloat(), req.color[2].toFloat(), 1f)
            val kind = BrushKind.entries.firstOrNull { it.name.equals(req.brush, ignoreCase = true) } ?: BrushKind.Round
            val r = if (req.dynamic) {
                c.stroke(g, c.dynamicBrush(kind, req.brushWidth, hairs = 32).load(color, stiff = 0.5), seed = req.seed).toString()
            } else {
                c.stroke(g, c.brush(kind, req.brushWidth).load(color), seed = req.seed); null
            }
            File(req.out).apply { parentFile?.mkdirs() }.writeBytes(c.png(relief = 0.6))
            return GestureReplayReport(
                ok = true, out = File(req.out).absolutePath, samples = g.samples.size, seconds = g.duration,
                fromLanes = lanes != null, keys = lanes?.x?.size, fit = fit, brush = r,
                pressure = g.samples.map { it.pressure }.let { listOf(it.min(), it.max()) },
                tilt = g.samples.map { it.tilt }.let { listOf(it.min(), it.max()) },
            )
        }
    }

    private fun lane(keys: List<Keyframe>) = KeyframeEvaluator(Json.encodeToString(keys))
}

/** What [GestureReplayMain] reads. Ranges and `into` as in [PaintCanvas.gesture]; `into` is `[x, y, w, h]`. */
@Serializable
data class GestureReplayRequest(
    val out: String,
    val samples: List<List<Double>> = emptyList(),
    val lanes: GestureReplayLanes? = null,
    val bake: Double? = null,
    val lanesOut: String? = null,
    val xRange: List<Double> = listOf(-1.0, 1.0),
    val yRange: List<Double> = listOf(-1.0, 1.0),
    val into: List<Double>? = null,
    val widthPx: Int = 800,
    val aspect: Double = 1.0,
    val sizeMm: Double = 200.0,
    val seed: Long = 1,
    val brush: String = "Round",
    val brushWidth: Double = 50.0,
    val dynamic: Boolean = true,
    val color: List<Double> = listOf(0.25, 0.15, 0.1),
    val pressure: Double = 0.6,
    val pressureScale: Double = 1.2,
    val tilt: Double = 30.0,
    val timeScale: Double = 1.0,
)

/** Keyframe lanes as the keyframe editor stores them. */
@Serializable
data class GestureReplayLanes(
    val x: List<Keyframe>, val y: List<Keyframe>,
    val pressure: List<Keyframe>? = null, val tiltX: List<Keyframe>? = null, val tiltY: List<Keyframe>? = null, val twist: List<Keyframe>? = null,
)

/** What [GestureReplayMain] prints. */
@Serializable
data class GestureReplayReport(
    val ok: Boolean,
    val error: String? = null,
    val out: String? = null,
    val samples: Int = 0,
    val seconds: Double = 0.0,
    val fromLanes: Boolean = false,
    val keys: Int? = null,
    val fit: String? = null,
    val brush: String? = null,
    val pressure: List<Double> = emptyList(),
    val tilt: List<Double> = emptyList(),
)
