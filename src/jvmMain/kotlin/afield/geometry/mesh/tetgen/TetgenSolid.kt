package afield.geometry.mesh.tetgen

import afield.geometry.solid.manifold.Solid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Tetrahedralize a Manifold [solid] straight from its `double` positions.
 *
 * The same mesh as `Tetgen.tetrahedralize(solid.toTriMesh(), ...)`, without the narrowing:
 * `TriMesh` stores `Float`, so going through it rounds every coordinate before TetGen sees it,
 * which on a curved body costs about one part in `10⁹` of volume. This reads the solid's welded
 * triangulation through [Solid.toPlanarMesh] and hands those doubles to TetGen unchanged, so the
 * tetrahedra enclose what Manifold computed to TetGen's own precision.
 *
 * The parameters are [Tetgen.tetrahedralize]'s. Manifold guarantees a closed, consistently wound
 * surface, so the seam checks the `TriMesh` overload runs are not needed here.
 */
fun Tetgen.tetrahedralize(
    solid: Solid,
    quality: Double = 1.2,
    targetEdgeLength: Double? = null,
    preserveSurface: Boolean = false,
    order: Int = 1,
): TetMesh {
    require(quality > 1.0) { "quality must exceed 1.0, got $quality" }
    require(order == 1 || order == 2) { "order must be 1 (4-node) or 2 (10-node), got $order" }
    targetEdgeLength?.let { require(it > 0 && it.isFinite()) { "targetEdgeLength must be positive" } }
    val surface = solid.toPlanarMesh()
    require(surface.indices.isNotEmpty()) { "Cannot tetrahedralize an empty solid" }
    val maxVolume = targetEdgeLength?.let { it * it * it / (6.0 * sqrt(2.0)) } ?: 0.0
    if (targetEdgeLength != null) {
        val surfaceEdge = medianEdgeLength(surface.positions, surface.indices)
        if (targetEdgeLength > surfaceEdge) {
            afield.app.diagnostics.logging.lprintln(
                "warning,tetgen",
                "Tetgen.tetrahedralize: targetEdgeLength ${"%.4g".format(targetEdgeLength)} is coarser than the " +
                    "solid's own median edge (${"%.4g".format(surfaceEdge)}); TetGen keeps every input triangle, " +
                    "so elements near the surface will be about that size instead.",
            )
        }
    }
    val linear = TetgenNative.tetrahedralize(surface.positions, surface.indices, quality, maxVolume, preserveSurface)
    val solidVolume = solid.volume
    val tetVolume = linear.volume()
    if (abs(tetVolume - solidVolume) > 1e-4 * max(solidVolume, 1e-12)) {
        afield.app.diagnostics.logging.lprintln(
            "warning,tetgen",
            "Tetgen.tetrahedralize: the tetrahedra hold ${"%.6g".format(tetVolume)} but the solid encloses " +
                "${"%.6g".format(solidVolume)}.",
        )
    }
    return if (order == 2) linear.quadratic() else linear
}

private fun medianEdgeLength(positions: DoubleArray, indices: IntArray): Double {
    val lengths = DoubleArray(indices.size) { k ->
        val a = indices[k]; val b = indices[if (k % 3 == 2) k - 2 else k + 1]
        val dx = positions[3 * a] - positions[3 * b]; val dy = positions[3 * a + 1] - positions[3 * b + 1]; val dz = positions[3 * a + 2] - positions[3 * b + 2]
        sqrt(dx * dx + dy * dy + dz * dz)
    }
    lengths.sort()
    return lengths[lengths.size / 2]
}
