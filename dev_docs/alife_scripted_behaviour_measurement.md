# Measuring a scripted-agent behaviour layer

How `afield.stdlib.alife_script` was verified on 2026-09-13, and the traps the harness found in it.

**Provenance.** The measurements below were produced by the branch's own harness,
`afield.examples.stdlib.AlifeScriptGallery` (`FIELD_TEST stdlib.alife.script.gallery`), and are
transcribed from that run. They were **not** independently re-measured when this page was written;
what was independently checked is that the source compiles (see *Verification status* at the foot).
Every figure is given with the population it was measured over, because a number without its
population is not a measurement.

## Method

```
python3 tools/run_field_tests.py --id stdlib.alife.script.gallery
```

with `ALIFE_SCRIPT_DIR=<dir>` exported to keep the PNGs. `run_field_tests.py` has no
`--jvm-main-args`, but the environment is inherited by the spawned JVM — confirmed by finding the
files afterwards rather than by assuming it. The harness reports **50 checks, 0 failures**, 5.3 s of
figure time inside a 19–27 s wall-clock run (the runner starts a Field).

Figures are written to a temp directory by default and **no asset is committed**, which is the
house rule for harnesses (`checkTestTreeWrites`); `AlifeEvolveGallery` is the precedent this copies.

## Determinism — population: 24 scripted agents, 900 steps at 60 Hz, seed 11

Determinism is the design claim of this layer over an ad-hoc coroutine behaviour system, so it is
the thing asserted rather than described.

- `state()` (96 doubles) compared with `contentEquals` at steps 1, 60, 300 and 900 — equal at all
  four.
- Event timeline: 432 events, `eventSignature` equal.
- Step-900 `stateSignature` = **`a196146e0386d8fd`**, identical across **two separate JVM
  invocations twenty minutes apart**. Cross-process reproducible, not merely stable within one
  process — which is the difference between a deterministic simulation and a warm cache.
- Perturbing agent 0's spawn x by **1e-3** *does* change the run, so the equality above is not a
  fixed point that any input would reach.
- Travel 6 706 units, 228 phase entries, floored at 3 500 and 120.

### The seed finding, which was a surprise and is recorded because it was

Seeds 11 and 12 give **byte-identical** runs. That is correct and initially looked like a bug: the
foraging script never draws a random number, so its per-agent streams are never read and the seed
reaches `wander` and nothing else. Rather than explain the anomaly away, the harness asserts the
seed's effect **separately**, on a 12-agent wandering population where the streams *are* read: same
seed → equal, different seed → different.

## Counterfactual — population: 24 agents per arm, 6 sites, 900 steps (15 s)

| arm | mean sites occupied / step | distinct sites reached | travel (units) |
| --- | --- | --- | --- |
| scripted | **5.560** | 6 | 6 706 |
| one global rule | 0.897 | 6 | 8 715 |
| boids (`BoidFlock.step`) | 0.809 | 6 | 23 464 |

Margin **4.663** against a floor of **2.5**. Worst single step after the 3 s warm-up: **6 of 6**,
floored at 4. The scripted arm travels the *least* and scores the most, which is the result worth
having — coverage here is a product of sequencing, not of motion.

**Floors run in both directions**, which is the part to copy:

- every arm's travel > 5 000, so a blank-versus-blank comparison cannot pass at zero difference;
- **the control's own score > 0.6**, because a margin measured over a broken control is not earned.

![four panels: scripted trails forming a hexagon with every site ringed and cross-links between
them; the global-rule arm's trails collapsed into a single C-shaped clump; the boid arm barely
leaving its start; and a four-trace plot of sites occupied per step in which the scripted trace
rises immediately and saturates while the others stay near the floor](assets/alife_script/script-counterfactual.png)

Re-captured and opened 2026-09-13 at `070e5c3ae`+ — the scripted panel visits all six rings, the
control clumps, and the boid arm travels furthest for the least coverage.

