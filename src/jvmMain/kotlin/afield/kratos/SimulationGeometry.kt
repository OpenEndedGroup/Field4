package afield.kratos

import afield.manifold.Solid
import afield.stdlib.TriMesh
import afield.stdlib.Vec3
import afield.stdlib.bvh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor

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

/** Read-only by convention: positions/cells are retained once and shared with result revisions. */
class BackgroundDomain private constructor(val id: String, val bounds: SimulationBounds, val resolution: Int,
                                          val positions: DoubleArray, val cells: IntArray, val buildMs: Double) {
    val nodeCount: Int get() = positions.size / 3
    val cellCount: Int get() = cells.size / 4
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
    }
}

/** Named spatial selection whose identity survives successive result revisions. */
data class SimulationProbe(val id: String, val x: Double, val y: Double, val z: Double) {
    init { require(id.isNotBlank() && listOf(x,y,z).all(Double::isFinite)) }
}
data class SimulationProbeSample(val probeId: String, val revision: SimulationRevision, val temperature: Double?)

/** Authoritative raw field, plus derived views which reuse Field's existing mesh graphics. */
class ThermalResult private constructor(val domain: BackgroundDomain, val revision: SimulationRevision,
                                       val temperature: DoubleArray, val distance: DoubleArray, val cellState: IntArray,
                                       val diagnostics: Map<String, Any?>) {
    fun sample(probe: SimulationProbe): SimulationProbeSample = SimulationProbeSample(probe.id, revision, sample(probe.x,probe.y,probe.z))

    /** Linear interpolation in the *same six-tetrahedra subdivision*, not trilinear hexahedral interpolation. */
    fun sample(x: Double, y: Double, z: Double): Double? = weights(x,y,z)?.let { (ids, w) ->
        if (ids.none { distance[it] > 0 }) null else ids.indices.sumOf { temperature[ids[it]] * w[it] }
    }

    private fun weights(x: Double, y: Double, z: Double): Pair<IntArray, DoubleArray>? {
        val b = domain.bounds
        val coord = doubleArrayOf((x-b.minX)/(b.maxX-b.minX), (y-b.minY)/(b.maxY-b.minY), (z-b.minZ)/(b.maxZ-b.minZ))
        if (coord.any { !it.isFinite() || it < 0 || it > 1 }) return null
        val base = IntArray(3) { floor(coord[it]*domain.resolution).toInt().coerceAtMost(domain.resolution-1) }
        val frac = DoubleArray(3) { coord[it]*domain.resolution-base[it] }
        val order = (0..2).sortedByDescending { frac[it] }
        val ids = IntArray(4)
        ids[0] = domain.index(base[0],base[1],base[2])
        for (i in 0..2) { base[order[i]]++; ids[i+1] = domain.index(base[0],base[1],base[2]) }
        val weights = doubleArrayOf(1-frac[order[0]], frac[order[0]]-frac[order[1]], frac[order[1]]-frac[order[2]], frac[order[2]])
        return ids to weights
    }

    private fun color(value: Double?): FloatArray {
        if (value == null) return floatArrayOf(.35f,.35f,.35f)
        val lo = (diagnostics["temperatureMin"] as Number).toDouble()
        val hi = (diagnostics["temperatureMax"] as Number).toDouble()
        val t = ((value-lo)/(hi-lo).coerceAtLeast(1e-15)).coerceIn(0.0,1.0).toFloat()
        return floatArrayOf(t, .25f + .4f*(1-abs(2*t-1)), 1-t)
    }

    /** Grey vertices have no active background support. Skin sampling is a view, not the raw field. */
    fun surfaceMesh(skin: TriMesh): TriMesh {
        val colors = FloatArray(skin.positions.size)
        for (i in 0 until skin.vertexCount) {
            val p = skin.positions
            color(sample(p[i*3].toDouble(),p[i*3+1].toDouble(),p[i*3+2].toDouble())).copyInto(colors, i*3)
        }
        return skin.withColors(colors)
    }

    /** Interior XY slice, cropped by the interpolated distance. Partial edge triangles are omitted. */
    fun sliceMesh(z: Double = (domain.bounds.minZ+domain.bounds.maxZ)/2, samples: Int = 64): TriMesh {
        require(samples in 2..256)
        val n = samples+1
        val positions = FloatArray(n*n*3)
        val colors = FloatArray(n*n*3)
        val inside = BooleanArray(n*n)
        val b = domain.bounds
        for (x in 0..samples) for (y in 0..samples) {
            val index = x*n+y
            val px = b.minX+(b.maxX-b.minX)*x/samples
            val py = b.minY+(b.maxY-b.minY)*y/samples
            positions[3*index]=px.toFloat(); positions[3*index+1]=py.toFloat(); positions[3*index+2]=z.toFloat()
            val weights = weights(px,py,z)
            inside[index] = weights?.let { (ids,w) -> ids.indices.sumOf { distance[ids[it]]*w[it] } > 0 } == true
            color(sample(px,py,z)).copyInto(colors,index*3)
        }
        val indices = ArrayList<Int>()
        fun triangle(a: Int,b: Int,c: Int) { if (inside[a] && inside[b] && inside[c]) { indices.add(a);indices.add(b);indices.add(c) } }
        for (x in 0 until samples) for (y in 0 until samples) {
            val i = x*n+y
            triangle(i,i+n,i+n+1); triangle(i,i+n+1,i+1)
        }
        return TriMesh(positions,indices.toIntArray(),colors=colors)
    }

    internal companion object {
        fun decode(domain: BackgroundDomain, revision: SimulationRevision, payload: Map<String, Any?>, geometryMs: Double, roundTripMs: Double, writeMs: Double = 0.0, readMs: Double = 0.0): ThermalResult {
            val diagnostics = payload["diagnostics"].fieldMap()
            require(diagnostics["backgroundId"] == domain.id && diagnostics.number("buildCount") == 1L) { "Worker rebuilt or replaced the background domain" }
            val arrays = KratosArrays(payload)
            val state = arrays.ints("cellState",domain.cellCount)
            require(state.all { it in 0..2 })
            return ThermalResult(domain,revision,arrays.doubles("temperature",domain.nodeCount),arrays.doubles("distance",domain.nodeCount),state,
                diagnostics + mapOf("fieldGeometryEvaluationMs" to geometryMs, "roundTripMs" to roundTripMs, "geometryTransferMs" to writeMs, "resultTransferMs" to readMs))
        }
    }
}
