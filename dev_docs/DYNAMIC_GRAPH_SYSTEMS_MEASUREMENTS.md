# Measurements behind three dynamic-graph parameters

`public/docs/dynamic_graph_systems_examples.md` used to carry an aside about three parameters whose
names described a quantity they were not. On 2026-10-01 (handoff `bee0d51b`) the names were changed
instead, with no change in behaviour, and the aside was deleted. The numbers that motivated each change
stay here.

## `SpringMotionProcess.restLengthScale` became `restLengthTimesWeight`

The rest length of an edge is `restLengthTimesWeight / weight`, so the parameter's units are length ×
weight. The weights these systems produce per frame are of order 0.02 (an observation across the
shipped systems, not a guarantee any one makes), so the default `1.0` asks for a rest length of about
50 world units in a cloud one unit across. The morphogenesis clip uses `0.075`: a rest length of 0.05
at a typical weight of 1.5. Whether the rest length should depend on the weight at all is still open.

## `exampleDynamicMorphogenesisStep` lost its no-op prune

It ended with `PruneIsolatedPointsProcess(minDegree = 0)`. `degree < 0` cannot hold for any point and
`resource < -Double.MAX_VALUE` cannot hold for any double, so that stage marked nothing; removing it
changed no output. `minDegree = 0` stays legal, because with a real `minResource` it is a prune by
resource alone.

Measured on a 40-point ring, with or without the removed stage:

| frame | points | edges |
|---|---|---|
| 0–109 | 40 | — |
| 110 onwards | ×1.05 **per frame** | — |
| 199 | 3603 | 78 360 |

The population is flat for 109 frames and then compounds. `DirectedTipGrowthProcess.maxPointCount`
is the ceiling that bounds it.

## `Vec3AlphaEventHypergraphCollapseSystem.reinforce` became `reinforcePerUnitArea`

The increment is multiplied by `min(1, area)`, and a triangle in a cloud one unit across has an
area of a few thousandths, so the system never approaches the shared ceiling the other five reach.

Measured on the six-system figure on the page, against a shared ceiling of 4.0:

| system | maximum weight reached | share of the ceiling |
|---|---|---|
| `Vec3AlphaEventHypergraphCollapseSystem` | **0.0137** | 0.3 % |
| the other five | at least a sixth of the ceiling | ≥ ~17 %, two of them at 100 % |

0.3 % against a sixth is a gap of about two orders of magnitude. The other choice, normalising the
area factor so a typical triangle gives about 1, would change the six-system figure; it was not taken.
