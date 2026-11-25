package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.interaction.PointerMachine
import afield.graphics.interaction.Ray
import afield.math.linalg.Matrix4f
import afield.math.linalg.Vector3f
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What a reading-diagram pick may land on, and how forgiving it is.
 *
 * Reach is what makes thin things pointable. A 1.5 mm stroke two metres away subtends a few
 * hundredths of a degree, far less than a hand or a controller holds still, so strokes and dots are
 * widened by [angularReach] radians of the pointer's own view, never less than [minReach] metres.
 * Text is picked against its ink box grown by [textPadding].
 */
data class DiagramPickOptions(
    val texts: Boolean = true,
    val paths: Boolean = true,
    val dots: Boolean = true,
    /** Items fainter than this — score opacity times colour alpha — are not there to point at. */
    val minOpacity: Float = .05f,
    /** Extra reach around a text run's ink, in metres. */
    val textPadding: Float = .004f,
    /** Reach around strokes and dots, in radians as seen from the ray's origin. */
    val angularReach: Double = .006,
    /** The smallest reach around strokes and dots, in metres. */
    val minReach: Float = .002f,
    /** Only items this accepts are considered. */
    val filter: (DiagramItem) -> Boolean = { true },
)

enum class DiagramHitKind { TEXT, PATH, DOT }

/**
 * Where a ray met a reading diagram: which item, where on it, and what the scene attached there.
 *
 * [properties] and [annotations] are the provenance a scene put on the item and on its characters
 * (see [DiagramItem.properties] and [DiagramTextSpan.annotate]); the pick hands them back untouched.
 */
class DiagramHit(
    val item: DiagramItem,
    val kind: DiagramHitKind,
    /** Distance along the ray to the contact, in world units. */
    val distance: Double,
    /** How far outside the item the ray passed, in world units: 0 over ink, inside a dot or on a stroke. */
    val miss: Double,
    /** The contact in world space. */
    val point: Vector3f,
    /** The contact in the diagram's own space. */
    val diagramPoint: Vector3f,
    /** For text, the character under the contact, or the nearest one in the run. */
    val charIndex: Int? = null,
    /** For text, the contact in font units: baseline-left origin, y up. */
    val textX: Float? = null,
    val textY: Float? = null,
    /** For a path, where along its full arclength the ray passed closest, `0..1`. */
    val fraction: Float? = null,
    /** For text, the annotations covering [charIndex], innermost first. */
    val annotations: List<DiagramTextAnnotation> = emptyList(),
) {
    val id get() = item.id

    val properties get() = item.properties

    /** The most specific annotation's value, or `null`. */
    val annotation: Any? get() = annotations.firstOrNull()?.value

    /** For text, the character under the contact. */
    val character: Char? get() = (item as? DiagramText)?.text?.getOrNull(charIndex ?: -1)

    fun describe(): String = buildString {
        append(kind.name.lowercase()).append(" '").append(id).append("'")
        if (charIndex != null) append(" char ").append(charIndex).append(" '").append(character ?: '?').append("'")
        if (fraction != null) append(" at %.3f".format(fraction))
        append("  %.3f away".format(distance))
        annotation?.let { append("  [").append(it).append("]") }
    }

    override fun toString() = "DiagramHit(${describe()})"
}

/** Small things drawn over text win over the text they sit on, within this depth, in world units. */
private fun DiagramHitKind.depthBias() = when (this) {
    DiagramHitKind.DOT -> .01
    DiagramHitKind.PATH -> .005
    DiagramHitKind.TEXT -> 0.0
}

/**
 * The nearest item under [ray], or `null`.
 *
 * @param ray in world space.
 * @param model the diagram's placement in the world — diagram space to world space. A
 *   [StereoDiagramRenderer] keeps it as [StereoDiagramRenderer.model]; [StereoDiagramRenderer.pick]
 *   passes it for you.
 */
fun DiagramFrame.pick(
    ray: Ray,
    model: Matrix4f = Matrix4f(),
    options: DiagramPickOptions = DiagramPickOptions(),
): DiagramHit? = pickAll(ray, model, options).firstOrNull()

