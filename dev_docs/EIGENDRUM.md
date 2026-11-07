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
