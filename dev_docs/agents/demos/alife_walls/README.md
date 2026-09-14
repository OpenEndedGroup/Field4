# alife_walls — a world with walls

Open this document in a fresh Field and run the boxes top to bottom.

```
uv run --project . --group backdoor python tools/field_backdoor.py \
  --workspace dev_docs/agents/demos --document alife_walls --command 'run hit_a_wall'
```

`afield.stdlib.alife_walls` gives `alife_script.kt`'s `ScriptWorld` an environment. Before it, the
world was a bare rectangle: the only spatial thing an agent could say was `avoidEdges(margin)`, and
everything else it could steer around was another agent. Now there are obstacles, the collision
that stops an agent crossing one is *swept* rather than discrete, and `route()` plans a way round
rather than pressing into it.

## The three boxes

**1 — A wall, and three answers to hitting it.** Three agents, one barrier, one room.
`WallResponse.SLIDE`, `.BOUNCE` (with `restitution`) and `.STOP`. Run captured here:

```
SLIDE     1 contact(s), ended at  118.0,  170.3
BOUNCE    5 contact(s), ended at  106.3,  177.7
STOP      1 contact(s), ended at  118.0,  126.5
```

A wall is a line *segment* and a segment has no thickness, so the obvious collision test — does the
new position overlap a wall — is false for any step big enough to step over it, at any speed. Raise
`maxSpeed` and `vx` from 90 to 900 in this box and the numbers stay sane, because the collision
solves for the first contact along the step instead of testing where the step landed.

**2 — Routed seek against direct seek, on a maze.** An S: the first barrier is open only at the
bottom, the second only at the top, so no fixed "always turn left" solves it. Both arms run
`avoidWalls` at the same weight — this is routing against the best local steering available, not
routing against nothing. 4 seeds x 8 agents = 32 per arm, 1800 steps:

| arm | arrived | mean travel |
| --- | --- | --- |
| routed | **0.969** | 302.5 |
| direct | 0.000 | 87.1 |

Both arms travel. The travel column is the non-degeneracy check: a margin over a control that never
left home would mean nothing. The same comparison at 8 seeds x 12 agents, with floors asserted, is
`AlifeWallsGallery.kt`'s counterfactual figure — 0.990 against 0.000 there.

**3 — Following a wall, and climbing a light.** `cling(distance, clockwise)` follows the nearest
wall at a chosen standoff — tangential motion plus a radial correction, which is exactly what
`orbit()` is, except that the thing being orbited is read off the geometry. `gradient(field,
towards)` climbs a `ScalarField` by central difference. Run captured here:

```
cling  : mean distance to the nearest wall 4.11 (asked for radius 1.0 + 3.0 = 4.0)
climb  : mean field value 0.933
         4 at the lamp at (150, 160), 4 at the lamp at (45, 40), 1 still travelling
```

The climbers start on a grid centred *between* the two lamps, on purpose: start them nearer one and
"they split" becomes a claim about the starting positions rather than about the sensing. A gradient
sensor reads the slope where it is standing and knows nothing about the other maximum.

## Two things worth knowing before you write a script here

**Plan outside your own avoidance range.** `route(goal, clearance = c)` puts the path `radius + c`
from every wall, and `avoidWalls(clearance = a)` pushes on anything within `radius + a`. Set `c`
below `a` and the two terms disagree all the way round every corner. Box 2 plans at 3.5 against an
avoidance of 3.0; at `clearance = 0.5` the arrival curve visibly sags and the wall-contact count
quadruples.

**Obstacles need `wrap = false`.** A wall segment does not wrap, so a swept test across the seam
would be quietly wrong. `addObstacle` refuses rather than being wrong in one place.

## What is not here

No screenshots are committed: the point of a demo is that you open it. The measurement figures for
the layer itself — the swept-against-discrete tunnelling pair, the maze counterfactual and the
steering panel, from the `stdlib.alife.walls.gallery` harness rather than from these boxes — are in
`dev_docs/assets/alife_walls/`, and the method behind them is `dev_docs/ALIFE_WALLS.md`.
