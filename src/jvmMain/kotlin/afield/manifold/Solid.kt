@file:afield.CompletionCategory("Graphics")

package afield.manifold

import afield.ToHTML
import afield.stdlib.TriMesh
import afield.stdlib.AxisAlignedBoundingBox3D
import afield.stdlib.Vec3
import afield.stdlib.diagnose
import afield.stdlib.weld
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Cleaner
import kotlin.math.abs

/**
 * [Solid] — a **guaranteed-closed** 3D volume you can subtract, union and intersect without thinking
 * about whether the answer is still a surface. Field's binding to
 * [Manifold](https://github.com/elalish/manifold), through Panama/FFM.
 *
 * ```kotlin
 * val block = Solid.cube(2.0, 2.0, 1.0, center = true)
 * val holes = (0..2).map { Solid.cylinder(2.0, 0.22, segments = 48).translated(it - 1.0, 0.0, 0.0) }
 * val plate = block - Solid.union(holes)
 * println("${plate.volume} mm³, genus ${plate.genus}, ${plate.triangleCount} triangles")
 * display(plate.toTriMesh())            // straight into Field's mesh visualizer
 * ```
 *
 * # Why this exists beside [TriMesh]
 *
 * Field already has a full CPU mesh family ([TriMesh] and friends) and it already marches SDFs into
 * surfaces. What it does **not** have — and what is genuinely hard — is a robust mesh Boolean. The
 * honest division of labour:
 *
 * | | `afield.stdlib` mesh family | `afield.manifold` |
 * |---|---|---|
 * | representation | [TriMesh]: flat arrays, may be open, may self-intersect | a closed, ε-valid manifold, always |
 * | booleans | none | exact, and the reason to be here |
 * | modelling from a field | [afield.stdlib.marchingCubes] | [levelSet], which additionally *guarantees* the output is closed |
 * | subdivision, smoothing, decimation, curvature, BVH, OBJ/STL | yes | mostly not |
 * | dependency | none | `brew install manifold` |
 *
 * So they compose rather than compete: build or read a [TriMesh], [toSolid] it to cut it, and
 * [toTriMesh] to smooth, decimate, measure, draw or export the result. The bridge is not a
 * conversion in the lossy sense — [TriMesh]'s positions are `Float` and Manifold's `MeshGL` is
 * `float32`, so going *in* is exact and coming *out* narrows Manifold's internal doubles to exactly
 * the precision [TriMesh] stores anyway.
 *
 * # What "guaranteed" means, and what it costs
 *
 * Manifold's contract is that every operation either produces a valid closed manifold or reports why
 * not. It does not throw and does not return null: a failure comes back as a [Solid] whose [status]
 * is not [ManifoldStatus.NO_ERROR] and whose [volume] is `0.0`. That keeps a twenty-step boolean
 * chain from needing twenty checks — but it does mean **an unchecked pipeline can end in silence**.
 * [checked] is the one-line answer, and worth reaching for at the end of anything load-bearing:
 *
 * ```kotlin
 * val part = (body - bore).checked()      // throws, naming the status, rather than yielding 0 mm³
 * ```
 *
 * The other half of the contract is that Manifold's booleans are *lazy*: `a - b` builds a small
 * expression node and the work happens when something asks for a number. So [volume], [genus] and
 * [toTriMesh] are the expensive calls, not the operators, and timing an operator measures nothing.
 *
 * # Lifetime
 *
 * A [Solid] owns memory the JVM heap does not account for, so it is [AutoCloseable] — and because
 * a live-coding box is the last place anyone wants `try`/`finally`, it is *also* registered with a
 * [Cleaner]. Abandon one and it is released when the JVM collects it; [close] it and the release is
 * immediate and the object refuses further use. Both paths are safe; only the second is prompt.
 * A box re-run thousands of times in a session should [close] its intermediates, or work in
 * [Solid.scope], which closes everything built inside it.
 *
 * @see Section for the 2D counterpart — offsetting, extrusion, revolution
 * @see toTriMesh for the way back into Field's mesh, drawing and export machinery
 */
class Solid internal constructor(handle: MemorySegment) : AutoCloseable, ToHTML {

    private val release = NativeRelease(handle, NativeRelease.Kind.MANIFOLD)
    private val cleanable: Cleaner.Cleanable = CLEANER.register(this, release)

    internal val ptr: MemorySegment
        get() {
            check(!release.released) { "this Solid is closed — it was released explicitly or by its enclosing scope" }
            return release.handle
        }

    /** `true` once [close] (or an enclosing [scope]) has released the native volume. */
    val isClosed: Boolean get() = release.released

    // ==========================================================================================
    // Booleans
    // ==========================================================================================

    /**
     * Union — the volume in either solid.
     *
     * Cheap: like every boolean here this returns immediately and the work happens when a number is
     * asked for. For more than two solids prefer [Solid.union], which lets Manifold plan the whole
     * batch instead of folding pairwise.
     */
    operator fun plus(other: Solid): Solid = wrap(ManifoldNative.union, ptr, other.ptr)

    /** Difference — this solid with [other] removed. */
    operator fun minus(other: Solid): Solid = wrap(ManifoldNative.difference, ptr, other.ptr)

    /** Intersection — only the volume in both solids. */
    infix fun and(other: Solid): Solid = wrap(ManifoldNative.intersection, ptr, other.ptr)

    /** Alias for [plus], for the reading where a union is an `or`. */
    infix fun or(other: Solid): Solid = this + other

    /**
     * The Minkowski sum — every point of this solid displaced by every point of [other]. Sweeping a
     * small sphere over a part is how you round all of its edges at once:
     *
     * ```kotlin
     * val rounded = bracket.minkowskiSum(Solid.sphere(0.4, segments = 24))
     * ```
     *
     * Convex operands are fast; a concave one decomposes first and can be very slow, so reach for it
     * knowingly.
     */
    fun minkowskiSum(other: Solid): Solid = wrap(ManifoldNative.minkowskiSum, ptr, other.ptr)

    /** The Minkowski difference — [minkowskiSum]'s erosion counterpart, for shrinking by a shape. */
    fun minkowskiDifference(other: Solid): Solid = wrap(ManifoldNative.minkowskiDifference, ptr, other.ptr)

