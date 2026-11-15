# Cerf diagrams (`afield.geometry.topology.tracking.CerfDiagram`)

Built on `FeatureTracker` over all three `CriticalType`s; no new critical-point classifier (the
Morse–Smale native library classifies). Births/deaths are paired through the frame's
`MorseSmaleComplex.persistencePairs` (saddle <-> extremum partner ids), so a pair event needs no
spatial heuristic. Swaps are value-order flips between consecutive frames for tracks alive in both.

Findings:
- The first frame is the starting state; counting its features as births was the first test failure.
- Grid-edge minima/saddles appear as flat lines; they are real critical points of the cut-off field.
- `GRAY_SCOTT_MITOSIS` makes all its events in the first ~30 steps from a seed square (sample every
  3–4 steps); afterwards it is static. `GRAY_SCOTT_WORMS` gives hundreds of events and ~10^4 swaps
  over 100 frames: use `showSwaps = false` and a larger `minPersistence`.
- The docs' complex figure: `GRAY_SCOTT_SPOTS` from `grayScottSeed(96, 96, 3, 10, seed = 7)`, 120 frames
  x 40 steps, blur 1.5, persistence 0.05: 114 births, 35 deaths, 12,734 swaps, ~1 s to record. Spots
  keep splitting for the whole run (the front has not filled the torus at frame 119).
- A slider's `controls` channel makes a box's output panel full width, so a box still with a slider is
  mostly empty panel and over the 2 MB asset budget; `trim` cannot remove it (it is not a uniform border).
  The docs figure shows one cursor; the demo keeps the slider. `trim` is not in the capture digest, so
  adding it alone does not recapture.
- Time-sampled, not the exact PL space-time construction of Dhar, Chakraborty, Natarajan
  (arXiv:2507.00725). Exact PL Cerf diagrams (vertex diagram, lower-link homology crossings) and
  their comparison measure are the natural next step.
- `build/field-morse-smale/` can vanish from a worktree mid-session (a build clean); rebuild with
  `./gradlew buildMorseSmaleNative` before trusting "not built" errors.
