package afield.four.fabricated

import afield.stdlib.TriMesh
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f

/**
 * [TriMesh] into [PathTraceScene], so that anything in Field which produces a mesh can be traced.
 *
 * [PathTraceScene.mesh] already takes indexed positions, so the arithmetic here is small. The
 * difference is what it does with the triangles a real producer emits and
 * [PathTraceScene.triangle] rejects: zero-area slivers where two vertices coincide, and vertex
 * normals that came out of a degenerate face as zero-length. A Conway operator chain, a
 * marching-cubes surface, a Manifold Boolean and a cloth simulation all produce some, and one of
 * them aborts the whole append. Here they are counted and skipped, and the count comes back in
 * [PathTraceTriMeshImport.degenerateCount].
 *
 * The producers whose output lands here:
 *
 * | source | route |
 * | --- | --- |
 * | a polytope, including Conway chains | [afield.stdlib.polytope.triangulate] `.mesh` |
 * | a Manifold solid, after Booleans | [afield.manifold.Solid.toTriMesh] |
 * | a scalar field | [afield.stdlib.marchingCubes], or [afield.manifold.Solid.levelSet] |
 * | a mesh off disk | [afield.stdlib.readObj] and the rest of `mesh_io.kt` |
 * | a heightfield, a tube, a revolve | `mesh_generate.kt` |
 *
 * # Normals decide whether it reads as faceted or smooth
 *
 * [TriMesh.normals] is optional, and when it is absent every triangle gets its own face normal, so
 * the surface reads faceted. That is the right look for a polyhedron and the wrong one for a
 * sphere. Both Manifold and Field's polytope triangulator can supply either: `toTriMesh(normals =
 * true)` and `triangulate(normals = NormalPolicy.Crease(30.0))` split a vertex per face at a hard
 * edge and average across a soft one.
 *
 * Splitting vertices for shading also breaks index-sharing, so the mesh that shades well is not the
 * one to measure topology on. Take two: the split mesh for the scene, the plain one for
 * [afield.stdlib.genus] and [afield.stdlib.isWatertight].
 *
 * # A material per triangle
 *
 * The overload taking a `(triangle: Int) -> Int` selector is handed each triangle's index in [mesh].
 * That numbering does not close up over a skipped triangle, so it stays lined up with the
 * per-triangle side tables a producer hands
 * out: [afield.stdlib.polytope.TriangulatedGeometry.sourceFace] names the polygon a triangle came
 * from, and its `triangleAttributes` carry whatever the triangulator was asked to record. So a
 * Conway chain's lineage can pick the material, and a face's provenance becomes something you can
 * see in the render.
 */

/** What one [importTriMesh] call did: the triangles that reached the scene, and the ones that did not. */
data class PathTraceTriMeshImport(
    /** Triangles appended to the scene. */
    val triangleCount: Int,
    /** Triangles skipped for zero area or a non-finite coordinate. */
    val degenerateCount: Int,
    /** Corner of the appended geometry's world-space bounding box. Both corners are zero when nothing was appended. */
    val boundsMin: Vector3f,
    /** The opposite corner. */
    val boundsMax: Vector3f,
    /** One line per distinct reason something was skipped or substituted. */
    val warnings: List<String>,
) {
    /** Extent of the appended geometry, which is what a camera distance is usually derived from. */
    val size: Vector3f get() = Vector3f(boundsMax).sub(boundsMin)
}

/**
 * Append [mesh] to this scene under [material], baking [transform] into world-space coordinates.
 *
 * ```kotlin
 * val scene = PathTraceScene()
 * val gold = scene.material(PathTraceMaterial.metal(0.95f, 0.72f, 0.3f, 0.12f))
 * val added = scene.importTriMesh(solid.toTriMesh(normals = true), gold)
 * println("${added.triangleCount} triangles, ${added.degenerateCount} dropped")
 * ```
 *
 * A [transform] with a negative determinant mirrors the geometry, which reverses the winding of
 * every triangle. The winding is reversed back here, so a mirrored mesh keeps its outward normals
 * and solid glass built from it still measures its attenuation in the right direction.
 *
 * @param material an index returned by [PathTraceScene.material].
 * @param transform applied to positions, and through its inverse transpose to normals.
 * @param vertexColors multiply [TriMesh.colors] into the material's base colour per corner. Ignored
 *   when the mesh carries no colours.
 * @param warning called once per distinct reason, in the manner of [importGltf].
 */
