# Measurements behind the three misnamed parameters

`public/docs/dynamic_graph_systems_examples.md` warns about three parameters whose names describe a
quantity they are not. The warning states the consequence and the fix; the numbers that establish
each consequence live here, so the page can stay about configuring the systems.

## `PruneIsolatedPointsProcess(minDegree = 0)` marks nothing

`degree < 0` cannot hold for any point and `resource < -Double.MAX_VALUE` cannot hold for any
double, so the predicate is unsatisfiable and the process is a provable no-op. That matters because
`exampleDynamicMorphogenesisStep` configures it exactly that way, so the pipeline it demonstrates
has an uncapped growth rule and a death rule that cannot fire.

Measured on a 40-point ring:

| frame | points | edges |
|---|---|---|
| 0–109 | 40 | — |
| 110 onwards | ×1.05 **per frame** | — |
| 199 | 3603 | 78 360 |

The population is flat for 109 frames and then compounds. `DirectedTipGrowthProcess.maxPointCount`
is the ceiling that bounds it; nothing in the prune process will.

## `Vec3AlphaEventHypergraphCollapseSystem` reinforcement against the other five

The increment is multiplied by `min(1, area)`, and a triangle in a cloud one unit across has an
area of a few thousandths, so the system never approaches the shared ceiling the other five reach.

Measured on the six-system figure on the page, against a shared ceiling of 4.0:

| system | maximum weight reached | share of the ceiling |
|---|---|---|
| `Vec3AlphaEventHypergraphCollapseSystem` | **0.0137** | 0.3 % |
| the other five | at least a sixth of the ceiling | ≥ ~17 %, two of them at 100 % |

0.3 % against a sixth is the two-orders-of-magnitude gap the page tells you to close with
`reinforce`. Without it this system's edges render invisible beside its neighbours'.

## Open

- No ground truth exists that would let any of the three be renamed or re-scaled without changing
  behaviour, which is why all three are documented rather than fixed.
- The `restLengthScale` note on the page quotes a typical per-frame weight of order 0.02. That is
  an observation across the ten shipped processes rather than a guarantee any one of them makes.
