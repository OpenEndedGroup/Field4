@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spectral

import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.marchingCubes
import afield.geometry.spatial.AxisAlignedBoundingBox3D
import afield.math.linalg.Vec3
import kotlin.math.roundToInt

/** A bounded sampling lattice, with X fastest and both boundary faces included. */
class PlaneWaveGrid(bounds: AxisAlignedBoundingBox3D, val nx: Int, val ny: Int = nx, val nz: Int = nx) {
    private val domain = AxisAlignedBoundingBox3D(Vec3(bounds.min), Vec3(bounds.max))
    private val spacing = domain.size
    private val strideY = Math.addExact(nx, 1)
    private val strideZ = Math.multiplyExact(strideY, Math.addExact(ny, 1))
    val pointCount = Math.multiplyExact(strideZ, Math.addExact(nz, 1))

    init {
        require(nx >= 1 && ny >= 1 && nz >= 1)
        require(spacing.isFinite() && spacing.x > 0 && spacing.y > 0 && spacing.z > 0)
        spacing.div(nx.toFloat(), ny.toFloat(), nz.toFloat())
    }

    /** Lazy list suitable for [SpectralGpuEvaluator.spatial] or a CPU sampling pass. */
    val points: List<Vec3> = object : AbstractList<Vec3>() {
        override val size = pointCount

        override fun get(index: Int): Vec3 {
            require(index in indices)
            val z = index / strideZ
            val y = index % strideZ / strideY
            val x = index % strideY
            return Vec3(domain.min.x + x * spacing.x, domain.min.y + y * spacing.y, domain.min.z + z * spacing.z)
        }
    }

    fun sample(field: PlaneWaveField): FloatArray =
        FloatArray(pointCount) { field.evaluate(points[it]).toFloat() }

    /** Reuses a CPU or GPU scalar grid, with no additional wave evaluations. */
    fun extract(values: FloatArray, isoValue: Double = 0.0, positiveOutside: Boolean = true): TriMesh {
        require(values.size == pointCount && values.all { it.isFinite() } && isoValue.isFinite())
        return marchingCubes({ point ->
            val i = ((point.x - domain.min.x) / spacing.x).roundToInt().coerceIn(0, nx)
            val j = ((point.y - domain.min.y) / spacing.y).roundToInt().coerceIn(0, ny)
            val k = ((point.z - domain.min.z) / spacing.z).roundToInt().coerceIn(0, nz)
            values[k * strideZ + j * strideY + i]
        }, domain, nx, ny, nz, isoValue, positiveOutside)
    }
}
