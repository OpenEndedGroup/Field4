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
import kotlin.math.PI
import kotlin.math.sqrt

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
    private val blocks = linkedMapOf<String, Int>()

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
     * A Brep: [faces] joined into one object. openNURBS builds the topology: each trim's 3D edge,
     * shared corners, one shared edge wherever two trims trace the same curve (a seam, or two faces
     * meeting), and a singular trim where a trim collapses to a point, as at a sphere's poles. A face
     * with a [RhinoBrepFace.renderMesh] carries it as Rhino's cached render mesh. [RhinoBreps] makes the
     * faces of a sphere, a cylinder, a box or a plate with holes.
     */
    fun brep(faces: List<RhinoBrepFace>, layer: String = "Default", name: String = "", color: Vec4? = null) {
        require(faces.isNotEmpty()) { "a Brep needs at least one face" }
        attributes(layer, name, color)
        RhinoWriterNative.addBrep(live(), faces)
    }

    /** An untrimmed NURBS surface, which Rhino keeps as a one-face Brep. */
    fun surface(surface: NurbsSurface, layer: String = "Default", name: String = "", color: Vec4? = null) {
        attributes(layer, name, color)
        RhinoWriterNative.addSurface(live(), surface)
    }

    /**
     * A SubD from its control net: [points], [faces] as vertex indices in order (counter-clockwise seen
     * from outside), and [creases] as vertex index pairs of edges to keep sharp. Where three creases
     * meet the vertex becomes a corner. [RhinoBreps.subdBox] makes the net of a box.
     */
    fun subd(
        points: List<Vec3>,
        faces: List<IntArray>,
        creases: List<Pair<Int, Int>> = emptyList(),
        layer: String = "Default",
        name: String = "",
        color: Vec4? = null,
    ) {
        require(faces.isNotEmpty() && faces.all { it.size >= 3 }) { "a SubD needs faces of three or more vertices" }
        attributes(layer, name, color)
        RhinoWriterNative.addSubD(live(), points, faces, creases)
    }

    /** A solid hatch filling [boundary]: its first subpath is the outline, the others holes, on a plane of constant z. */
    fun hatch(boundary: FLine, layer: String = "Default", name: String = "", color: Vec4? = null) {
        attributes(layer, name, color)
        RhinoWriterNative.addHatch(live(), boundary)
    }

    /**
     * A block definition named [name], holding whatever [build] adds (on the layers it names); place it
     * with [instance]. Returns the name.
     */
    fun block(name: String, build: RhinoWriter.() -> Unit): String {
        require(name !in blocks) { "a block named \"$name\" is already defined" }
        RhinoWriterNative.beginBlock(live(), name)
        try {
            build()
        } finally {
            blocks[name] = RhinoWriterNative.endBlock(live())
        }
        return name
    }

    /** Place block [block], moved by [translation] (and, when given, transformed by the row-major 4 x 4 [matrix] first). */
    fun instance(
        block: String,
        translation: Vec3 = vec(0.0, 0.0, 0.0),
        matrix: DoubleArray? = null,
        layer: String = "Default",
        name: String = "",
        color: Vec4? = null,
    ) {
        val index = blocks[block] ?: throw IllegalArgumentException("no block named \"$block\"; define it with block(...) first")
        require(matrix == null || matrix.size == 16) { "a block transform is 16 numbers, row-major" }
        val m = matrix?.copyOf() ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        m[3] += translation.x.toDouble(); m[7] += translation.y.toDouble(); m[11] += translation.z.toDouble()
        attributes(layer, name, color)
        RhinoWriterNative.addInstance(live(), index, m)
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
    private val brepHandle by lazy {
        handle("field_opennurbs_writer_add_brep", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT))
    }
    private val surfaceHandle by lazy {
        handle(
            "field_opennurbs_writer_add_surface",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
        )
    }
    private val subdHandle by lazy {
        handle(
            "field_opennurbs_writer_add_subd",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
        )
    }
    private val hatchHandle by lazy {
        handle("field_opennurbs_writer_add_hatch", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT))
    }
    private val beginBlockHandle by lazy { handle("field_opennurbs_writer_begin_block", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)) }
    private val endBlockHandle by lazy { handle("field_opennurbs_writer_end_block", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val instanceHandle by lazy {
        handle("field_opennurbs_writer_add_instance", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
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

    /** The packed layout of `field_opennurbs_writer_add_brep`. */
    internal fun packBrep(faces: List<RhinoBrepFace>): DoubleArray {
        val out = ArrayList<Double>()
        out += faces.size.toDouble()
        for (f in faces) {
            out += if (f.reversed) 1.0 else 0.0
            packSurface(f.surface, out)
            out += f.loops.size.toDouble()
            for (loop in f.loops) {
                out += if (loop.inner) 2.0 else 1.0
                out += loop.trims.size.toDouble()
                for (t in loop.trims) {
                    out += t.degree.toDouble()
                    out += t.count.toDouble()
                    for (k in t.knots) out += k
                    for (i in 0 until t.count) {
                        val w = t.weights?.get(i) ?: 1.0
                        out += t.points[2 * i] * w; out += t.points[2 * i + 1] * w; out += w
                    }
                }
            }
            val mesh = f.renderMesh
            if (mesh == null || mesh.triangleCount == 0) out += 0.0 else {
                out += mesh.vertexCount.toDouble()
                for (p in mesh.positions) out += p.toDouble()
                out += mesh.triangleCount.toDouble()
                for (i in mesh.indices) out += i.toDouble()
            }
        }
        return out.toDoubleArray()
    }

    private fun packSurface(s: NurbsSurface, out: MutableList<Double>) {
        out += s.degreeU.toDouble(); out += s.degreeV.toDouble(); out += s.countU.toDouble(); out += s.countV.toDouble()
        for (i in 1 until s.knotsU.size - 1) out += s.knotsU[i]  // openNURBS drops the two end knots
        for (i in 1 until s.knotsV.size - 1) out += s.knotsV[i]
        for (c in s.cvs) out += c
    }

    fun addBrep(w: MemorySegment, faces: List<RhinoBrepFace>) = Arena.ofConfined().use { a ->
        val packed = packBrep(faces)
        val message = a.allocate(JAVA_BYTE, MESSAGE_CAPACITY.toLong())
        val status = brepHandle.invokeExact(w, a.allocateFrom(JAVA_DOUBLE, *packed), packed.size, message, MESSAGE_CAPACITY) as Int
        if (status != 0) throw RhinoException("openNURBS would not build a Brep of ${faces.size} faces: ${message.getString(0)}")
    }

    fun addSurface(w: MemorySegment, s: NurbsSurface) = Arena.ofConfined().use { a ->
        val status = surfaceHandle.invokeExact(
            w, s.degreeU, s.degreeV, s.countU, s.countV,
            a.allocateFrom(JAVA_DOUBLE, *s.knotsU.copyOfRange(1, s.knotsU.size - 1)),
            a.allocateFrom(JAVA_DOUBLE, *s.knotsV.copyOfRange(1, s.knotsV.size - 1)),
            a.allocateFrom(JAVA_DOUBLE, *s.cvs),
        ) as Int
        if (status != 0) throw RhinoException("openNURBS rejected a ${s.countU} x ${s.countV} NURBS surface as invalid")
    }

    fun addSubD(w: MemorySegment, points: List<Vec3>, faces: List<IntArray>, creases: List<Pair<Int, Int>>) =
        Arena.ofConfined().use { a ->
            val flat = faces.flatMap { it.asList() }.toIntArray()
            val pairs = creases.flatMap { listOf(it.first, it.second) }.toIntArray()
            val status = subdHandle.invokeExact(
                w, a.allocateFrom(JAVA_DOUBLE, *xyz(points)), points.size,
                a.allocateFrom(JAVA_INT, *faces.map { it.size }.toIntArray()), faces.size,
                a.allocateFrom(JAVA_INT, *flat),
                if (pairs.isEmpty()) MemorySegment.NULL else a.allocateFrom(JAVA_INT, *pairs), creases.size,
            ) as Int
            if (status != 0) throw RhinoException("openNURBS would not build a SubD from ${points.size} points and ${faces.size} faces")
        }

    fun addHatch(w: MemorySegment, line: FLine) {
        val (points, ops) = pathOps(line)
        require(ops.isNotEmpty()) { "a hatch needs a boundary" }
        Arena.ofConfined().use { a ->
            val status = hatchHandle.invokeExact(
                w, a.allocateFrom(JAVA_DOUBLE, *xyz(points)), points.size, a.allocateFrom(JAVA_INT, *ops.toIntArray()), ops.size,
            ) as Int
            if (status != 0) throw RhinoException("openNURBS would not make a hatch from $line (are its loops closed and flat?)")
        }
    }

    fun beginBlock(w: MemorySegment, name: String) = Arena.ofConfined().use { a ->
        val status = beginBlockHandle.invokeExact(w, a.allocateFrom(name)) as Int
        if (status != 0) throw RhinoException("cannot start block \"$name\" (blocks do not nest)")
    }

    fun endBlock(w: MemorySegment): Int {
        val index = endBlockHandle.invokeExact(w) as Int
        if (index < 0) throw RhinoException("openNURBS would not add the block definition")
        return index
    }

    fun addInstance(w: MemorySegment, block: Int, matrix: DoubleArray) = Arena.ofConfined().use { a ->
        val status = instanceHandle.invokeExact(w, block, a.allocateFrom(JAVA_DOUBLE, *matrix)) as Int
        if (status != 0) throw RhinoException("openNURBS would not place block $block (is a block still open?)")
    }

    fun addPath(w: MemorySegment, line: FLine): Int {
        val (points, ops) = pathOps(line)
        if (ops.isEmpty()) return 0
        return Arena.ofConfined().use { a ->
            val added = pathHandle.invokeExact(
                w, a.allocateFrom(JAVA_DOUBLE, *xyz(points)), points.size, a.allocateFrom(JAVA_INT, *ops.toIntArray()), ops.size,
            ) as Int
            if (added < 0) throw RhinoException("openNURBS would not add a curve from $line")
            added
        }
    }

    private fun pathOps(line: FLine): Pair<List<Vec3>, List<Int>> {
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
        return points to ops
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

/**
 * A trim curve for [RhinoWriter.brep]: a 2D NURBS curve in its face's surface parameters.
 *
 * @param degree polynomial degree, at least 1.
 * @param points `u v` per control point, unweighted.
 * @param knots `count + degree - 1` knots in openNURBS's convention, without the two superfluous end knots.
 * @param weights one per control point for a rational curve, else `null`.
 */
class RhinoTrim(val degree: Int, val points: DoubleArray, val knots: DoubleArray, val weights: DoubleArray? = null) {
    /** How many control points. */
    val count: Int get() = points.size / 2

    init {
        require(points.size % 2 == 0) { "trim points are u v pairs, got ${points.size} numbers" }
        require(degree >= 1 && count > degree) { "a degree-$degree trim needs at least ${degree + 1} points, got $count" }
        require(knots.size == count + degree - 1) { "a degree-$degree trim with $count points takes ${count + degree - 1} knots, got ${knots.size}" }
        require(weights == null || weights.size == count) { "one weight a point: $count, got ${weights?.size}" }
    }

    companion object {
        /** The straight trim from `(u0, v0)` to `(u1, v1)`. */
        fun line(u0: Double, v0: Double, u1: Double, v1: Double) =
            RhinoTrim(1, doubleArrayOf(u0, v0, u1, v1), doubleArrayOf(0.0, 1.0))

        /**
         * The exact circle of [radius] about `(u, v)`, starting at angle 0: counter-clockwise for an outer
         * loop, [clockwise] for a hole.
         */
        fun circle(u: Double, v: Double, radius: Double, clockwise: Boolean = false): RhinoTrim {
            val s = sqrt(0.5)
            val unit = doubleArrayOf(1.0, 0.0, 1.0, 1.0, 0.0, 1.0, -1.0, 1.0, -1.0, 0.0, -1.0, -1.0, 0.0, -1.0, 1.0, -1.0, 1.0, 0.0)
            val weights = DoubleArray(9) { if (it % 2 == 0) 1.0 else s }
            val points = DoubleArray(18) { k -> (if (k % 2 == 0) u else v) + radius * unit[if (clockwise) (8 - k / 2) * 2 + k % 2 else k] }
            return RhinoTrim(2, points, doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 2.0, 3.0, 3.0, 4.0, 4.0), weights)
        }
    }
}

/** One trim loop: [trims] end to end, an outer boundary (counter-clockwise in uv) or, when [inner], a hole (clockwise). */
class RhinoTrimLoop(val trims: List<RhinoTrim>, val inner: Boolean = false)

/**
 * A face for [RhinoWriter.brep]: a [surface], trimmed by [loops] (by default the edges of the
 * surface's domain), facing against the surface's normal when [reversed], with an optional cached
 * [renderMesh] such as Rhino saves with a shaded model.
 */
class RhinoBrepFace(
    val surface: NurbsSurface,
    val loops: List<RhinoTrimLoop> = listOf(domainLoop(surface)),
    val reversed: Boolean = false,
    val renderMesh: TriMesh? = null,
) {
    companion object {
        /** The outer loop around [surface]'s whole domain: four straight trims, counter-clockwise. */
        fun domainLoop(surface: NurbsSurface): RhinoTrimLoop {
            val (u0, u1) = surface.domainU.let { it[0] to it[1] }
            val (v0, v1) = surface.domainV.let { it[0] to it[1] }
            return RhinoTrimLoop(
                listOf(
                    RhinoTrim.line(u0, v0, u1, v0), RhinoTrim.line(u1, v0, u1, v1),
                    RhinoTrim.line(u1, v1, u0, v1), RhinoTrim.line(u0, v1, u0, v0),
                ),
            )
        }
    }
}

/**
 * Exact NURBS faces of common solids for [RhinoWriter.brep], and the control net of a box for
 * [RhinoWriter.subd]. Every closed solid faces outwards.
 *
 * ```kotlin
 * Rhino3dm.save("~/Desktop/solids.3dm") {
 *     brep(RhinoBreps.sphere(vec(0.0, 0.0, 0.0), 10.0), name = "ball")
 *     brep(RhinoBreps.cylinder(vec(30.0, 0.0, 0.0), 5.0, 20.0), name = "post")
 * }
 * ```
 */
object RhinoBreps {

    private val s = sqrt(0.5)
    private val circleX = doubleArrayOf(1.0, 1.0, 0.0, -1.0, -1.0, -1.0, 0.0, 1.0, 1.0)
    private val circleY = doubleArrayOf(0.0, 1.0, 1.0, 1.0, 0.0, -1.0, -1.0, -1.0, 0.0)
    private val circleW = DoubleArray(9) { if (it % 2 == 0) 1.0 else s }
    private val circleKnots = doubleArrayOf(0.0, 0.0, 0.0, 0.5, 0.5, 1.0, 1.0, 1.5, 1.5, 2.0, 2.0, 2.0).map { it * PI }.toDoubleArray()

    /** A sphere: one face, u around the z axis, v from the south pole to the north, collapsing at both poles. */
    fun sphere(center: Vec3, radius: Double): List<RhinoBrepFace> {
        // The meridian: a half circle in (distance from the axis, z), five rational points.
        val mr = doubleArrayOf(0.0, 1.0, 1.0, 1.0, 0.0)
        val mz = doubleArrayOf(-1.0, -1.0, 0.0, 1.0, 1.0)
        val mw = doubleArrayOf(1.0, s, 1.0, s, 1.0)
        val cvs = DoubleArray(9 * 5 * 4)
        for (i in 0 until 9) for (j in 0 until 5) {
            val w = circleW[i] * mw[j]
            val at = (i * 5 + j) * 4
            cvs[at] = (center.x + radius * circleX[i] * mr[j]) * w
            cvs[at + 1] = (center.y + radius * circleY[i] * mr[j]) * w
            cvs[at + 2] = (center.z + radius * mz[j]) * w
            cvs[at + 3] = w
        }
        val kv = doubleArrayOf(0.0, 0.0, 0.0, 0.5, 0.5, 1.0, 1.0, 1.0).map { (it - 0.5) * PI }.toDoubleArray()
        return listOf(RhinoBrepFace(NurbsSurface(2, 2, 9, 5, circleKnots, kv, cvs)))
    }

    /**
     * A cylinder of [radius] about the z axis through [base], [height] tall, closed by flat caps when
     * [capped]: three faces that share their circular edges, or one open tube.
     */
    fun cylinder(base: Vec3, radius: Double, height: Double, capped: Boolean = true): List<RhinoBrepFace> {
        val cvs = DoubleArray(9 * 2 * 4)
        for (i in 0 until 9) for (j in 0 until 2) {
            val w = circleW[i]
            val at = (i * 2 + j) * 4
            cvs[at] = (base.x + radius * circleX[i]) * w
            cvs[at + 1] = (base.y + radius * circleY[i]) * w
            cvs[at + 2] = (base.z + height * j) * w
            cvs[at + 3] = w
        }
        val side = RhinoBrepFace(NurbsSurface(2, 1, 9, 2, circleKnots, doubleArrayOf(0.0, 0.0, height, height), cvs))
        if (!capped) return listOf(side)
        fun cap(z: Double, down: Boolean): RhinoBrepFace {
            val c = vec(base.x.toDouble(), base.y.toDouble(), z)
            val plane = flat(c, radius)
            return RhinoBrepFace(plane, listOf(RhinoTrimLoop(listOf(RhinoTrim.circle(0.0, 0.0, radius)))), reversed = down)
        }
        return listOf(side, cap(base.z.toDouble(), true), cap(base.z + height, false))
    }

    /**
     * A square plate in the plane z = [center].z, [size] across, centred on [center], with circular
     * holes given as `(x, y, radius)` in model coordinates. It faces +z.
     */
    fun plate(center: Vec3, size: Double, holes: List<Triple<Double, Double, Double>> = emptyList()): List<RhinoBrepFace> {
        val surface = flat(center, size / 2)
        val loops = listOf(RhinoBrepFace.domainLoop(surface)) + holes.map { (x, y, r) ->
            RhinoTrimLoop(listOf(RhinoTrim.circle(x - center.x, y - center.y, r, clockwise = true)), inner = true)
        }
        return listOf(RhinoBrepFace(surface, loops))
    }

    /**
     * The axis-aligned box from [lo] to [hi]: six flat faces. With [savedMeshes] each face carries the
     * two triangles of its square as a cached render mesh, as Rhino saves a shaded box.
     */
    fun box(lo: Vec3, hi: Vec3, savedMeshes: Boolean = false): List<RhinoBrepFace> {
        fun p(x: Boolean, y: Boolean, z: Boolean) =
            vec(if (x) hi.x else lo.x, if (y) hi.y else lo.y, if (z) hi.z else lo.z)
        // Each face as (origin, +u corner, +v corner, far corner), with u x v pointing out.
        val quads = listOf(
            listOf(p(false, false, false), p(false, true, false), p(true, false, false), p(true, true, false)), // bottom, -z
            listOf(p(false, false, true), p(true, false, true), p(false, true, true), p(true, true, true)), // top, +z
            listOf(p(false, false, false), p(true, false, false), p(false, false, true), p(true, false, true)), // front, -y
            listOf(p(false, true, false), p(false, true, true), p(true, true, false), p(true, true, true)), // back, +y
            listOf(p(false, false, false), p(false, false, true), p(false, true, false), p(false, true, true)), // left, -x
            listOf(p(true, false, false), p(true, true, false), p(true, false, true), p(true, true, true)), // right, +x
        )
        return quads.map { (a, b, c, d) ->
            val surface = bilinear(a, b, c, d)
            val mesh = if (!savedMeshes) null else TriMesh(
                floatArrayOf(a.x, a.y, a.z, b.x, b.y, b.z, d.x, d.y, d.z, c.x, c.y, c.z),
                intArrayOf(0, 1, 2, 0, 2, 3),
            )
            RhinoBrepFace(surface, renderMesh = mesh)
        }
    }

    /**
     * The control net of a box from [lo] to [hi], [segments] faces along each side, for
     * [RhinoWriter.subd]: its points, its quads facing out, and, when [creased], the box's twelve edges
     * as creases (its limit surface is then the box itself; smooth, it rounds inside the net).
     */
    fun subdBox(lo: Vec3, hi: Vec3, segments: Int = 2, creased: Boolean = false): Triple<List<Vec3>, List<IntArray>, List<Pair<Int, Int>>> {
        require(segments >= 1) { "a SubD box needs at least one segment a side" }
        val points = ArrayList<Vec3>()
        val index = HashMap<Triple<Int, Int, Int>, Int>()
        fun id(i: Int, j: Int, k: Int) = index.getOrPut(Triple(i, j, k)) {
            points += vec(
                lo.x + (hi.x - lo.x) * i / segments.toDouble(),
                lo.y + (hi.y - lo.y) * j / segments.toDouble(),
                lo.z + (hi.z - lo.z) * k / segments.toDouble(),
            )
            points.size - 1
        }
        val n = segments
        val faces = ArrayList<IntArray>()
        for (a in 0 until n) for (b in 0 until n) {
            faces += intArrayOf(id(a, b, 0), id(a, b + 1, 0), id(a + 1, b + 1, 0), id(a + 1, b, 0)) // -z
            faces += intArrayOf(id(a, b, n), id(a + 1, b, n), id(a + 1, b + 1, n), id(a, b + 1, n)) // +z
            faces += intArrayOf(id(a, 0, b), id(a + 1, 0, b), id(a + 1, 0, b + 1), id(a, 0, b + 1)) // -y
            faces += intArrayOf(id(a, n, b), id(a, n, b + 1), id(a + 1, n, b + 1), id(a + 1, n, b)) // +y
            faces += intArrayOf(id(0, a, b), id(0, a, b + 1), id(0, a + 1, b + 1), id(0, a + 1, b)) // -x
            faces += intArrayOf(id(n, a, b), id(n, a + 1, b), id(n, a + 1, b + 1), id(n, a, b + 1)) // +x
        }
        val creases = ArrayList<Pair<Int, Int>>()
        if (creased) {
            val ijk = index.entries.associate { (k, v) -> v to k }
            fun onEdge(t: Triple<Int, Int, Int>) = listOf(t.first, t.second, t.third).count { it == 0 || it == n } >= 2
            val seen = HashSet<Pair<Int, Int>>()
            for (f in faces) for (e in f.indices) {
                val a = f[e]; val b = f[(e + 1) % f.size]
                val ta = ijk.getValue(a); val tb = ijk.getValue(b)
                // Both ends on the box's edge lines, and on the same one: the coordinates at an extreme agree.
                val same = listOf(ta.first to tb.first, ta.second to tb.second, ta.third to tb.third)
                    .count { (x, y) -> x == y && (x == 0 || x == n) } >= 2
                if (onEdge(ta) && onEdge(tb) && same && seen.add(minOf(a, b) to maxOf(a, b))) creases += a to b
            }
        }
        return Triple(points, faces, creases)
    }

    /** The flat square `center ± half` in x and y, parameterised by the offset from [center]. */
    private fun flat(center: Vec3, half: Double): NurbsSurface {
        val cx = center.x.toDouble(); val cy = center.y.toDouble(); val z = center.z.toDouble()
        return NurbsSurface(
            1, 1, 2, 2, doubleArrayOf(-half, -half, half, half), doubleArrayOf(-half, -half, half, half),
            doubleArrayOf(
                cx - half, cy - half, z, 1.0, cx - half, cy + half, z, 1.0,
                cx + half, cy - half, z, 1.0, cx + half, cy + half, z, 1.0,
            ),
        )
    }

    /** The bilinear patch with corners [a] (0, 0), [b] (1, 0), [c] (0, 1) and [d] (1, 1). */
    private fun bilinear(a: Vec3, b: Vec3, c: Vec3, d: Vec3): NurbsSurface {
        val k = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
        fun h(p: Vec3) = doubleArrayOf(p.x.toDouble(), p.y.toDouble(), p.z.toDouble(), 1.0)
        return NurbsSurface(1, 1, 2, 2, k, k, h(a) + h(c) + h(b) + h(d))  // u-major: (0,0) (0,1) (1,0) (1,1)
    }
}
