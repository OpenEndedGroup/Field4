@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.rhino

import afield.geometry.mesh.TriMesh
import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.filled
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
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Rhino `.3dm` files, read with McNeel's own openNURBS.
 *
 * ```kotlin
 * val model = Rhino3dm.load("~/Downloads/model.3dm")
 * println(model)                       // layers, objects by kind, anything skipped and why
 * three { rhino(model) }               // every visible layer, coloured as Rhino colours it
 * ```
 *
 * A [RhinoModel] is the file's layer tree and its objects, each already turned into something Field
 * draws: a [TriMesh] for meshes, Breps and extrusions, an [FLine] for curves and hatch boundaries,
 * a plane, a height and a string for text, and points. Block instances are flattened into their
 * members, moved into place.
 *
 * # What it will not do
 *
 * It never evaluates a NURBS surface. A Brep comes out as the render mesh Rhino saved with it, and
 * one saved without (*Save Small*, or a face Rhino never managed to shade) is a [RhinoSkipped]
 * that says so — still carrying its edges, so it can be drawn as a wireframe. SubD objects are
 * skipped too: public openNURBS has no SubD mesher. Curves are the exception that costs nothing:
 * lines, polylines and polynomial spans up to cubic come over exactly, and arcs and other rational
 * spans become cubics within a hundred-thousandth of the curve's size.
 *
 * The native wrapper is optional: build it once with `./gradlew buildOpenNurbsNative`. [describe]
 * says whether it is there.
 */
object Rhino3dm {