    /**
     * Cut by a plane and keep **both** halves: `first` is the side the normal points *toward*,
     * `second` is the rest.
     *
     * That order is upstream's and is easy to get backwards, so it is asserted rather than assumed —
     * a 2x2x2 cube centred on the origin split by `(0,0,1)` at `offset = 0.5` gives `first` a volume
     * of 2 (the 0.5-tall cap above the plane) and `second` 6.
     *
     * @param offset how far along the normal the plane sits, so `(0,0,1)` with `offset = 0.5` is the
     *   horizontal plane at `z = 0.5`.
     */
    fun splitByPlane(
        normalX: Double,
        normalY: Double,
        normalZ: Double,
        offset: Double = 0.0,
    ): Pair<Solid, Solid> {
        Arena.ofConfined().use { arena ->
            val pair = ManifoldNative.invokeStruct(
                ManifoldNative.splitByPlane,
                arena,
                ManifoldNative.newManifold(),
                ManifoldNative.newManifold(),
                ptr,
                normalX,
                normalY,
                normalZ,
                offset,
            )
            // Two ManifoldManifold* returned in a by-value struct; each is one of the two buffers we
            // just allocated, so wrapping them transfers ownership exactly once.
            return Solid(pair.get(ValueLayout.ADDRESS, 0)) to Solid(pair.get(ValueLayout.ADDRESS, 8))
        }
    }

    /** Split by another solid: `first` is the part inside [other], `second` the part outside. */
    fun split(other: Solid): Pair<Solid, Solid> {
        Arena.ofConfined().use { arena ->
            val pair = ManifoldNative.invokeStruct(
                ManifoldNative.split,
                arena,
                ManifoldNative.newManifold(),
                ManifoldNative.newManifold(),
                ptr,
                other.ptr,
            )
            return Solid(pair.get(ValueLayout.ADDRESS, 0)) to Solid(pair.get(ValueLayout.ADDRESS, 8))
        }
    }

    /**
     * Keep only the half the normal points toward — [splitByPlane]'s `first`, without building the
     * offcut. The cheap way to open a solid up and look inside it.
     */
    fun trimByPlane(normalX: Double, normalY: Double, normalZ: Double, offset: Double = 0.0): Solid =
        wrap(ManifoldNative.trimByPlane, ptr, normalX, normalY, normalZ, offset)

    /**
     * Split into connected components, largest first by volume.
     *
     * The counterpart of [Solid.compose]: a union of two disjoint blocks is one [Solid] of genus 0
     * with two components, and this is how you get at them separately.
     */
    fun decompose(): List<Solid> {
        val vec = ManifoldNative.ptr(ManifoldNative.decompose, ManifoldNative.ptr(ManifoldNative.allocManifoldVec), ptr)
        try {
            val n = ManifoldNative.long(ManifoldNative.manifoldVecLength, vec)
            return (0 until n)
                .map { Solid(ManifoldNative.ptr(ManifoldNative.manifoldVecGet, ManifoldNative.newManifold(), vec, it)) }
                .sortedByDescending { it.volume }
        } finally {
            ManifoldNative.unit(ManifoldNative.deleteManifoldVec, vec)
        }
    }

    // ==========================================================================================
    // Transforms
    // ==========================================================================================

    fun translated(x: Double, y: Double, z: Double): Solid = wrap(ManifoldNative.translate, ptr, x, y, z)

    fun translated(v: Vec3): Solid = translated(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())

    /** Rotate by Euler angles **in degrees**, applied X then Y then Z — Manifold's own convention. */
    fun rotated(xDegrees: Double, yDegrees: Double, zDegrees: Double): Solid =
        wrap(ManifoldNative.rotate, ptr, xDegrees, yDegrees, zDegrees)

    fun scaled(x: Double, y: Double, z: Double): Solid = wrap(ManifoldNative.scale, ptr, x, y, z)

    fun scaled(factor: Double): Solid = scaled(factor, factor, factor)

    /**
     * Mirror through the plane with this normal, through the origin.
     *
     * Worth knowing: a mirror reverses winding, and Manifold flips the triangles back rather than
     * handing you an inside-out solid — so `mirrored` output is still closed and still has positive
     * [volume].
     */
    fun mirrored(normalX: Double, normalY: Double, normalZ: Double): Solid =
        wrap(ManifoldNative.mirror, ptr, normalX, normalY, normalZ)

    /**
     * Apply a 3x4 affine transform, given column-major as three basis columns and a translation —
     * the same 12 numbers Manifold takes.
     */
    fun transformed(
        c0x: Double, c0y: Double, c0z: Double,
        c1x: Double, c1y: Double, c1z: Double,
        c2x: Double, c2y: Double, c2z: Double,
        tx: Double, ty: Double, tz: Double,
    ): Solid = wrap(ManifoldNative.transform, ptr, c0x, c0y, c0z, c1x, c1y, c1z, c2x, c2y, c2z, tx, ty, tz)

    /**
     * Move every vertex by a Kotlin function — a bend, a twist, a taper — **without** re-meshing.
     *
     * ```kotlin
     * val twisted = Solid.cube(1.0, 1.0, 4.0, center = true).refined(24).warped { x, y, z ->
     *     val a = z * 0.5
     *     Triple(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a), z)
     * }
     * ```
     *
     * Two things to know, because they are the difference between this working and disappointing:
     *
     *  - **The mesh is not subdivided first**, so a warp only shows up where there are vertices to
     *    move. Almost every interesting warp wants [refined] or [refinedToLength] in front of it —
     *    twisting a 12-triangle cube moves 8 corners and looks like nothing.
     *  - **[map] runs on Manifold's own worker threads**, concurrently, and must be thread-safe. It
     *    must also not throw: an exception cannot unwind through the C++ frames between here and
     *    there, so one is caught, the vertex is left where it was, and the exception is rethrown from
     *    this call once the native work is finished.
     *
     * A warp that is not injective (folds the surface onto itself) produces a solid whose [status]
     * reports the damage rather than a crash.
     */
    fun warped(map: (Double, Double, Double) -> Triple<Double, Double, Double>): Solid {
        val bridge = ManifoldNative.WarpBridge(map)
        Arena.ofConfined().use { arena ->
            val stub = ManifoldNative.upcallWarp(bridge, arena)
            val out = wrap(ManifoldNative.warp, ptr, stub, MemorySegment.NULL)
            bridge.firstFailure?.let { throw IllegalStateException("the warp function threw", it) }
            return out
        }
    }

    /**
     * Subdivide every triangle into `n²` smaller ones, so a later [warped] or [smoothOut] has
     * somewhere to act. Triangle count grows as the square of [n].
     */
    fun refined(n: Int): Solid = wrap(ManifoldNative.refine, ptr, n)

    /** Subdivide until no edge is longer than [length] — the resolution-independent [refined]. */
    fun refinedToLength(length: Double): Solid = wrap(ManifoldNative.refineToLength, ptr, length)