/** Every item under [ray], nearest first (see [pick]); one hit per item. */
fun DiagramFrame.pickAll(
    ray: Ray,
    model: Matrix4f = Matrix4f(),
    options: DiagramPickOptions = DiagramPickOptions(),
): List<DiagramHit> {
    val toDiagram = Matrix4f(model).invert()
    val origin = toDiagram.transformPosition(Vector3f(ray.originX.toFloat(), ray.originY.toFloat(), ray.originZ.toFloat()))
    val along = toDiagram.transformDirection(Vector3f(ray.dirX.toFloat(), ray.dirY.toFloat(), ray.dirZ.toFloat()))
    // Diagram units per world unit along the ray; 1 for the usual rigid placement.
    val unitsPerWorld = along.length().toDouble()
    if (unitsPerWorld < 1e-12) return emptyList()
    val dir = Vector3f(along).normalize()
    val hits = ArrayList<DiagramHit>()

    fun reach(diagramT: Double) = max(options.minReach.toDouble(), options.angularReach * diagramT / unitsPerWorld) * unitsPerWorld

    fun hit(
        item: DiagramItem, kind: DiagramHitKind, diagramT: Double, missDiagram: Double, at: Vector3f,
        charIndex: Int? = null, textX: Float? = null, textY: Float? = null, fraction: Float? = null,
        annotations: List<DiagramTextAnnotation> = emptyList(),
    ) {
        hits += DiagramHit(
            item, kind, diagramT / unitsPerWorld, missDiagram / unitsPerWorld,
            model.transformPosition(Vector3f(at)), Vector3f(at), charIndex, textX, textY, fraction, annotations,
        )
    }

    val player = diagramPlayer

    if (options.texts) for (run in diagram.texts) {
        if (!options.filter(run)) continue
        if (pose(run).opacity * run.color.w < options.minOpacity) continue
        // The run's plane, from the same transform that places its glyphs: font units -> diagram space.
        val o = textPoint(run, 0f, 0f)
        val ex = textPoint(run, 1f, 0f).sub(o)
        val ey = textPoint(run, 0f, 1f).sub(o)
        val normal = Vector3f(ex).cross(ey)
        val denom = normal.dot(dir).toDouble()
        if (normal.lengthSquared() < 1e-20f || abs(denom) < 1e-12) continue
        val t = normal.dot(Vector3f(o).sub(origin)) / denom
        if (t < 0) continue
        val p = Vector3f(origin).fma(t.toFloat(), dir)
        val r = Vector3f(p).sub(o)
        val g11 = ex.dot(ex).toDouble()
        val g12 = ex.dot(ey).toDouble()
        val g22 = ey.dot(ey).toDouble()
        val det = g11 * g22 - g12 * g12
        if (abs(det) < 1e-30) continue
        val r1 = r.dot(ex).toDouble()
        val r2 = r.dot(ey).toDouble()
        val x = ((r1 * g22 - r2 * g12) / det).toFloat()
        val y = ((r2 * g11 - r1 * g12) / det).toFloat()
        val ink = measure(run).ink
        val pad = (options.textPadding * unitsPerWorld / sqrt(g11)).toFloat()
        if (x < ink.left - pad || x > ink.right + pad || y < ink.bottom - pad || y > ink.top + pad) continue
        val charIndex = characterAt(player.shape(run), run.text, x, y)
        hit(
            run, DiagramHitKind.TEXT, t, 0.0, p, charIndex, x, y,
            annotations = charIndex?.let { run.annotationsAt(it) } ?: emptyList(),
        )
    }

    if (options.paths) for (path in diagram.paths) {
        if (!options.filter(path)) continue
        val pose = pose(path)
        if (pose.opacity * path.ink.color.w < options.minOpacity) continue
        val from = max(path.ink.drawFrom, pose.erase)
        val to = minOf(path.ink.drawTo, pose.reveal)
        if (to <= from) continue
        val line = path(path)
        val halfWidth =
            ((line.properties["thickness"] as? Number)?.toFloat() ?: path.ink.width) * player.strokeWidthScale * .5
        val contours = flatten(line)
        val total = contours.sumOf { c -> (1 until c.size).sumOf { c[it - 1].distance(c[it]).toDouble() } }
        if (total <= 0.0) continue
        var walked = 0.0
        var best: Triple<Double, Double, Pair<Vector3f, Float>>? = null // t, miss, (point, fraction)
        for (contour in contours) for (i in 1 until contour.size) {
            val a = contour[i - 1]
            val b = contour[i]
            val length = a.distance(b).toDouble()
            val (t, u, gap) = closestApproach(origin, dir, a, b)
            val fraction = ((walked + u * length) / total).toFloat()
            walked += length
            if (t < 0 || fraction < from || fraction > to) continue
            if (gap > halfWidth + reach(t)) continue
            val miss = max(0.0, gap - halfWidth)
            if (best == null || miss < best.second || (miss == best.second && t < best.first))
                best = Triple(t, miss, Vector3f(a).lerp(b, u.toFloat()) to fraction)
        }
        best?.let { (t, miss, at) -> hit(path, DiagramHitKind.PATH, t, miss, at.first, fraction = at.second) }
    }

    if (options.dots && player.showDots) for (dot in diagram.dots) {
        if (!options.filter(dot)) continue
        val pose = pose(dot)
        if (pose.opacity * dot.color.w < options.minOpacity) continue
        val c = position(dot.at).add(pose.offset)
        val radius = (dot.radius * pose.scale * player.dotRadiusScale).toDouble()
        val t = Vector3f(c).sub(origin).dot(dir).toDouble()
        if (t < 0) continue
        val nearest = Vector3f(origin).fma(t.toFloat(), dir)
        val gap = nearest.distance(c).toDouble()
        if (gap > radius + reach(t)) continue
        hit(dot, DiagramHitKind.DOT, t, max(0.0, gap - radius), c)
    }

    return hits.sortedWith(compareBy<DiagramHit> { it.distance - it.kind.depthBias() }.thenBy { it.miss })
}

