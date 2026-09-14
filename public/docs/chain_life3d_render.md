# Three-dimensional Life, counted and traced

A cellular automaton in three dimensions is a volume of ones and zeros, and there is no obvious way
to ask it the question you actually want to ask: *how many separate things are left?* This page runs
`LIFE3D_BLOB` for eight generations, turns the surviving cells into a Manifold solid, asks that
question with one call, and traces the answer.

Three parts of Field, and the middle one is what makes the question answerable. `alife_ca.kt` owns
the automaton, `afield.manifold` owns closed solids and the operations over them, and the GPU path
tracer draws whatever it is handed.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_life3d_render/clusters.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="life3d study converged" embed=true hidden=true timeout="300" name="chain-life3d-clusters" alt="Several dozen small blue faceted lumps floating in a loose constellation over a pale floor with the Venice lagoon behind, with one larger lump near the centre rendered in gold" macro-file="../../dev_docs/agents/transcripts/life3d_manifold_path_trace.yaml" capture-box="life3d_manifold_trace"}
// Built and launched from dev_docs/agents/transcripts/life3d_manifold_path_trace.yaml.
```

Every blue lump is a separate closed solid and the gold one is the largest. They are separate in the
sense that matters: `decompose()` returned them as independent solids, each with its own volume, and
their volumes sum to the volume of the thing they came from.

Environment: Venice Sunset by Greg Zaal / Poly Haven (CC0), shared with the
[Fabricate demo assets](assets/fabricate_demo/ATTRIBUTION.md).

## The readout

No renderer needed: everything up to the trace is CPU work.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_life3d_render/readout.png" wait="10" capture="box" open-outputs="true" w="1100" h="320" padding="18" timeout="180" name="chain-life3d-readout" embed=true}
:::static
import afield.manifold.Solid
import afield.stdlib.*
import kotlin.random.Random

val n = 36
val generations = 8
val edgeLength = 0.7

// A fixed seed, so every number below is the same number on every machine.
val rng = Random(11)
val seeded = IntVolume(n, n, n, IntArray(n * n * n), wrap = false)
val half = 8
val from = n / 2 - half
for (z in from until from + 2 * half) for (y in from until from + 2 * half) for (x in from until from + 2 * half) {
    seeded[x, y, z] = if (rng.nextDouble() < 0.45) 1 else 0
}

var volume = seeded
val populations = mutableListOf(volume.population())
repeat(generations) { volume = volume.step(LIFE3D_BLOB); populations += volume.population() }

:::fold two marchers on one field, then the census
// smoothRadius = 0 marches the raw occupancy. See the callout below for why the usual radius of
// one is wrong here.
val field = volume.asDensityField(smoothRadius = 0, iso = 0.5)
val bounds = volume.marchBounds()

val marched = volume.toMesh(resolution = 110, smoothRadius = 0, iso = 0.5)

// parallel = false: the parallel path is faster and does not return the same mesh twice.
val whole = Solid.levelSet(bounds, edgeLength = edgeLength, parallel = false) { x, y, z ->
    field(vec(x, y, z)).toDouble()
}
val parts = whole.decompose()
val volumes = parts.map { it.volume }

markdown("census", """
#### `LIFE3D_BLOB`, ${generations} generations from a 45%-filled 16-cubed seed

live cells per generation: `${populations.joinToString(" -> ")}`

| | triangles | watertight | genus | volume |
|---|---|---|---|---|
| Field's `marchingCubes` | ${marched.triangleCount} | ${marched.isWatertight()} | ${marched.genus()} | ${"%.4f".format(marched.signedVolume())} |
| Manifold's `levelSet` | ${whole.triangleCount} | by construction | ${whole.genus} | ${"%.4f".format(whole.volume)} |

`decompose()` returns **${parts.size}** separate solids, and `1 - ${parts.size} = ${1 - parts.size}`
is the genus above. Each one on its own has genus ${parts.take(5).map { it.genus }.distinct()}.

| | volume |
|---|---|
| largest cluster | ${"%.3f".format(volumes.first())} |
| median cluster | ${"%.3f".format(volumes[volumes.size / 2])} |
| smallest cluster | ${"%.3f".format(volumes.last())} |
| the ${parts.size} summed | ${"%.4f".format(volumes.sum())} |
| the solid they came from | ${"%.4f".format(whole.volume)} |
""", width = 1020)

parts.forEach { it.close() }
whole.close()

// How much of the census is a property of the field, and how much of the spacing it was sampled
// at. Both marchers, three spacings each, so the answer is in the figure rather than in prose.
val sweep = buildString {
    appendLine("| spacing | clusters | volume |")
    appendLine("|---|---|---|")
    listOf(1.4, 0.7, 0.35).forEach { edge ->
        Solid.scope {
            val s = Solid.levelSet(bounds, edgeLength = edge, parallel = false) { x, y, z ->
                field(vec(x, y, z)).toDouble()
            }
            appendLine("| `levelSet` edgeLength ${edge} | ${s.decompose().size} | " +
                "${"%.2f".format(s.volume)} |")
        }
    }
    listOf(55, 110, 220).forEach { resolution ->
        val m = marchingCubes(field, bounds, resolution = resolution, positiveOutside = false)
        appendLine("| `marchingCubes` resolution ${resolution} | | ${"%.2f".format(m.signedVolume())} |")
    }
}
markdown("spacing", "#### The same field at three spacings each way\n\n" + sweep, width = 1020)
:::endfold
```