    /** Read [path]; a leading `~` is your home directory. Throws [RhinoException] for a file openNURBS cannot read. */
    fun load(path: String): RhinoModel = load(File(path.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home"))))

    /** Read [file]. Throws [RhinoException] for a file openNURBS cannot read. */
    fun load(file: File): RhinoModel {
        require(file.isFile) { "No .3dm file at ${file.absolutePath}" }
        return OpenNurbsNative.read(file)
    }

    /** Whether the native wrapper has been built where Field can find it. */
    val isAvailable: Boolean get() = OpenNurbsLibrary.isAvailable

    /** One line for a box to print: where the wrapper is and which openNURBS, or how to build it. */
    fun describe(): String = OpenNurbsLibrary.describe()

    /**
     * Write a small `.3dm` with one of everything this reader converts — nested layers, a mesh, a
     * shaded and an unshaded Brep, a line, a polyline, a cubic, a circle, a polycurve, text, a point,
     * a hatch and a block placed twice — to [file]. For tests and for trying the reader without a
     * Rhino file to hand.
     */
    fun writeSample(file: File): File {
        OpenNurbsNative.writeFixture(file)
        return file
    }
}

/** openNURBS could not read a file, or reported a failure converting it. */
class RhinoException(message: String) : RuntimeException(message)

/**
 * A layer, with its place in the tree.
 *
 * @property index position in [RhinoModel.layers].
 * @property name the layer's own name.
 * @property path the full name Rhino shows, `"Parent::Child"`.
 * @property id the layer's id in the file.
 * @property parent the enclosing layer, or `null` at the top.
 * @property color the layer's colour, alpha 1 unless the layer is translucent.
 * @property ownVisibility whether the layer itself is switched on — [isVisible] folds in its parents.
 * @property locked whether it is locked in Rhino.
 */
class RhinoLayer internal constructor(
    val index: Int,
    val name: String,
    val path: String,
    val id: String,
    val color: Vec4,
    val ownVisibility: Boolean,
    val locked: Boolean,
) {
    var parent: RhinoLayer? = null
        internal set

    /** The layer's path as a list, outermost first — the form [afield.graphics.three.ThreeSceneBuilder.mesh]'s `layer` takes. */
    val pathParts: List<String> get() = path.split("::")

    /** On, and so is every layer above it: what Rhino actually shows. */
    val isVisible: Boolean get() = ownVisibility && (parent?.isVisible ?: true)

    /** Whether [other] is this layer or one of its descendants. */
    fun contains(other: RhinoLayer?): Boolean {
        var at = other
        while (at != null) {
            if (at === this) return true
            at = at.parent
        }
        return false
    }

    override fun toString(): String = "RhinoLayer($path${if (isVisible) "" else ", hidden"})"
}

/**
 * Something on a layer. Every kind carries the same identity; the subclasses add the geometry.
 *
 * @property name the object's name in Rhino, often empty.
 * @property id the object's id. Members of one block instance share the instance's.
 * @property type the openNURBS class it was read from, e.g. `"ON_Brep"`.
 * @property layer the object's layer, or `null` if the file names one that is not there.
 * @property color the display colour Rhino would use, resolved from layer, object, material or block.
 * @property ownVisibility the object's own show/hide state; [isVisible] also asks its layer.
 * @property block for a member of a block instance, the definition's name (`"Outer/Inner"` when
 *   nested); `null` otherwise.
 */
sealed class RhinoObject(
    val name: String,
    val id: String,
    val type: String,
    val layer: RhinoLayer?,
    val color: Vec4,
    val ownVisibility: Boolean,
    val block: String?,
) {
    /** Shown in Rhino: the object is not hidden and its layer, and every layer above it, is on. */
    val isVisible: Boolean get() = ownVisibility && (layer?.isVisible ?: true)

    /** A label for lists and viewers: the name, else the type and the layer. */
    val label: String get() = name.ifBlank { "${type.removePrefix("ON_")} on ${layer?.path ?: "no layer"}" }

    override fun toString(): String = "${javaClass.simpleName}($label)"
}

/**
 * A mesh, a Brep or an extrusion, as triangles.
 *
 * @property mesh positions, triangles and, when the file has them, vertex normals, in model units.
 * @property edges for a Brep or extrusion, its edge curves — Rhino's wireframe of it, which the
 *   mesh's facets are not; `null` for a plain mesh.
 * @property note when some Brep faces had no render mesh, which ones are missing; `null` otherwise.
 */
class RhinoMesh internal constructor(
    name: String, id: String, type: String, layer: RhinoLayer?, color: Vec4, visible: Boolean, block: String?,
    val mesh: TriMesh,
    val edges: FLine?,
    val note: String?,
) : RhinoObject(name, id, type, layer, color, visible, block)

/**
 * A curve, or a hatch's boundary loops.
 *
 * @property line the curve as an FLine in model units, coloured [color]; a hatch's is filled.
 * @property closed whether the curve closes on itself.
 * @property approximated whether some span (an arc, a circle, a rational or high-degree NURBS
 *   span) was replaced by cubics; everything else is exact.
 * @property isHatch whether this came from a hatch rather than a curve.
 */
class RhinoCurve internal constructor(
    name: String, id: String, type: String, layer: RhinoLayer?, color: Vec4, visible: Boolean, block: String?,
    val line: FLine,
    val closed: Boolean,
    val approximated: Boolean,
    val isHatch: Boolean,
) : RhinoObject(name, id, type, layer, color, visible, block)

/**
 * A text object.
 *
 * The plane is the text's own: [origin] is where it is attached, [xAxis] runs along its baseline
 * and [yAxis] up its letters. [height] is Rhino's text height — the height of a capital — in model
 * units, with the dimension style's scale applied.
 *
 * @property horizontalAlignment `0` left, `1` centre, `2` right of [origin].
 * @property verticalAlignment where [origin] sits on the text, as openNURBS numbers it: `0` the top
 *   of the first line's capitals, `1` their middle, `2` the first baseline, `3` the middle of the
 *   whole block, `4` the middle of the last line, `5` the last baseline, `6` the bottom of the ink.
 */
class RhinoText internal constructor(
    name: String, id: String, type: String, layer: RhinoLayer?, color: Vec4, visible: Boolean, block: String?,
    val text: String,
    val origin: Vec3,
    val xAxis: Vec3,
    val yAxis: Vec3,
    val height: Double,
    val horizontalAlignment: Int,
    val verticalAlignment: Int,
) : RhinoObject(name, id, type, layer, color, visible, block) {

    /** The plane's normal, the way the text faces. */
    val normal: Vec3 get() = Vec3(xAxis).cross(yAxis).normalize()

    /** A point [x] along the baseline and [y] up the letters from [origin], in model units. */
    fun at(x: Double, y: Double): Vec3 = vec(
        origin.x + x * xAxis.x + y * yAxis.x,
        origin.y + x * xAxis.y + y * yAxis.y,
        origin.z + x * xAxis.z + y * yAxis.z,
    )
}

/** A point or a point cloud, in model units. */
class RhinoPoints internal constructor(
    name: String, id: String, type: String, layer: RhinoLayer?, color: Vec4, visible: Boolean, block: String?,
    val points: List<Vec3>,
) : RhinoObject(name, id, type, layer, color, visible, block)

/**
 * An object this reader did not convert, and why ([reason]).
 *
 * @property edges for a Brep skipped for want of a render mesh, its edge curves, so it can still be
 *   drawn as a wireframe; `null` otherwise.
 */
class RhinoSkipped internal constructor(
    name: String, id: String, type: String, layer: RhinoLayer?, color: Vec4, visible: Boolean, block: String?,
    val reason: String,
    val edges: FLine?,
) : RhinoObject(name, id, type, layer, color, visible, block)

/**
 * A read `.3dm` file.
 *
 * @property file where it was read from.
 * @property archiveVersion the Rhino version that wrote it, times ten: `70` is Rhino 7.
 * @property unitName the model's units, e.g. `"millimeters"`.
 * @property metersPerUnit how many metres one model unit is (`0.001` for millimetres); `0` when the
 *   file does not say.
 * @property layers every layer, parents before children as Rhino lists them.
 * @property objects every object, block instances already flattened into their members.
 * @property log anything openNURBS reported while reading; usually empty.
 */
class RhinoModel internal constructor(
    val file: File,
    val archiveVersion: Int,
    val unitName: String,
    val metersPerUnit: Double,
    val layers: List<RhinoLayer>,
    val objects: List<RhinoObject>,
    val log: String,
) {
    val meshes: List<RhinoMesh> get() = objects.filterIsInstance<RhinoMesh>()
    val curves: List<RhinoCurve> get() = objects.filterIsInstance<RhinoCurve>()
    val texts: List<RhinoText> get() = objects.filterIsInstance<RhinoText>()
    val points: List<RhinoPoints> get() = objects.filterIsInstance<RhinoPoints>()
    val skipped: List<RhinoSkipped> get() = objects.filterIsInstance<RhinoSkipped>()

    /** The layer whose full path ([RhinoLayer.path]) or, failing that, own name is [name]. */
    fun layer(name: String): RhinoLayer = layers.firstOrNull { it.path == name }
        ?: layers.singleOrNull { it.name == name }
        ?: throw NoSuchElementException(
            "No layer \"$name\" in ${file.name}; its layers are " + layers.joinToString(", ") { "\"${it.path}\"" },
        )

    /** The objects on [layer] and, with [sublayers], on the layers inside it. */
    fun on(layer: String, sublayers: Boolean = true): List<RhinoObject> {
        val target = layer(layer)
        return objects.filter { if (sublayers) target.contains(it.layer) else it.layer === target }
    }

    /** Every [isVisible][RhinoObject.isVisible] mesh merged into one [TriMesh] — for measuring, or a single draw. */
    fun mesh(visibleOnly: Boolean = true): TriMesh =
        merge(meshes.filter { !visibleOnly || it.isVisible }.map { it.mesh })

    /** Every curve, hatch boundary and Brep edge as FLines, each coloured as its object is. */
    fun lines(visibleOnly: Boolean = true, edges: Boolean = true): List<FLine> = buildList {
        for (o in objects) {
            if (visibleOnly && !o.isVisible) continue
            when (o) {
                is RhinoCurve -> add(o.line)
                is RhinoMesh -> if (edges) o.edges?.let(::add)
                is RhinoSkipped -> if (edges) o.edges?.let(::add)
                else -> {}
            }
        }
    }

    override fun toString(): String = buildString {
        val shown = objects.count { it.isVisible }
        append("${file.name}: Rhino ${archiveVersion / 10} file, $unitName, ")
        append("${layers.size} layers (${layers.count { it.isVisible }} visible), ")
        append("${objects.size} objects ($shown visible)\n")
        append("  ${meshes.size} meshes (${meshes.sumOf { it.mesh.triangleCount }} triangles), ")
        append("${curves.size} curves, ${texts.size} texts, ${points.size} point sets, ${skipped.size} skipped")
        skipped.groupingBy { "${it.type}: ${it.reason}" }.eachCount().forEach { (why, n) -> append("\n  skipped $n × $why") }
        meshes.mapNotNull { it.note }.groupingBy { it }.eachCount().forEach { (why, n) -> append("\n  partial $n × $why") }
        if (log.isNotBlank()) append("\n  openNURBS: ${log.trim().lines().first()}")
    }

    private fun merge(parts: List<TriMesh>): TriMesh {
        val positions = FloatArray(parts.sumOf { it.positions.size })
        val indices = IntArray(parts.sumOf { it.indices.size })
        val withNormals = parts.isNotEmpty() && parts.all { it.normals != null }
        val normals = if (withNormals) FloatArray(positions.size) else null
        var p = 0
        var i = 0
        for (part in parts) {
            val base = p / 3
            part.positions.copyInto(positions, p)
            normals?.let { part.normals!!.copyInto(it, p) }
            for (k in part.indices.indices) indices[i + k] = part.indices[k] + base
            p += part.positions.size
            i += part.indices.size
        }
        return TriMesh(positions, indices, normals)
    }
}

/** Where the wrapper library is looked for, in order; the same arrangement as TetGen's. */
object OpenNurbsLibrary {

    private val isWindows: Boolean get() = System.getProperty("os.name").lowercase().contains("win")
    private val isMac: Boolean
        get() = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }

    /** The library's file name on this platform. */
    val fileName: String
        get() = when {
            isWindows -> "field_opennurbs_jvm.dll"
            isMac -> "libfield_opennurbs_jvm.dylib"
            else -> "libfield_opennurbs_jvm.so"
        }

    /** Every path [resolvedPath] tries, in order — quoted in the "not built" error. */
    val searchedPaths: List<Path>
        get() = listOfNotNull(
            System.getProperty("afield.opennurbs.library")?.takeIf { it.isNotBlank() },
            System.getenv("AFIELD_OPENNURBS_LIBRARY")?.takeIf { it.isNotBlank() },
        ).map(Path::of) + listOf(
            Path.of("build", "field-opennurbs", "build", fileName).toAbsolutePath(),
            Path.of("build", "field-opennurbs", "build", "Release", fileName).toAbsolutePath(),
        )

    /** Not cached: building it during a live session should be picked up by the next box run. */
    val resolvedPath: Path? get() = searchedPaths.firstOrNull { it.exists() }

    /** Whether the library is there to load. */
    val isAvailable: Boolean get() = resolvedPath != null

    /** How to get the library, for an error message. */
    val HOW_TO_BUILD: String
        get() = "Build it with `./gradlew buildOpenNurbsNative` (needs cmake and the network once), " +
            "or point -Dafield.opennurbs.library at a $fileName you built."

    /** One line for a box or a test to print. */
    fun describe(): String = resolvedPath?.let { "openNURBS wrapper at $it (${OpenNurbsNative.version()})" }
        ?: "openNURBS wrapper not built. $HOW_TO_BUILD"
}

/**
 * The FFM half. Every call is a plain int, double or pointer; the native side holds the converted
 * model until [close], and everything here is copied out before that.
 */
internal object OpenNurbsNative {

