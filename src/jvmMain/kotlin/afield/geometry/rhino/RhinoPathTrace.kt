@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.rhino

import afield.graphics.scene.fabricated.PathTraceMaterial
import afield.graphics.scene.fabricated.PathTraceScene
import afield.graphics.scene.fabricated.importTriMesh
import afield.math.linalg.Matrix4f
import afield.math.linalg.Vector3f

/**
 * What [PathTraceScene.rhino] put into the scene.
 *
 * @property objects how many Rhino objects became geometry: every [RhinoMesh] that passed the
 *   filters. Curves, text and points have no surface to trace and are not counted.
 * @property triangleCount triangles appended to the scene.
 * @property degenerateCount triangles skipped for zero area, as [importTriMesh] counts them.
 * @property materials the scene material each object got, keyed by object, in the model's order.
 * @property boundsMin a corner of the appended geometry's bounds, in scene coordinates (after the
 *   Z-up turn, when there was one).
 * @property boundsMax the opposite corner.
 */
data class RhinoPathTraceImport(
    val objects: Int,
    val triangleCount: Int,
    val degenerateCount: Int,
    val materials: Map<RhinoObject, Int>,
    val boundsMin: Vector3f,
    val boundsMax: Vector3f,
) {
    /** Extent of the appended geometry, which a camera distance is usually derived from. */
    val size: Vector3f get() = Vector3f(boundsMax).sub(boundsMin)

    /** The middle of the appended geometry, which a camera usually looks at. */
    val center: Vector3f get() = Vector3f(boundsMin).add(boundsMax).mul(0.5f)
}

/**
 * Add a [RhinoModel]'s surfaces to this scene, one object at a time, so each keeps its own material.
 *
 * ```kotlin
 * val model = Rhino3dm.load("~/Downloads/model.3dm")
 * val scene = PathTraceScene()
 * val added = scene.rhino(model)
 * println("${added.objects} objects, ${added.triangleCount} triangles")
 * ```
 *
 * The walk is the one [afield.graphics.three.ThreeSceneBuilder.rhino] makes for the three viewer,
 * cut down to what a path tracer can show: every [RhinoMesh] (a mesh, a Brep or extrusion, a SubD)
 * whose object passes [include] is appended with [importTriMesh]. Curves, text and points have no
 * area and are left out. Objects are kept apart rather than merged as [RhinoModel.mesh] merges
 * them, because the merged mesh has forgotten which triangle came from which object, and so which
 * colour it should be.
 *
 * By default each object is a plastic in its display colour, the one Rhino shades it with (from its
 * layer, its own colour, its material or its block). Objects of the same colour share one scene
 * material. Pass [material] to choose per object: `{ o -> if (o.layer?.name == "Glass")
 * PathTraceMaterial.thinGlass(1.5f) else PathTraceMaterial.plastic(o.color.x, o.color.y, o.color.z) }`.
 *
 * @param hiddenLayers include objects Rhino has hidden, on a switched-off layer or hidden
 *   themselves. `false`, the default, traces what Rhino shows.
 * @param zUp turn the model from Rhino's Z up to the tracer's Y up. `false` keeps model coordinates.
 * @param scale multiply model units by this, after the turn: a model in millimetres is a long way
 *   from a camera placed in metres.
 * @param include which objects to trace at all — `{ it.layer?.path != "Construction" }`, say.
 * @param material the material for an object. Objects for which it returns equal values share one
 *   scene material.
 */
fun PathTraceScene.rhino(
    model: RhinoModel,
    hiddenLayers: Boolean = false,
    zUp: Boolean = true,
    scale: Float = 1f,
    include: (RhinoObject) -> Boolean = { true },
    material: (RhinoObject) -> PathTraceMaterial = { o -> PathTraceMaterial.plastic(o.color.x, o.color.y, o.color.z, 0.35f) },
): RhinoPathTraceImport {
    val transform = Matrix4f().scale(scale)
    if (zUp) transform.rotateX(-(Math.PI / 2).toFloat())
    val slots = HashMap<PathTraceMaterial, Int>()
    val chosen = LinkedHashMap<RhinoObject, Int>()
    val lo = Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    val hi = Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
    var triangles = 0
    var dropped = 0
    for (o in model.meshes) {
        if (!hiddenLayers && !o.isVisible) continue
        if (!include(o)) continue
        val wanted = material(o)
        val slot = slots.getOrPut(wanted) { this.material(wanted) }
        val added = importTriMesh(o.mesh, slot, transform, warning = { println("Rhino ${o.label}: $it") })
        chosen[o] = slot
        triangles += added.triangleCount
        dropped += added.degenerateCount
        if (added.triangleCount > 0) {
            lo.min(added.boundsMin)
            hi.max(added.boundsMax)
        }
    }
    val any = triangles > 0
    return RhinoPathTraceImport(
        objects = chosen.size,
        triangleCount = triangles,
        degenerateCount = dropped,
        materials = chosen,
        boundsMin = if (any) lo else Vector3f(),
        boundsMax = if (any) hi else Vector3f(),
    )
}