    /** Subdivide until the mesh is within [tolerance] of its own smooth interpolation. */
    fun refinedToTolerance(tolerance: Double): Solid = wrap(ManifoldNative.refineToTolerance, ptr, tolerance)

    /**
     * Round the mesh off, treating edges sharper than [minSharpAngle] degrees as creases to keep.
     *
     * Unlike [afield.stdlib.laplacianSmooth] this is an *interpolating* smooth — it does not shrink
     * the solid — and it needs vertices to work with, so put [refined] in front of it.
     */
    fun smoothOut(minSharpAngle: Double = 60.0, minSmoothness: Double = 0.0): Solid =
        wrap(ManifoldNative.smoothOut, ptr, minSharpAngle, minSmoothness)

    /**
     * Collapse edges shorter than [tolerance], keeping the solid valid.
     *
     * This is the decimator you want *after* a boolean: a cut across a dense surface leaves slivers,
     * and this removes them without opening the mesh. It is not a general [afield.stdlib.decimate] —
     * it will not hit a triangle budget — it removes what is below the tolerance and stops.
     */
    fun simplified(tolerance: Double): Solid = wrap(ManifoldNative.simplify, ptr, tolerance)

    /** Set the ε Manifold treats as "the same point" for later operations. */
    fun withTolerance(tolerance: Double): Solid = wrap(ManifoldNative.setTolerance, ptr, tolerance)

    /** The convex hull of this solid. */
    fun hull(): Solid = wrap(ManifoldNative.hull, ptr)

    // ==========================================================================================
    // 3D to 2D
    // ==========================================================================================

    /**
     * The cross section at `z = height` — a real slice, not a silhouette.
     *
     * This is the primitive under slicing for fabrication: a stack of these is a toolpath or a print.
     *
     * ```kotlin
     * val layers = (0..40).map { part.slice(it * 0.25) }        // a print, bottom to top
     * display(layers.flatMap { it.toFLines() })
     * ```
     */
    fun slice(height: Double = 0.0, fillRule: FillRule = FillRule.POSITIVE): Section =
        sectionFromPolygons(ManifoldNative.slice, fillRule, ptr, height)

    /** The shadow of the whole solid on the XY plane — the union of every slice. */
    fun project(fillRule: FillRule = FillRule.POSITIVE): Section =
        sectionFromPolygons(ManifoldNative.project, fillRule, ptr)

    /**
     * Both 3D-to-2D entry points return `ManifoldPolygons*`, not `ManifoldCrossSection*`.
     *
     * That distinction is load-bearing rather than pedantic, and it is the one real trap in this
     * whole binding: every constructor here builds into memory the *caller* allocated, so handing
     * `manifold_slice` a cross-section-sized buffer builds a `Polygons` inside it, and the first
     * thing that touches the resulting object's internals aborts the process with
     * `std::system_error: mutex lock failed` — no FFM error, no exception, no stack. It reproduces
     * identically from C, which is how it was pinned down. So the conversion is done here, once.
     */
    private fun sectionFromPolygons(
        handle: java.lang.invoke.MethodHandle,
        fillRule: FillRule,
        vararg args: Any?,
    ): Section {
        val polygons = ManifoldNative.ptr(handle, ManifoldNative.ptr(ManifoldNative.allocPolygons), *args)
        try {
            return Section(
                ManifoldNative.ptr(
                    ManifoldNative.sectionOfPolygons,
                    ManifoldNative.newSection(),
                    polygons,
                    fillRule.code,
                ),
            )
        } finally {
            ManifoldNative.unit(ManifoldNative.deletePolygons, polygons)
        }
    }

    // ==========================================================================================
    // Measurement
    // ==========================================================================================

    /**
     * Enclosed volume. Exact for the primitives — `Solid.cube(2,3,4).volume` is `24.0`, not
     * `23.999997` — because Manifold measures the solid rather than summing float triangles.
     *
     * This is one of the calls that forces the lazy boolean chain behind it, so it is where a
     * pipeline's time actually goes.
     */
    val volume: Double get() = ManifoldNative.double(ManifoldNative.volume, ptr)

    /** Total surface area. Exact for the primitives, for the same reason as [volume]. */
    val surfaceArea: Double get() = ManifoldNative.double(ManifoldNative.surfaceArea, ptr)

    val vertexCount: Long get() = ManifoldNative.long(ManifoldNative.numVert, ptr)

    val edgeCount: Long get() = ManifoldNative.long(ManifoldNative.numEdge, ptr)

    val triangleCount: Long get() = ManifoldNative.long(ManifoldNative.numTri, ptr)

    /** Per-vertex property channels beyond position, if any were carried through. */
    val propertyCount: Long get() = ManifoldNative.long(ManifoldNative.numProp, ptr)

    /**
     * Topological genus — the number of holes *through* the solid. A block is `0`, a torus `1`, a
     * plate with three bores `3`.
     *
     * Because the solid is closed by construction this is always meaningful, which is the difference
     * from [afield.stdlib.genus] on a [TriMesh]: that one returns `null` when the mesh is open, and a
     * marched or scanned surface often is.
     *
     * **It can be negative, and that is not an error.** It is computed from the Euler characteristic
     * of the whole surface, `g = (2 - χ) / 2`, and χ adds up across disjoint components — so `n`
     * separate blocks report `1 - n`: two cubes are `-1`, three are `-2`. Read a negative genus as
     * "more components than holes", and use [decompose] to get the per-part genus you probably meant.
     */
    val genus: Int get() = ManifoldNative.int(ManifoldNative.genus, ptr)

    /** `true` for a solid with no volume — including the result of a failed operation. */
    val isEmpty: Boolean get() = ManifoldNative.int(ManifoldNative.isEmpty, ptr) != 0

    /** Why the last operation could not produce a valid solid, or [ManifoldStatus.NO_ERROR]. */
    val status: ManifoldStatus get() = ManifoldStatus.of(ManifoldNative.int(ManifoldNative.status, ptr))

    /** The ε this solid treats as "the same point". */
    val tolerance: Double get() = ManifoldNative.double(ManifoldNative.tolerance, ptr)

    /** Manifold's own precision estimate for this solid's coordinate range. */
    val epsilon: Double get() = ManifoldNative.double(ManifoldNative.epsilon, ptr)

