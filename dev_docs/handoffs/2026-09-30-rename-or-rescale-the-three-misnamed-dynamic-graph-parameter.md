# Rename or rescale the three misnamed dynamic-graph parameters

- **Handoff:** `bee0d51b-84fa-40dc-8114-fab3772ab491` (project)
- **Filed from:** https://openendedgroup.com/field4/dynamic_graph_systems_examples/
- **Filed by:** agent:claude, 2026-09-30 20:41
- **Close with:** `python tools/docs_notes.py handoff-close bee0d51b "what happened"`

## The notes that led here

- `4ce99722` (public/docs/dynamic_graph_systems_examples.md:28): stop this. If their names are really this bad, change the names. We have no users (yet)
  > Three of the parameters here are named for a quantity they are not, and the gap is wide enough that a badly-set one looks like the system misbehaving rather than like a units error. There is no ground truth that would let them be renamed or re-scaled, so they are documented instead. Read them before you configure anything:

## What was asked

On `public/docs/dynamic_graph_systems_examples.md`, the page used to apologise for three
parameters "named for a quantity they are not" and said they could not be renamed. The human's
note: "stop this. If their names are really this bad, change the names. We have no users (yet)."
That is an API change in `src/jvmMain/kotlin/afield/stdlib/dynamic_graph_systems.kt`, its tests,
and the docs fences that use it — not a prose edit.

## Evidence (verified in source)

1. `SpringMotionProcess.restLengthScale` — `dynamic_graph_systems.kt` around the KDoc
   "`@param restLengthScale **not a length.**`" and `val rest = restLengthScale / edge.weight`.
   Units are length × weight; the default `1.0` means a rest length of ~50 units at the typical
   per-frame weight of 0.02. Used in `DynamicGraphSystemsTest.kt` (the spring ring test,
   `restLengthScale = 0.13 * weight`; the morphogenesis test, `restLengthScale = 0.075`) and in the
   morphogenesis fence on the docs page.
2. `PruneIsolatedPointsProcess(minDegree = 0)` is a no-op (KDoc near `class PruneIsolatedPointsProcess`
   says so), and `exampleDynamicMorphogenesisStep` configures it exactly that way
   (`PruneIsolatedPointsProcess(minDegree = 0)` inside that function). So the shipped example
   pipeline carries a death rule that cannot fire.
3. `Vec3AlphaEventHypergraphCollapseSystem`: `val areaFactor = min(1.0, sqrt(area2))` multiplies
   `reinforce`, so in a unit-sized cloud this system reaches ~0.3 % of the ceiling where the other
   five systems reach ≥ 1/6. The name `reinforce` means something different here from the other five.

## Proposed approach

- (1) Replace `restLengthScale` with a real `restLength: Double` (world units) and make the
  weight dependence explicit and optional, e.g. `rest = restLength * (referenceWeight / weight)`
  with `referenceWeight` defaulting to 1.0, or drop the weight dependence entirely
  (`rest = restLength`). Alternative: keep the formula, rename to `restLengthTimesWeight`.
- (2) Either give `exampleDynamicMorphogenesisStep` a prune rule that can fire (`minDegree = 1`), or
  remove the prune process from it; and make `minDegree = 0` either illegal (`require`) or remove
  the default that allows it.
- (3) Normalise the area factor (e.g. divide by `alphaRadius²`-scale reference area so a typical
  triangle gives ~1), so `reinforce` is comparable across the six systems. Alternative: rename the
  parameter to `reinforcePerUnitArea`.
- Update `dev_docs/DYNAMIC_GRAPH_SYSTEMS_MEASUREMENTS.md`, `DynamicGraphSystemsTest`, the KDoc, and
  every docs fence on this page that sets these parameters (section 1 "six systems" fence sets a
  shared `reinforce`; the morphogenesis fence sets `restLengthScale = 0.075`), then recapture.

## Questions for the human

- Should the spring rest length still depend on edge weight at all?
- For (2): fix the example so the prune fires (changes the morphogenesis clip and its table), or
  just delete the no-op process?
- For (3): normalise the area factor (changes captured figures in section 1) or rename?

## What the page needs afterwards

The interim `??? aside "Three parameters that do not mean what they read"` near the top of the
page (the apologetic lead paragraph is already gone) should be deleted once the names are honest,
or cut to whichever of the three survives. Fences that set these parameters must be updated and
recaptured, and the numbers in the morphogenesis table re-read from the new run.
