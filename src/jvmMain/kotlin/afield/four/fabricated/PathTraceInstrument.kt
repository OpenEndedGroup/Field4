package afield.four.fabricated

import afield.Box
import afield.CellRole
import afield.FieldMarkdown
import afield.H2ValueSnapshot
import afield.RunsAt
import afield.Table
import afield.facts
import afield.newRoot
import afield.registerH2Value
import afield.registerValuePrinter
import afield.showSignals
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import space.kscience.plotly.Plot
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min

/**
 * The path tracer's instrument: what it is doing, why it restarted, and how fast it is converging.
 *
 * Answering any of that from a box by hand means a `frames { repeat(180) { frame() } }` loop with a
 * `println` inside: it costs frames of its own, it misses a reset that lands between frame
 * boundaries, and it cannot see the camera basis the tracer actually built. What is here instead is
 * a `.h` printer, a `.h2` drill-down and [PathTraceConvergence], which the tracer appends to from
 * its own render. Recompiling the integrator in place, the other thing such a loop gets written
 * for, is [PathTracingRenderer.reloadIntegrator].
 *
 * ## The two renderings
 *
 * [registerPathTraceInstrument] gives [PathTracingRenderer] both an [afield.registerValuePrinter]
 * and an [afield.registerH2Value] entry, because the class is not a data class and reflection alone
 * serves the two panels unevenly. `H2Reflect` reflects any type, so `.h2` reaches every public
 * tuning property without help; `.h` with no printer falls through to `ValueVisualizer`'s
 * `formatFallbackObject`, which prints the class name and an identity hash and nothing else — while
 * the `afield.four` types the tracer is *built out of* (`Renderer`, `Camera`,
 * `ComputeModulePointer`, `RenderToTexture`, `VulkanBuffer`, …) all have printers in
 * `FourVisualizers.kt`.
 *
 * So the two renderings do different jobs, and the split is why the `.h` panel stays short:
 *
 *  - **`.h`** is the glance. One header line and a handful of rows, with the rows that are at their
 *    defaults *dropped* — so a plain tracer shows five rows, and each of the five optional groups
 *    (a non-agreeing up, a lens, temporal reuse, fog, an attached convergence log) costs one more,
 *    to a maximum of ten. It is meant to fit in the gap above the next box.
 *  - **`.h2`** is the drill-down. A strict superset of what reflection reaches on its own: the same
 *    values, grouped rather than flat, with the derived facts beside them and the sub-objects still
 *    reachable so each one lands on its own printer.
 *
 * ## The fact reflection cannot reach
 *
 * The tracer does not use `camera.up`. `updateFrame` builds its own up as `right × forward`, i.e.
 * the part of the declared up that is perpendicular to the view direction, normalised, and that is
 * what reaches the `Frame` uniform. A declared up of `(0, 3, 0)`, or one tilted 20° out of the view
 * plane, gives a *different* vector from the one the image is built with, and no property tree can
 * show it, because the value is a local inside a private method. [PathTraceCameraBasis] computes
 * both and reports the angle between them, and the `.h` row appears only when they disagree.
 */

// ── status ───────────────────────────────────────────────────────────

/**
 * The camera as the tracer resolves it, declared and effective side by side.
 *
 * [declaredUp] is `camera.up` as written. [effectiveUp] is `right × forward` normalised — what
 * `PathTracingRenderer.updateFrame` actually writes into the `Frame` uniform. They agree only when
 * the declared up is already unit-length and perpendicular to the view direction.
 */
data class PathTraceCameraBasis(
    val at: Vector3f,
    val target: Vector3f,
    val declaredUp: Vector3f,
    val effectiveUp: Vector3f,
    val right: Vector3f,
    val forward: Vector3f,
    val fovRadians: Double,
    val distanceToTarget: Float
) {
    /**
     * Angle between [declaredUp] and [effectiveUp], in degrees, or `NaN` when either is degenerate.
     *
     * Clamped before `acos` because a dot product of two normalised floats reaches `1.0000001` often
     * enough to matter, and `acos` of that is `NaN` — which would read as "degenerate" on a camera
     * that is perfectly fine.
     */
    val upTiltDegrees: Double
        get() {
            val a = Vector3f(declaredUp)
            val b = Vector3f(effectiveUp)
            if (a.lengthSquared() < 1e-12f || b.lengthSquared() < 1e-12f) return Double.NaN
            val cosine = a.normalize().dot(b.normalize()).toDouble()
            return Math.toDegrees(acos(max(-1.0, min(1.0, cosine))))
        }

    /** Whether the declared up is the one the image is built with, to within a twentieth of a degree. */
    val upAgrees: Boolean get() = upTiltDegrees.let { !it.isNaN() && it < 0.05 }
}

