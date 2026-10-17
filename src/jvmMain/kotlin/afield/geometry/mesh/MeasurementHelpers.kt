package afield.geometry.mesh

import afield.document.graph.Box
import afield.geometry.spatial.AxisAlignedBoundingBox3D
import afield.math.linalg.Vec3
import afield.ui.output.f6
import java.util.Locale
import java.util.Random
import kotlin.math.abs

/**
 * Print a measured value beside its analytic answer, absolute error and (for a nonzero answer)
 * relative error. Numeric text is locale-independent; a zero answer reports absolute error only.
 */
fun Box.compare(label: String, measured: Number, analytic: Number) {
    val actual = measured.toDouble()
    val expected = analytic.toDouble()
    val error = abs(actual - expected)
    val relative = if (expected == 0.0) "" else
        "; relative " + String.format(Locale.ROOT, "%.2e", error / abs(expected))
    println("$label: measured ${actual.f6}; analytic ${expected.f6}; absolute error ${error.f6}$relative")
}

/** Integrate one scalar per vertex using [mixedVertexAreas], the same weights as the curvature operators. */
fun TriMesh.integrate(field: DoubleArray): Double {
    require(field.size == vertexCount) { "field has ${field.size} values for $vertexCount vertices" }
    val areas = mixedVertexAreas()
    return field.indices.sumOf { field[it] * areas[it] }
}

/** [integrate] for a float-valued vertex field; accumulation remains in double precision. */
fun TriMesh.integrate(field: FloatArray): Double {
    require(field.size == vertexCount) { "field has ${field.size} values for $vertexCount vertices" }
    val areas = mixedVertexAreas()
    return field.indices.sumOf { field[it].toDouble() * areas[it] }
}

/**
 * Uniform points in an explicit axis-aligned 3D box. Uses a fresh seeded Java Random and three
 * nextFloat samples per point, so calls are repeatable without changing any shared random state.
 * Bounds may have zero extent along an axis; their coordinates must be finite and ordered.
 */
fun randomPoints(count: Int, seed: Long = 0L, bounds: AxisAlignedBoundingBox3D): List<Vec3> {
    require(count >= 0) { "point count must be non-negative" }
    val low = bounds.min
    val high = bounds.max
    require(listOf(low.x, low.y, low.z, high.x, high.y, high.z).all { it.isFinite() } &&
        low.x <= high.x && low.y <= high.y && low.z <= high.z) { "bounds must be finite and ordered" }
    val random = Random(seed)
    return List(count) {
        Vec3(
            (low.x + (high.x.toDouble() - low.x) * random.nextFloat()).toFloat(),
            (low.y + (high.y.toDouble() - low.y) * random.nextFloat()).toFloat(),
            (low.z + (high.z.toDouble() - low.z) * random.nextFloat()).toFloat(),
        )
    }
}
