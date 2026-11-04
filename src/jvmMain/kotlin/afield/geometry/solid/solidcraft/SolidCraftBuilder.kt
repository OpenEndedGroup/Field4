package afield.geometry.solid.solidcraft

import afield.geometry.path.FLine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Writing a SolidCraft part in Kotlin instead of drawing it: sketches, the features that turn them
 * into solids and surfaces, and named parameters that stay editable afterwards.
 *
 * [solidCraft] opens a [SolidCraftBuilder], whose calls accumulate into SolidCraft's own design
 * document, the same JSON a saved design holds. So a part written here is evaluated by the engine
 * that opens one from disk, and everything downstream treats the two alike.
 * [SolidCraftBuilder.json] is that document and [SolidCraftBuilder.open] evaluates it, giving back
 * a [SolidCraftDesign] to mesh, re-parameterise or export.
 *
 * Only the evaluation needs the native bridge, since the document is assembled as plain JSON. A
 * box can therefore build and inspect a design on a machine where SolidCraft was never compiled,
 * and [SolidCraftLibrary] says whether this one has it.
 *
 * Lengths and angles are SolidCraft expressions rather than numbers: `"12"` in the design's units,
 * a quantity such as `"30 deg"`, or a formula over the design's parameters such as `"width/2"`.
 * That is what keeps a finished part adjustable, through [SolidCraftDesign.setParameter], without
 * rebuilding it here.
 */

/**
 * Builds a SolidCraft design from Kotlin: sketches of points and curves (lines, arcs, circles,
 * splines, 3D wires) and the features that turn them into bodies and surfaces — extrude, revolve,
 * sweep with guide rails, loft, pipe, and the surface sweep, loft, revolve and boundary patch.
 *
 * The builder writes SolidCraft's own design JSON, so [open] evaluates it with the same engine
 * that opens a saved file; an unknown field or a feature that fails to evaluate is reported by
 * the engine with the feature's id. Lengths and angles are SolidCraft expressions: a number
 * (`"12"`, in the design's units), a quantity (`"30 deg"`, `"2 in"`) or a formula over [param]s
 * (`"width/2"`), and parameters stay editable afterwards with [SolidCraftDesign.setParameter].
 *
 * ```kotlin
 * val design = solidCraft("Twisted bar") {
 *     param("twist", "90 deg")
 *     val section = sketch("XY") { rectangle(-4.0, -2.0, 4.0, 2.0) }
 *     val path = sketch("XZ") { line(0.0, 0.0, 0.0, 60.0) }
 *     sweep(section, path, twist = "twist")
 * }.open()
 * ```
 *
 * Anything this builder does not name can be added as raw JSON with [feature]; the schema is
 * SolidCraft's `FeatureKind` (`crates/doc/src/document.rs` at the pinned revision).
 */
fun solidCraft(name: String = "Untitled", units: String = "mm", build: SolidCraftBuilder.() -> Unit): SolidCraftBuilder =
    SolidCraftBuilder(name, units).apply(build)

/** A sketch feature already added to a [SolidCraftBuilder]; pass it to the features that use it. */
class SolidCraftSketchRef internal constructor(val id: Long, val name: String, val curves: List<String>, val wires: List<String>)

/** A feature added to a [SolidCraftBuilder], by the id and name SolidCraft reports it under. */
class SolidCraftFeatureRef internal constructor(val id: Long, val name: String)

/** How a sweep's guide rails reshape its profile: evenly (`SCALE`), or only across to the rail. */
enum class SolidCraftRailFit(internal val json: String) { SCALE("scale"), STRETCH("stretch") }

/** How a joining feature meets bodies already in the design. */
enum class SolidCraftOperation(internal val json: String) { NEW_BODY("new_body"), JOIN("join"), CUT("cut"), INTERSECT("intersect") }

/** How much of a neighbouring surface a patch or loft end repeats: position, tangent, curvature, its change. */
enum class SolidCraftContinuity(internal val json: String) { G0("g0"), G1("g1"), G2("g2"), G3("g3") }

class SolidCraftBuilder internal constructor(private val name: String, private val units: String) {
    private val params = JSONArray()
    private val features = JSONArray()
    private var nextId = 1L
    private val counts = HashMap<String, Int>()

