# Eigendrum (afield.audio.modal) — method and numbers

Source idea: eigendrum.com (non-commercial source-available; no code copied — textbook FEM).
Code: `src/jvmMain/kotlin/afield/audio/modal/Eigendrum.kt`, `EigendrumDocs.kt`;
test `src/jvmTest/kotlin/afield/audio/modal/EigendrumTest.kt`; page `public/docs/eigendrum.md`;
starting point `dev_docs/agents/transcripts/eigendrum.yaml`.

## Method
- Mesh: rim subdivided at spacing h (corners kept), interior triangular lattice of spacing h
  (points closer than 0.45h to the rim dropped), JTS Delaunay, triangles kept if centroid inside
  (even-odd ray cast). Not conforming: a very concave rim with coarse h could lose a sliver.
- P1 elements; mass = ½(consistent + lumped) — the blend cancels the leading O(h²) error
  (consistent over-, lumped under-estimates). Measured: square λ errors 3e-5..1.3e-3 at h=0.04.
- Solver: free nodes sorted by (x, y) → band; banded Cholesky of K; subspace iteration
  (p = max(k+8, 2k) vectors) with Rayleigh–Ritz (small Cholesky + cyclic Jacobi), stop at
  1e-10 relative change.
- Strike: aᵢ = φᵢᵀ M g, g a nodal Gaussian. Synthesis: recursive damped sinusoid per mode,
  f = f₀√(λᵢ/λ₁), σ = α + β(fᵢ/f₀)², peak-normalised to 0.9.

## Numbers (EigendrumTest, 2026-11-07)
| case | h | computed | exact | rel |
|---|---|---|---|---|
| square λ₁ | 0.04 | 19.7399 | 19.7392 | 3.4e-5 |
| square λ₅ | 0.04 | 98.570 | 98.696 | 1.3e-3 |
| disk λ₁ | 0.05 | 5.78113 | 5.78319 | 3.6e-4 |
| disk λ₂,₃ | 0.05 | 14.6758/14.6763 | 14.6820 | 4e-4 |
| GWW A vs B, first 10 | 0.06 | — | — | max 4.6e-4 |
| GWW λ₁ vs 4×Driscoll 2.53794 | 0.06 | 10.1906 | 10.1518 | 3.8e-3 |

Driscoll's λ₁ = 2.53794 matched only after ×4, i.e. his drums are half the linear size of the
Moler vertex set used here (`KacPair`); inferred, not checked against his paper's figure.
Timings: square/disk ≈0.13–0.17 s, Kac pair (two drums) 0.55 s.

## Known gaps
- Degenerate pairs come back as arbitrary mixtures (rotated pictures).
- Live engine: sound is a rendered buffer shown as a WAV player, not a `FieldAudioEngine` voice;
  `ResonatorBank` (analysis bank) was not reused — it is a tracking analyser, not a modal voice.
- Drawing-layer path (`notation.strokes`) ran only on its fallback in the demo; no real stroke
  was drawn during verification.

## Status of the page's captures
`public/docs/eigendrum.md` fences have never been captured: the 2026-11-07 run was refused by the
docs power gate (battery 66% < 80%, no AC). Recapture on AC with
`FIELD_DOCS_RUNNER_MODE=always uv run --project . --group docs python tools/build_zensical_docs.py --page eigendrum.md`.
The same box code was run through the backdoor and its screenshots inspected; the five audio
scenes were rendered offline via `EigendrumDocs.renderWav` (2.0–2.5 s, 44.1 kHz, decaying).

## Live voice (ModalDrum, added later 2026-11-07)
- `ModalDrum` is a `Signal`: one damped complex phasor per mode. `strike`/`retune` run on the
  caller's thread (projection, bank build) and post through a ConcurrentLinkedQueue /
  AtomicReference; the audio thread drains them at block start — no Runloop code touches DSP state.
  Strike amplitudes are divided by 2πr² so aᵢ ≈ φᵢ(x, y).
- `morph = shapes`: the audio thread interpolates frequency ratios by mode rank (triangle wave,
  per block). Rank-following means crossings swap roles.
- Verified in the real engine: launching ed_live, `ModalDrum.latest.framesRendered` rose
  29,696 → 177,152 over ~3 s (≈48 kHz) with block peaks ≈0.1; ed_morph peaks 0.19–0.49.
  Unit test renders blocks via SynthContext: silent until struck, peak 1.18 at gain 0.2 (default
  now 0.15), still ringing after retune.
- Real drawing-layer path: a box wrote `notation { stroke(fline) }`; ed_draw then printed
  "drum from your stroke: 764 nodes" and drew its modes (not the fallback).

## Raman's harmonic drum and inverse design (2026-11-08)

Code: `HarmonicDrum.kt` (density, labels, harmonicity, sweep), `DrumDesign.kt` (`designSyahi`,
`designShape`, `PolarShape`, `polarMorphed`, `energyShare`); test `HarmonicDrumTest`; page sections
"Raman's harmonic drum" and "Designing a drum" in `public/docs/eigendrum.md`; starting point
`dev_docs/agents/transcripts/harmonic_drum.yaml`.

### Method
- `DrumMesh.density` (per triangle, optional) multiplies the element mass only; K is unchanged.
  `withDensity { x, y -> }` averages over 21 points per triangle so a sharp load edge gives the
  area-weighted mix (keeps the sweep smooth instead of staircased).