/** Whatever a [PathTraceConvergence] has recorded, reduced to the five numbers worth a row. */
data class PathTraceConvergenceSummary(
    val points: Int,
    val seconds: Double,
    val samples: Int,
    val resets: Int
) {
    /** Fresh samples per wall second over the recorded window, or `NaN` when nothing has elapsed. */
    val samplesPerSecond: Double get() = if (seconds > 1e-9) samples / seconds else Double.NaN
}

/**
 * An immutable snapshot of everything the instrument shows, read once off a live tracer.
 *
 * Immutable and plain: that is what makes the two renderings below testable without a Vulkan
 * device, and it means the `.h` a box printed cannot change under it while the render thread
 * carries on. [tuning] is ordered, holds every property the reflective `.h2` was already showing,
 * and is the group both renderings read for "the other twenty".
 */
data class PathTraceStatus(
    val name: String,
    val backend: PathTracingRenderer.Backend,
    val width: Int,
    val height: Int,
    val triangleCount: Int,
    val samples: Int,
    val samplesPerFrame: Int,
    val paused: Boolean,
    val resetGeneration: Long,
    val lastResetReason: String,
    val camera: PathTraceCameraBasis,
    val tuning: Map<String, Any?>,
    val recorded: PathTraceConvergenceSummary? = null
)

/**
 * Read this tracer's [PathTraceStatus] now.
 *
 * Every read is a field read or a small vector allocation — nothing here builds a pipeline, touches
 * the GPU or blocks on the render thread, so it is safe to call from a box, from a coroutine, or
 * from inside a frame loop.
 */
fun PathTracingRenderer.status(): PathTraceStatus {
    val forward = camera.forward
    val right = camera.right
    return PathTraceStatus(
        name = name,
        backend = backend,
        width = width,
        height = height,
        triangleCount = triangleCount,
        samples = samples,
        samplesPerFrame = samplesPerFrame,
        paused = paused,
        resetGeneration = resetGeneration,
        lastResetReason = lastResetReason,
        camera = PathTraceCameraBasis(
            at = Vector3f(camera.at),
            target = Vector3f(camera.target),
            declaredUp = Vector3f(camera.up),
            effectiveUp = Vector3f(right).cross(forward).normalize(),
            right = right,
            forward = forward,
            fovRadians = camera.p.fov,
            distanceToTarget = camera.distanceToTarget()
        ),
        tuning = linkedMapOf(
            "maxBounces" to maxBounces,
            "samplesPerFrame" to samplesPerFrame,
            "pixelJitter" to pixelJitter,
            "apertureRadius" to apertureRadius,
            "focusDistance" to focusDistance,
            "exposure" to exposure,
            "indirectClamp" to indirectClamp,
            "rayEpsilon" to rayEpsilon,
            "environmentIntensity" to environmentIntensity,
            "environmentRotation" to environmentRotation,
            "showEnvironment" to showEnvironment,
            "background" to background,
            "temporalReuse" to temporalReuse,
            "temporalMaxSamples" to temporalMaxSamples,
            "temporalMotionSensitivity" to temporalMotionSensitivity,
            "temporalRoughnessSensitivity" to temporalRoughnessSensitivity,
            "temporalMaxBlurRadius" to temporalMaxBlurRadius,
            "fogDensity" to fogDensity,
            "fogStartDistance" to fogStartDistance,
            "fogColor" to fogColor,
            "fogBackground" to fogBackground
        ),
        recorded = convergence?.summary
    )
}

// ── .h, the glance ───────────────────────────────────────────────────

private fun fmt(v: Float): String = when {
    v.isNaN() -> "NaN"
    v == v.toInt().toFloat() && abs(v) < 1e6 -> v.toInt().toString()
    abs(v) >= 1000f || (abs(v) < 0.001f && v != 0f) -> "%.3g".format(v)
    else -> "%.3f".format(v).trimEnd('0').trimEnd('.')
}

private fun fmt(v: Vector3f): String = "(${fmt(v.x)}, ${fmt(v.y)}, ${fmt(v.z)})"
private fun fmt(v: Vector4f): String = "(${fmt(v.x)}, ${fmt(v.y)}, ${fmt(v.z)}, ${fmt(v.w)})"

