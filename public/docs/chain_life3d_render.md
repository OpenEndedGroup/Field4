# Three-dimensional Life, counted and traced

Run a cellular automaton in three dimensions and you get a volume of ones and zeros. How many
separate things are left inside it? Here we'll run `LIFE3D_BLOB` (a 3D Life rule: a dead cell with exactly five live neighbours is
born, a live one with four or five survives) for eight generations, turn the
surviving cells into a Manifold solid, count its separate parts, and render them.

`alife_ca.kt` runs the automaton, `afield.geometry.solid.manifold` turns it into closed solids and counts them,
and the GPU path tracer draws the result.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_life3d_render/clusters.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="life3d study converged" embed=true hidden=true timeout="300" name="chain-life3d-clusters" alt="Several dozen small blue faceted lumps floating in a loose constellation over a pale floor with the Venice lagoon behind, with one larger lump near the centre rendered in gold" macro-file="../../dev_docs/agents/transcripts/life3d_manifold_path_trace.yaml" capture-box="life3d_manifold_trace"}
// Built and launched from dev_docs/agents/transcripts/life3d_manifold_path_trace.yaml.
```

Every blue lump is a separate closed solid and the gold one is the largest. `decompose()` returned them as
independent solids, each with its own volume, and their volumes add up to the volume of the whole.

Environment: Venice Sunset by Greg Zaal / Poly Haven (CC0), shared with the
[Fabricate demo assets](assets/fabricate_demo/ATTRIBUTION.md).

## The readout

This box seeds and runs the automaton, surfaces what survives with both marchers, and prints the
census. It needs no renderer: everything before the trace runs on the CPU.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_life3d_render/readout.png" wait="10" capture="box" open-outputs="true" w="1100" h="320" padding="18" timeout="180" name="chain-life3d-readout" embed=true alt="Tables: live cells per generation falling from 1852 to 164; Field's marching cubes reporting 10688 triangles, watertight, genus 0 against Manifold's level set reporting 5374 triangles and genus minus 47; a cluster census whose 48 volumes sum exactly to the whole solid's; and a sweep showing both the cluster count and the volume changing with the sampling spacing"}
:::static
import afield.geometry.solid.manifold.Solid
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
repeat(generations) {
    volume = volume.step(LIFE3D_BLOB)
    populations += volume.population()
}

:::fold two marchers on one field, then the census
// smoothRadius = 0 marches the raw occupancy, which is why the clusters are faceted at the voxel
// scale: this rule leaves filaments and isolated cells that a blur would erase.
val field = volume.asDensityField(smoothRadius = 0, iso = 0.5)
val bounds = volume.marchBounds()

val marched = volume.toMesh(resolution = 110, smoothRadius = 0, iso = 0.5)

val whole =
    Solid.levelSet(bounds, edgeLength = edgeLength) { x, y, z ->
        field(vec(x, y, z)).toDouble()
    }
val parts = whole.decompose()
val volumes = parts.map { it.volume }

markdown(
    "census",
    """
#### `LIFE3D_BLOB`, ${generations} generations from a 45%-filled 16-cubed seed

live cells per generation: `${populations.joinToString(" -> ")}`

| | triangles | watertight | genus | volume |
|---|---|---|---|---|
| Field's `marchingCubes` | ${marched.triangleCount} | ${marched.isWatertight()} | ${marched.genus()} | ${marched.signedVolume().f4} |
| Manifold's `levelSet` | ${whole.triangleCount} | by construction | ${whole.genus} | ${whole.volume.f4} |

`decompose()` returns **${parts.size}** separate solids, and `1 - ${parts.size} = ${1 - parts.size}`
is the genus above. Each one on its own has genus ${parts.take(5).map { it.genus }.distinct()}.

| | volume |
|---|---|
| largest cluster | ${volumes.first().f3} |
| median cluster | ${volumes[volumes.size / 2].f3} |
| smallest cluster | ${volumes.last().f3} |
| the ${parts.size} summed | ${volumes.sum().f4} |
| the solid they came from | ${whole.volume.f4} |
""",
    width = 1020,
)

parts.forEach { it.close() }
whole.close()

// How much of the census is a property of the field, and how much of the spacing it was sampled
// at. Both marchers, three spacings each, so the answer is in the figure rather than in prose.
val sweep = buildString {
    appendLine("| spacing | clusters | volume |")
    appendLine("|---|---|---|")
    [1.4, 0.7, 0.35].forEach { edge ->
        Solid.scope {
            val s =
                Solid.levelSet(bounds, edgeLength = edge) { x, y, z ->
                    field(vec(x, y, z)).toDouble()
                }
            appendLine(
                "| `levelSet` edgeLength ${edge} | ${s.decompose().size} | " + "${s.volume.f2} |",
            )
        }
    }
    [55, 110, 220].forEach { resolution ->
        val m = marchingCubes(field, bounds, resolution = resolution, positiveOutside = false)
        appendLine("| `marchingCubes` resolution ${resolution} | | ${m.signedVolume().f2} |")
    }
}
markdown("spacing", "#### The same field at three spacings each way\n\n" + sweep, width = 1020)
:::endfold
```


## Why the two marchers disagree about genus

The readout marches the field twice, once with Field's `marchingCubes` and once with
Manifold's `levelSet`, and the two report different genus (the number of holes through a surface). Both are right about different things.

Field's `genus()` reads one Euler characteristic and solves \( \chi = 2 - 2g \), which is the genus of a
**connected** surface. Handed forty-eight disjoint closed surfaces it returns the genus of the one it
implicitly assumes, and says nothing about the other forty-seven.

Manifold's `genus` is computed over the whole boundary. For *k* disjoint genus-zero spheres,
χ is \( 2k \), so the formula gives \( g = 1 - k \). Here that gives \( 1 - 48 = -47 \): the value reflects
the number of disconnected pieces rather than a count of holes.

`decompose()` turns that back into the number you wanted: the separate pieces, sorted largest
first, each with its own volume. Because Manifold computes each volume independently, the pieces
adding up to the whole is a check that no cluster was lost or counted twice.

### The count depends on the sampling

Both the volume and the number of clusters depend on how finely the field is sampled, which the
second panel of the readout above measures: the same field, both marchers, three spacings each.

Cells that are diagonal neighbours are bridged by a surface at a coarse spacing and separated at a
fine one, so the cluster count falls as the triangles get bigger. And a trilinear iso-surface over a
0/1 occupancy grid gains volume as the sampling gets finer, so both marchers climb, in the same
direction, and at comparable spacings they land within a few percent of each other. Neither number
is "the" volume of the automaton.

Include the sampling spacing when you report a count. The genus relation and the sum of the
pieces describe the surface produced at that spacing.

## What the render is framed on

The camera is placed from the bounds of the geometry, not of the grid it came from.
`importTriMesh` returns the world-space bounds of what it appended, so the transcript unions those
over the forty-eight imports and derives the target, the distance and the floor height from the
result. Eight generations of a decaying rule leave a cloud a little over half the width of the volume
it started in, and a camera placed for the volume puts it in the middle distance.

```diffstatic {data-lang=kotlin}
val added = scene.importTriMesh(part.toTriMesh(normals = true, minSharpAngle = 25.0), material)
if (added.triangleCount > 0) {
    sceneLo.min(added.boundsMin)
    sceneHi.max(added.boundsMax)
}
```

A few of the level set's triangles have zero area; the tracer skips them rather than failing the
scene (see [A polytope, cut open, and traced](chain_polytope_render.md)).

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
