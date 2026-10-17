# Particles, welded, and traced

A cloth simulation holds particles and distances between them. A path tracer wants closed geometry.
Field's Boolean kernel connects the two: it can weld hundreds of overlapping capsules into one solid
and tell you exactly how many holes it has. This page drapes a sheet over a sphere, turns each of its
distance constraints into a rod, welds the rods, checks the number of holes, and traces the result.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_physics_render/drape.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="cloth study converged" embed=true hidden=true timeout="420" name="chain-physics-drape" alt="A path-traced lattice of pale gold rods draped over an unseen sphere and pooling on a floor, the Venice lagoon behind it" macro-file="../../dev_docs/agents/transcripts/physics_manifold_path_trace.yaml" capture-box="physics_manifold_trace"}
// Built and launched from dev_docs/agents/transcripts/physics_manifold_path_trace.yaml.
```

The sphere the sheet is hanging on is not in the scene. It is a `SphereCollider`, which is a
post-solve position projection rather than a piece of geometry, so it shapes the drape and then has
nothing to contribute to a render.

The document that makes this figure is a starting point:
[physics_manifold_path_trace](agent_transcripts.md#physics_manifold_path_trace).

## From the cloth to the tracer

`afield.simulation.physics` runs the simulation, `afield.geometry.solid.manifold` welds, and `afield.graphics.scene.fabricated`
traces. Positions come out of the cloth as `cloth.position(col, row)`, and the welded solid goes into
the scene as a `TriMesh`. Nothing else passes between them.

```diffstatic {data-lang=kotlin}
val sim = SimdVerlet(SimdVerlet.State3.empty())
val cloth = sim.addCloth(
    width = side, height = side, spacing = spacing,
    stiffness = 60f, curvatureStrength = 20f,
    position = { c, r -> vec((c - (side - 1) * 0.5f) * spacing, 0f, (r - (side - 1) * 0.5f) * spacing) },
    pinned = { _, _ -> false },
)
val world = sim.addColliders(
    SphereCollider(0f, -0.78f, 0f, radius = 0.52f).also { it.friction = 0.8f; it.margin = 0.01f },
    PlaneCollider(0f, 1f, 0f, offset = -1.28f).also { it.friction = 0.9f },
)
repeat(settleFrames) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 16) }
```

## A capsule is the hull of two balls

A rod along an arbitrary segment usually means building an orthonormal basis from its direction.
There is no need. The convex hull of a ball at each end *is* the capsule, and `Solid.hullOf` computes
it, so the orientation arithmetic never gets written:

```diffstatic {data-lang=kotlin}
val rods = struts.map { (a, b) ->
    Solid.hullOf(
        Solid.sphere(rod, segments = 10).translated(a.first, a.second, a.third),
        Solid.sphere(rod, segments = 10).translated(b.first, b.second, b.third),
    )
}
val welded = Solid.union(rods).checked("cloth net")
```

`Solid.union(list)` takes the whole list. Folding `-` or `+` over it would nest *n* − 1 Booleans and
give Manifold no chance to plan; the batch call lets it build the tree itself. The box prints how
long the union took.

`segments = 10` on each ball is a budget decision. A hull of two ten-segment spheres is a few dozen
triangles, and several hundred of those unioned is a mesh in the tens of thousands, which the tracer
builds a BVH over without noticing. Twenty segments would be four times that for a rod whose radius
is a thirtieth of the frame.

## Genus against the sheet's quad count

For an *n* × *n* sheet the distance constraints run along the grid, so there are 2*n*(*n* − 1) of
them, and the grid graph has (*n* − 1)² independent cycles. Every cycle is a hole through the welded
solid. So the genus of the union has to equal the sheet's quad count, and the box compares the two
for you:

```diffstatic {data-lang=kotlin}
println("genus $netGenus against $quads quads in the sheet; " +
    if (netGenus == quads) "they agree, so no two rods have been welded by contact"
    else "they differ by ${quads - netGenus}, so that many cycles have been closed by folds touching")
```

The two can legitimately differ. A drape tight enough for one fold to touch
another welds two rods that were not neighbours, closes a cycle, and drops the genus. Raise
`settleFrames` or shrink the collider until the sheet crumples and the message changes.

Field's own `isWatertight()` and `genus()` read the returned mesh's indices, and Manifold's `genus`
reads its own half-edge structure. The two share no code, so if they disagree, suspect the conversion
between Field's mesh and Manifold's.

## Degenerate triangles at the bridge

The hull of two balls has a fan of triangles where the two hemispheres meet, and a union of several
hundred of them leaves some of those with no area at all. `PathTraceScene.triangle` rejects a
zero-area triangle outright, which would lose the whole scene. `importTriMesh` skips them and reports
how many:

```diffstatic {data-lang=kotlin}
val added = scene.importTriMesh(shaded, metal)
println("${added.triangleCount} triangles appended, ${added.degenerateCount} dropped")
```

Watch that second number when you change an earlier stage. If it goes up, that stage has started
producing slivers.

## Where each piece is documented

* [Particles, Cloth & Constraints](physics.md) for the solver: the force-based constraint pass, why
  `stiffness × iterations` is the number that matters, colliders as position projections, tearing and
  wind.
* [Solids & Booleans (Manifold)](manifold.md) for the Boolean layer, `hullOf`, batching and the
  status model.
* [Progressive Path Tracing](path_tracing.md) for the tracer.
* [A polytope, cut open, and traced](chain_polytope_render.md) is the same last two hops with a
  Conway operator chain at the front instead of a simulation.
