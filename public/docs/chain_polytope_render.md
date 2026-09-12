# A polytope, cut open, and traced

Three parts of Field, one object. `afield.stdlib.polytope` keeps a ranked incidence topology and the
Conway operator algebra over it. `afield.manifold` does exact Booleans on closed solids, over
Manifold's C ABI. `afield.four.fabricated` runs a GPU path tracer. None of the three knows the other
two exist, and this page sends one shape through all of them, checking each hop against the next.

The shape is `bD`: bevel of the dodecahedron, better known as the truncated icosidodecahedron. It has
62 faces, which is small enough to count and rich enough that a mistake anywhere shows.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_polytope_render/lineage.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="lineage study converged" embed=true hidden=true timeout="240" name="chain-polytope-lineage" alt="A path-traced truncated icosidodecahedron over a pale floor with the Venice lagoon behind it: twelve brushed-gold decagons, twenty deep blue hexagons, thirty transparent squares" macro-file="../../dev_docs/agents/transcripts/polytope_path_trace_lineage.yaml" capture-box="polytope_lineage_trace"}
// Built and launched from dev_docs/agents/transcripts/polytope_path_trace_lineage.yaml.
```

Gold, blue and glass are not decoration. Each face of `bD` descends from exactly one element of the
dodecahedron it was built from, and the **rank** of that one element picks the material: a face over a
pentagon is gold, a face over an edge is glass, a face over a vertex is blue. So the twelve decagons,
the thirty squares and the twenty hexagons in that picture are the dodecahedron's twelve faces,
thirty edges and twenty vertices, and the picture is a drawing of the seed's own incidence counts.

Environment: Venice Sunset by Greg Zaal / Poly Haven (CC0), shared with the
[Fabricate demo assets](assets/fabricate_demo/ATTRIBUTION.md).

## What crosses each hop, and what does not

| hop | the call | what crosses | what stops here |
| --- | --- | --- | --- |
| topology to triangles | [`triangulate`](reference/polytopes.md) | positions, indices, and a map from each triangle back to its polygon | nothing; this is the only hop that carries provenance forwards |
| triangles to a solid | [`toSolid`](reference/graphics.md) | positions and indices | the triangle-to-polygon map. The binding hands Manifold three floats per vertex and nothing else |
| a solid back to triangles | [`toTriMesh`](reference/graphics.md) | positions, indices, optionally split normals | any correspondence with the triangles that went in. A Boolean retriangulates |
| triangles to a scene | [`importTriMesh`](reference/renderers.md#pathtracetrimesh-kt-functions) | positions, normals, a material per triangle | zero-area triangles, which are counted and skipped |

The first row is why the picture above is possible and the second row is why it has to happen before
any Boolean. `triangulate` returns a `TriangulatedGeometry`, and `sourceFace(t)` on it names the
polygon triangle `t` was cut from, so the material selector is a lineage lookup with nothing in
between:

```diffstatic {data-lang=kotlin}
val chain = canonicalConway("bD")
val geometry = chain.polyhedron.triangulate(normals = NormalPolicy.Flat)

val scene = PathTraceScene()
val gold = scene.material(PathTraceMaterial.metal(1.0f, 0.73f, 0.31f, 0.32f))
val glass = scene.material(PathTraceMaterial.thinGlass(1.52f, Vector3f(0.9f, 0.97f, 0.94f)))
val plastic = scene.material(PathTraceMaterial.plastic(0.05f, 0.09f, 0.34f, 0.3f))

scene.importTriMesh(geometry.mesh, { t ->
    // Bevel sends every output face back to exactly one element of the seed, so `single()` is
    // safe here and throws the moment that stops being true.
    when (chain.sourceElements(geometry.sourceFace(t)).single().rank) {
        2 -> gold
        1 -> glass
        else -> plastic
    }
})
```

!!! note "Thin glass rather than solid glass, for the thirty squares"

    Solid glass measures its attenuation between surface crossings, so it assumes a closed,
    outward-wound, non-overlapping mesh. A single face of a closed polyhedron is a patch, not a
    volume, and `thinGlass` is the material for a patch.

Roughness 0.32 on the gold is a choice about the environment rather than about the metal. A decagon
is flat and large, and a flat mirror that size returns one direction of a hazy sky, which reads as
pale. Spreading the specular lobe averages the map and the gold tint comes back.

## The numbers each hop owes the next

Run this and nothing below is taken on trust. It needs no renderer: everything up to the trace is
CPU work.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_polytope_render/readout.png" wait="6" capture="box" open-outputs="true" w="1060" h="240" padding="18" name="chain-polytope-readout" embed=true}
:::static
import afield.manifold.*
import afield.stdlib.*
import afield.stdlib.polytope.*

val thickness = 0.09   // wall thickness, in the polytope's own units; the shape spans about 2

val chain = canonicalConway("bD")
val geometry = chain.polyhedron.triangulate(normals = NormalPolicy.Flat)

// The triangulator cuts each polygon on its own, so a corner shared by three faces comes out as
// three vertices. Weld before handing it over: being a manifold is a property of the indices, and
// unwelded vertices describe the same surface while sharing an edge nowhere.
val plain = geometry.mesh.weld(1e-6)

:::fold the census, the two independent volumes, and the wall
println("bD: ${chain.polyhedron.topology.elements(2).size} faces, " +
    "${geometry.triangleCount} triangles, " +
    "${geometry.mesh.vertexCount} vertices unwelded, ${plain.vertexCount} welded")
val census = chain.polyhedron.topology.elements(2)
    .groupingBy { chain.sourceElements(it.id).single().rank }.eachCount().toSortedMap()
census.forEach { (rank, count) -> println("  rank $rank of the seed -> $count faces") }
println("  the dodecahedron has 12 faces, 30 edges and 20 vertices")

val report = Solid.scope {
    val solid = plain.toSolid()
    println()
    println("Manifold: status ${solid.status}, genus ${solid.genus}, " +
        "volume ${"%.4f".format(solid.volume)}, area ${"%.4f".format(solid.surfaceArea)}")
    println("Field:    watertight ${plain.isWatertight()}, genus ${plain.genus()}, " +
        "volume ${"%.4f".format(plain.signedVolume())}, " +
        "apart by ${"%.2e".format(kotlin.math.abs(plain.signedVolume() - solid.volume))}")

    // Erode by a ball, then subtract. What is left is the set of points within `thickness` of the
    // surface, so the wall is that thick everywhere. Scaling the solid down instead gives a wall
    // thicker at the vertices than at the face centres, which looks similar and is a different shape.
    val ball = Solid.sphere(thickness, segments = 24)
    val wall = solid - solid.minkowskiDifference(ball)
    println()
    println("shell:    volume ${"%.4f".format(wall.volume)}, genus ${wall.genus}, " +
        "${wall.triangleCount} triangles")
    println("          area * thickness would be ${"%.4f".format(solid.surfaceArea * thickness)}, " +
        "so the ratio is ${"%.3f".format(wall.volume / (solid.surfaceArea * thickness))}")
    wall.trimByPlane(0.0, -1.0, 0.0, 0.0).checked("cut shell")
}
println()
println("cut open: genus ${report.genus}, volume ${"%.4f".format(report.volume)}")
report.close()
```