- Strike projection stays φᵀF with the *unweighted* load vector (a mallet is a force, not a mass).
- `circularLabel`: angular order n = argmax over n of the energy in the nθ Fourier harmonic
  (lumped-mass weighted, binned in r); circles = sign changes of that harmonic's radial profile
  (projected on the strongest bin's phase, 8% floor) + 1 for the rim. Right on every disk we tried
  (bare, composite, smooth tanh), including degenerate pairs.
- Harmonicity: the 15 lowest eigenvalues (sorted, pairs counted twice) against Raman's multiset
  1,2,2,3,3,3,4,4,4,4,5,5,5,5,5, rms cents after the least-squares common scale (log domain).
  This is not Sathej & Adhikari's Q (nearest integer, scaled by the first overtone); their optimum
  (σ = 2.57 → density 6.6, k = 0.492, ξ = 0.091) scores 46 cents on ours.
- Gradient: for mass-normalised φ, ∂ ln λ / ∂ ln ρ(patch) = −Σ_patch ρₜ φᵀM̂ₜφ (the energy share),
  checked against finite differences to 1e-4 (test). Used as the exact Jacobian in `designSyahi`.
- `designShape` morphs one unit-disk mesh radially (ρ,θ) → ρ r(θ), so λ is smooth in the Fourier
  coefficients and finite differences are clean; remeshing per evaluation made λ jump by the
  discretisation error. k = 1 terms stay fixed (≈ translation).

### Literature checked (PDFs read 2026-11-08)
- Raman 1935 (*Proc. Indian Acad. Sci.* A 1, 179): nine modes → five tones; tone 3 = one nodal
  circle + two diameters [(0,2)+(2,1)], tone 4 = (1,2)+(3,1), tone 5 = (4,1)+(2,2)+(0,3) (the last
  from "experiments made at Calcutta in 1919 with a fine large Mridanga"). Superposition sand
  figures: circle → ellipse → two parallel lines → hyperbolas → two diameters. Paste "stepped down
  by three, five or seven stages towards the margin". No numeric ratio table in Raman 1935.
- Sathej & Adhikari, JASA 125, 831 (2009), arXiv:0809.1320, Table I: measured (normalised so
  ψ11 = 2): ψ01 1.03, ψ11 2, ψ21 3, ψ02 3, ψ31 4, ψ12 4, ψ03 5.04, ψ41 5.03, ψ22 5.08; composite
  (Ramakrishna & Sondhi, σ = 3.125 i.e. density 9.77, k = 0.4) 1.0309, 2, 3.0412, 3.1546, 4.0928,
  4.2268, 4.9794, 5.1134, 5.3093. Note their σ is the square root of the density ratio. Their
  "smooth" column for (3.125, 0.4, 0.091) matches ours only when sorted within each tone: our
  labeller puts (0,2) below (2,1) and (1,2) below (3,1) there, so their column appears to be
  assigned by rank.
- Ashbaugh & Benguria, Annals of Math 135, 601 (1992): λ₂/λ₁ ≤ (j₁₁/j₀₁)² for every uniform
  Dirichlet membrane, equality only for the disk ⇒ f₂/f₁ ≤ 1.5933.

### Numbers
| case | h | result |
|---|---|---|
| bare disk vs Raman target | 0.05–0.08 | 190.6–190.7 cents rms (worst 556) |
| composite (0.4, ×9.77), FEM families vs exact | 0.05 | max rel 0.70% ((4,1)); 0.56% at h=0.04 |
| composite vs Raman target | 0.05 | 35.6 cents (worst 65.7) |
| sweep 11×11, k 0.2–0.7 × μ 1–30 log | 0.07 | best (0.40, 10.8) 35.4 cents; valley to (0.45, 7.7) 37, (0.5, 5.5) 45 |
| 1-layer syahi fit (edge 0.04, cap 16) | 0.06 | 28.6 cents, r 0.419, +8.14 |
| 2-layer | 0.06 | 20.8 cents, r 0.335 +3.82, 0.472 +7.50 |
| 3-layer | 0.06 | 18.1 cents (two layers merge at 0.459) |
| uniform outline fit vs Raman target, 4 starts, 25 its | 0.08 | 188.0–188.2 (3 starts), 238.9 (ellipse start, stuck) |
| heart → 4:5:6 (3 harmonics) | 0.07 | 0.0 cents in 6 iterations, 14 s |
| heart → 4:5:6:8 (6 harmonics) | 0.07 | 59.6 cents (stuck) |

### Dead ends
- Free-form loading on hat knots in log density: the unbounded fit drove one ring's density to
  ~0 (a massless ring) and reached 16.8 cents; with load ≥ 0 (ρ = 1 + p²) it stalled in local
  minima (41–123 cents depending on the knot count and start). Replaced by stacked layers.
- Unbounded layer loads run to 1e10: past ~20× the ratios stop depending on the mass (the heavy
  limit Sathej & Adhikari also note), and a 2-layer syahi in that limit scores 15 cents. Hence the
  16× cap (top of S&A's range for real dayans).
- Fitting outlines to Raman's target with LM: every start walks back towards the circle (the
  Ashbaugh–Benguria bound makes the octave unreachable); the page shows a random-shape sweep instead.

### Where to go next
- Air loading: the kettle raises modes with net volume change (the (0,n) family most); a
  boundary-coupled air cavity term would move the fundamental, the one mode still sharp here.
- Eccentric loading (the bayan): `syahi(cx = ...)` already offsets the paste; labels and the
  harmonicity metric need a non-circular mode identification (by overlap with the concentric modes).
- Shape and paste together: `designSyahi` on a `polarMorphed` mesh, for a non-circular drum that is
  also harmonic (fit both coefficient sets at once).
- Hadamard shape gradient: −∫(∂φ/∂n)² V·n ds from boundary triangles, to replace the finite
  differences in `designShape` (cost now 2 × params solves per iteration).
- Targets beyond the third note: more harmonics or a different parameterisation for 4:5:6:8.