/** The character whose advance box holds font-space ([x], [y]), else the nearest; `null` for empty text. */
internal fun characterAt(shape: ReadingText, text: String, x: Float, y: Float): Int? {
    var best: Int? = null
    var bestDistance = Float.MAX_VALUE
    for (i in text.indices) {
        if (text[i] == '\n') continue
        // A character inside a shaped cluster (a ligature) has no box of its own; its cluster's first does.
        val box = runCatching { shape.measure(i, i + 1).advance }.getOrNull() ?: continue
        val dx = max(0f, max(box.left - x, x - box.right))
        val dy = max(0f, max(box.bottom - y, y - box.top))
        val d = dx * dx + dy * dy
        if (d < bestDistance) {
            bestDistance = d
            best = i
            if (d == 0f) break
        }
    }
    return best
}

/** Contours of [line] as polylines; cubics are subdivided finely enough to point at. */
internal fun flatten(line: FLine): List<List<Vector3f>> {
    val contours = ArrayList<MutableList<Vector3f>>()
    var current: MutableList<Vector3f>? = null
    for (node in line.nodes) when (node) {
        is FLine.MoveTo -> current = mutableListOf(Vector3f(node.to)).also { contours += it }
        is FLine.LineTo -> (current ?: mutableListOf<Vector3f>().also { contours += it; current = it }).add(Vector3f(node.to))
        is FLine.CubicTo -> {
            val c = current ?: mutableListOf<Vector3f>().also { contours += it; current = it }
            val p0 = c.lastOrNull() ?: Vector3f(node.to)
            val hull = p0.distance(node.c1) + node.c1.distance(node.c2) + node.c2.distance(node.to)
            val steps = (hull / .002f).toInt().coerceIn(4, 64)
            for (k in 1..steps) {
                val s = k.toFloat() / steps
                val m = 1 - s
                c += Vector3f(p0).mul(m * m * m)
                    .fma(3 * m * m * s, node.c1)
                    .fma(3 * m * s * s, node.c2)
                    .fma(s * s * s, node.to)
            }
        }
        // A Close draws the edge back to the subpath's start, and anything after it continues from there.
        is FLine.Close -> current?.takeIf { it.size >= 2 }?.let { c ->
            val start = Vector3f(c.first())
            c += Vector3f(start)
            current = mutableListOf(start).also { contours += it }
        }
        else -> {}
    }
    return contours.filter { it.size >= 2 }
}