/**
 * What the `.h` printer draws, as a function of a [PathTraceStatus] alone.
 *
 * Split out from the registration so it can be exercised without a Vulkan device — a hand-built
 * status is a complete input. See `PathTraceInstrumentTest`.
 *
 * Rows at their constructor default are dropped rather than shown as zeros. That is the whole
 * reason the panel is short enough to sit above the next box: a plain tracer is **five** rows, and
 * the optional groups (a non-agreeing up, a lens, temporal reuse, fog, a convergence log) cost a
 * row each only once they apply — ten rows at the very most.
 */
internal fun pathTraceStatusHtml(s: PathTraceStatus): String {
    val c = s.camera
    val rate = s.recorded?.samplesPerSecond?.takeIf { !it.isNaN() }
    val rows = Table(listOf("label", "value"))
        .rows(
            listOfNotNull<List<Any?>>(
                listOf("image", "${s.width}×${s.height} · ${s.triangleCount} triangles"),
                listOf(
                    "samples",
                    "${s.samples} spp in generation ${s.resetGeneration}" +
                        (rate?.let { " · ${"%.1f".format(it)} spp/s" } ?: "") +
                        (if (s.paused) "  [paused]" else "")
                ),
                listOf("last reset", s.lastResetReason),
                listOf("camera", "${fmt(c.at)} → ${fmt(c.target)}  d=${fmt(c.distanceToTarget)}"),
                // Only when the declared up is not the one the image is built with. On a camera
                // where they agree this row is noise; on one where they do not it is the answer.
                if (c.upAgrees) null else listOf(
                    "up",
                    if (c.upTiltDegrees.isNaN()) "declared ${fmt(c.declaredUp)} is degenerate; tracer uses ${fmt(c.effectiveUp)}"
                    else "declared ${fmt(c.declaredUp)} ≠ effective ${fmt(c.effectiveUp)} (${"%.2f".format(c.upTiltDegrees)}° apart)"
                ),
                listOf("paths", "${s.tuning["maxBounces"]} bounces · ${s.samplesPerFrame} spp/frame · jitter ${fmt(s.tuning["pixelJitter"] as? Float ?: 0f)}"),
                (s.tuning["apertureRadius"] as? Float)?.takeIf { it > 0f }?.let {
                    listOf("lens", "aperture ${fmt(it)} · focus " + ((s.tuning["focusDistance"] as? Float)?.let(::fmt) ?: "camera target"))
                },
                (s.tuning["temporalReuse"] as? Boolean)?.takeIf { it }?.let {
                    listOf("temporal", "reuse on · max ${s.tuning["temporalMaxSamples"]} carried · blur ≤ ${fmt(s.tuning["temporalMaxBlurRadius"] as? Float ?: 0f)} px")
                },
                (s.tuning["fogDensity"] as? Float)?.takeIf { it > 0f }?.let {
                    listOf("fog", "density ${fmt(it)} from ${fmt(s.tuning["fogStartDistance"] as? Float ?: 0f)} · ${fmt(s.tuning["fogColor"] as? Vector3f ?: Vector3f())}")
                },
                s.recorded?.let { listOf("recorded", "${it.points} points over ${"%.1f".format(it.seconds)} s · ${it.resets} resets") }
            )
        )
        .role("label", CellRole.ACCENT)
        .also { it.showHeader = false }
    val query = if (s.backend == PathTracingRenderer.Backend.RAY_QUERY) "hardware ray queries" else "software BVH traversal"
    return FieldMarkdown.card(
        """
        {accent:PathTracingRenderer} {ok:'${s.name}'} {muted:${s.backend} · $query}

        ${rows.toHTML()}

        {muted:${s.tuning.size} tuning properties, the camera basis and the convergence log are in `.h2`}
        """
    )
}

// ── .h2, the drill-down ──────────────────────────────────────────────

/**
 * What the `.h2` adapter substitutes for a tracer, as a function of a [PathTraceStatus] and the
 * tracer's own sub-objects.
 *
 * Deliberately a superset of the reflective tree it replaces. `H2Reflect` was already showing all
 * 33 public properties, flat; this shows the same values grouped, adds the derived camera basis and
 * the reset bookkeeping that are not properties at all, and keeps the four heavyweight sub-objects
 * under `objects` so each still resolves onto its own `FourVisualizers` printer when clicked.
 * [objects] is passed in rather than read here so this function stays deviceless.
 */