    private fun featureName(kind: String) = "$kind${counts.merge(kind, 1, Int::plus)}"

    /** A named, editable parameter: `param("width", "40 mm")`. `unit` is inferred from a plain quantity. */
    fun param(name: String, expression: String, unit: String = inferUnit(expression)) {
        params.put(JSONObject().put("name", name).put("expr", expression).put("unit", unit).put("model", false))
    }

    /**
     * Adds a sketch on `plane` (`"XY"`, `"XZ"` or `"YZ"`, or [offsetPlane]), drawn by `draw`.
     * Coordinates are the sketch's own 2D ones; wires are 3D, in world coordinates.
     */
    fun sketch(plane: Any = "XY", draw: SolidCraftSketch.() -> Unit): SolidCraftSketchRef {
        val sketch = SolidCraftSketch().apply(draw)
        val planeJson = when (plane) {
            is String -> JSONObject().put("type", "origin").put("name", plane.uppercase())
            is JSONObject -> plane
            else -> throw IllegalArgumentException("plane must be \"XY\", \"XZ\", \"YZ\", offsetPlane(...) or planeSquareTo(...), not $plane")
        }
        val ref = add("Sketch", "sketch") { it.put("plane", planeJson).put("sketch", sketch.json()) }
        return SolidCraftSketchRef(ref.id, ref.name, sketch.curveIds.toList(), sketch.wireIds.toList())
    }

    /** `base` (`"XY"`…) moved along its normal by `distance`, for stacking loft sections. */
    fun offsetPlane(base: String, distance: String): JSONObject =
        JSONObject().put("type", "offset").put("base", JSONObject().put("type", "origin").put("name", base.uppercase())).put("distance", distance)

