package afield.kratos

import afield.manifold.Solid
import afield.stdlib.TriMesh
import afield.stdlib.Vec3
import afield.stdlib.bvh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor

/**
 * What a session is told about shape, and the discretisation that shape gets solved on.
 *
 * Every [SimulationGeometry] answers two questions, for two different readers.
 * `surface()` returns a [TriMesh] for Field to draw, and Manifold builds that in every case.
 * `workerData(domain)` returns what goes over the wire, and the implementations answer it in two
 * different currencies.
 *
 * # Symbolic, or sampled
 *
 * A [PrimitiveGeometry] sends `expression()`: a small tree of boxes, spheres, cylinders and boolean
 * operations that the worker evaluates as an exact distance at each node it cares about. A
 * [ManifoldGeometry] sends the distances themselves, one double per node of the domain, measured
 * against Field's own BVH over the triangulated solid and negated on the way out because Kratos
 * counts distance positive inside.
 *
 * That difference decides what a display setting can and cannot affect. A primitive's tessellation
 * is a property of the picture only: refining a sphere's `segments` changes no node's
 * classification, because the worker never sees the triangles. For a [ManifoldGeometry] the
 * triangles are the input, so its payload is both sized to one particular domain's node count and
 * only as good as the mesh it was snapshotted from.
 *
 * # Identity is authored, not derived
 *
 * The `id` on a geometry is chosen by whoever built it and is independent of the triangle indices
 * Manifold happens to produce, which is what lets a boundary condition or a material keep naming
 * the same region across a re-solve: a formulation derives its region keys from that id.
 * [SimulationGroup] requires its operands' ids to be distinct for the same reason. Bounds are
 * metres, and coefficients are SI throughout, kelvin included.
 *
 * # The domain is two different things behind one type
 *
 * [BackgroundDomain] is the node and cell arrays a result is indexed against, and its `resolution`
 * tells its two kinds apart. Positive means the worker built a structured Freudenthal grid from
 * [SimulationBounds], six tetrahedra to a cube, which is what makes sampling a closed-form index
 * calculation and what lets an embedded formulation reclassify new geometry without remeshing.
 * Zero means Field meshed a body itself and sent it, in which case there is no (i, j, k) to compute
 * from and the index-based views are unavailable.
 */

/** Bounds are metres in schema 1; all simulation coefficients use SI, including kelvin. */
data class SimulationBounds(val minX: Double, val minY: Double, val minZ: Double, val maxX: Double, val maxY: Double, val maxZ: Double) {
    init { require(wire().all(Double::isFinite) && minX < maxX && minY < maxY && minZ < maxZ) }
    internal fun wire(): List<Double> = listOf(minX, minY, minZ, maxX, maxY, maxZ)
}

/** Stable authored identity, independent of Manifold's temporary triangle indices. */
sealed interface SimulationGeometry {
    val id: String
    fun surface(): TriMesh
    fun workerData(domain: BackgroundDomain): Map<String, Any?>
}

/** Analytic CSG classification; Manifold remains the implementation of the renderable surface. */
sealed interface PrimitiveGeometry : SimulationGeometry {
    fun expression(): Map<String, Any?>
    fun solid(): Solid
    override fun surface(): TriMesh = Solid.scope { solid().checked().toTriMesh() }
    override fun workerData(domain: BackgroundDomain): Map<String, Any?> = mapOf("geometry" to expression())
}

data class SimulationBox(override val id: String, val bounds: SimulationBounds) : PrimitiveGeometry {
    init { require(id.isNotBlank()) }
    override fun expression(): Map<String, Any?> = mapOf("id" to id, "op" to "box", "min" to bounds.wire().take(3), "max" to bounds.wire().drop(3))
    override fun solid(): Solid = bounds.run {
        Solid.cube(maxX-minX, maxY-minY, maxZ-minZ).use { it.translated(minX, minY, minZ) }
    }
}

/**
 * A ball, as an exact distance and as a Manifold solid.
 *
 * `segments` is the Manifold tessellation only. The worker never sees it: the analytic distance is
 * the true sphere, so refining the display surface does not change a single node's classification.
 * That asymmetry is the point of keeping a primitive symbolic rather than shipping its triangles.
 */
data class SimulationSphere(override val id: String, val x: Double, val y: Double, val z: Double,
                            val radius: Double, val segments: Int = 0) : PrimitiveGeometry {
    init {
        require(id.isNotBlank())
        require(listOf(x, y, z, radius).all(Double::isFinite) && radius > 0) { "sphere needs a finite positive radius" }
    }
    override fun expression(): Map<String, Any?> =
        mapOf("id" to id, "op" to "sphere", "centre" to listOf(x, y, z), "radius" to radius)
    override fun solid(): Solid = Solid.sphere(radius, segments).use { it.translated(x, y, z) }
}