    private const val MESSAGE_CAPACITY = 2048

    // Mirrors of field_opennurbs.h.
    private const val KIND_MESH = 1
    private const val KIND_CURVE = 2
    private const val KIND_HATCH = 3
    private const val KIND_TEXT = 4
    private const val KIND_POINTS = 5
    private const val OP_MOVE = 0
    private const val OP_LINE = 1
    private const val OP_CUBIC = 3
    private const val FLAG_VISIBLE = 1
    private const val FLAG_CLOSED = 4
    private const val FLAG_APPROXIMATED = 16
    private const val FLAG_LOCKED = 32

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

    private val open by lazy { handle("field_opennurbs_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT)) }
    private val close by lazy { handle("field_opennurbs_close", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val versionHandle by lazy { handle("field_opennurbs_version", FunctionDescriptor.of(ADDRESS)) }
    private val modelInt by lazy { handle("field_opennurbs_model_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)) }
    private val metersPerUnit by lazy { handle("field_opennurbs_meters_per_unit", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS)) }
    private val layerInt by lazy { handle("field_opennurbs_layer_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)) }
    private val objectInt by lazy { handle("field_opennurbs_object_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)) }
    private val string by lazy { handle("field_opennurbs_string", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)) }
    private val pointsHandle by lazy { pointer("field_opennurbs_object_points") }
    private val indicesHandle by lazy { pointer("field_opennurbs_object_indices") }
    private val normalsHandle by lazy { pointer("field_opennurbs_object_normals") }
    private val edgePointsHandle by lazy { pointer("field_opennurbs_object_edge_points") }
    private val edgeIndicesHandle by lazy { pointer("field_opennurbs_object_edge_indices") }
    private val frameHandle by lazy { pointer("field_opennurbs_object_frame") }
    private val writeFixtureHandle by lazy {
        handle("field_opennurbs_write_fixture", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
    }

    private fun pointer(name: String) = handle(name, FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT))

    fun version(): String = (versionHandle.invokeExact() as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)

    fun writeFixture(file: File) = Arena.ofConfined().use { arena ->
        val message = arena.allocate(JAVA_BYTE, MESSAGE_CAPACITY.toLong())
        val status = writeFixtureHandle.invokeExact(arena.allocateFrom(file.absolutePath), message, MESSAGE_CAPACITY) as Int
        if (status != 0) throw RhinoException("openNURBS could not write ${file.absolutePath}: ${message.getString(0)}")
    }

    fun read(file: File): RhinoModel = Arena.ofConfined().use { arena ->
        val message = arena.allocate(JAVA_BYTE, MESSAGE_CAPACITY.toLong())
        val out = arena.allocate(ADDRESS)
        val status = open.invokeExact(arena.allocateFrom(file.absolutePath), out, message, MESSAGE_CAPACITY) as Int
        if (status != 0) throw RhinoException("Could not read ${file.absolutePath} (status $status): ${message.getString(0)}")
        val model = out.get(ADDRESS, 0)
        try {
            convert(model, file)
        } finally {
            close.invokeExact(model)
        }
    }

    private fun str(model: MemorySegment, what: Int, index: Int): String =
        (string.invokeExact(model, what, index) as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)

    private fun oInt(model: MemorySegment, what: Int, index: Int): Int = objectInt.invokeExact(model, what, index) as Int

    private fun doubles(h: MethodHandle, model: MemorySegment, index: Int, count: Int): DoubleArray {
        if (count == 0) return DoubleArray(0)
        val at = h.invokeExact(model, index) as MemorySegment
        return if (at == MemorySegment.NULL) DoubleArray(0) else at.reinterpret(count * 8L).toArray(JAVA_DOUBLE)
    }

    private fun ints(h: MethodHandle, model: MemorySegment, index: Int, count: Int): IntArray {
        if (count == 0) return IntArray(0)
        val at = h.invokeExact(model, index) as MemorySegment
        return if (at == MemorySegment.NULL) IntArray(0) else at.reinterpret(count * 4L).toArray(JAVA_INT)
    }

    private fun floats(h: MethodHandle, model: MemorySegment, index: Int, count: Int): FloatArray? {
        if (count == 0) return null
        val at = h.invokeExact(model, index) as MemorySegment
        return if (at == MemorySegment.NULL) null else at.reinterpret(count * 4L).toArray(JAVA_FLOAT)
    }

    private fun color(argb: Int): Vec4 = vec(
        ((argb ushr 16) and 0xFF) / 255.0,
        ((argb ushr 8) and 0xFF) / 255.0,
        (argb and 0xFF) / 255.0,
        ((argb ushr 24) and 0xFF) / 255.0,
    )

    private fun convert(model: MemorySegment, file: File): RhinoModel {
        val layerCount = modelInt.invokeExact(model, 2) as Int
        val layers = (0 until layerCount).map { i ->
            val flags = layerInt.invokeExact(model, 2, i) as Int
            RhinoLayer(
                index = i,
                name = str(model, 0, i),
                path = str(model, 1, i),
                id = str(model, 2, i),
                color = color(layerInt.invokeExact(model, 1, i) as Int),
                ownVisibility = flags and FLAG_VISIBLE != 0,
                locked = flags and FLAG_LOCKED != 0,
            )
        }
        layers.forEachIndexed { i, layer ->
            val parent = layerInt.invokeExact(model, 0, i) as Int
            layer.parent = layers.getOrNull(parent)?.takeIf { it !== layer }
        }

        val objectCount = modelInt.invokeExact(model, 3) as Int
        val objects = (0 until objectCount).map { i -> readObject(model, i, layers) }
        return RhinoModel(
            file = file,
            archiveVersion = modelInt.invokeExact(model, 0) as Int,
            unitName = unitName(modelInt.invokeExact(model, 1) as Int),
            metersPerUnit = metersPerUnit.invokeExact(model) as Double,
            layers = layers,
            objects = objects,
            log = str(model, 9, 0),
        )
    }

    private fun readObject(model: MemorySegment, i: Int, layers: List<RhinoLayer>): RhinoObject {
        val kind = oInt(model, 0, i)
        val layer = layers.getOrNull(oInt(model, 1, i))
        val color = color(oInt(model, 2, i))
        val flags = oInt(model, 3, i)
        val visible = flags and FLAG_VISIBLE != 0
        val name = str(model, 3, i)
        val id = str(model, 4, i)
        val type = str(model, 5, i)
        val note = str(model, 7, i)
        val block = str(model, 8, i).ifEmpty { null }
        val pointCount = oInt(model, 4, i)
        val indexCount = oInt(model, 5, i)
        val points = doubles(pointsHandle, model, i, pointCount * 3)
        val indices = ints(indicesHandle, model, i, indexCount)
        val edges = path(
            doubles(edgePointsHandle, model, i, oInt(model, 8, i) * 3),
            ints(edgeIndicesHandle, model, i, oInt(model, 9, i)),
        )?.also { it.color = color }

        return when (kind) {
            KIND_MESH -> RhinoMesh(
                name, id, type, layer, color, visible, block,
                mesh = TriMesh(
                    FloatArray(points.size) { points[it].toFloat() },
                    indices,
                    floats(normalsHandle, model, i, pointCount * 3),
                ),
                edges = edges,
                note = note.ifEmpty { null },
            )
            KIND_CURVE, KIND_HATCH -> {
                val line = path(points, indices) ?: FLine()
                line.color = color
                if (kind == KIND_HATCH) line.filled = true
                RhinoCurve(
                    name, id, type, layer, color, visible, block, line,
                    closed = flags and FLAG_CLOSED != 0,
                    approximated = flags and FLAG_APPROXIMATED != 0,
                    isHatch = kind == KIND_HATCH,
                )
            }
            KIND_TEXT -> {
                val f = doubles(frameHandle, model, i, 10)
                RhinoText(
                    name, id, type, layer, color, visible, block,
                    text = str(model, 6, i),
                    origin = vec(f[0], f[1], f[2]),
                    xAxis = vec(f[3], f[4], f[5]),
                    yAxis = vec(f[6], f[7], f[8]),
                    height = f[9],
                    horizontalAlignment = oInt(model, 6, i),
                    verticalAlignment = oInt(model, 7, i),
                )
            }
            KIND_POINTS -> RhinoPoints(
                name, id, type, layer, color, visible, block,
                points = (0 until pointCount).map { k -> vec(points[3 * k], points[3 * k + 1], points[3 * k + 2]) },
            )
            else -> RhinoSkipped(name, id, type, layer, color, visible, block, note.ifEmpty { "not converted" }, edges)
        }
    }

    /** A move / line / cubic path, as the wrapper encodes it, into an FLine; `null` when empty. */
    private fun path(points: DoubleArray, ops: IntArray): FLine? {
        if (ops.isEmpty()) return null
        val line = FLine()
        var p = 0
        fun next(): Vec3 = vec(points[p], points[p + 1], points[p + 2]).also { p += 3 }
        for (op in ops) {
            when (op) {
                OP_MOVE -> line.moveTo(next())
                OP_LINE -> line.lineTo(next())
                OP_CUBIC -> {
                    val c1 = next()
                    val c2 = next()
                    line.cubicTo(c1, c2, next())
                }
                else -> throw RhinoException("openNURBS wrapper produced unknown path operation $op")
            }
        }
        return line
    }

    /** ON::LengthUnitSystem, by its stored number. */
    private fun unitName(unitSystem: Int): String = when (unitSystem) {
        0 -> "no units"
        1 -> "microns"
        2 -> "millimeters"
        3 -> "centimeters"
        4 -> "meters"
        5 -> "kilometers"
        6 -> "microinches"
        7 -> "mils"
        8 -> "inches"
        9 -> "feet"
        10 -> "miles"
        11 -> "custom units"
        12 -> "angstroms"
        13 -> "nanometers"
        14 -> "decimeters"
        15 -> "dekameters"
        16 -> "hectometers"
        17 -> "megameters"
        18 -> "gigameters"
        19 -> "yards"
        20 -> "printer points"
        21 -> "printer picas"
        22 -> "nautical miles"
        23 -> "astronomical units"
        24 -> "light years"
        25 -> "parsecs"
        else -> "unit system $unitSystem"
    }
}