internal fun pathTraceStatusMap(s: PathTraceStatus, objects: Map<String, Any?> = emptyMap()): LinkedHashMap<String, Any?> {
    val c = s.camera
    return linkedMapOf(
        "name" to s.name,
        "backend" to "${s.backend} — " + if (s.backend == PathTracingRenderer.Backend.RAY_QUERY)
            "KHR ray queries are enabled on this device" else "portable compute BVH traversal",
        "image" to linkedMapOf<String, Any?>(
            "width" to s.width,
            "height" to s.height,
            "pixels" to s.width.toLong() * s.height,
            "triangles" to s.triangleCount,
            "paused" to s.paused
        ),
        "convergence" to linkedMapOf<String, Any?>(
            "samples" to s.samples,
            "samplesPerFrame" to s.samplesPerFrame,
            "resetGeneration" to s.resetGeneration,
            "lastResetReason" to s.lastResetReason,
            "recordedPoints" to (s.recorded?.points ?: 0),
            "recordedSeconds" to (s.recorded?.seconds ?: 0.0),
            "samplesPerSecond" to (s.recorded?.samplesPerSecond ?: Double.NaN),
            "recordedResets" to (s.recorded?.resets ?: 0)
        ),
        // Declared and effective together, in that order, because the point of the group is that
        // they can differ.
        "camera" to linkedMapOf<String, Any?>(
            "at" to c.at,
            "target" to c.target,
            "distanceToTarget" to c.distanceToTarget,
            "declaredUp" to c.declaredUp,
            "effectiveUp" to c.effectiveUp,
            "upTiltDegrees" to c.upTiltDegrees,
            "upAgrees" to c.upAgrees,
            "right" to c.right,
            "forward" to c.forward,
            "fovRadians" to c.fovRadians,
            "fovDegrees" to Math.toDegrees(c.fovRadians)
        ),
        "tuning" to LinkedHashMap(s.tuning),
        "objects" to LinkedHashMap(objects)
    )
}

// ── the recorder ─────────────────────────────────────────────────────

/**
 * A per-frame convergence log: samples against wall time, plus every reset and why it happened.
 *
 * Attach one to [PathTracingRenderer.convergence] (or call [PathTracingRenderer.recordConvergence])
 * and the tracer appends a point from its own `render`, on the render thread, for as long as it is
 * attached. This is the thing four hand-written `frames { repeat(180) { frame() } }` loops were
 * approximating, and it is strictly better than them in two ways: it costs no frames of its own,
 * and it sees resets that a loop sampling `samples` at frame boundaries would miss.
 *
 * **Bounded, at a cost to resolution.** Memory is capped at [maxPoints]; when the log fills, every
 * other point is dropped and the sampling stride doubles. So a run of any length keeps the *shape*
 * of its curve in fixed memory, at halving resolution, rather than either stopping or growing
 * without limit. [points] is what is retained, not what happened.
 *
 * Thread-safe: the render thread appends under a lock and every reader takes a copy, so a box can
 * plot a log that is still filling.
 */
class PathTraceConvergence(val maxPoints: Int = 4096) {
    init { require(maxPoints >= 8) { "A convergence log needs room for at least 8 points" } }

    private val lock = Any()
    private val nanos = ArrayList<Long>()
    private val counts = ArrayList<Int>()
    private val resetNanos = ArrayList<Long>()
    private val resetWhy = ArrayList<String>()
    private var origin = 0L
    private var stride = 1
    private var frames = 0L
    private var previousSamples = -1

    /** How many reset marks are retained. The oldest are dropped, because the recent ones explain the picture on screen. */
    private val maxResets = 256

    /** Called by [PathTracingRenderer.render]; see [PathTracingRenderer.convergence]. */
    internal fun record(tracer: PathTracingRenderer) = record(tracer.samples, tracer.lastResetReason, System.nanoTime())