## Regime switching — population: 90 agents, 1 200 steps (20 s), 5 s phases

Polarization via `asBoidFlock()` → `polarization(vx, vy)`, meaned over the last second of each
phase, across flock / scatter / flock / scatter:

**0.972 / 0.082 / 0.696 / 0.073**

Mean nearest-neighbour distance at the end 12.02, floored at 2.0 — an anti-collapse check, because a
population that has converged to a point is trivially polarized. Phase floor 0.60, ceiling 0.45,
swing floor 0.30. Zero behaviour failures.

The two ordered flock phases differ (0.972 vs 0.696) and that is not noise: the first starts from
the evenly-spread spawn ring, the second from whatever 5 s of hard wandering left behind.

![five panels: four snapshots of ninety short heading arrows at 4.9 s tightly aligned, at 9.9 s
scattered with random headings, at 14.9 s re-formed into two aligned clusters, and at 19.9 s
scattered again; plus a polarization trace that starts high, collapses to noise, recovers, and
collapses again](assets/alife_script/script-regime.png)

Re-captured and opened 2026-09-13. The trace reads high-low-high-low **with high at the top** —
worth stating because bug 3 below is that this plot was once drawn upside down, and only opening
the file could tell.

## Four bugs the harness found in the code it was written for

This is the section worth reading, because in every case the mechanism was right and the
*measurement* or the *parameter* was wrong.

1. **`align` does not bootstrap from rest.** 90 agents spawned at rest reached polarization 0.106
   after 4 s of a flocking script. `align` sums its neighbours' velocity components and steers
   toward their mean (`alife_script.kt:1400#fun align`), so at rest the sum is zero and the drive
   has no direction to give. **Rest is not disordered, it is empty.** Fix: `spawn()` takes an
   initial velocity, documented at the parameter.
2. **`wander(turn = 6.0)` is not a scatter.** 0.05 rad/step at 60 Hz random-walks a heading only
   0.05·√300 ≈ 0.9 rad across a 5 s phase, so it *preserved* the alignment it inherited — the figure
   measured polarization **rising** during a phase labelled "scatter" (0.736). `turn = 40` is needed
   to decorrelate inside the phase.
3. **Both plots were upside down.** `rasterizeFLines` writes row 0 at the **top**, so "more order"
   drew *lower* on the page, and the three breadth bands were stacked in reverse panel order. Only
   visible by opening the PNG — no assertion catches an inverted axis.
4. **A byte-length check on a PNG passes on a 300×300 rectangle of pure black**, which is exactly
   what a figure looks like when the geometry missed the frame. The ledger now decodes the file and
   counts lit pixels (`inkFraction > 0.002`).

Items 3 and 4 are the same lesson from two directions: a figure can satisfy every cheap assertion
and still be wrong in the only way that matters, so a harness that produces images has to decode
them, and a human has to look once.

## Verification status, stated plainly

- **Compiles:** yes — `compileKotlinJvm --rerun` forced (an unforced run reported `UP-TO-DATE` in
  6 s and proves nothing).
- **The measurements above:** produced by the harness during the run, transcribed here, **not**
  re-measured at writing time.
- **Figures:** written to a temp directory and **not committed**, so the render proof does not live
  in this worktree beside the code that produced it. The upside-down-plot finding above means those
  figures were opened and looked at while the work was live; they cannot be re-inspected from this
  branch alone without re-running the harness.
- **Loadable demo:** **none shipped.** `git diff --name-only $BASE..HEAD -- dev_docs/agents/demos/`
  is empty. A feature that cannot be opened in a fresh Field is not finished, and this one cannot.
- **Generated reference:** drifts and is **declared, not regenerated** — see the salvage commit's
  manifest. `alife.md` and `index.md` are out of date; the corpus goes 13 370 → 13 489 public
  symbols (+119) across the same 20 pages.
