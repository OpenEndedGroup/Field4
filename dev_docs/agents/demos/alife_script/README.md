# alife_script — giving one agent a program

Open this document in a fresh Field and run the boxes top to bottom.

```
uv run --project . --group backdoor python tools/field_backdoor.py \
  --workspace dev_docs/agents/demos --document alife_script --command 'run one_agent'
```

`afield.stdlib.alife_script` adds a per-agent behaviour layer to ALife. The nine `alife_*.kt` files
already there advance a whole population with one `step(params)` call — `BoidFlock.step`,
`PhysarumSwarm.step`. This layer lets an individual agent run a *program*: sequencing, waiting on
simulation time, named phases, bounded concurrent sub-behaviours, and steering drives that are
composed from whichever behaviours are active right now.

The clock is a fixed step and the world is seeded, so a run is reproducible — the same document,
run twice, is the same run, including across JVMs.

## The three boxes

**1 — One agent, one program.** Four lines: a world, one agent whose whole program is "orbit that
point", `run(480)`, and its trail. Start here. Change `distance = 40.0` to `20.0`, or swap
`orbit(centre, …)` for `wander(turn = 30.0)`, and re-run.

**2 — A division of labour, which one global rule does not buy.** Six sites, twenty-four agents,
two arms at the same speed on the same integrator
(`AlifeScriptGallery.kt:456#private fun figureCounterfactual` asserts this margin against a floor).
Scripted agents each walk the six sites in their *own* rotation; the control puts the site on the
world clock, so everybody goes to the same place at once. Measured here, over 900 steps:

| arm | mean sites occupied, of 6 |
| --- | --- |
| scripted | **5.610** |
| one global rule | 0.952 |

Both arms move — the box prints each arm's travel, and the scripted one travels *less*. A margin
over a control that never left home would mean nothing. Change every agent's `order` to
`(0 until 6).toList()` — same script, no rotation — and the scripted arm collapses towards the
control. The rotation *is* the division of labour.

**3 — Phases: flock, scatter, flock, scatter.** Ninety agents, four 5 s phases, alignment measured
with `alife.kt`'s own `polarization` through `asBoidFlock()`. Means over the last second of each
phase, from the run captured here:

```
flock    0.944
scatter  0.122
flock    0.990
scatter  0.110
```

Two things in that box's comments are worth more than the figure, because both were bugs first:

- **`wander(turn = 6.0)` is not a scatter.** At 60 Hz a small turn random-walks a heading less than
  a radian across five seconds, so it *preserves* the alignment it inherited. An early version
  measured polarization **rising** during a phase labelled "scatter". `turn = 40` decorrelates
  inside the phase.
- **A symmetric start is a fixed point.** Ninety agents on a ring all facing along it cancel
  exactly, so polarization reads `0.000`, and because `align` and `cohere` preserve that symmetry it
  *stays* there for the whole first phase. Random headings fix it. A symmetric population is not
  disordered, it is cancelling — the sibling of "rest is not disordered, it is empty", which is why
  `spawn` takes an initial velocity.

## One sharp edge you will hit

Targets are built by the behaviour scope (`point`, `actor`, `nearest`, `centroid`), and inside a
steering block the receiver is the steering builder, which does not expose them. So this does not
compile:

```kotlin
moveFor(4.0) { orbit(point(100.0, 100.0), distance = 40.0) }   // "cannot be called in this
                                                               //  context with an implicit receiver"
```

Hoist the target to where the program can see it and pass it in — box 1 does this with `centre`.
Worth fixing in the API rather than in every script; noted in the branch worklog.

## What is not here

No screenshots are committed: the point of a demo is that you open it. The measurement figures for
the layer itself — the counterfactual and the regime plot, from the `stdlib.alife.script.gallery`
harness rather than from these boxes — are in `dev_docs/assets/alife_script/`, and the method behind
them is `dev_docs/alife_scripted_behaviour_measurement.md`.