    /** The seam [record] is really made of, so the bookkeeping can be tested without a device. */
    internal fun record(samples: Int, reason: String, atNanos: Long) {
        synchronized(lock) {
            if (nanos.isEmpty() && resetNanos.isEmpty()) origin = atNanos
            // `render` appends here *after* `samples += samplesPerFrame`, so on an undisturbed frame
            // the count has strictly risen. Failing to rise is therefore exactly "accumulation was
            // discarded this frame", and it needs no cooperation from the tracer beyond the reason
            // string — including the camera-motion resets, which zero `samples` without bumping the
            // reset generation at all.
            //
            // `<=`, not `<`. A *sustained* reset — a camera being dragged resets on every frame it
            // moves — holds `samples` at `samplesPerFrame` rather than lowering it, so `<` saw a
            // two-hundred-frame drag as a single reset. Found by this file's own test.
            if (previousSamples >= 0 && samples <= previousSamples) {
                resetNanos.add(atNanos)
                resetWhy.add(reason)
                if (resetNanos.size > maxResets) { resetNanos.removeAt(0); resetWhy.removeAt(0) }
            }
            previousSamples = samples
            frames++
            if ((frames - 1) % stride != 0L) return
            nanos.add(atNanos)
            counts.add(samples)
            if (nanos.size >= maxPoints) decimate()
        }
    }

    /** Keep every other point and double the stride. Halves resolution, keeps the span. */
    private fun decimate() {
        var write = 0
        for (read in nanos.indices step 2) {
            nanos[write] = nanos[read]
            counts[write] = counts[read]
            write++
        }
        while (nanos.size > write) { nanos.removeAt(nanos.size - 1); counts.removeAt(counts.size - 1) }
        stride *= 2
    }

    /** Wall seconds since the first recorded frame, one per retained point. */
    val seconds: List<Double> get() = synchronized(lock) { nanos.map { (it - origin) / 1e9 } }

    /** Fresh samples per pixel, one per retained point, aligned with [seconds]. */
    val samples: List<Int> get() = synchronized(lock) { ArrayList(counts) }

    /** How many points are retained. Less than the number of frames once [decimate] has run. */
    val points: Int get() = synchronized(lock) { nanos.size }

    /** The sampling stride: one point per this many frames. Doubles each time the log fills. */
    val framesPerPoint: Int get() = synchronized(lock) { stride }

    /**
     * Reset marks as `"reason" to seconds`, the shape [showSignals] wants for `marks`.
     *
     * **Consecutive resets with the same reason collapse to the first of the run.** Dragging a
     * camera resets accumulation on *every* frame it moves, so without this a ten-second drag
     * contributes six hundred identical vertical rules and the plot is a solid block. What you get
     * instead is one mark reading `camera motion` where the drag began. The most recent [limit]
     * marks are returned.
     */
    fun resetMarks(limit: Int = 12): List<Pair<String, Double>> = synchronized(lock) {
        val out = ArrayList<Pair<String, Double>>()
        resetWhy.indices.forEach { i ->
            if (i == 0 || resetWhy[i] != resetWhy[i - 1]) out.add(resetWhy[i] to (resetNanos[i] - origin) / 1e9)
        }
        out.takeLast(limit)
    }

    /** Every reset kept, newest last, as `"reason" to seconds`. [resetMarks] is the plottable form. */
    val resets: List<Pair<String, Double>>
        get() = synchronized(lock) { resetWhy.indices.map { resetWhy[it] to (resetNanos[it] - origin) / 1e9 } }

    /** Five numbers for the instrument, or null before the second point (one point spans no time). */
    val summary: PathTraceConvergenceSummary?
        get() = synchronized(lock) {
            if (nanos.size < 2) return null
            PathTraceConvergenceSummary(
                points = nanos.size,
                seconds = (nanos.last() - nanos.first()) / 1e9,
                samples = counts.last(),
                resets = resetWhy.size
            )
        }

    /** Forget everything and start again, keeping the attachment. */
    fun clear() = synchronized(lock) {
        nanos.clear(); counts.clear(); resetNanos.clear(); resetWhy.clear()
        origin = 0L; stride = 1; frames = 0L; previousSamples = -1
    }

    override fun toString(): String =
        "PathTraceConvergence($points points, 1 per $framesPerPoint frames, ${resetWhy.size} resets)"
}

/** Start logging convergence on this tracer, replacing any existing log, and hand it back. */
fun PathTracingRenderer.recordConvergence(maxPoints: Int = 4096): PathTraceConvergence =
    PathTraceConvergence(maxPoints).also { convergence = it }