/**
 * A capped cylinder between two points, at any orientation.
 *
 * Manifold builds cylinders along +Z, so the solid is one built at the origin and pushed through a
 * 3x4 affine whose third column is the axis direction. The two columns across it are an arbitrary
 * orthonormal pair — a cylinder is rotationally symmetric, so which pair does not matter, and
 * choosing them without a branch avoids the degenerate case where the axis is parallel to whatever
 * reference vector a naive cross product picked.
 */
data class SimulationCylinder(override val id: String,
                              val ax: Double, val ay: Double, val az: Double,
                              val bx: Double, val by: Double, val bz: Double,
                              val radius: Double, val segments: Int = 0) : PrimitiveGeometry {
    init {
        require(id.isNotBlank())
        require(listOf(ax, ay, az, bx, by, bz, radius).all(Double::isFinite) && radius > 0) {
            "cylinder needs finite endpoints and a positive radius"
        }
        require(axisLength() > 0) { "cylinder endpoints must differ" }
    }

    private fun axisLength(): Double = kotlin.math.sqrt(
        (bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az))

    override fun expression(): Map<String, Any?> = mapOf(
        "id" to id, "op" to "cylinder", "a" to listOf(ax, ay, az), "b" to listOf(bx, by, bz), "radius" to radius)

    override fun solid(): Solid {
        val h = axisLength()
        val (dx, dy, dz) = Triple((bx - ax) / h, (by - ay) / h, (bz - az) / h)
        // Duff et al's branchless orthonormal basis around (dx, dy, dz).
        val sign = if (dz >= 0.0) 1.0 else -1.0
        val a = -1.0 / (sign + dz)
        val b = dx * dy * a
        return Solid.cylinder(h, radius, radius, segments).use {
            it.transformed(
                1.0 + sign * dx * dx * a, sign * b, -sign * dx,
                b, sign + dy * dy * a, -dy,
                dx, dy, dz,
                ax, ay, az,
            )
        }
    }
}

enum class SimulationBoolean { UNION, DIFFERENCE, INTERSECTION }
data class SimulationCSG(override val id: String, val a: PrimitiveGeometry, val b: PrimitiveGeometry,
                         val operation: SimulationBoolean = SimulationBoolean.UNION) : PrimitiveGeometry {
    init { require(id.isNotBlank()) }
    override fun expression(): Map<String, Any?> = mapOf("id" to id, "op" to operation.name.lowercase(), "a" to a.expression(), "b" to b.expression())
    override fun solid(): Solid = a.solid().use { left -> b.solid().use { right ->
        when (operation) { SimulationBoolean.UNION -> left + right; SimulationBoolean.DIFFERENCE -> left - right; SimulationBoolean.INTERSECTION -> left and right }
    } }
}

/**
 * The n-ary spelling of [SimulationCSG]: one operation over any number of operands.
 *
 * Worth preferring over nested pairs for a union of many parts, for the same reason
 * `Solid.union(list)` beats folding `+`: Manifold plans the whole batch instead of nesting *n−1*
 * operations, and on the worker side one `np.maximum.reduce` replaces a recursion. `DIFFERENCE`
 * reads as the first operand minus every other one.
 */
data class SimulationGroup(override val id: String, val operation: SimulationBoolean,
                           val children: List<PrimitiveGeometry>) : PrimitiveGeometry {
    init {
        require(id.isNotBlank())
        require(children.size >= 2) { "$operation needs at least two operands, got ${children.size}" }
        val duplicates = children.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "operand ids must be distinct for stable identity: $duplicates" }
    }
    override fun expression(): Map<String, Any?> =
        mapOf("id" to id, "op" to operation.name.lowercase(), "children" to children.map { it.expression() })
    override fun solid(): Solid {
        val solids = children.map { it.solid() }
        try {
            return when (operation) {
                SimulationBoolean.UNION -> Solid.union(solids)
                SimulationBoolean.INTERSECTION -> Solid.intersection(solids)
                SimulationBoolean.DIFFERENCE -> Solid.difference(solids)
            }
        } finally {
            solids.forEach { it.close() }
        }
    }
}

/**
 * Snapshot of the existing Manifold conversion. The caller retains ownership of [Solid].
 * Field's existing BVH supplies distances (negated to Kratos' positive-inside convention).
 * Classification runs on the session executor. Triangle provenance is not invented here.
 */