fun PathTraceScene.importTriMesh(
    mesh: TriMesh,
    material: Int,
    transform: Matrix4f = Matrix4f(),
    vertexColors: Boolean = false,
    warning: (String) -> Unit = { println("TriMesh: $it") },
): PathTraceTriMeshImport {
    require(material in materials.indices) { "Unknown material $material; call PathTraceScene.material(...) first" }
    return importTriMesh(mesh, { material }, transform, vertexColors, warning)
}

/**
 * [importTriMesh] with the material chosen per triangle.
 *
 * [material] is called with each triangle's index in [mesh], counting from zero. A skipped triangle
 * is not asked about and does not shift the numbering, so an index into a producer's own
 * per-triangle table stays aligned.
 *
 * ```kotlin
 * val geometry = conway("kbD").polyhedron.triangulate()
 * val perSeed = seeds.associateWith { scene.material(PathTraceMaterial.plastic(...)) }
 * scene.importTriMesh(geometry.mesh, { t -> perSeed[seedOf(geometry.sourceFace(t))]!! })
 * ```
 */
fun PathTraceScene.importTriMesh(
    mesh: TriMesh,
    material: (triangle: Int) -> Int,
    transform: Matrix4f = Matrix4f(),
    vertexColors: Boolean = false,
    warning: (String) -> Unit = { println("TriMesh: $it") },
): PathTraceTriMeshImport {
    val determinant = transform.determinant()
    require(determinant.isFinite() && kotlin.math.abs(determinant) > 1e-20f) {
        "Singular transform passed to importTriMesh (determinant $determinant)"
    }
    val messages = linkedSetOf<String>()

    val positions = List(mesh.vertexCount) { v ->
        transform.transformPosition(Vector3f(mesh.x(v), mesh.y(v), mesh.z(v)))
    }
    val normalMatrix = Matrix4f(transform).invert().transpose()
    val normals = mesh.normals?.let { source ->
        List(mesh.vertexCount) { v ->
            val n = normalMatrix.transformDirection(Vector3f(source[v * 3], source[v * 3 + 1], source[v * 3 + 2]))
            if (n.lengthSquared() > 1e-16f && n.lengthSquared().isFinite()) n.normalize() else null
        }
    }
    val colors = if (vertexColors) mesh.colors?.let { source ->
        List(mesh.vertexCount) { v -> Vector4f(source[v * 3], source[v * 3 + 1], source[v * 3 + 2], 1f) }
    } else null
    if (vertexColors && colors == null) messages += "vertexColors was asked for on a mesh that carries none."

    val lo = Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    val hi = Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
    var added = 0
    var dropped = 0
    for (t in 0 until mesh.triangleCount) {
        val ids = intArrayOf(mesh.indices[t * 3], mesh.indices[t * 3 + 1], mesh.indices[t * 3 + 2])
        if (determinant < 0f) {
            val swap = ids[1]; ids[1] = ids[2]; ids[2] = swap
        }
        val p = ids.map { positions[it] }
        if (p.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) {
            messages += "Triangles with a non-finite coordinate are omitted."
            dropped++
            continue
        }
        // The same test PathTraceScene.triangle applies, applied first so a sliver skips instead of
        // aborting the whole append.
        if (Vector3f(p[1]).sub(p[0]).cross(Vector3f(p[2]).sub(p[0])).lengthSquared() <= 1e-16f) {
            messages += "Zero-area triangles are omitted."
            dropped++
            continue
        }
        val n = normals?.let { ids.map { id -> it[id] } }
        if (n != null && n.any { it == null }) messages += "Zero-length vertex normals fall back to the face normal."
        val slot = material(t)
        require(slot in materials.indices) {
            "material(triangle = $t) returned $slot, which is not one of this scene's ${materials.size} materials"
        }
        triangle(p[0], p[1], p[2], slot, n?.get(0), n?.get(1), n?.get(2))
        if (colors != null) {
            val last = triangles.lastIndex
            triangles[last] = triangles[last].copy(color = ids.map { colors[it] })
        }
        p.forEach { lo.min(it); hi.max(it) }
        added++
    }
    messages.forEach(warning)
    return PathTraceTriMeshImport(
        triangleCount = added,
        degenerateCount = dropped,
        boundsMin = if (added == 0) Vector3f() else lo,
        boundsMax = if (added == 0) Vector3f() else hi,
        warnings = messages.toList(),
    )
}
