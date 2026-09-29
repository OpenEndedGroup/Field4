package afield.manifold

import afield.stdlib.TriMesh
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout

/**
 * One polygonal face in Manifold's full-precision mesh export.
 *
 * [faceId] is only meaningful inside [runIndex]. All triangles in this group are one planar face
 * according to Manifold's topology, even when the face has holes or several boundary loops.
 */
data class SolidPlanarFace(
    val runIndex: Int,
    val originalId: Int?,
    val faceId: Long,
    /** Triangle ordinals in [SolidPlanarMesh.indices], after degenerate index triangles are removed. */
    val triangles: IntArray,
)

/**
 * Manifold's full-precision boundary mesh: double-precision positions plus native polygonal-face provenance.
 *
 * [triangleFaces] maps every retained triangle to [faces]. Rendering may triangulate a face however
 * it likes; different entries can also mark coplanar patches retained from separate Boolean inputs.
 * They do not by themselves prove a physical crease. Use geometry as well as this
 * provenance for dimensional and planarity checks. [toTriMesh] is deliberately
 * a final, lossy conversion for Field's renderer.
 */
data class SolidPlanarMesh(
    val positions: DoubleArray,
    val indices: IntArray,
    val triangleFaces: IntArray,
    val faces: List<SolidPlanarFace>,
    val nativeTolerance: Double,
) {
    init {
        require(positions.size % 3 == 0) { "positions must contain xyz triples" }
        require(positions.all { it.isFinite() }) { "positions must be finite" }
        require(nativeTolerance.isFinite() && nativeTolerance >= 0.0)
        require(indices.all { it in 0 until positions.size / 3 }) { "index outside vertex array" }
        require(indices.size % 3 == 0) { "indices must contain triangle triples" }
        require(triangleFaces.size == indices.size / 3) {
            "triangleFaces must name one face for every triangle"
        }
        require(triangleFaces.all { it in faces.indices }) { "triangleFaces contains an unknown face" }
        val seen = IntArray(triangleFaces.size)
        faces.forEachIndexed { f, face ->
            require(face.triangles.isNotEmpty()) { "empty provenance face" }
            face.triangles.forEach { t ->
                require(t in triangleFaces.indices && triangleFaces[t] == f) { "inconsistent face membership" }
                seen[t]++
            }
        }
        require(seen.all { it == 1 }) { "each triangle must occur in exactly one face" }
    }

    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    fun toTriMesh(): TriMesh = TriMesh(FloatArray(positions.size) { positions[it].toFloat() }, indices.copyOf())
}

/** Rebuild a native solid without the Float32 narrowing of [TriMesh.toSolid]. */
fun SolidPlanarMesh.toSolid(check: Boolean = true): Solid {
    if (triangleCount == 0) return Solid.empty()
    Arena.ofConfined().use { arena ->
        val vertexProperties = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, *positions)
        val triangleMemory = arena.allocateFrom(
            ValueLayout.JAVA_LONG,
            *LongArray(indices.size) { indices[it].toLong() },
        )
        val mesh = ManifoldNative.ptr(
            ManifoldNative.meshGL64,
            ManifoldNative.ptr(ManifoldNative.allocMeshGL64),
            vertexProperties,
            vertexCount.toLong(),
            3L,
            triangleMemory,
            triangleCount.toLong(),
        )
        try {
            val solid = Solid.wrap(ManifoldNative.ofMeshGL64, mesh)
            return try { if (check) solid.checked("this SolidPlanarMesh") else solid }
            catch (failure: Throwable) { solid.close(); throw failure }
        } finally {
            ManifoldNative.unit(ManifoldNative.deleteMeshGL64, mesh)
        }
    }
}
