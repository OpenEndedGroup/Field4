# Braitenberg vehicles: where next (2026-11-01)

Measurements behind sections 5-9 of `public/docs/braitenberg_vehicles.md`; pinned by
`src/jvmTest/kotlin/afield/simulation/agents/VehicleWhereNextTest.kt`. Four lamps at
(60,60) (180,70) (120,180) (200,200) on a 240 torus, as on the page.

## BUMP orbits
- `orbitBias(d)` = `weight · brightness(d)`; AGGRESSOR wiring, `gain = 4`, `tauMembrane = 1`.
- 60 vehicles, seed 3, 6000 settle + 2000 measured: medians 10.25 / 14.42 / 19.20 / 24.47 for d = 8/12/16/20.
- **Trap:** the default `tauMembrane = 4` destroys the orbit (median ~48, the uniform-torus value);
  so does `gain <= 1` (bump tails too faint to steer). `gain = 2, tau = 1` orbits only at small d.

## Speed cost (80 vehicles, seed 5, learning 3.0, 15 000 steps, last 2000)
| cost | light | speed | summed w |
|---|---|---|---|
| 0 | 0.584 | 0.897 | -1.40 |
| 0.2 | 0.554 | 0.832 | -3.48 |
| 0.5 | 0.535 | 0.677 | -6.33 |
| 1.0 | 0.509 | 0.534 | -7.60 |

## Learning + homeostasis (seeds 5/7/9, 15 000 steps, last 5000)
- light: neither 0.40-0.45, homeostasis 0.29-0.31, learning 0.55-0.59, both 0.46-0.51.
- The old page bullet ("1.7 visits over 17 000 steps, 0.49 vs 0.41") was replaced by these.
- Freezing learned weights and then adding homeostasis *lowers* light (0.59 -> 0.41 at seed 5)
  and visits; homeostasis does not add foraging to a learned population.

## Moving lights (lamps at 0.1/step, 1000 settle, 4000 measured)
- learned-frozen 0.41, learned-still-learning 0.38, untrained 0.34, LOVER 0.75.
- `trailAlong` for lovers: -0.8 at speed 0.1, -7.4 at 0.2, -13.6 at 0.4. For the random-ish learned
  population trailAlong is dominated by vehicles passing through, so it is not a clean lag measure.

## Evolved wirings
- `VehicleWiringGenome` + `vehicleForageObjective` (30 vehicles, 3000 steps, last 1000) fit cheaply:
  300 evaluations ~8 s.
- Seeds 13/17/19 at budget 300: random 0.989/1.004/1.013, evolve 1.021/1.001/1.014. LOVER 0.641.
  The winners are lopsided wirings that spin on the lamp. A four-weight space is covered by random
  sampling, so the search adds little; a genome including neuron params would be the next step.