![Tables: live cells per generation falling from 1852 to 164; Field's marching cubes reporting 10688 triangles, watertight, genus 0 against Manifold's level set reporting 5374 triangles and genus minus 47; a cluster census whose 48 volumes sum exactly to the whole solid's; and a sweep showing both the cluster count and the volume changing with the sampling spacing](assets/chain_life3d_render/readout.png)

## The two marchers disagree about genus, and that is the finding

Both numbers are right about different things.

Field's `genus()` reads one Euler characteristic and solves `χ = 2 − 2g`, which is the genus of a
**connected** surface. Handed forty-eight disjoint closed surfaces it returns the genus of the one it
implicitly assumes, and says nothing about the other forty-seven.

Manifold's `genus` is computed over the whole boundary, and for a set of *k* disjoint genus-zero
spheres χ is `2k`, so `g = 1 − k`. A negative genus is therefore not an error and not a hole count:
it is a component count in disguise, and `1 − 48 = −47` is the arithmetic the readout shows.

`decompose()` is the operation that turns it back into the number you wanted, sorted largest first.
That is what makes "how many separate things survived, and how big is each" a one-line question of a
cellular automaton, and it is the thing the Boolean layer adds that a mesh library does not have.

The volumes summing to the whole is the check on all of it. Each component's volume is integrated
independently by Manifold; nothing shares an intermediate; and a decomposition that lost or
double-counted a cluster would not add up.

### The count is a question about the surface, not only about the automaton

Both the volume and the number of clusters depend on how finely the field is sampled, which the
second panel of the readout above measures: the same field, both marchers, three spacings each.

Cells that are diagonal neighbours are bridged by a surface at a coarse spacing and separated at a
fine one, so the cluster count falls as the triangles get bigger. And a trilinear iso-surface over a
0/1 occupancy grid gains volume as the sampling gets finer, so both marchers climb, in the same
direction, and at comparable spacings they land within a few percent of each other. Neither number
is "the" volume of the automaton.

So a census wants its spacing stated beside it, which is what the readout's parameters are for. What
is *not* resolution-dependent is the identity between the genus and the component count, and the
components summing to the whole: those hold at every spacing, because they are arithmetic about
whatever surface was produced rather than measurements of the field.

!!! warning "`parallel = true` is nondeterministic"

    The readout above uses `parallel = false`, and the reason is not caution. Marched repeatedly on
    one identical field the parallel path varies in triangle count, in volume, and in how many
    components it finds; the sequential path returns the same three numbers every time. Clusters an
    edge-length across are marginal, and which way a marginal one goes depends on the order the
    samples arrive in.

    The parallel path is roughly three times faster at this size. Take it when the output is going
    to a screen; take the sequential one when a number off it is going to be read.

!!! note "A radius-one blur is the usual choice, and it is the wrong one here"

    `asDensityField`'s default smooths the occupancy grid by a voxel before marching, which is right
    for a dense automaton: it turns a box of cubes into a shape, and it drops isolated single voxels
    that would each contribute a tiny shell. `LIFE3D_BLOB` after eight generations is filaments and
    isolated cells, and a 27-voxel average over those never reaches an iso of 0.5. The surface comes
    back **empty**, from both marchers, with no error. `smoothRadius = 0` marches the raw occupancy
    instead, which is why the clusters above are faceted at the voxel scale.

## What the render is framed on

The camera is placed from what the geometry turned out to be rather than from the grid it came from.
`importTriMesh` returns the world-space bounds of what it appended, so the transcript unions those
over the forty-eight imports and derives the target, the distance and the floor height from the
result. Eight generations of a decaying rule leave a cloud a little over half the width of the volume
it started in, and a camera placed for the volume puts it in the middle distance.

```diffstatic {data-lang=kotlin}
val added = scene.importTriMesh(part.toTriMesh(normals = true, minSharpAngle = 25.0), material)
if (added.triangleCount > 0) { sceneLo.min(added.boundsMin); sceneHi.max(added.boundsMax) }
```

Fourteen of the level set's triangles come back with zero area and are skipped rather than aborting
the scene; [A polytope, cut open, and traced](chain_polytope_render.md) has the rest of that story.

## Where each piece is documented

* [Spatial Artificial Life](alife_examples.md) for the automata, including the 2D rules and the
  independent oracles, and [ALife](reference/alife.md) for the declarations.
* [Solids & Booleans (Manifold)](manifold.md) for the Boolean layer, `levelSet`, `decompose` and the
  status model.
* [Meshes & 3D Geometry](mesh_examples.md) for `marchingCubes`, `isWatertight` and `genus` on the
  `TriMesh` side.
* [Progressive Path Tracing](path_tracing.md) for the tracer.
* [A cellular automaton you can hear](chain_ca_sound.md) takes the same family of automata in the
  other direction, into notation and then into sound.
