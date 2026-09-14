# `evolve` and `evolveVariety`: the measured runs

Displaced from `public/docs/functions_examples.md` on 2026-09-14. The page keeps the mechanism —
which mutation operator does what, and why `normalize` changes the metric — plus the two score
endpoints and the variety spread, because those three are asserted. What is here is the
iteration-by-iteration record, which nobody reading the page in order needs.

Everything below comes from `afield.examples.functions.EvolveWorkedExample` under its committed
seeds: `rngSeed = 3L`, `iterations = 120`, `lambda = 8`, `matchFitness(target, samples = 160,
complexityWeight = 0.01)`, target `sin(frequency = 1.0, amplitude = 0.5)`, seed
`cos(frequency = 1.0, amplitude = 1.0)`.

```bash
./gradlew jvmTest --no-daemon --tests 'afield.examples.functions.EvolveWorkedExampleTest'
```

## Asserted, and therefore safe to quote on a page

`EvolveWorkedExampleTest.producesDeterministicImprovingRun` pins these:

| quantity | value | tolerance |
| --- | --- | --- |
| `run.initialScore` | −0.8421 | 5e-5 |
| `run.bestScore` | −0.1568 | 5e-5 |
| `variety.meanPairwiseDistance` | 1.513 | 5e-4 |
| `variety.results.size` | `VARIETY_COUNT` | exact |

It also asserts `run.improved`, `bestScore > initialScore`, at least one accepted mutation, a
strictly positive variety spread, and determinism: a second `runExample()` reproduces both scores to
zero tolerance and the same `graphSignature()` on the evolved graph.

## Printed, not asserted

`EvolveWorkedExample.main` prints `acceptedMutations` and `evaluations`; no assertion covers either,
so they are a record of a run rather than a contract. Under the seeds above: **12** accepted
mutations and **961** total evaluations, the last accepted improvement landing at iteration 23 of
the 120 the search was allowed.

The accepted-mutation trace, same run:

| iteration | accepted mutation | score (−RMSE − penalty) |
| --- | --- | --- |
| 0 (seed) | — | -0.8421 |
| 1 | substituteOp | -0.4024 |
| 3 | jitter | -0.3572 |
| 4 | nudge | -0.3209 |
| 5 | nudge | -0.3148 |
| 7 | prune | -0.3048 |
| 8 | prune | -0.2948 |
| 13 | prune | -0.2844 |
| 16 | prune | -0.2744 |
| 17 | jitter | -0.2600 |
| 18 | nudge | -0.2252 |
| 19 | prune | -0.1718 |
| 23 | prune | -0.1568 |

The first and last rows are the two asserted endpoints; the eleven between them are not guarded and
will move if the mutation palette or the acceptance rule changes. That is the reason they are here
and not on the page.

The page also used to claim the figure re-renders to a "byte-identical SVG". Nothing checks that —
the determinism assertions cover the scores and the graph signature, not the rendered output — so
the claim is not made anywhere now.

## Open

- No assertion covers the shape of the trace, only its endpoints. A guard on "the first accepted
  mutation is a `substituteOp`" would pin the one step the page describes as the structural leap,
  and would be cheap. Not written.
- The `normalize = false` counterpart of the variety run is described on the page in qualitative
  terms because it has never been measured under a fixed seed. Measuring it would let the page say
  how much larger the unnormalised spread is.
