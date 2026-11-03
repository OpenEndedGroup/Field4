# A Deadlock, Sculpted and Traced

Five philosophers sit at a round table with one fork between each pair. Each reaches for the fork
on their left, then the one on their right, eats, and puts both down. [Petri Nets](petri_nets.md)
builds this table as a net and asks `stateSpace()` for every marking (a count of tokens in each place, the net's
state) any order of firings can
reach. Let's turn the resulting graph into a sculpture: each marking becomes a glass bead, each
firing a metal tube, and the shortest route to deadlock glows.

`afield.simulation.petri` searches the state space, `tubeMesh` makes the tubes, and the GPU path
tracer lights and renders the result.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_petri_sculpture/sculpture.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="petri sculpture converged" embed=true hidden=true timeout="400" name="chain-petri-sculpture" alt="Two cage-like sculptures of thin curved tubes in blue, teal and violet, hung over a dark reflective floor that fades into black. Each starts at a silver bead at the top and spreads downward through layers of small glass beads. In each, one thicker tube glows orange from the top bead down to a bright red bead. The left sculpture is dense; the right one has far fewer tubes" macro-file="../../dev_docs/agents/transcripts/petri_state_space_sculpture.yaml" capture-box="petri_sculpture_trace"}
// Built and launched from dev_docs/agents/transcripts/petri_state_space_sculpture.yaml.
```

The left sculpture is the whole reachable state space of the symmetric table. The silver bead at
the top is the starting marking, with every fork on the table. Each layer down is one more firing.
A bead sits closer to a philosopher's seat the more that philosopher holds: halfway out for one
fork, all the way out while eating. Tubes are coloured by what the firing does: blue for picking
up a left fork, teal for starting to eat, violet for putting both forks down. The glowing tube is
the shortest route to the red bead, the marking where every philosopher holds one fork and nobody
can move.

The right sculpture is the same net searched with `PetriReduction.STUBBORN`, which explores one
order of independent firings instead of all of them. It reaches the same deadlock with far fewer
markings.

Lighting: Venice Sunset by Greg Zaal / Poly Haven (CC0), shared with the
[Fabricate demo assets](assets/fabricate_demo/ATTRIBUTION.md).

## The two graphs in numbers

This box builds both graphs, with no renderer, and tabulates what the sculptures show: how many
markings and firings each has, how many markings sit on each layer, and the route each one takes
to the deadlock.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_petri_sculpture/readout.png" wait="4" capture="box" open-outputs="true" w="1100" padding="18" timeout="180" name="chain-petri-sculpture-readout" embed=true alt="Two tables. The first compares the full and the stubborn-set state space of the five-philosopher table: markings, firings, deepest layer, and the deadlock route, five grabs in both, each replayed through the net. The second lists how many markings sit on each breadth-first layer of each graph"}
:::static
import afield.simulation.petri.*

:::fold the five-philosopher table
fun table(seats: Int) = petri {
    for (i in 0 until seats) place("fork.$i", tokens = 1)
    for (i in 0 until seats) {
        val left = i
        val right = (i + 1) % seats
        t("grab.$i") {
            from("fork.$left")
            to("holding.$i")
        }
        t("eat.$i") {
            from("holding.$i")
            from("fork.$right")
            to("eating.$i")
        }
        t("done.$i") {
            from("eating.$i")
            to("fork.$left")
            to("fork.$right")
        }
    }
}

:::endfold

val full = table(5).stateSpace()
val reduced = table(5).stateSpace(reduction = PetriReduction.STUBBORN)

fun layers(graph: PetriStateGraph) = graph.states.indices.groupingBy { graph.depth(it) }.eachCount().toSortedMap()

fun row(name: String, graph: PetriStateGraph): String {
    val trace = graph.deadlockTrace()!!
    return "| $name | ${graph.stateCount} | ${graph.edgeCount} | ${graph.diameterFound} | " +
        "${trace.transitions.joinToString(" → ")} | ${trace.verify() ?: "replays"} |"
}

val fullLayers = layers(full)
val reducedLayers = layers(reduced)

markdown(
    "main",
    """
#### Five philosophers, searched two ways

| | markings | firings | deepest layer | route to the deadlock | checked |
|---|---|---|---|---|---|
${row("full", full)}
${row("stubborn sets", reduced)}

#### Markings on each layer

| layer | ${fullLayers.keys.joinToString(" | ")} |
|---|${fullLayers.keys.joinToString("") { "---|" }}
| full | ${fullLayers.values.joinToString(" | ")} |
| stubborn sets | ${fullLayers.keys.joinToString(" | ") { "${reducedLayers[it] ?: ""}" }} |
""",
    width = 1040,
)
```

Both searches find the deadlock, and `verify()` replays each route through the net to confirm it.
The reduced search keeps the markings it needs to settle the deadlock question and drops most of
the rest, which is why the layers of the right-hand sculpture are sparse.

## Placing a marking

A marking is a count of tokens per place, so it needs a rule to become a point. The sculpture
uses breadth-first depth for height and gives each philosopher a direction around a circle. A
philosopher's tokens pull the marking that way, and the pulls add up:

```diffstatic {data-lang=kotlin}
fun seat(i: Int) = Vec3(cos(2 * PI * i / seats).toFloat(), 0f, sin(2 * PI * i / seats).toFloat())

val p = Vec3(0f, -layer * graph.depth(id), 0f)
for (i in 0 until seats) {
    val pull = 0.5f * m.count("holding.$i") + 1.0f * m.count("eating.$i")
    p.add(Vec3(seat(i)).mul(pull))
}
```

The starting marking and the deadlock both land on the axis: in one nobody holds anything, and in
the other everyone holds one fork and the pulls cancel. Markings that land on the same point are
spread on a small ring around it, so each keeps its own bead.

Each firing is a short arc from one bead to the next that bows away from the axis. It is swept
into a tube and handed to the tracer with its material:

```diffstatic {data-lang=kotlin}
scene.importTriMesh(tubeMesh(arc(at[edge.from], at[edge.to], axis), radius = 0.011, sides = 10), material)
```

The route to the deadlock is found by firing `deadlockTrace().transitions` from the starting
marking and keeping the edge each one takes. Its tube and the deadlock bead use emissive
materials, so they also light the tubes and beads around them.

## Where each piece is documented

* [Petri Nets](petri_nets.md), "Checking every possible order", for `stateSpace()`,
  `deadlockTrace()` and stubborn-set reduction.
* [Meshes & 3D Geometry](mesh_examples.md) for `tubeMesh` and `TriMesh`.
* [Progressive Path Tracing](path_tracing.md) for the tracer and its materials.
* [Three-Dimensional Life, Counted and Traced](chain_life3d_render.md) takes a cellular automaton
  through the same tracer.