    /** The axis-aligned bounds, as the same type Field's mesh family and [afield.stdlib.marchingCubes] use. */
    val bounds: AxisAlignedBoundingBox3D
        get() {
            val box = ManifoldNative.ptr(ManifoldNative.boundingBox, ManifoldNative.ptr(ManifoldNative.allocBox), ptr)
            try {
                Arena.ofConfined().use { arena ->
                    val lo = ManifoldNative.vec3(ManifoldNative.invokeStruct(ManifoldNative.boxMin, arena, box))
                    val hi = ManifoldNative.vec3(ManifoldNative.invokeStruct(ManifoldNative.boxMax, arena, box))
                    return AxisAlignedBoundingBox3D(
                        Vec3(lo.first.toFloat(), lo.second.toFloat(), lo.third.toFloat()),
                        Vec3(hi.first.toFloat(), hi.second.toFloat(), hi.third.toFloat()),
                    )
                }
            } finally {
                ManifoldNative.unit(ManifoldNative.deleteBox, box)
            }
        }

    /**
     * The smallest gap between this solid's surface and [other]'s, searching out to [searchLength].
     *
     * The clearance check for an assembly: two parts that must not touch, or must touch within a
     * tolerance. Returns [searchLength] when nothing was found within it.
     */
    fun minGap(other: Solid, searchLength: Double): Double =
        ManifoldNative.double(ManifoldNative.minGap, ptr, other.ptr, searchLength)

    /**
     * Every intersection of a ray with this solid's surface, near to far.
     *
     * The ray runs from ([originX], [originY], [originZ]) along ([dirX], [dirY], [dirZ]) for
     * [length]. An odd number of hits means the origin is inside the solid, which is the cheapest
     * inside test there is — and is what [contains] uses.
     *
     * [RayHit.distance] is in the solid's own units. Note that this is a **conversion**, not a
     * pass-through: the C API reports the hit as a fraction of the origin-to-end segment, so a hit at
     * `x = -1` on a ray of length `1e4` comes back as `4.0e-4` and would read as "essentially at the
     * origin" if it were handed straight on. Multiplying by [length] here means the number means the
     * same thing whatever [length] the caller happened to pass.
     */
    fun raycast(
        originX: Double,
        originY: Double,
        originZ: Double,
        dirX: Double,
        dirY: Double,
        dirZ: Double,
        length: Double = 1e4,
    ): List<RayHit> {
        val norm = kotlin.math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
        require(norm > 0.0) { "ray direction must not be zero" }
        val hits = ManifoldNative.ptr(
            ManifoldNative.rayCast,
            ManifoldNative.ptr(ManifoldNative.allocRayHitVec),
            ptr,
            originX, originY, originZ,
            originX + dirX / norm * length, originY + dirY / norm * length, originZ + dirZ / norm * length,
        )
        try {
            val n = ManifoldNative.long(ManifoldNative.rayHitVecLength, hits)
            Arena.ofConfined().use { arena ->
                return (0 until n).map { i ->
                    val hit = ManifoldNative.invokeStruct(ManifoldNative.rayHitVecGet, arena, hits, i)
                    RayHit(
                        faceId = hit.get(ValueLayout.JAVA_LONG, 0),
                        distance = hit.get(ValueLayout.JAVA_DOUBLE, 8) * length,
                        x = hit.get(ValueLayout.JAVA_DOUBLE, 16),
                        y = hit.get(ValueLayout.JAVA_DOUBLE, 24),
                        z = hit.get(ValueLayout.JAVA_DOUBLE, 32),
                        normalX = hit.get(ValueLayout.JAVA_DOUBLE, 40),
                        normalY = hit.get(ValueLayout.JAVA_DOUBLE, 48),
                        normalZ = hit.get(ValueLayout.JAVA_DOUBLE, 56),
                    )
                }.sortedBy { it.distance }
            }
        } finally {
            ManifoldNative.unit(ManifoldNative.deleteRayHitVec, hits)
        }
    }

    /** `true` when [x], [y], [z] is inside the solid, by [raycast]'s parity test. */
    fun contains(x: Double, y: Double, z: Double): Boolean {
        // A ray along an axis is far more likely to graze an edge exactly than an oblique one, and a
        // grazing hit is the one case parity gets wrong. So the probe direction is deliberately not
        // axis-aligned and not a nice ratio.
        val span = bounds.size.let { maxOf(it.x, it.y, it.z) }.toDouble() * 4.0 + 1.0
        return raycast(x, y, z, 0.5773502691, 0.5568900383, 0.5971385942, span).size % 2 == 1
    }

    /**
     * Throw if this solid is not valid, naming the [status]. The end of a load-bearing pipeline.
     *
     * @param what a name for the thing being built, so the message says which step of a box failed.
     */
    fun checked(what: String = "solid"): Solid {
        val s = status
        if (s.isError) {
            throw IllegalStateException(
                "$what is not a valid manifold: $s. " + when (s) {
                    ManifoldStatus.NOT_MANIFOLD ->
                        "The input mesh has an edge shared by other than two triangles — weld it first " +
                            "(TriMesh.weld) or check its winding (TriMesh.fixWinding)."
                    ManifoldStatus.NON_FINITE_VERTEX -> "A vertex is NaN or infinite."
                    ManifoldStatus.RESULT_TOO_LARGE -> "Try a coarser resolution or a smaller bound."
                    else -> "See ManifoldStatus for what this code means."
                },
            )
        }
        return this
    }

    // ==========================================================================================
    // Back to Field
    // ==========================================================================================

