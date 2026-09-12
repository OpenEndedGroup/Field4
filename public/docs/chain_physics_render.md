# Particles, welded, and traced

A cloth simulation holds particles and distances between them. A path tracer wants closed geometry.
Between them sits a Boolean kernel that can weld several hundred overlapping capsules into one solid
and then say, exactly, how many holes that solid has. This page drapes a sheet over a sphere, turns
every one of its distance constraints into a rod, welds the rods, checks the count of holes against
the cloth's own arithmetic, and traces the result.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_physics_render/drape.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="cloth study converged" embed=true hidden=true timeout="420" name="chain-physics-drape" alt="A path-traced lattice of pale gold rods draped over an unseen sphere and pooling on a floor, the Venice lagoon behind it" macro-file="../../dev_docs/agents/transcripts/physics_manifold_path_trace.yaml" capture-box="physics_manifold_trace"}
// Built and launched from dev_docs/agents/transcripts/physics_manifold_path_trace.yaml.
```

The sphere the sheet is hanging on is not in the scene. It is a `SphereCollider`, which is a
post-solve position projection rather than a piece of geometry, so it shapes the drape and then has
nothing to contribute to a render.

## Three stages, three owners

`afield.four.physics` runs the simulation. `afield.manifold` welds. `afield.four.fabricated` traces.
The interfaces between them are narrow enough to state in full: positions come out of the cloth as
`cloth.position(col, row)`, a solid goes into the scene as a `TriMesh`, and that is the whole
contract.

```diffstatic {data-lang=kotlin}
val sim = SimdVerlet(SimdVerlet.State3.empty())
val cloth = sim.addCloth(
    width = side, height = side, spacing = spacing,
    stiffness = 60f, curvatureStrength = 20f,
    position = { c, r -> vec((c - (side - 1) * 0.5f) * spacing, 0f, (r - (side - 1) * 0.5f) * spacing) },
    pinned = { _, _ -> false },
)
val world = sim.addColliders(
    SphereCollider(0f, 0.78f, 0f, radius = 0.52f).also { it.friction = 0.8f; it.margin = 0.01f },
    PlaneCollider(0f, -1f, 0f, offset = -1.28f).also { it.friction = 0.9f },
)
repeat(settleFrames) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 9.8f, iterations = 16) }
```

!!! warning "The two subsystems disagree about which way is up"

    Gravity is **+y** in `SimdVerlet`, which is why the sphere sits at `y = 0.78` *below* a sheet laid
    out at `y = 0`, and why the floor is a plane with normal `-y`. The path tracer has +y up. Negate
    `y` once, where the positions are read out, rather than mirroring the camera and the floor to
    compensate: a mirrored camera reverses the winding of everything it sees.

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
give Manifold no chance to plan; the batch call lets it build the tree itself. The measured cost of
the batch is printed by the box, and it is the smaller half of this page's runtime.

`segments = 10` on each ball is a budget decision. A hull of two ten-segment spheres is a few dozen
triangles, and several hundred of those unioned is a mesh in the tens of thousands, which the tracer
builds a BVH over without noticing. Twenty segments would be four times that for a rod whose radius
is a thirtieth of the frame.

## The count of holes is the check

For an *n* × *n* sheet the distance constraints run along the grid, so there are 2*n*(*n* − 1) of
them, and the grid graph has (*n* − 1)² independent cycles. Every cycle is a hole through the welded
solid. So the genus of the union has to equal the sheet's quad count, and the box compares the two
itself rather than printing both and leaving you to:

```diffstatic {data-lang=kotlin}
println("genus $netGenus against $quads quads in the sheet; " +
    if (netGenus == quads) "they agree, so no two rods have been welded by contact"
    else "they differ by ${quads - netGenus}, so that many cycles have been closed by folds touching")
```

A disagreement is information rather than a failure. A drape tight enough for one fold to touch
another welds two rods that were not neighbours, closes a cycle, and drops the genus. Raise
`settleFrames` or shrink the collider until the sheet crumples and the message changes.

Field's own `isWatertight()` and `genus()` read the returned mesh's indices, and Manifold's `genus`
reads its own half-edge structure. Both are in the readout because two implementations that share no
code, agreeing about a surface, is evidence about the bridge between them.

## What the tracer refuses, and what the bridge does about it

The hull of two balls has a fan of triangles where the two hemispheres meet, and a union of several
hundred of them leaves a noticeable number of those with no area at all.
`PathTraceScene.triangle` rejects a zero-area triangle outright, which for a mesh this size would
mean losing the whole scene over a fraction of a percent of it. `importTriMesh` counts them, skips
them, and reports the count:

```diffstatic {data-lang=kotlin}
val added = scene.importTriMesh(shaded, metal)
println("${added.triangleCount} triangles appended, ${added.degenerateCount} dropped")
```

Watch that second number when a chain changes. It going up is the first sign that an upstream stage
has started producing slivers.

## Where each piece is documented

* [Particles, Cloth & Constraints](physics.md) for the solver: the force-based constraint pass, why
  `stiffness × iterations` is the number that matters, colliders as position projections, tearing and
  wind.
* [Solids & Booleans (Manifold)](manifold.md) for the Boolean layer, `hullOf`, batching and the
  status model.
* [Progressive Path Tracing](path_tracing.md) for the tracer.
* [A polytope, cut open, and traced](chain_polytope_render.md) is the same last two hops with a
  Conway operator chain at the front instead of a simulation.