Four of those lines are cross-checks rather than reports.

* **120 welded vertices.** The truncated icosidodecahedron has 120 vertices, so the weld recovering
  exactly that number says the triangulator's duplicated corners were duplicates and not a shape.
* **Two volumes that agree.** Manifold integrates the solid in double precision; Field sums signed
  tetrahedra over float triangles in `signedVolume`. They share no code, so their agreeing to the
  last few digits is evidence about the bridge rather than about either of them.
* **Genus 0 twice.** Field's `genus()` reads indices and Manifold's `genus` reads its own
  half-edge structure.
* **Area times thickness.** A wall of constant thickness over a convex body has volume `A·t` less a
  curvature term, so the ratio has to land a little under one. Reading exactly one would mean the
  erosion had not happened; reading far under it would mean the thickness was not constant.

Genus goes to −1 for the shell, which is not an error. Manifold reports genus from the Euler
characteristic of the whole boundary, a closed shell has two boundary components, and two spheres
give χ = 4, so *g* = −1. `decompose()` is how you get at components separately.

## Through the Booleans, and back out to the tracer

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_polytope_render/shell.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="shell study converged" embed=true hidden=true timeout="300" name="chain-polytope-shell" alt="A path-traced terracotta bowl: the lower half of a constant-thickness shell of the truncated icosidodecahedron, its rim thickness visible all the way round and its interior facets rounded" macro-file="../../dev_docs/agents/transcripts/polytope_manifold_path_trace.yaml" capture-box="polytope_manifold_trace"}
// Built and launched from dev_docs/agents/transcripts/polytope_manifold_path_trace.yaml.
```

The rim is the interesting part: its thickness is the same the whole way round, which is what
distinguishes an erosion from a scale. The interior facets are rounded where the exterior ones are
sharp, because eroding by a ball cannot reproduce a concave corner tighter than the ball.

Two details of the last hop are worth copying:

```diffstatic {data-lang=kotlin}
// A 20-degree crease rather than the default 60. This surface is all facets, and at 60 the shallow
// ones average together and the polytope stops reading as one.
val shaded = cut.toTriMesh(normals = true, minSharpAngle = 20.0)

val added = scene.importTriMesh(shaded, body)
println("${added.triangleCount} triangles appended, ${added.degenerateCount} dropped")
```

The dropped count is not zero. Manifold's output of a Boolean followed by a plane trim contains a
few triangles of zero area, and `PathTraceScene.triangle` rejects those outright, which would abort
a scene of half a million for the sake of three. `importTriMesh` counts and skips them instead, and
reports what it skipped. That is most of the reason the bridge exists rather than being a call to
`PathTraceScene.mesh` with a list comprehension in front of it.

## Two meshes, for two jobs

`normals = true` splits a vertex per face at every hard edge so each face can carry its own normal.
That is what makes a render read as facets, and it is also why that mesh shares no edges and reports
the wrong genus. The measuring above all runs on the plain mesh, and the render runs on the split
one. Asking either to do the other's job produces a true statement about a different mesh, which is
worse than an error.

## Where each piece is documented

* [Polytopes & the Conway Algebra](polytope_examples.md) for the topology, the operator algebra and
  the lineage queries, and [Polytopes](reference/polytopes.md) for the declarations.
* [Solids & Booleans (Manifold)](manifold.md) for the Boolean layer, the status model and what it
  refuses.
* [Progressive Path Tracing](path_tracing.md) for the tracer: materials, camera, environment maps,
  convergence and the two traversal backends.
* [Particles, welded, and traced](chain_physics_render.md) is the same last two hops with a cloth
  simulation at the front instead of a polytope.