    /**
     * The [TriMesh] Field's mesh family, visualizers, `FLine` bridge and OBJ/STL writers all take.
     *
     * ```kotlin
     * display((body - bore).toTriMesh())                 // the inline mesh card
     * display((body - bore).toTriMesh().toFLines())       // as a wireframe FLine
     * writeObj(part.toTriMesh(), File("part.obj"))
     * ```
     *
     * # `normals = true` changes the topology, and that trips people up
     *
     * A vertex on a hard edge needs a *different* normal for each face touching it, so a mesh with
     * per-vertex normals cannot share that vertex — Manifold duplicates the position instead. A cube
     * comes back as 8 vertices without normals and **24 with them**, three per corner.
     *
     * The geometry is identical either way ([afield.stdlib.signedVolume] agrees exactly), but the
     * *index* topology is not, and Field's [afield.stdlib.isWatertight] and [afield.stdlib.genus] read
     * indices. So a crease-split mesh reports `isWatertight() == false` and the wrong genus, which
     * looks exactly like a broken bridge and is not one. The rule:
     *
     * | you want | ask for |
     * |---|---|
     * | to draw it, `showMesh`, upload it to a GPU | `normals = true` |
     * | to measure its topology, weld it, subdivide it, hand it back to [toSolid] | `normals = false` |
     *
     * [afield.stdlib.weld] repairs the topology of a crease-split mesh, at the cost of the normals that
     * caused the split. And nothing is lost by asking twice: the solid is the source of truth, and
     * `toTriMesh()` for the numbers beside `toTriMesh(normals = true)` for the picture is the right
     * shape for a box.
     *
     * @param normals ask Manifold for per-vertex normals, creased at [minSharpAngle] degrees. Better
     *   than [afield.stdlib.withVertexNormals] on the result, because Manifold still knows which
     *   edges came from which original face — see the warning above about what it costs.
     */
    fun toTriMesh(normals: Boolean = false, minSharpAngle: Double = 60.0): TriMesh {
        // `normalIdx = 0`, not 3, and the difference is not obvious from the header: Manifold counts
        // properties *excluding* position while MeshGL counts them *including* it. So asking for
        // normals at index 3 leaves three unset channels in front of them and lands the normal at
        // MeshGL index 6, silently — measured: numProp 9, vertex 0 = [pos, 0,0,0, normal]. Index 0
        // puts it where a reader expects, right after the position, at MeshGL index 3 with numProp 6.
        val withNormals = if (!normals) this else wrap(ManifoldNative.calculateNormals, ptr, 0, minSharpAngle)
        try {
            val mesh = ManifoldNative.ptr(
                ManifoldNative.getMeshGL,
                ManifoldNative.ptr(ManifoldNative.allocMeshGL),
                withNormals.ptr,
            )
            try {
                val vertexCount = ManifoldNative.long(ManifoldNative.meshGLNumVert, mesh)
                val triangleCount = ManifoldNative.long(ManifoldNative.meshGLNumTri, mesh)
                val props = ManifoldNative.long(ManifoldNative.meshGLNumProp, mesh)
                if (vertexCount == 0L || triangleCount == 0L) return TriMesh(FloatArray(0), IntArray(0))
                check(props >= 3) { "MeshGL reported $props properties per vertex; the first three must be the position" }

                Arena.ofConfined().use { arena ->
                    // These two do not *return* a buffer, they fill the one they are given — a detail
                    // worth stating because the C signature (`float* f(void* mem, ...)`) reads like an
                    // allocator and is not one.
                    val vertexProperties = arena.allocate(ValueLayout.JAVA_FLOAT, vertexCount * props)
                    val triangles = arena.allocate(ValueLayout.JAVA_INT, triangleCount * 3)
                    ManifoldNative.ptr(ManifoldNative.meshGLVertProperties, vertexProperties, mesh)
                    ManifoldNative.ptr(ManifoldNative.meshGLTriVerts, triangles, mesh)

                    val positions = FloatArray((vertexCount * 3).toInt())
                    val normalArray = if (normals && props >= 6) FloatArray((vertexCount * 3).toInt()) else null
                    for (v in 0 until vertexCount) {
                        val base = v * props
                        val out = (v * 3).toInt()
                        positions[out] = vertexProperties.floatAt(base)
                        positions[out + 1] = vertexProperties.floatAt(base + 1)
                        positions[out + 2] = vertexProperties.floatAt(base + 2)
                        normalArray?.let {
                            it[out] = vertexProperties.floatAt(base + 3)
                            it[out + 1] = vertexProperties.floatAt(base + 4)
                            it[out + 2] = vertexProperties.floatAt(base + 5)
                        }
                    }
                    val indices = IntArray((triangleCount * 3).toInt()) { triangles.intAt(it.toLong()) }
                    return TriMesh(positions, indices, normals = normalArray)
                }
            } finally {
                ManifoldNative.unit(ManifoldNative.deleteMeshGL, mesh)
            }
        } finally {
            if (normals) withNormals.close()
        }
    }

    /** The inline card: [toTriMesh]'s, so a [Solid] draws like every other mesh in Field, plus the numbers only a solid has. */
    override fun toHTML(): String {
        val mesh = toTriMesh(normals = true)
        val s = status
        val badge = if (s.isError) {
            "<span style='color:#ff8f7a'>$s</span>"
        } else {
            "genus $genus &middot; volume ${"%.5g".format(volume)} &middot; area ${"%.5g".format(surfaceArea)}"
        }
        return "<div>" + mesh.toHTML() +
            "<div style='font-size:12px;color:#8fa6b8;margin-top:4px'>Manifold solid &middot; $badge</div></div>"
    }

    override fun toString(): String = if (isClosed) {
        "Solid(closed)"
    } else {
        "Solid(${triangleCount} tris, genus $genus, volume ${"%.5g".format(volume)}" +
            (if (status.isError) ", $status" else "") + ")"
    }

    /**
     * Release the native volume now.
     *
     * Optional — the [Cleaner] gets there eventually — but not pointless: this is native memory the
     * JVM's heap pressure knows nothing about, so a box building thousands of intermediates per
     * second collects late and grows. Using this object afterwards throws rather than reading freed
     * memory.
     */
    override fun close() {
        cleanable.clean()
    }