class ManifoldGeometry(override val id: String, solid: Solid) : SimulationGeometry {
    private val mesh = solid.checked().toTriMesh()
    private val acceleration by lazy { mesh.bvh() }
    init { require(id.isNotBlank() && !mesh.isEmpty) }
    override fun surface(): TriMesh = mesh
    override fun workerData(domain: BackgroundDomain): Map<String, Any?> {
        val data = ByteBuffer.allocate(domain.nodeCount * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until domain.nodeCount) {
            val p = domain.positions
            data.putDouble(-acceleration.signedDistance(Vec3(p[3*i].toFloat(), p[3*i+1].toFloat(), p[3*i+2].toFloat())))
        }
        return mapOf("geometryId" to id, "distance" to data.array())
    }
}

/**
 * The discretisation a session solves on, however it was made.
 *
 * Two kinds, and [resolution] tells them apart. A **structured** domain (`resolution > 0`) is the
 * Freudenthal grid the worker builds from bounds; its regularity is what makes barycentric
 * sampling a closed-form index calculation, and what lets an embedded formulation reclassify
 * without remeshing. An **unstructured** domain (`resolution == 0`) is a body-fitted tetrahedral
 * mesh Field produced with TetGen and sent; it has no (i, j, k), so point sampling and slicing
 * are unavailable on it and [SimulationResult.boundaryMesh] is the view to use instead.
 */
class BackgroundDomain private constructor(val id: String, val bounds: SimulationBounds, val resolution: Int,
                                          val positions: DoubleArray, val cells: IntArray, val buildMs: Double,
                                          /**
                                           * 4 for linear tetrahedra, 10 for quadratic ones. A
                                           * structured grid is always linear; a supplied mesh is
                                           * whatever [afield.tetgen.Tetgen] was asked for. Anything
                                           * reading connectivity for its *shape* wants the first
                                           * four of each group and this as the stride.
                                           */
                                          val nodesPerCell: Int = 4) {

    /** True when this is a structured grid and so can be sampled by index. */
    val isStructured: Boolean get() = resolution > 0
    val nodeCount: Int get() = positions.size / 3
    val cellCount: Int get() = cells.size / nodesPerCell
    val buildCount: Int = 1
    internal fun index(x: Int, y: Int, z: Int): Int = (x * (resolution+1) + y) * (resolution+1) + z
    internal companion object {
        fun decode(bounds: SimulationBounds, resolution: Int, payload: Map<String, Any?>): BackgroundDomain {
            require(resolution in 4..32)
            val arrays = KratosArrays(payload)
            val nodes = (resolution+1)*(resolution+1)*(resolution+1)
            val cells = arrays.ints("cells", resolution*resolution*resolution*6, 4)
            require(cells.all { it in 0 until nodes }) { "Invalid background node index" }
            require(payload.number("buildCount") == 1L)
            return BackgroundDomain(payload["backgroundId"] as String, bounds, resolution,
                arrays.doubles("positions", nodes, 3), cells, (payload["domainBuildMs"] as Number).toDouble())
        }

        /**
         * The domain for a mesh Field meshed itself and sent, so nothing comes back over the wire
         * except an id: the caller already has every coordinate.
         */
        fun ofSuppliedMesh(id: String, mesh: afield.tetgen.TetMesh, buildMs: Double): BackgroundDomain {
            val p = mesh.positions
            val bounds = SimulationBounds(
                (0 until mesh.nodeCount).minOf { p[3 * it] }, (0 until mesh.nodeCount).minOf { p[3 * it + 1] },
                (0 until mesh.nodeCount).minOf { p[3 * it + 2] }, (0 until mesh.nodeCount).maxOf { p[3 * it] },
                (0 until mesh.nodeCount).maxOf { p[3 * it + 1] }, (0 until mesh.nodeCount).maxOf { p[3 * it + 2] })
            return BackgroundDomain(id, bounds, 0, mesh.positions, mesh.cells, buildMs, mesh.nodesPerCell)
        }
    }
}

/** Named spatial selection whose identity survives successive result revisions. */
data class SimulationProbe(val id: String, val x: Double, val y: Double, val z: Double) {
    init { require(id.isNotBlank() && listOf(x,y,z).all(Double::isFinite)) }
}
data class SimulationProbeSample(val probeId: String, val revision: SimulationRevision, val value: Double?) {
    /** The schema-1 name, when the field being probed is a temperature. */
    val temperature: Double? get() = value
}
