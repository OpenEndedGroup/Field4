# A Generator, Graded by Datalog

A flow-matching generator makes new points shaped like a cloud. To find out whether it kept the
cloud's shape, you can hand its output to a Datalog program. Datalog doesn't need to know where
the points came from: it reads them as facts, and its rules say which points are near each other,
which ones have no target point nearby, and how many separate pieces there are.

In this example the target is two interlocking half-moons, 200 points in two pieces that do not
touch. The generator's 200 points are drawn below, coloured by the piece Datalog put them in. Grey
is the target. The red points are the ones Datalog calls strays.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_flow_islands/islands.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="drawn:" embed=true hidden=true timeout="300" padding="12" name="chain-flow-islands-hero" alt="Two interlocking half-moons of large grey target points. Small generated points lie along them, cyan on one moon and gold on the other. Eleven red points sit apart from both moons: several in the gap between them, a few past the ends of the arcs" macro-file="../../dev_docs/agents/transcripts/flow_islands.yaml" capture-box="fi_draw"}
// Built and run from dev_docs/agents/transcripts/flow_islands.yaml.
```

The example has three boxes, each a child of the one before. The first trains the generator, the
second runs the Datalog program, and the third draws what the program found.

## The generator

`flowMatch` with no `from` cloud learns to carry a Gaussian onto the target, and `sample` draws
new points through the trained field. The box publishes both clouds, so the boxes below it can
read them:

```diffstatic {data-lang=kotlin}
prop<List<Vec3>>(moonTarget)
prop<List<Vec3>>(moonSamples)

val field = flowMatch(moonTarget, options = FlowMatchOptions(steps = 6000, seed = 11))
moonSamples = field.sample(200, seed = 12)
```

A field like this is continuous, so it cannot split one Gaussian into two pieces cleanly: some
samples end up partway between the moons. [Flow Matching](flow_matching.md) shows the same limit
when it moves one shape onto another.

## The points as facts

The second box writes each cloud into a `Datalog` as rows of an id and two coordinates:

```diffstatic {data-lang=kotlin}
d.facts("pt", moonSamples.mapIndexed { i, p -> [i, p.x.toDouble(), p.y.toDouble()] })
d.facts("tgt", moonTarget.mapIndexed { i, p -> [i, p.x.toDouble(), p.y.toDouble()] })
```

The rules then measure distance with `hypot` and do the rest:

```diffstatic {data-lang=kotlin}
"""
#datalog
% how many target points lie within 12 units of each generated one
support(A, N) :- pt(A, X, Y), N = count : { tgt(_, U, V), hypot(X - U, Y - V) < 12 }.
stray(A) :- support(A, 0).

% join kept points closer than 18, and label each piece by its smallest id
near(A, B) :- kept(A), kept(B), pt(A, X, Y), pt(B, U, V), A != B, hypot(X - U, Y - V) < 18.
reach(A, A) :- kept(A).
reach(A, C) :- reach(A, B), near(B, C).
island(A, L) :- kept(A), L = min B : { reach(A, B) }.
size(L, N) :- island(_, L), N = count : { island(B, L) }.
"""
```

A stray is a generated point with no target point within 12 units. `reach` follows `near` as far
as it goes, so each point's island is the smallest id it can reach. `kept` is the one rule the
program leaves out. The box adds it twice, to two separate models: once as every point, and once
as every point that is not a stray.

```diffstatic {data-lang=kotlin}
val everything = model("kept(A) :- pt(A, _, _).")
islandModel = model("kept(A) :- pt(A, _, _), !stray(A).")
```

## What the program found

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_flow_islands/counts.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="islands counted" embed=true hidden=true timeout="300" padding="12" name="chain-flow-islands-counts" alt="A three-line table: strays (no target point within 12), 11 of 200; every point, 4 islands, sizes 101, 96, 2 and 1; strays left out, 2 islands, sizes 97 and 92" macro-file="../../dev_docs/agents/transcripts/flow_islands.yaml" capture-box="fi_islands"}
// Built and run from dev_docs/agents/transcripts/flow_islands.yaml.
```

Eleven of the 200 samples are strays. Counted with every point, the cloud has four islands: two
big ones, and two small ones made only of strays. Eight strays are close enough to a moon to join
its island, and the other three form islands of their own. Leave the strays out and two islands
are left, one for each moon. So the generator kept the two pieces apart, and Datalog shows exactly
which samples fall outside them.

The two radii are choices, and the counts depend on them: a smaller `support` radius calls more
points strays, and a larger `near` radius lets more of them join a moon.

## Drawing what it found

The third box asks the model for `island(A, L)` and `stray(A)`, and draws one `FLine` of points
per island and one for the strays:

```diffstatic {data-lang=kotlin}
val island = islandModel.query("island(A, L)").rows.associate { (it[0] as Long).toInt() to it[1] as Long }
val strays = islandModel.query("stray(A)").column("A").map { moonSamples[(it as Long).toInt()] }
```

Datalog stores every whole number as a `Long`, so an id that went in as an `Int` comes back as a
`Long` and has to be converted before it can index the list.

## Where each piece is documented

* [Flow Matching](flow_matching.md) for `flowMatch`, `sample` and `FlowMatchOptions`.
* [Datalog in a box](datalog.md) for facts, rules, aggregates and negation.
* [Agent transcripts](agent_transcripts.md#flow_islands) to replay the three boxes into a document.