    companion object {

        /** One [Cleaner] for the whole package: its thread is shared by every [Solid] and [Section]. */
        internal val CLEANER: Cleaner = Cleaner.create { runnable ->
            Thread(runnable, "afield-manifold-cleaner").apply { isDaemon = true }
        }

        /** The empty solid. Not an error — [ManifoldStatus.NO_ERROR], no volume. */
        fun empty(): Solid = Solid(ManifoldNative.ptr(ManifoldNative.empty, ManifoldNative.newManifold()))

        /**
         * An axis-aligned box.
         *
         * @param center `true` puts the origin at the middle, `false` at the minimum corner —
         *   Manifold's own flag, and worth passing explicitly because the two conventions are one
         *   flag apart and produce silently different assemblies.
         */
        fun cube(x: Double, y: Double, z: Double, center: Boolean = false): Solid =
            wrap(ManifoldNative.cube, x, y, z, if (center) 1 else 0)

        /** A cube of side [size]. */
        fun cube(size: Double, center: Boolean = false): Solid = cube(size, size, size, center)

        /**
         * A sphere.
         *
         * @param segments segments around the equator. `0` means "use the global quality setting" —
         *   see [setCircularQuality]. Manifold's spheres are geodesic-ish rather than UV, so the
         *   triangles stay near-equilateral at the poles.
         */
        fun sphere(radius: Double, segments: Int = 0): Solid = wrap(ManifoldNative.sphere, radius, segments)

        /**
         * A cylinder, cone or truncated cone, along Z.
         *
         * @param radiusHigh the far-end radius; leave it equal to [radiusLow] for a cylinder, `0.0`
         *   for a cone.
         */
        fun cylinder(
            height: Double,
            radiusLow: Double,
            radiusHigh: Double = radiusLow,
            segments: Int = 0,
            center: Boolean = false,
        ): Solid = wrap(ManifoldNative.cylinder, height, radiusLow, radiusHigh, segments, if (center) 1 else 0)

        /** A cone along Z — [cylinder] with a zero far radius. */
        fun cone(height: Double, radius: Double, segments: Int = 0, center: Boolean = false): Solid =
            cylinder(height, radius, 0.0, segments, center)

        /** The regular tetrahedron with vertices at alternating cube corners, edge length `2√2`. */
        fun tetrahedron(): Solid = Solid(ManifoldNative.ptr(ManifoldNative.tetrahedron, ManifoldNative.newManifold()))

        /**
         * Union of many solids at once, which is **not** the same as folding [plus] over them:
         * Manifold plans the whole batch, so a hundred overlapping spheres is one operation rather
         * than ninety-nine nested ones.
         */
        fun union(solids: List<Solid>): Solid = batchBoolean(solids, BooleanOp.UNION)

        /** [union] for a handful named inline. */
        fun union(vararg solids: Solid): Solid = union(solids.toList())

        /** Intersection of many solids at once. See [union] for why the batch form matters. */
        fun intersection(solids: List<Solid>): Solid = batchBoolean(solids, BooleanOp.INTERSECTION)

        /** Difference of many: the first solid with all the others removed. */
        fun difference(solids: List<Solid>): Solid = batchBoolean(solids, BooleanOp.DIFFERENCE)

        /** The general batch boolean, for when the op is a variable. */
        fun batchBoolean(solids: List<Solid>, op: BooleanOp): Solid {
            if (solids.isEmpty()) return empty()
            if (solids.size == 1) return solids[0].copy()
            val vec = ManifoldNative.ptr(
                ManifoldNative.manifoldVec,
                ManifoldNative.ptr(ManifoldNative.allocManifoldVec),
                solids.size.toLong(),
            )
            try {
                solids.forEachIndexed { i, s -> ManifoldNative.unit(ManifoldNative.manifoldVecSet, vec, i.toLong(), s.ptr) }
                return wrap(ManifoldNative.batchBoolean, vec, op.code)
            } finally {
                // Deletes the vector, not the solids in it: `manifold_manifold_vec_set` copies the
                // Manifold into the vector's own slot, so each caller's Solid still owns its own.
                ManifoldNative.unit(ManifoldNative.deleteManifoldVec, vec)
            }
        }

        /** One solid holding every input as a separate component, with no boolean between them. */
        fun compose(solids: List<Solid>): Solid {
            if (solids.isEmpty()) return empty()
            val vec = ManifoldNative.ptr(
                ManifoldNative.manifoldVec,
                ManifoldNative.ptr(ManifoldNative.allocManifoldVec),
                solids.size.toLong(),
            )
            try {
                solids.forEachIndexed { i, s -> ManifoldNative.unit(ManifoldNative.manifoldVecSet, vec, i.toLong(), s.ptr) }
                return wrap(ManifoldNative.compose, vec)
            } finally {
                ManifoldNative.unit(ManifoldNative.deleteManifoldVec, vec)
            }
        }

        /**
         * The convex hull of a point cloud, straight from the points.
         *
         * Named apart from [hullOf] rather than overloaded on it, because `List<Vec3>` and
         * `List<Solid>` erase to the same JVM signature.
         */
        fun hullOfPoints(points: List<Vec3>): Solid {
            require(points.size >= 4) { "a hull needs at least 4 points, got ${points.size}" }
            Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(ManifoldNative.VEC3, points.size.toLong())
                points.forEachIndexed { i, p ->
                    val base = i * 24L
                    buffer.set(ValueLayout.JAVA_DOUBLE, base, p.x.toDouble())
                    buffer.set(ValueLayout.JAVA_DOUBLE, base + 8, p.y.toDouble())
                    buffer.set(ValueLayout.JAVA_DOUBLE, base + 16, p.z.toDouble())
                }
                return wrap(ManifoldNative.hullPoints, buffer, points.size.toLong())
            }
        }

        /** The convex hull of several solids together — a loft, for two profiles a distance apart. */
        fun hullOf(vararg solids: Solid): Solid = hullOf(solids.toList())

        /** The convex hull of several solids together. */
        fun hullOf(solids: List<Solid>): Solid {
            require(solids.isNotEmpty()) { "a hull needs at least one solid" }
            val vec = ManifoldNative.ptr(
                ManifoldNative.manifoldVec,
                ManifoldNative.ptr(ManifoldNative.allocManifoldVec),
                solids.size.toLong(),
            )
            try {
                solids.forEachIndexed { i, s -> ManifoldNative.unit(ManifoldNative.manifoldVecSet, vec, i.toLong(), s.ptr) }
                return wrap(ManifoldNative.batchHull, vec)
            } finally {
                ManifoldNative.unit(ManifoldNative.deleteManifoldVec, vec)
            }
        }