/**
 * Plot a [PathTraceConvergence] — samples against wall seconds, with a labelled dashed rule
 * wherever accumulation restarted and why.
 *
 * This is one call to [showSignals] and no new plotting code. Plotly is this repo's first choice for
 * a chart and nothing about this figure asks for more than it offers:
 * `showSignals` already takes named series, a shared `x` in seconds, and
 * `marks: List<Pair<String, Number>>`, which it draws as full-height dashed rules carrying their
 * label into the legend. That is exactly "samples against wall time, and the reason the last reset
 * happened". Neither FLine geometry nor a raster path is involved.
 *
 * Returns null, having displayed nothing, until there are two points — a single point spans no time
 * and a one-point line plot is a blank panel with axes, which reads as a bug.
 */
fun Box.showConvergence(
    log: PathTraceConvergence,
    title: String? = "path tracer convergence",
    channel: String = "output",
    marks: Int = 12
): Plot? {
    val x = log.seconds
    val y = log.samples
    if (x.size < 2) return null
    return showSignals(
        "samples/pixel" to y,
        x = x,
        channel = channel,
        title = title,
        xTitle = "seconds",
        yTitle = "samples per pixel",
        marks = log.resetMarks(marks).map { (reason, at) -> reason to (at as Number) }
    )
}

// ── the reload result ────────────────────────────────────────────────

/**
 * What [PathTracingRenderer.reloadIntegrator] did: three outcomes, not a boolean.
 *
 * `ComputeModulePointer.reload` answers `false` for two unrelated things — "this source is what is
 * already compiled" and "this source does not compile, I kept the working pipeline" — so a caller
 * wrapping it in `check(...)` throws on a no-op reload. Naming the third case is most of the value
 * of this type; [errors] carries the compiler's own messages for the case that matters.
 */
class IntegratorReload internal constructor(
    val status: Status,
    /** Characters of source offered. Not the source itself: an integrator is ~15 KB and this ends up in logs. */
    val sourceLength: Int,
    val errors: List<String>
) {
    enum class Status {
        /** Byte-identical to what is already compiled. Nothing was rebuilt and nothing was reset. */
        UNCHANGED,

        /** A new pipeline is live, the superseded one is queued for release, and accumulation restarts. */
        RELOADED,

        /** It did not compile. The last working pipeline is still rendering; see [errors]. */
        FAILED
    }

    /** True only for [Status.RELOADED] — i.e. the image is about to change. */
    val reloaded: Boolean get() = status == Status.RELOADED

    /** True unless it failed to compile, so `check(result.ok)` does not fire on a no-op. */
    val ok: Boolean get() = status != Status.FAILED

    override fun toString(): String = when (status) {
        Status.UNCHANGED -> "integrator unchanged ($sourceLength chars, identical to the live pipeline)"
        Status.RELOADED -> "integrator reloaded ($sourceLength chars); accumulation restarts, camera and scene preserved"
        Status.FAILED -> "integrator failed to compile ($sourceLength chars); last working pipeline kept" +
            if (errors.isEmpty()) "" else ":\n" + errors.joinToString("\n")
    }

    internal companion object {
        fun unchanged(source: String) = IntegratorReload(Status.UNCHANGED, source.length, emptyList())
        fun reloaded(source: String) = IntegratorReload(Status.RELOADED, source.length, emptyList())
        fun failed(source: String, errors: List<String>) = IntegratorReload(Status.FAILED, source.length, errors.toList())
    }
}

// ── registration ─────────────────────────────────────────────────────

/**
 * Register both renderings. Discovered by `di.kt`'s ClassGraph scan of `@RunsAt`, like
 * `registerFourVisualizers` and `registerH2DomainAdapters`.
 *
 * This lives in its own file rather than in `FourVisualizers.kt` or `H2DomainAdapters.kt` on
 * purpose: the tracer's instrument is the tracer's business, and both of those files are edited by
 * everybody.
 */
@RunsAt(newRoot)
fun registerPathTraceInstrument() {
    registerValuePrinter<PathTracingRenderer> { tracer -> pathTraceStatusHtml(tracer.status()) }

    registerH2Value<PathTracingRenderer> { tracer ->
        val s = tracer.status()
        H2ValueSnapshot(
            value = pathTraceStatusMap(
                s,
                linkedMapOf(
                    "camera" to tracer.camera,
                    "output" to tracer.output,
                    "materialModule" to tracer.materialModule,
                    "renderer" to tracer.renderer
                )
            ),
            summary = "PathTracingRenderer '${s.name}' · ${s.backend} · ${s.width}×${s.height} · " +
                "${s.triangleCount} triangles · ${s.samples} spp in generation ${s.resetGeneration} " +
                "(${s.lastResetReason})",
            title = "PathTracingRenderer ${s.name}"
        )
    }
}