/**
 * Closest approach between the ray `origin + t dir` (unit `dir`) and the segment `a..b`:
 * `(t, u, gap)`, with `u` in `0..1` along the segment and `gap` the distance between the two points.
 */
internal fun closestApproach(origin: Vector3f, dir: Vector3f, a: Vector3f, b: Vector3f): Triple<Double, Double, Double> {
    val seg = Vector3f(b).sub(a)
    val w = Vector3f(origin).sub(a)
    val segLen2 = seg.dot(seg).toDouble()
    val d = dir.dot(seg).toDouble()
    val e = dir.dot(w).toDouble()
    val f = seg.dot(w).toDouble()
    var u: Double
    var t: Double
    if (segLen2 < 1e-20) {
        u = 0.0
        t = -e
    } else {
        val denom = segLen2 - d * d // |dir| = 1
        u = if (denom > 1e-14) ((f - d * e) / denom).coerceIn(0.0, 1.0) else 0.0
        t = d * u - e
    }
    if (t < 0) t = 0.0
    // Re-fit u to the clamped t, so the pair is mutually closest.
    if (segLen2 >= 1e-20) u = ((d * t + f) / segLen2).coerceIn(0.0, 1.0)
    val onRay = Vector3f(origin).fma(t.toFloat(), dir)
    val onSeg = Vector3f(a).fma(u.toFloat(), seg)
    return Triple(t, u, onRay.distance(onSeg).toDouble())
}

/** [pick] on this player's current frame. */
fun ReadingDiagramPlayer.pick(
    ray: Ray,
    model: Matrix4f = Matrix4f(),
    options: DiagramPickOptions = DiagramPickOptions(),
): DiagramHit? = currentFrame().pick(ray, model, options)

/** [pick] against [player] as this renderer places it in the world, through [StereoDiagramRenderer.model]. */
fun StereoDiagramRenderer.pick(
    ray: Ray,
    player: ReadingDiagramPlayer,
    options: DiagramPickOptions = DiagramPickOptions(),
): DiagramHit? = player.currentFrame().pick(ray, model, options)

/** The key a hover dwells on: the item, narrowed to the innermost annotation under the pointer. */
data class DiagramHitKey(val id: String, val annotationStart: Int?, val annotationEnd: Int?)

/**
 * Hover, press, drag and click over a reading diagram — the [PointerMachine] with [DiagramHit]s.
 *
 * Hover identity is the item narrowed to its innermost annotation, so moving across the subterms of
 * an annotated formula moves the hover from subterm to subterm, while moving within one does not.
 *
 * ```kotlin
 * val mouse = stereo.mirrorPointer(WindowPointer(window, "reading-mouse"))
 * val interaction = DiagramPointerInteraction({ ray -> drawing.pick(ray, gallery.current) })
 * // each frame:
 * interaction.update(mouse.sample())
 * interaction.hover?.let { println(it.describe()) }
 * ```
 */
class DiagramPointerInteraction(
    /** What a ray hits; swap freely. */
    var picker: (Ray) -> DiagramHit?,
    pressThreshold: Double = 0.6,
    releaseThreshold: Double = 0.35,
    switchSamples: Int = 3,
) : PointerMachine<DiagramHit>(pressThreshold, releaseThreshold, switchSamples) {
    override fun pick(ray: Ray): DiagramHit? = picker(ray)

    override fun keyOf(hit: DiagramHit): Any {
        val a = hit.annotations.firstOrNull()
        return DiagramHitKey(hit.id, a?.start, a?.endExclusive)
    }

    override fun contactPoint(hit: DiagramHit): Vector3f = hit.point

    override fun describeHit(hit: DiagramHit): String = hit.describe()
}