        /**
         * March a Kotlin function into a **guaranteed-closed** solid.
         *
         * ```kotlin
         * val blob = Solid.levelSet(boundsAround(radius = 1.6), edgeLength = 0.03) { x, y, z ->
         *     val r = sqrt(x * x + y * y + z * z)
         *     0.9 - r + 0.15 * sin(5 * x) * sin(5 * y) * sin(5 * z)     // positive inside
         * }
         * ```
         *
         * # How this differs from [afield.stdlib.marchingCubes]
         *
         * Field's own marcher is topologically consistent and produces a watertight surface for a
         * well-behaved field. This one produces a **valid manifold** — the guarantee survives a field
         * with a thin sheet, a self-touching surface or a feature below the grid size, which are
         * exactly the cases where a marching-cubes surface becomes a soup. It is also the version
         * whose output you can immediately subtract from something.
         *
         * Sign convention here is `positive inside`, matching Manifold. Field's SDF family
         * ([afield.stdlib.sdfSphere] and friends) is `negative inside`, so pass one through
         * [levelSetOf] instead of negating by hand.
         *
         * # [field] is called from Manifold's thread pool
         *
         * That is where the speed comes from — measured on a unit sphere at `edgeLength = 0.04`,
         * 465k samples: **82 ms across 10 threads, against 151 ms sequential**. In exchange, [field]
         * must be thread-safe. If it is not (it accumulates into a shared array, it reuses a scratch
         * [Vec3], it touches a `Box`), pass `parallel = false` and take the 1.84×.
         *
         * An exception thrown by [field] cannot unwind through Manifold's C++ frames, so it is caught,
         * the sample reads as far-outside, and the exception is rethrown from this call.
         *
         * @param bounds the window to march; a shape touching the wall comes out cut off there.
         * @param edgeLength target triangle edge length. Cost grows as its cube — halving it is 8x.
         * @param level the iso-level to extract.
         * @param tolerance simplify the output to within this of the true level set; `-1.0` leaves it
         *   at full marching resolution.
         * @param parallel let Manifold call [field] from many threads at once.
         */
        fun levelSet(
            bounds: AxisAlignedBoundingBox3D,
            edgeLength: Double,
            level: Double = 0.0,
            tolerance: Double = -1.0,
            parallel: Boolean = true,
            field: (Double, Double, Double) -> Double,
        ): Solid {
            require(edgeLength > 0.0) { "edgeLength must be positive, got $edgeLength" }
            val size = bounds.size
            require(size.x > 0f && size.y > 0f && size.z > 0f) { "level-set bounds must have positive extent, got $size" }
            val bridge = ManifoldNative.SdfBridge(field)
            Arena.ofConfined().use { arena ->
                val stub = ManifoldNative.upcallSdf(bridge, arena)
                val box = ManifoldNative.ptr(
                    ManifoldNative.boxOf,
                    ManifoldNative.ptr(ManifoldNative.allocBox),
                    bounds.min.x.toDouble(), bounds.min.y.toDouble(), bounds.min.z.toDouble(),
                    bounds.max.x.toDouble(), bounds.max.y.toDouble(), bounds.max.z.toDouble(),
                )
                try {
                    val out = wrap(
                        if (parallel) ManifoldNative.levelSet else ManifoldNative.levelSetSeq,
                        stub,
                        box,
                        edgeLength,
                        level,
                        tolerance,
                        MemorySegment.NULL,
                    )
                    bridge.firstFailure?.let { throw IllegalStateException("the level-set field threw", it) }
                    return out
                } finally {
                    ManifoldNative.unit(ManifoldNative.deleteBox, box)
                }
            }
        }

        /**
         * [levelSet] taking one of Field's own [afield.stdlib.ScalarField3D]s — the `sdfSphere` /
         * `sdfSmoothUnion` family — and flipping the sign for you.
         *
         * ```kotlin
         * val body = sdfSmoothUnion(sdfSphere(0.8), sdfBox(vec(1.4, 0.25, 0.25)), k = 0.25)
         * val solid = Solid.levelSetOf(body, boundsAround(radius = 1.6), edgeLength = 0.02)
         * ```
         *
         * `parallel` defaults to **false** here, deliberately and unlike [levelSet]: a
         * [afield.stdlib.ScalarField3D] takes a [Vec3], `Vec3` is mutable, and every combinator in
         * that family is free to reuse the one it is handed. Calling such a field from ten threads is
         * the aliasing bug the mesh family's whole design avoids. Pass `parallel = true` only for a
         * field you know allocates per call.
         */
        fun levelSetOf(
            field: (Vec3) -> Float,
            bounds: AxisAlignedBoundingBox3D,
            edgeLength: Double,
            level: Double = 0.0,
            tolerance: Double = -1.0,
            parallel: Boolean = false,
        ): Solid {
            val probe = ThreadLocal.withInitial { Vec3() }
            return levelSet(bounds, edgeLength, -level, tolerance, parallel) { x, y, z ->
                // Negated: Field's SDFs are negative inside, Manifold's level sets positive inside.
                -field(probe.get().set(x.toFloat(), y.toFloat(), z.toFloat())).toDouble()
            }
        }

        /**
         * Manifold's global circular quality, which is what `segments = 0` on [sphere] and [cylinder]
         * consults.
         *
         * This is **process-global mutable state** in the native library, so it is not a thing to set
         * casually from a box that shares a VM with other boxes. It is here because matching a
         * project's segment count once beats passing `segments =` to every call, and because
         * [resetCircularQuality] exists to put it back.
         *
         * @param minAngle degrees per segment; the default is 10.
         * @param minEdgeLength segments no shorter than this, whatever the angle says.
         * @param segments a hard segment count that overrides both.
         */
        fun setCircularQuality(minAngle: Double? = null, minEdgeLength: Double? = null, segments: Int? = null) {
            minAngle?.let { ManifoldNative.unit(ManifoldNative.setMinCircularAngle, it) }
            minEdgeLength?.let { ManifoldNative.unit(ManifoldNative.setMinCircularEdgeLength, it) }
            segments?.let { ManifoldNative.unit(ManifoldNative.setCircularSegments, it) }
        }

        /** Put [setCircularQuality] back to Manifold's defaults. */
        fun resetCircularQuality() = ManifoldNative.unit(ManifoldNative.resetCircularDefaults)

        /** How many segments a circle of this radius gets under the current quality setting. */
        fun circularSegments(radius: Double): Int = ManifoldNative.int(ManifoldNative.circularSegments, radius)

        /**
         * Run [block], [close] every [Solid] and [Section] built inside it, and keep the one it
         * returns.
         *
         * The answer to "a box that runs every frame should not wait for a [Cleaner]":
         *
         * ```kotlin
         * val part = Solid.scope {
         *     val body = Solid.cube(2.0, 2.0, 1.0, center = true)
         *     val bore = Solid.cylinder(4.0, 0.3, center = true)
         *     body - bore                  // kept; body and bore are released here
         * }
         * ```
         *
         * The returned value is exempted, including one nested inside a [List], [Pair], [Triple] or
         * [Map] — so returning `listOf(a, b)` or `splitByPlane(...)` keeps both halves and closes the
         * rest. Anything else the block built is released in reverse order of construction.
         *
         * If a scope's result is also reachable from something the block kept a reference to, prefer
         * [copy]: an exemption is decided by *what came back*, not by what is still in use.
         */
        fun <T> scope(block: () -> T): T {
            val previous = TRACKER.get()
            val tracked = ArrayList<AutoCloseable>()
            TRACKER.set(tracked)
            val result: T
            try {
                result = block()
            } finally {
                TRACKER.set(previous)
            }
            val kept = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AutoCloseable, Boolean>())
            collectCloseables(result, kept)
            // Anything kept has to be handed to the enclosing scope, or a nested scope's result would
            // outlive the outer one that was meant to own it.
            previous?.addAll(kept.filter { it in tracked })
            // Reverse order, so a solid built from another is released before the one it came from --
            // which does not matter to Manifold (each owns its own memory) but keeps the failure mode
            // legible if it ever does.
            tracked.asReversed().forEach { if (it !in kept) runCatching { it.close() } }
            return result
        }

        /** One level of unwrapping, which is as far as a "what did the block return" rule should reach. */
        private fun collectCloseables(value: Any?, into: MutableSet<AutoCloseable>) {
            when (value) {
                null -> Unit
                is Solid -> into += value
                is Section -> into += value
                is Iterable<*> -> value.forEach { collectCloseables(it, into) }
                is Pair<*, *> -> { collectCloseables(value.first, into); collectCloseables(value.second, into) }
                is Triple<*, *, *> -> {
                    collectCloseables(value.first, into)
                    collectCloseables(value.second, into)
                    collectCloseables(value.third, into)
                }
                is Map<*, *> -> value.forEach { (k, v) -> collectCloseables(k, into); collectCloseables(v, into) }
                is Array<*> -> value.forEach { collectCloseables(it, into) }
                else -> Unit
            }
        }

        /** Solids and sections built on this thread inside the innermost [scope], if any. */
        internal val TRACKER = ThreadLocal<MutableList<AutoCloseable>?>()

        /** Every op funnels through here, so tracking and ownership are stated once. */
        internal fun wrap(handle: java.lang.invoke.MethodHandle, vararg args: Any?): Solid {
            // Manifold's constructors build into the buffer they are handed and return it, so the
            // returned pointer is the one we allocated. Wrapping the *returned* value rather than the
            // buffer means a future version that decides otherwise leaks instead of double-freeing.
            val out = ManifoldNative.ptr(handle, ManifoldNative.newManifold(), *args)
            return Solid(out)
        }
    }

    /** An independent copy — the one thing that survives its enclosing [scope]. */
    fun copy(): Solid = wrap(ManifoldNative.copy, ptr)

    init {
        TRACKER.get()?.add(this)
    }
}

