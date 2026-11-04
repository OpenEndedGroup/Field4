# Equal-budget search comparison (`compareSearch`)

`afield.study.search.compareSearch` (`src/jvmMain/kotlin/afield/study/search/SearchComparison.kt`)
is the generic form of the Braitenberg §10 comparison. It takes any `GenomeOps<G>` and `Objective<G>`
and runs a list of `SearchArm`s (`SearchArm.standard()` = random, hill climb patience 30, evolve
µ6/λ12 crossover 0.3, evolve µ12/λ24 + 25% immigrants) at an identical budget per seed, in parallel
over seeds. `compareVehicleSearch` is now a thin caller (three arms, the old parameters).

## Pins (`src/jvmTest/kotlin/afield/study/search/SearchComparisonTest.kt`)

- every arm spends exactly the budget;
- parallel == sequential;
- `bestAt(arm, n)` of a long run == best of a run at budget n (all drivers, so `budgetTable` is exact);
- `vehicleResultsAreUnchanged`: `compareVehicleSearch` traces and best genomes equal the direct
  `PopulationSearch.random/hillClimb/evolve` calls the vehicle-specific code made.

## Results (1000 evals x 12 seeds, read at 100/300/1000; `-Dafield.searchComparisonSweep=true`, ~13 min)

Medians of per-seed best; (n) = seeds of 12 beating random.

| genome / objective | evals | random | hillclimb | evolve | immigrants |
| --- | --- | --- | --- | --- | --- |
| LifeRule r=2, edge of chaos (2 soups 48², warmup 20, scored 30) | 100 | 0.412 | 0.423 (7) | 0.458 (9) | 0.429 (5) |
| | 300 | 0.426 | 0.470 (9) | 0.486 (9) | 0.470 (9) |
| | 1000 | 0.481 | 0.483 (7) | 0.491 (6) | 0.498 (8) |
| Lenia r=6, soliton (48², 90 steps) | 100 | 1.686 | 1.673 (6) | 1.645 (5) | 1.715 (8) |
| | 300 | 1.790 | 1.919 (9) | 1.747 (4) | 1.907 (8) |
| | 1000 | 1.899 | 2.197 (11) | 2.037 (8) | 2.135 (8) |
| LSystem default, dimension 1.7 | 100 | 0.239 | 0.240 (6) | 0.237 (7) | 0.241 (6) |
| | 300 | 0.242 | 0.245 (10) | 0.246 (10) | 0.247 (9) |
| | 1000 | 0.247 | 0.247 (6) | 0.248 (8) | 0.247 (7) |

Readings: Lenia is the only genome where search clearly wins (hill climbing best, 11/12); plain
evolution loses to random at 300 there. Life rules: small median margins, heavy right tail (one or
two seeds per searcher > 0.95; random max 0.685, random upper quartile 0.583 above every searcher
median). L-systems: the objective saturates at ~0.25 for every arm, random included — it cannot rank
methods.

## Traps

- The objective must be thread-safe under `parallel = true` (all alife/vehicle objectives build their
  own world per call).
- `fitnessRange()` includes the first-evaluation quartile band, so an objective with an
  `EXTINCT_SCORE` floor (-1) squashes the plot; pass `yRange`.
- Running the sweep while editing tracked files trips `checkTestTreeWrites` (it saw the new transcript).
