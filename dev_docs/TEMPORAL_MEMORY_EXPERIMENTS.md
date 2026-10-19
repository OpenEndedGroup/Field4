# Timestral and Variable Markov Oracle: first experiment report

2026-10-19. These are the experiments from the "Field4 experiments: Timestral and Variable Markov Oracle"
proposal, run on Marc's M-series laptop, CPU only. The public page is
`public/docs/temporal_memory.md`; the code is `afield.math.oracle` and `afield.math.temporal`.

## What was built, against the proposal

| Proposal item | Where | State |
|---|---|---|
| Map existing facilities first | — | No sequence-memory or trajectory type existed. `math/matrixprofile` was the closest relative; plotting reuses `showSignals` / FLine `lines`; Python uses uv as the `cv`/`voice` groups do; lifetime uses `RunScope.own` |
| Shared recording type with episodes, scaling, velocity features | `oracle/Trajectory.kt` (`Trajectory`, `GestureFeatures`) | done; angles are not wrapped, so encode them as sin/cos |
| Async session contract, revisions, bounded queue, underflow policy | `temporal/TemporalSession.kt` (`TemporalSession`, `RecedingHorizon`) | done; one request in flight, and the newest history is used at send time |
| Loop and nearest-history baselines | `Improviser(policy = Loop / NearestHistory)` | done; the linear-AR baseline is not built |
| Reference VMO, reproduced | `VariableMarkovOracle` (Kotlin port), `VmoReferenceTest`, `tools/vmo_reference_fixture.py`, `pyproject` group `vmo` | sfx, lrs and summed IR identical to `vmo==0.30.5` on 11 cases |
| Episode boundaries, gesture-aware matching, branching with phrase persistence, scored jumps, join correction with rejection, provenance, takeover/release | `Improviser` | done; the tempo, translation and amplitude transforms are not built |
| Timestral wrapper, median rollout, quantiles, two sizes | `TimestralSession`, `python/fieldtemporal/timestral_worker.py` (PEP 723, its own uv env, framed binary protocol) | done; there is no residual sampler, deliberately |
| Known-future covariate test | `TimestralSessionTest.knownCovariateRelationshipIsUsed` | passes |
| Tutorial | `public/docs/temporal_memory.md` | done |
| Hybrid | — | not attempted, as the proposal sequences it last |

License note: Field is AGPL-3.0+, so the GPL-3.0 reference is used directly: a port, plus a pinned
dependency group. Timestral code is Apache-2.0; its weights are under Mistral's Modified MIT and are
downloaded only by `TimestralSession.install`.

## Harness

`AFIELD_TEMPORAL_EXPERIMENTS=1 AFIELD_TEMPORAL_MODELS=mistralai/Timestral-28M,mistralai/Timestral-209M
./gradlew jvmTest --tests 'afield.math.temporal.TemporalExperiments'` writes the CSVs and
`summary*.tsv` under `build/temporal-experiments/`. The rate is 20 Hz, each performance is 5 minutes,
and the seed is 1. Each Timestral run feeds the last episode as history.

The fixtures are: coupled oscillators (0.5 Hz with a 0.05 Hz swell, plus 0.75 Hz; 45 s); three phrase
episodes (rise / hesitation / burst / pause; about 11 s each); a figure-eight (40 s, self-crossing).

The measures are the standard deviation over the first and last quarter, the dominant frequency in
the last quarter, and the "speed", which is the mean |Δ| in the last quarter divided by the demo's.

## Results

The last-quarter "std" is shown as a fraction of the demo's.

| fixture | run | last-quarter std | peak Hz (demo) | speed | notes |
|---|---|---|---|---|---|
| oscillators | Loop | 1.0 / 1.0 | 0.49, 0.75 (0.50, 0.75) | 1.0 | restart corrections up to 2.0 σ |
| oscillators | Oracle | 1.0 / 1.0 | 0.50, 0.75 | 1.0 | 243 segments, coverage 0.97, reuse 0.02 |
| oscillators | 28M receding | 0.11 / 0.16 | 0.62, 0.74 | 0.14–0.17 | washes out |
| oscillators | 209M receding | 0.00 / 0.90 | 0.46, 0.76 | 0.00 / 0.91 | swelling channel dies; steady channel survives |
| phrases | NearestHistory | 1.0 | 0.17 (0.19) | 0.93 | coverage 0.68, reuse 0.51 |
| phrases | Oracle | 1.0 | 0.16 | 0.93 | coverage 0.98, reuse 0.25 |
| phrases | 28M / 209M receding | ≈0 | — | ≈0 | both settle into the pause |
| figure8 | Oracle | 1.0 / 1.0 | 0.33, 0.17 | 1.0 | coverage 0.92 |
| figure8 | 28M receding | 0.10 / 0.33 | 0.32, 0.17 | 0.09 / 0.32 | washes out |
| figure8 | 209M receding | 1.24 / 1.09 | 0.33, 0.17 | 1.23 / 1.06 | sustained, slightly growing |

A single long forecast (4096 samples, one call) decays to the mean for both sizes on every fixture
(28M: std → 0.01; 209M: figure-eight std → 0.6 of the demo, the others → 0). This is the
documented median feedback across 1024-step blocks, and a long forecast is a different test from
the receding loop.

