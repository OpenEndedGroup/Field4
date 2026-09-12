import afield.four.fabricated.PathTraceEnvironment
import afield.four.fabricated.PathTraceMaterial
import afield.four.fabricated.PathTraceScene
import afield.four.fabricated.PathTracingRenderer
import afield.four.fabricated.importTriMesh
import afield.four.modules.fromContext
import afield.four.util.Camera
import afield.stdlib.linalg.Vector3f
import afield.stdlib.polytope.ElementId
import afield.stdlib.polytope.NormalPolicy
import afield.stdlib.polytope.canonicalConway
import afield.stdlib.polytope.triangulate
import java.io.File

val renderer: Renderer = fromContext()
prop<PathTracingRenderer>(tracer)

// ---- the topology ---------------------------------------------------------------
// Bevel the dodecahedron, then canonicalize so the faces come out edge-tangent and the
// whole thing is worth looking at. Both steps keep the lineage.
val chain = canonicalConway("bD")
val poly = chain.polyhedron
val geometry = poly.triangulate(normals = NormalPolicy.Flat)

// Bevel sends every output face back to exactly one element of the seed, so `single()` is
// safe here and would throw the moment that stopped being true. The rank is the whole
// classification: 2 means the face sits over one of the dodecahedron's pentagons, 1 over
// one of its edges, 0 over one of its vertices.
fun seedRank(face: ElementId): Int = chain.sourceElements(face).single().rank

val faces = poly.topology.elements(2)
val census = faces.groupingBy { seedRank(it.id) }.eachCount().toSortedMap()
println("bD: ${faces.size} faces, ${geometry.triangleCount} triangles")
census.forEach { (rank, count) ->
    val sides = faces.filter { seedRank(it.id) == rank }
        .map { poly.topology.faceCycle(it.id).size }.distinct().sorted()
    println("  rank $rank -> $count faces, $sides sides each")
}
println("  the dodecahedron has 12 faces, 30 edges and 20 vertices")

// ---- the materials -------------------------------------------------------------
// Thin glass rather than solid: a face of a closed polyhedron is a patch, and solid glass
// measures its attenuation between surface crossings, which a patch does not give it.
val scene = PathTraceScene()
// Roughness 0.32 rather than a mirror finish: a decagon is flat and large, and a flat
// mirror this size returns one direction of a hazy sky, which reads as pale rather than
// as metal. Spreading the lobe averages the map and the gold tint comes back.
val gold = scene.material(PathTraceMaterial.metal(1.0f, 0.73f, 0.31f, 0.32f))
val glass = scene.material(PathTraceMaterial.thinGlass(1.52f, Vector3f(0.9f, 0.97f, 0.94f)))
val plastic = scene.material(PathTraceMaterial.plastic(0.05f, 0.09f, 0.34f, 0.3f))
val floor = scene.material(PathTraceMaterial.plastic(0.55f, 0.56f, 0.58f, 0.55f))

fun materialFor(face: ElementId): Int = when (seedRank(face)) {
    2 -> gold      // the twelve decagons, one per pentagon of the dodecahedron
    1 -> glass     // the thirty squares, one per edge
    else -> plastic // the twenty hexagons, one per vertex
}

// `sourceFace(t)` is the polygon triangle t was cut from, so the per-triangle selector is
// the lineage lookup with nothing in between.
val added = scene.importTriMesh(
    geometry.mesh,
    { t -> materialFor(geometry.sourceFace(t)) },
)
println("scene: ${added.triangleCount} triangles appended, ${added.degenerateCount} dropped")
println("       bounds ${added.boundsMin} .. ${added.boundsMax}")

val low = added.boundsMin.y - 0.02f
val reach = 6f
scene.quad(
    Vector3f(-reach, low, -reach), Vector3f(reach, low, -reach),
    Vector3f(reach, low, reach), Vector3f(-reach, low, reach),
    floor,
)

// ---- the render ----------------------------------------------------------------
val environment = PathTraceEnvironment.fromFile(
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath,
)
val camera = Camera(renderer).also {
    it.at.set(2.9f, 0.85f, 4.6f)
    it.target.set(0f, 0.02f, 0f)
    it.p.fov = 0.44
}
// 1000 x 614 is the window's own aspect, so the blit fills it rather than letterboxing.
tracer = PathTracingRenderer(renderer, scene, environment, 1000, 614, camera).install()
tracer.samplesPerFrame = 2
tracer.maxBounces = 8
tracer.exposure = 1.35f
tracer.environmentRotation = 2.1f
tracer.indirectClamp = 10f // Optional biased suppression of indirect fireflies.
tracer.blitToScreen()
println("${tracer.backend}: ${tracer.triangleCount} triangles")
frames {
    while (tracer.samples < 900) { frame() }
    println("lineage study converged: ${tracer.samples} samples")
}
