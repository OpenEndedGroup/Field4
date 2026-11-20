@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.rhino

import afield.geometry.mesh.TriMesh
import afield.geometry.path.FLine
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

/**
 * Write a Rhino `.3dm` file to [path] (a leading `~` is your home directory), in [units], filled by
 * [build]. Returns the file.
 *
 * ```kotlin
 * Rhino3dm.save("~/Desktop/spiral.3dm", units = "millimeters") {
 *     layer("Spiral", vec(0.9, 0.3, 0.1, 1.0))
 *     curve(spiralLine, layer = "Spiral", name = "spiral")
 *     mesh(plinth, layer = "Base::Plinth")
 *     text("v1", vec(0.0, -20.0, 0.0), height = 5.0, layer = "Notes")
 * }
 * ```
 *
 * See [RhinoWriter] for what goes in, and [Rhino3dm.save] with a [RhinoModel] to write back a model
 * you read.
 *
 * @param units the model's length unit: a name the reader reports (`"millimeters"`, `"meters"`,
 *   `"inches"`, ...), or `"mm"`, `"cm"`, `"m"`, `"in"`, `"ft"`, or `"none"`.
 * @param version the Rhino version to write for, 2 to 8; 0 writes the newest openNURBS can.
 */
fun Rhino3dm.save(path: String, units: String = "millimeters", version: Int = 0, build: RhinoWriter.() -> Unit): File =
    save(File(path.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home"))), units, version, build)

/** [Rhino3dm.save] to a [File]. */
fun Rhino3dm.save(file: File, units: String = "millimeters", version: Int = 0, build: RhinoWriter.() -> Unit): File {
    val writer = RhinoWriter(RhinoWriter.unitSystem(units))
    try {
        writer.build()
        writer.write(file, version)
    } finally {
        writer.close()
    }
    return file
}

/**
 * Write [model] — perhaps one you read with [Rhino3dm.load] and changed — to [file], in its own
 * units, plus whatever [build] adds. Breps and extrusions go out as the meshes they were read as,
 * hatches as their boundary curves, and [RhinoSkipped] objects are left out.
 */
fun Rhino3dm.save(model: RhinoModel, file: File, version: Int = 0, build: RhinoWriter.() -> Unit = {}): File =
    save(file, model.unitName, version) {
        add(model)
        build()
    }

/** Write this model to [file]: `Rhino3dm.save(this, file)`. */
fun RhinoModel.save(file: File, version: Int = 0): File = Rhino3dm.save(this, file, version)

/**
 * What a [Rhino3dm.save] block fills: layers, and objects placed on them.
 *
 * Layers are named by their full path, `"Parent::Child"`; an object put on a layer that has not
 * been declared makes it (and its parents), black and switched on. Declare one with [layer] first
 * to give it a colour or hide it. Every object takes an optional [name] and [color]; with no colour
 * it is drawn in its layer's, as Rhino does by default.
 *
 * What goes out is exact: a mesh's vertices, a curve's points, and a NURBS curve's control points,
 * weights and knots come back from [Rhino3dm.load] unchanged.
 */
class RhinoWriter internal constructor(unitSystem: Int) : AutoCloseable {

    private var handle: MemorySegment = RhinoWriterNative.create(unitSystem)
    private val layers = linkedMapOf<String, Int>()

    /** How many objects have been added so far. */
    val objectCount: Int get() = RhinoWriterNative.objectCount(live())

    /**
     * Declare the layer [path] (`"Parent::Child"`), coloured [color] and [visible] or not. Parents
     * not declared yet are made, black and on. Declaring a path twice returns the first; returns the path.
     */
    fun layer(path: String, color: Vec4 = vec(0.0, 0.0, 0.0, 1.0), visible: Boolean = true): String {
        layerIndex(path, color, visible)
        return path
    }

    /** A mesh: its triangles, its vertex normals when it has them, and its vertex colours when it has those. */
    fun mesh(mesh: TriMesh, layer: String = "Default", name: String = "", color: Vec4? = null) {
        attributes(layer, name, color)
        RhinoWriterNative.addMesh(live(), mesh)
    }

    /**
     * A curve from an [FLine]: each of its subpaths becomes one Rhino curve — a degree-1 polyline when
     * it is all straight lines, otherwise a polycurve of degree-1 and degree-3 (Bézier) NURBS spans
     * with exactly the line's points. A `close()` is a line back to the subpath's start. Returns how
     * many curves that made.
     */
    fun curve(line: FLine, layer: String = "Default", name: String = "", color: Vec4? = null): Int {
        attributes(layer, name, color)
        return RhinoWriterNative.addPath(live(), line)
    }

    /**
     * A NURBS curve of [degree] through control points [points], with [knots] in openNURBS's
     * convention — `points.size + degree - 1` of them, without the two superfluous end knots — and,
     * for a rational curve, one weight a point ([points] are given unweighted).
     */
    fun nurbs(
        degree: Int,
        points: List<Vec3>,
        knots: List<Double>,
        weights: List<Double>? = null,
        layer: String = "Default",
        name: String = "",
        color: Vec4? = null,
    ) {
        require(degree >= 1) { "degree must be at least 1, got $degree" }
        require(points.size > degree) { "a degree-$degree curve needs at least ${degree + 1} points, got ${points.size}" }
        require(knots.size == points.size + degree - 1) {
            "a degree-$degree curve with ${points.size} points takes ${points.size + degree - 1} knots, got ${knots.size}"
        }
        require(weights == null || weights.size == points.size) { "one weight a point: ${points.size}, got ${weights?.size}" }
        attributes(layer, name, color)
        RhinoWriterNative.addNurbs(live(), degree, points, knots, weights)
    }

    /** One point, or a point cloud when there are several. */
    fun points(points: List<Vec3>, layer: String = "Default", name: String = "", color: Vec4? = null) {
        if (points.isEmpty()) return
        attributes(layer, name, color)
        RhinoWriterNative.addPoints(live(), points)
    }

    /** A single point. */
    fun point(point: Vec3, layer: String = "Default", name: String = "", color: Vec4? = null) =
        points(listOf(point), layer, name, color)

    /**
     * Text [text] at [origin], [height] (Rhino's capital height) in model units, on the plane spanned
     * by [xAxis] (along the baseline) and [yAxis] (up the letters) — flat on the ground by default.
     */
    fun text(
        text: String,
        origin: Vec3,
        height: Double = 1.0,
        xAxis: Vec3 = vec(1.0, 0.0, 0.0),
        yAxis: Vec3 = vec(0.0, 1.0, 0.0),
        layer: String = "Default",
        name: String = "",
        color: Vec4? = null,
    ) {
        attributes(layer, name, color)
        RhinoWriterNative.addText(live(), text, origin, xAxis, yAxis, height)
    }

    /**
     * Everything in [model]: its layers, with their colours and visibility, and its meshes, curves,
     * text and points on them. An object keeps its own colour when it differs from its layer's.
     * Returns how many objects were left out ([RhinoSkipped] ones).
     */
    fun add(model: RhinoModel): Int {
        for (l in model.layers) layer(l.path, l.color, l.ownVisibility)
        var left = 0
        for (o in model.objects) {
            val layer = o.layer?.path ?: "Default"
            val color = o.color.takeIf { o.layer == null || !same(it, o.layer.color) }
            when (o) {
                is RhinoMesh -> mesh(o.mesh, layer, o.name, color)
                is RhinoCurve -> curve(o.line, layer, o.name, color)
                is RhinoText -> text(o.text, o.origin, o.height, o.xAxis, o.yAxis, layer, o.name, color)
                is RhinoPoints -> points(o.points, layer, o.name, color)
                is RhinoSkipped -> left++
            }
        }
        return left
    }

    internal fun write(file: File, version: Int) {
        file.absoluteFile.parentFile?.mkdirs()
        RhinoWriterNative.write(live(), file, version)
    }

    override fun close() {
        if (handle != MemorySegment.NULL) RhinoWriterNative.free(handle)
        handle = MemorySegment.NULL
    }

    private fun live(): MemorySegment {
        check(handle != MemorySegment.NULL) { "this RhinoWriter has already been written and closed" }
        return handle
    }

    private fun attributes(layer: String, name: String, color: Vec4?) {
        RhinoWriterNative.attributes(live(), layerIndex(layer, null, true), color, name)
    }

    private fun layerIndex(path: String, color: Vec4?, visible: Boolean): Int {
        require(path.isNotBlank()) { "a layer needs a name" }
        layers[path]?.let { return it }
        val parts = path.split("::")
        val parent = if (parts.size > 1) layerIndex(parts.dropLast(1).joinToString("::"), null, true) else -1
        val index = RhinoWriterNative.addLayer(live(), parts.last(), color ?: vec(0.0, 0.0, 0.0, 1.0), parent, visible)
        layers[path] = index
        return index
    }

    private fun same(a: Vec4, b: Vec4) = argb(a) == argb(b)

    companion object {
        /** ON::LengthUnitSystem's number for [units]; see [Rhino3dm.save]. */
        fun unitSystem(units: String): Int = when (units.trim().lowercase()) {
            "none", "no units", "" -> 0
            "um", "micron", "microns" -> 1
            "mm", "millimeter", "millimeters", "millimetre", "millimetres" -> 2
            "cm", "centimeter", "centimeters", "centimetre", "centimetres" -> 3
            "m", "meter", "meters", "metre", "metres" -> 4
            "km", "kilometer", "kilometers" -> 5
            "in", "inch", "inches" -> 8
            "ft", "foot", "feet" -> 9
            "mi", "mile", "miles" -> 10
            "nm", "nanometer", "nanometers" -> 13
            "yd", "yard", "yards" -> 19
            else -> throw IllegalArgumentException(
                "Unknown units \"$units\"; use millimeters, centimeters, meters, inches, feet or none",
            )
        }

        internal fun argb(c: Vec4): Int {
            fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            return (byte(c.w) shl 24) or (byte(c.x) shl 16) or (byte(c.y) shl 8) or byte(c.z)
        }
    }
}

/** The FFM half of [RhinoWriter]: field_opennurbs_write.h. */
internal object RhinoWriterNative {

    private const val MESSAGE_CAPACITY = 2048
    private const val OP_MOVE = 0
    private const val OP_LINE = 1
    private const val OP_CUBIC = 3

    private val linker: Linker = Linker.nativeLinker()

    private val lookup: SymbolLookup by lazy {
        val path = OpenNurbsLibrary.resolvedPath
            ?: throw UnsatisfiedLinkError(
                "The openNURBS wrapper is not built. ${OpenNurbsLibrary.HOW_TO_BUILD}\n" +
                    "Looked at: " + OpenNurbsLibrary.searchedPaths.joinToString(", "),
            )
        SymbolLookup.libraryLookup(path, Arena.global())
    }

    private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookup.find(name).orElseThrow {
            UnsatisfiedLinkError("${OpenNurbsLibrary.fileName} has no symbol $name; rebuild it with ./gradlew buildOpenNurbsNative")
        }
        return linker.downcallHandle(symbol, descriptor)
    }

    private val newHandle by lazy { handle("field_opennurbs_writer_new", FunctionDescriptor.of(ADDRESS, JAVA_INT)) }
    private val freeHandle by lazy { handle("field_opennurbs_writer_free", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val layerHandle by lazy {
        handle("field_opennurbs_writer_add_layer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT))
    }
    private val attributesHandle by lazy {
        handle(
            "field_opennurbs_writer_attributes",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
        )
    }
    private val meshHandle by lazy {
        handle(
            "field_opennurbs_writer_add_mesh",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
        )
    }
    private val nurbsHandle by lazy {
        handle(
            "field_opennurbs_writer_add_nurbs_curve",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
        )
    }
    private val pathHandle by lazy {
        handle("field_opennurbs_writer_add_path", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT))
    }
    private val pointsHandle by lazy {
        handle("field_opennurbs_writer_add_points", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
    }
    private val textHandle by lazy {
        handle(
            "field_opennurbs_writer_add_text",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_DOUBLE),
        )
    }
    private val countHandle by lazy { handle("field_opennurbs_writer_object_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val writeHandle by lazy {
        handle("field_opennurbs_writer_write", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT))
    }

    fun create(unitSystem: Int): MemorySegment {
        val w = newHandle.invokeExact(unitSystem) as MemorySegment
        if (w == MemorySegment.NULL) throw RhinoException("openNURBS could not make an empty model")
        return w
    }

    fun free(w: MemorySegment) {
        freeHandle.invokeExact(w)
    }

    fun objectCount(w: MemorySegment): Int = countHandle.invokeExact(w) as Int

    fun addLayer(w: MemorySegment, name: String, color: Vec4, parent: Int, visible: Boolean): Int = Arena.ofConfined().use { a ->
        val index = layerHandle.invokeExact(w, a.allocateFrom(name), RhinoWriter.argb(color), parent, if (visible) 1 else 0) as Int
        if (index < 0) throw RhinoException("openNURBS would not add the layer \"$name\"")
        index
    }

    fun attributes(w: MemorySegment, layer: Int, color: Vec4?, name: String) {
        Arena.ofConfined().use { a ->
            val argb = color?.let(RhinoWriter::argb) ?: 0
            attributesHandle.invokeExact(w, layer, if (color != null) 1 else 0, argb, a.allocateFrom(name), 1)
            Unit // the void call must not be the lambda's value, or invokeExact expects an Object back
        }
    }

    fun addMesh(w: MemorySegment, mesh: TriMesh) = Arena.ofConfined().use { a ->
        require(mesh.triangleCount > 0) { "a mesh with no triangles cannot be written to a .3dm" }
        val xyz = a.allocateFrom(JAVA_DOUBLE, *DoubleArray(mesh.positions.size) { mesh.positions[it].toDouble() })
        val normals = mesh.normals?.let { a.allocateFrom(JAVA_FLOAT, *it) } ?: MemorySegment.NULL
        val colors = mesh.colors?.let { c ->
            a.allocateFrom(JAVA_INT, *IntArray(c.size / 3) { RhinoWriter.argb(vec(c[3 * it].toDouble(), c[3 * it + 1].toDouble(), c[3 * it + 2].toDouble(), 1.0)) })
        } ?: MemorySegment.NULL
        val status = meshHandle.invokeExact(
            w, xyz, mesh.positions.size / 3, a.allocateFrom(JAVA_INT, *mesh.indices), mesh.triangleCount, normals, colors,
        ) as Int
        if (status != 0) throw RhinoException("openNURBS would not add a mesh of ${mesh.triangleCount} triangles")
    }

    fun addNurbs(w: MemorySegment, degree: Int, points: List<Vec3>, knots: List<Double>, weights: List<Double>?) =
        Arena.ofConfined().use { a ->
            val status = nurbsHandle.invokeExact(
                w, degree, points.size, a.allocateFrom(JAVA_DOUBLE, *xyz(points)),
                weights?.let { a.allocateFrom(JAVA_DOUBLE, *it.toDoubleArray()) } ?: MemorySegment.NULL,
                a.allocateFrom(JAVA_DOUBLE, *knots.toDoubleArray()),
            ) as Int
            if (status != 0) throw RhinoException("openNURBS rejected a degree-$degree NURBS curve with ${points.size} points as invalid")
        }

    fun addPath(w: MemorySegment, line: FLine): Int {
        val points = ArrayList<Vec3>()
        val ops = ArrayList<Int>()
        var start: Vec3? = null
        var at: Vec3? = null
        for (node in line.nodes) {
            when (node) {
                is FLine.MoveTo -> { ops += OP_MOVE; points += node.to; start = node.to; at = node.to }
                is FLine.LineTo -> { if (at == null) { ops += OP_MOVE; points += node.to; start = node.to } else { ops += OP_LINE; points += node.to }; at = node.to }
                is FLine.CubicTo -> {
                    if (at == null) throw IllegalArgumentException("an FLine cannot start with a cubic")
                    ops += OP_CUBIC
                    points += node.c1
                    points += node.c2
                    points += node.to
                    at = node.to
                }
                is FLine.Close -> {
                    val s = start
                    if (s != null && at != null && s.distance(at) > 0f) { ops += OP_LINE; points += s; at = s }
                }
            }
        }
        if (ops.isEmpty()) return 0
        return Arena.ofConfined().use { a ->
            val added = pathHandle.invokeExact(
                w, a.allocateFrom(JAVA_DOUBLE, *xyz(points)), points.size, a.allocateFrom(JAVA_INT, *ops.toIntArray()), ops.size,
            ) as Int
            if (added < 0) throw RhinoException("openNURBS would not add a curve from $line")
            added
        }
    }

    fun addPoints(w: MemorySegment, points: List<Vec3>) = Arena.ofConfined().use { a ->
        val status = pointsHandle.invokeExact(w, a.allocateFrom(JAVA_DOUBLE, *xyz(points)), points.size) as Int
        if (status != 0) throw RhinoException("openNURBS would not add ${points.size} points")
    }

    fun addText(w: MemorySegment, text: String, origin: Vec3, xAxis: Vec3, yAxis: Vec3, height: Double) =
        Arena.ofConfined().use { a ->
            val status = textHandle.invokeExact(
                w, a.allocateFrom(text),
                a.allocateFrom(JAVA_DOUBLE, *xyz(listOf(origin))),
                a.allocateFrom(JAVA_DOUBLE, *xyz(listOf(xAxis))),
                a.allocateFrom(JAVA_DOUBLE, *xyz(listOf(yAxis))),
                height,
            ) as Int
            if (status != 0) throw RhinoException("openNURBS would not add the text \"$text\" (are the axes parallel?)")
        }

    fun write(w: MemorySegment, file: File, version: Int) = Arena.ofConfined().use { a ->
        val message = a.allocate(JAVA_BYTE, MESSAGE_CAPACITY.toLong())
        val status = writeHandle.invokeExact(w, a.allocateFrom(file.absolutePath), version, message, MESSAGE_CAPACITY) as Int
        if (status != 0) throw RhinoException("Could not write ${file.absolutePath}: ${message.getString(0)}")
    }

    private fun xyz(points: List<Vec3>) = DoubleArray(points.size * 3) { k ->
        val p = points[k / 3]
        when (k % 3) { 0 -> p.x.toDouble(); 1 -> p.y.toDouble(); else -> p.z.toDouble() }
    }
}