    /**
     * The plane through the start of `line`, square to it there, with the sketch origin on the
     * line. A profile drawn about (0, 0) on it sweeps along `wire(line)` as a true tube, because a
     * sweep keeps the angle the profile was drawn at to the path's start.
     */
    fun planeSquareTo(line: FLine): JSONObject {
        val cursor = line.cursor()
        require(cursor.lengthD() > 0f) { "FLine has no length" }
        cursor.setD(0f)
        val o = cursor.position().let { doubleArrayOf(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
        cursor.setD(minOf(1e-3f * cursor.lengthD(), 0.01f))
        val q = cursor.position()
        val n = normalize(doubleArrayOf(q.x - o[0], q.y - o[1], q.z - o[2]))
        val helper = if (kotlin.math.abs(n[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val x = normalize(cross(helper, n))
        val y = cross(n, x)
        return JSONObject().put("type", "placed").put("origin", JSONArray(o.toList()))
            .put("x_dir", JSONArray(x.toList())).put("y_dir", JSONArray(y.toList()))
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun normalize(v: DoubleArray): DoubleArray = kotlin.math.sqrt(v.sumOf { it * it }).let { l -> DoubleArray(3) { v[it] / l } }

    /** Extrudes every closed profile of `sketch` by `distance` along its normal. */
    fun extrude(sketch: SolidCraftSketchRef, distance: String, operation: SolidCraftOperation = SolidCraftOperation.NEW_BODY, symmetric: Boolean = false, taper: String? = null) =
        add("Extrude", "extrude") {
            val extent = JSONObject().put("distance", distance).put("direction", if (symmetric) "symmetric" else "positive")
            if (symmetric) extent.put("convention", "total_distance")
            taper?.let { t -> extent.put("taper", t) }
            it.put("sketch", sketch.id).put("profiles", allProfiles()).put("extent", extent).put("operation", operation.json)
        }

    /** Revolves every closed profile of `sketch` about its sketch `axis` (`"x"` or `"y"`). */
    fun revolve(sketch: SolidCraftSketchRef, axis: String = "y", angle: String = "360 deg", operation: SolidCraftOperation = SolidCraftOperation.NEW_BODY) =
        add("Revolve", "revolve") {
            it.put("sketch", sketch.id).put("profiles", allProfiles()).put("axis", sketchAxis(axis)).put("angle", angle)
                .put("operation", operation.json).put("convention", "sketch_normal")
        }

    /**
     * Sweeps the closed profiles of `profile` along `path` (its curves and wires, as one chain
     * unless `pathCurves` picks some). Each of `rails` is a sketch whose chain the profile's far
     * side follows, reshaping it by `railFit`. Without rails, `twist` turns the profile along the
     * way and `scale` grows it to the end; with rails, the rails decide both.
     */
    fun sweep(
        profile: SolidCraftSketchRef, path: SolidCraftSketchRef, pathCurves: List<String> = path.chain(),
        rails: List<SolidCraftSketchRef> = emptyList(), railFit: SolidCraftRailFit = SolidCraftRailFit.SCALE,
        twist: String? = null, scale: String? = null, operation: SolidCraftOperation = SolidCraftOperation.NEW_BODY,
    ) = add("Sweep", "sweep") {
        it.put("sketch", profile.id).put("profiles", allProfiles()).put("operation", operation.json)
        sweepPath(it, path, pathCurves, rails, railFit, twist, scale)
    }

    /** A solid through the closed profiles of `sections`, in order (each sketch one section). */
    fun loft(sections: List<SolidCraftSketchRef>, operation: SolidCraftOperation = SolidCraftOperation.NEW_BODY) = add("Loft", "loft") {
        it.put("sections", JSONArray(sections.map { s -> JSONObject().put("sketch", s.id).put("profiles", allProfiles()) })).put("operation", operation.json)
    }

    /** A round tube of `diameter` along `path`; `wall` makes it hollow. */
    fun pipe(path: SolidCraftSketchRef, diameter: String, wall: String? = null, pathCurves: List<String> = path.chain(), operation: SolidCraftOperation = SolidCraftOperation.NEW_BODY) =
        add("Pipe", "pipe") {
            it.put("path_sketch", path.id).put("path", JSONArray(pathCurves)).put("diameter", diameter).put("operation", operation.json)
            wall?.let { w -> it.put("wall", w) }
        }

    /** A fillet of `radius` on the edges nearest each of `edgesNear` (world points). */
    fun fillet(radius: String, vararg edgesNear: DoubleArray) = add("Fillet", "fillet") {
        it.put("edges", JSONArray(edgesNear.map { p -> JSONArray(p.toList()) })).put("radius", radius)
    }

    /** A surface (open sheet body) swept from the curves of `profile` along `path`; rails as in [sweep]. */
    fun surfaceSweep(
        profile: SolidCraftSketchRef, path: SolidCraftSketchRef, profileCurves: List<String> = profile.curves,
        pathCurves: List<String> = path.chain(), rails: List<SolidCraftSketchRef> = emptyList(),
        railFit: SolidCraftRailFit = SolidCraftRailFit.SCALE, twist: String? = null, scale: String? = null,
    ) = add("SurfaceSweep", "surface_sweep") {
        it.put("sketch", profile.id).put("curves", JSONArray(profileCurves))
        sweepPath(it, path, pathCurves, rails, railFit, twist, scale)
    }

    /** A surface through one curve chain per section sketch; `start`/`end` set how it leaves the first and last. */
    fun surfaceLoft(sections: List<SolidCraftSketchRef>, start: SolidCraftContinuity = SolidCraftContinuity.G0, end: SolidCraftContinuity = SolidCraftContinuity.G0) =
        add("SurfaceLoft", "surface_loft") {
            it.put("sections", JSONArray(sections.map { s -> JSONObject().put("sketch", s.id).put("curves", JSONArray(s.chain())) }))
            it.put("continuity", JSONArray(listOf(start.json, end.json)))
        }

    /** A surface of revolution of the curves of `profile` about its sketch `axis`. */
    fun surfaceRevolve(profile: SolidCraftSketchRef, axis: String = "y", angle: String = "360 deg", curves: List<String> = profile.curves) =
        add("SurfaceRevolve", "surface_revolve") {
            it.put("sketch", profile.id).put("curves", JSONArray(curves)).put("axis", sketchAxis(axis)).put("angle", angle)
        }

    /**
     * One patch through the boundary curves of `sketch`, given in loop order (each ends where
     * the next begins). Two curves give the bridge between them; three or four give the patch
     * their sides span, which takes no `crown`. Draw both curves in the same direction:
     * the bridge joins start to start, so opposed curves give a patch folded over itself.
     * `orders` is the continuity across each boundary; `crown` fills the middle (0 plain, up to
     * 3 fuller, down to -0.9 flatter).
     */
    fun surfaceBoundary(sketch: SolidCraftSketchRef, curves: List<String> = sketch.curves, orders: List<SolidCraftContinuity> = emptyList(), crown: Double = 0.0, samples: Int = 16) =
        add("SurfaceBoundary", "surface_boundary") {
            it.put("sketch", sketch.id).put("curves", JSONArray(curves)).put("orders", JSONArray(orders.map { o -> o.json }))
                .put("crown", crown).put("samples", samples)
        }

    /** Thickens surface bodies (by name, as [SolidCraftDesign.summary] lists them) into solids. */
    fun thicken(bodies: List<String>, thickness: String, symmetric: Boolean = false) = add("Thicken", "thicken") {
        it.put("bodies", JSONArray(bodies)).put("thickness", thickness).put("symmetric", symmetric).put("operation", "new_body")
    }

    /**
     * Any other SolidCraft feature, as its JSON fields beside `"type"` (snake_case, as in
     * `FeatureKind`): `feature("shell", JSONObject().put("faces", ...).put("thickness", "2"))`.
     */
    fun feature(type: String, fields: JSONObject) = add(type.split('_').joinToString("") { p -> p.replaceFirstChar(Char::uppercase) }, type) {
        for (key in fields.keys()) it.put(key, fields.get(key))
    }

    /** The design as SolidCraft's JSON document. */
    fun json(): JSONObject = JSONObject().put("format", "solidcraft/1").put("name", name).put("units", units)
        .put("params", params).put("features", features).put("next_id", nextId)

    /** Evaluates the design; a feature that fails throws with SolidCraft's message. */
    fun open(): SolidCraftDesign = SolidCraftDesign.open(json().toString().toByteArray(Charsets.UTF_8))

    private fun add(kind: String, type: String, fill: (JSONObject) -> Unit): SolidCraftFeatureRef {
        val id = nextId++
        val name = featureName(kind)
        features.put(JSONObject().put("id", id).put("name", name).put("suppressed", false).put("type", type).also(fill))
        return SolidCraftFeatureRef(id, name)
    }

    private fun sweepPath(o: JSONObject, path: SolidCraftSketchRef, pathCurves: List<String>, rails: List<SolidCraftSketchRef>, railFit: SolidCraftRailFit, twist: String?, scale: String?) {
        require(rails.isEmpty() || (twist == null && scale == null)) { "a sweep with guide rails takes no twist or scale: the rails set the profile's turn and size" }
        o.put("path_sketch", path.id).put("path", JSONArray(pathCurves))
        if (rails.isNotEmpty()) o.put("rails", JSONArray(rails.map { r -> JSONObject().put("sketch", r.id).put("curves", JSONArray(r.chain())) }))
        if (railFit != SolidCraftRailFit.SCALE) o.put("rail_fit", railFit.json)
        twist?.let { o.put("twist", it) }
        scale?.let { o.put("scale", it) }
    }

    private fun allProfiles() = JSONObject().put("type", "all")
    private fun sketchAxis(axis: String) = JSONObject().put("type", "sketch_axis").put("axis", axis.lowercase())

    private companion object {
        fun inferUnit(expression: String): String = when {
            expression.endsWith("deg") || expression.endsWith("rad") -> "deg"
            Regex("""[0-9.]\s*(mm|cm|m|in|ft)$""").containsMatchIn(expression) -> "mm"
            else -> ""
        }
    }
}

private fun SolidCraftSketchRef.chain(): List<String> = curves + wires

/**
 * The points and curves of one sketch. Each drawing call returns the new curve's id, which
 * features take to pick curves (`pathCurves`, `surfaceBoundary(curves = ...)`). Endpoints that
 * coincide are shared, so consecutive lines and splines form one connected chain.
 */
class SolidCraftSketch internal constructor() {
    private val points = JSONArray().put(point("origin", 0.0, 0.0, fixed = true))
    private val curves = JSONArray()
    private val wires = JSONArray()
    internal val curveIds = ArrayList<String>()
    internal val wireIds = ArrayList<String>()
    private var next = 1

    private fun point(id: String, x: Double, y: Double, fixed: Boolean = false) =
        JSONObject().put("id", id).put("pos", JSONArray(listOf(x, y))).put("fixed", fixed)

    private fun at(x: Double, y: Double): Int {
        for (i in 1 until points.length()) {
            val p = points.getJSONObject(i).getJSONArray("pos")
            if (kotlin.math.abs(p.getDouble(0) - x) < 1e-9 && kotlin.math.abs(p.getDouble(1) - y) < 1e-9) return i
        }
        points.put(point("p${next++}", x, y, fixed = true))
        return points.length() - 1
    }

    private fun curve(prefix: String, fill: JSONObject.() -> Unit): String {
        val id = "$prefix${next++}"
        curves.put(JSONObject().put("id", id).put("construction", false).apply(fill))
        curveIds += id
        return id
    }

    fun line(x0: Double, y0: Double, x1: Double, y1: Double): String = curve("l") { put("type", "line").put("a", at(x0, y0)).put("b", at(x1, y1)) }

    fun circle(cx: Double, cy: Double, r: Double): String = curve("c") { put("type", "circle").put("c", at(cx, cy)).put("r", r) }

    /** An arc about (cx, cy) running counter-clockwise from (x0, y0) to (x1, y1). */
    fun arc(cx: Double, cy: Double, x0: Double, y0: Double, x1: Double, y1: Double): String =
        curve("a") { put("type", "arc").put("c", at(cx, cy)).put("a", at(x0, y0)).put("b", at(x1, y1)) }

    /** A smooth spline through `xy` (x0, y0, x1, y1, …); `periodic` closes it. */
    fun spline(vararg xy: Double, periodic: Boolean = false, degree: Int = 3): String {
        require(xy.size >= 4 && xy.size % 2 == 0) { "spline needs at least two (x, y) pairs" }
        val pts = (xy.indices step 2).map { at(xy[it], xy[it + 1]) }
        return curve("s") { put("type", "spline").put("pts", JSONArray(pts)).put("degree", degree).also { if (periodic) it.put("periodic", true) } }
    }

    /** Four lines around the rectangle (x0, y0)–(x1, y1). */
    fun rectangle(x0: Double, y0: Double, x1: Double, y1: Double): List<String> =
        listOf(line(x0, y0, x1, y0), line(x1, y0, x1, y1), line(x1, y1, x0, y1), line(x0, y1, x0, y0))

    /** A closed polyline through (x, y) pairs. */
    fun polygon(vararg xy: Double): List<String> {
        require(xy.size >= 6 && xy.size % 2 == 0) { "polygon needs at least three (x, y) pairs" }
        val n = xy.size / 2
        return (0 until n).map { i -> val j = (i + 1) % n; line(xy[2 * i], xy[2 * i + 1], xy[2 * j], xy[2 * j + 1]) }
    }

    /** A 3D curve in world coordinates through `xyz` (x0, y0, z0, …), usable as a sweep path, rail or loft section. */
    fun wire(vararg xyz: Double): String {
        require(xyz.size >= 6 && xyz.size % 3 == 0) { "wire needs at least two (x, y, z) points" }
        val id = "w${next++}"
        wires.put(JSONObject().put("id", id).put("pts", JSONArray((xyz.indices step 3).map { JSONArray(listOf(xyz[it], xyz[it + 1], xyz[it + 2])) })))
        wireIds += id
        return id
    }

    /** `line` as a 3D wire, sampled every `spacing` along its length (its own coordinates, z included). */
    fun wire(line: FLine, spacing: Float = 1f): String {
        val cursor = line.cursor()
        val length = cursor.lengthD()
        require(length > 0f) { "FLine has no length" }
        val xyz = ArrayList<Double>()
        var d = 0f
        while (true) {
            cursor.setD(minOf(d, length))
            val p = cursor.position()
            xyz += p.x.toDouble(); xyz += p.y.toDouble(); xyz += p.z.toDouble()
            if (d >= length) break
            d += spacing
        }
        return wire(*xyz.toDoubleArray())
    }

    internal fun json(): JSONObject = JSONObject().put("points", points).put("curves", curves).put("constraints", JSONArray())
        .also { if (wires.length() > 0) it.put("wires", wires) }
}