Replanning latency, 20 s × 2 channels of history, 4 s horizon, symmetric averaging on:

| model | p50 | p95 | warm load |
|---|---|---|---|
| 28M | 31–33 ms | 36–66 ms | 0.5–0.9 s |
| 209M | 148–253 ms | 372–660 ms | 1.4–2.5 s (94 s on first download) |

The 209M misses the proposal's nominal 250 ms p95. With `commit = 1 s` there were no underruns in
lockstep runs. Live, it needs at least about 0.7 s of lookahead.

The known-future covariate test (A2): `y` is `x` delayed by 1 s; `x` is irregular (AR(1)-smoothed noise).
The 5 s forecast of `y` has MAE 0.198 with `x`'s future supplied and 0.435 without it, against a signal std of 0.725.
Timestral picks up the lag from history alone.

## Decisions against the proposal's criteria

**VMO: keep.** It stays coherent and varied over 5 minutes from under a minute of material, and
provenance (`segments`, `source`) explains every jump. Takeover recovery works through masked cueing.
Against plain nearest-history matching it uses far more of the memory on phrase material (0.98 vs
0.68) with half the reuse. A bug found here and fixed: the episode-end fallback cue accepted joins
needing a 3 σ correction. Every jump candidate now passes `maxCorrection` (default 1 σ), or the
improviser holds.

**Timestral: keep as a dynamics prior and uncertainty display; reject the generative claim for the
small models.** The 28M washes out within a minute on everything. The 209M sustains smooth
periodic motion (the figure-eight) through 5 minutes of feedback, but loses an amplitude-modulated
channel and collapses bursty/pausing material to silence. The known-future covariate behaves
as advertised and is the most useful steering control. The quantile fan is honest and worth
keeping regardless of the generative results.

**No residual sampler.** Proposal A3's residual-passage mechanism was not built. Quantiles are
per-step marginals, and the page says so plainly, using the same pointwise-versus-sample distinction as
`gaussian_processes.md`.

## Not done yet

- The linear AR baseline, and spectra and lag/phase measures beyond peak frequency.
- Blind listening/viewing comparisons.
- VMO tempo, translation and amplitude transforms; multiscale matching; waypoint ranking.
- Live (non-lockstep) latency under load with underrun counts; running `RecedingHorizon` from a real
  `frames {}` loop with a performer.
- Progressive memory (adding an episode during playback); the oracle is rebuilt, not appended.
- The hybrid (VMO phrases scored by Timestral).

## Hybrid: the oracle proposes, Timestral judges (branch `claude/temporal-hybrid`)

`Improviser(judge = ForecastJudge(session, oracle))`. At each jump decision, every suffix-link
candidate's next 2 s, and staying put, is rated by mean pinball loss against a Timestral forecast
of the played output. The forecast is refreshed every 1 s, with 20 s of history and a 4 s horizon. The
loss enters the score as `judgeWeight × loss / mean loss`. Same fixtures, 5 min each, seed 1.

| fixture | run | reuse | coverage | judged / changed / stayed | p95 forecast |
|---|---|---|---|---|---|
| oscillators | Oracle | 0.02 | 0.97 | — | — |
| oscillators | +28M w1 / w3 | 0.09 / 0.10 | 0.98 / 0.96 | 343/229/143, 332/231/129 | 34–66 ms |
| oscillators | +209M w1 / w3 | 0.06 / 0.02 | 0.97 / 0.95 | 335/218/132, 338/218/137 | 163–201 ms |
| phrases | Oracle | 0.25 | 0.98 | — | — |
| phrases | +28M w1 / w3 | 0.45 / 0.45 | 0.87 / 0.81 | 424/227/156, 475/262/146 | 30 ms |
| phrases | +209M w1 / w3 | 0.44 / 0.46 | 0.87 / 0.77 | 413/203/135, 497/263/145 | 267–311 ms |
| figure8 | Oracle | 0.21 | 0.92 | — | — |
| figure8 | +28M / +209M | 0.16–0.19 | 0.92–0.93 | ~387/265/197 | 45–257 ms |

The spread, frequency and speed statistics are unchanged from oracle-only on every fixture (within 0.03 of
the demo), so nothing collapses. As expected, every sample is still played from memory.

The judge has a real effect: it changes the most likely choice in 55–70% of the decisions it scores,
and picks "stay" in about 40% of them. But on the phrase material the effect runs the wrong way for
the stated goal. Reuse rises from 0.25 to 0.45 and coverage falls from 0.98 to 0.77–0.87. A
pinball-loss judge rewards the continuation the forecaster finds most *predictable*, and on
phrase material that is what was just played. On the periodic fixtures it is neutral.

Next to try: the judge as a *veto* only (reject candidates whose loss is several times that of staying)
rather than a preference, so it removes implausible joins without pulling towards repetition. Or
pair it with a stronger novelty term. Either needs a measure of "better dynamics" that is not the
judge's own loss; the statistics above cannot show an improvement, because oracle-only already matches
the demos on them.