/**
 * The [Cleaner] action, and the reason it is its own class: a cleaning action must not reference the
 * object it cleans up after, or that object is never collectable and the [Cleaner] never fires. So
 * this holds the pointer and the kind, and knows nothing about the [Solid] or [Section] in front of it.
 */
internal class NativeRelease(val handle: MemorySegment, private val kind: Kind) : Runnable {
    enum class Kind { MANIFOLD, SECTION }

    @Volatile
    var released: Boolean = false
        private set

    override fun run() {
        if (released) return
        released = true
        when (kind) {
            Kind.MANIFOLD -> ManifoldNative.unit(ManifoldNative.deleteManifold, handle)
            Kind.SECTION -> ManifoldNative.unit(ManifoldNative.deleteSection, handle)
        }
    }
}

// ==============================================================================================
// The bridges from Field's own geometry
// ==============================================================================================

/**
 * Read a [TriMesh] into Manifold, so it can be cut.
 *
 * ```kotlin
 * val scan = readObj(File("scan.obj")).toSolid()
 * val trimmed = scan.trimByPlane(0.0, 0.0, 1.0, 0.0)
 * ```
 *
 * The mesh must already be closed and consistently wound, because that is what a manifold *is*.
 * Anything else comes back as a [Solid] whose [Solid.status] says so — and rather than leave that to
 * be discovered later, this checks by default.
 *
 * # When it refuses, it says why
 *
 * Do not guess from the list below — the exception this throws now carries
 * [afield.stdlib.TriMesh.diagnose]'s report, which names the actual defect: how many boundary edges in
 * how many loops and how big, how many edges have three faces, whether the winding is consistent.
 * The two calls worth knowing:
 *
 * | you want | call |
 * |---|---|
 * | to know *why* this mesh is not a solid | [afield.stdlib.diagnose] — `println(mesh.diagnose().report())` |
 * | to fix it, and be told what could not be fixed | [afield.stdlib.repairToSolid] |
 *
 * [afield.stdlib.repairToSolid] welds, drops degenerate and duplicated faces, removes dangling fins,
 * fixes the winding, fills the holes and fixes the winding again, and returns a
 * [afield.stdlib.MeshRepairResult] rather than a bare mesh — because the interesting part is the list
 * of things it could *not* do. The older [afield.stdlib.repaired] is still there and still fine for a
 * mesh whose only problem is an unwelded seam; it does not fill holes.
 *
 * @param check throw on an invalid mesh instead of returning a [Solid] that quietly has no volume.
 * @param weldTolerance weld coincident vertices first. `null` (the default) does not weld, because
 *   welding a mesh that did not need it is not free; pass `1e-6` for anything that came off disk.
 */
fun TriMesh.toSolid(check: Boolean = true, weldTolerance: Double? = null): Solid {
    val source = weldTolerance?.let { weld(it) } ?: this
    require(source.vertexCount > 0 && source.triangleCount > 0) { "cannot make a Solid from an empty TriMesh" }
    Arena.ofConfined().use { arena ->
        val vertexProperties = arena.allocateFrom(ValueLayout.JAVA_FLOAT, *source.positions)
        val triangles = arena.allocateFrom(ValueLayout.JAVA_INT, *source.indices)
        val mesh = ManifoldNative.ptr(
            ManifoldNative.meshGL,
            ManifoldNative.ptr(ManifoldNative.allocMeshGL),
            vertexProperties,
            source.vertexCount.toLong(),
            3L,
            triangles,
            source.triangleCount.toLong(),
        )
        try {
            val solid = Solid.wrap(ManifoldNative.ofMeshGL, mesh)
            val status = solid.status
            if (!check || !status.isError) return solid
            // Manifold can only say *that* it refused. Field can say why, so it does: the diagnosis
            // names the actual defect — which loops are open and how big, which edges have too many
            // faces — instead of the old message's list of things the reader might try.
            // `status` is read *before* the close below: the accessor throws on a released handle.
            val why = runCatching { source.diagnose().report() }.getOrNull()
            solid.close()
            throw IllegalStateException(
                "this TriMesh is not a valid manifold: $status.\n" +
                    (why ?: "Diagnosis unavailable; see afield.stdlib.TriMesh.diagnose().") +
                    "\nTriMesh.repairToSolid() attempts these in order and reports what it could not do.",
            )
        } finally {
            ManifoldNative.unit(ManifoldNative.deleteMeshGL, mesh)
        }
    }
}

/** `true` when Manifold agrees this mesh is a closed solid — the cheap pre-flight for [toSolid]. */
fun TriMesh.isValidSolid(): Boolean =
    runCatching { toSolid(check = false).use { !it.status.isError } }.getOrDefault(false)

/**
 * How far apart two solids' volumes are, relative to the larger — the assertion a geometry test
 * actually wants, and the reason it is here rather than repeated in each one.
 */
internal fun relativeVolumeError(a: Double, b: Double): Double {
    val scale = maxOf(abs(a), abs(b))
    return if (scale == 0.0) 0.0 else abs(a - b) / scale
}
