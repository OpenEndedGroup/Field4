# Nonlinear ZDF filters and ADAA shapers (after evieve)

Landed by the 2026-11-18 nightly. User-facing docs: `public/docs/spectral_examples.md`
§ "Saturation without aliasing, and filters that sing". Code: end of
`src/jvmMain/kotlin/afield/audio/synth/Filters.kt`. Tests: `NonlinearTptTest`. Clips:
`TptFilterDocs` scenes `tanh-naive`, `tanh-adaa`, `zdf-ladder`, `zdf-svf`, `stepped-raw`,
`stepped-smoothed`. Demo: `dev_docs/agents/transcripts/nonlinear_filters.yaml`.

## What is there

- `AdaaShaper(shape, drive, order)`: ADAA over `AdaaShape.TANH | HARD_CLIP | CUBIC`. Each shape
  carries `f`, `f1`, `f2`. Order 2 (Bilbao et al. 2017) for HARD_CLIP and CUBIC only: tanh's second
  antiderivative needs the dilogarithm. Fallback thresholds `EPS1 = 1e-5`, `EPS2 = 1e-3`
  (second order divides twice; with drive ≈ 10 the cancellation error at 1e-4 reached ~1e-5).
- `ZdfLadder`: one tanh at the loop input (Zavalishin ch. 6), linear TPT stages, loop solved by
  Newton on y4 (the residual is monotone, derivative ≥ 1). k = 4·resonance, resonance ≤ 1.2.
- `ZdfSvf`: damping `2R·bp + 2(bp − tanh(d·bp)/d)`, R = 1 − resonance ≥ −0.2, Newton on bp
  (monotone for R ≥ −0.2 because 2g ≤ 1 + g²). The cubic-like term is what bounds
  self-oscillation; a plain tanh on the damping with negative R would grow without bound.
- Both filters add a ~1e-6 LCG noise floor so they self-oscillate from silence.
- `Smoother(time, mode)` and `Param.smoothed(seconds)`. Constant-mode `Param.base` writes jump at
  block boundaries; supplier-mode Params already ramp linearly over one block (5 ms at 256).

## Measured (NonlinearTptTest)

- Small-signal responses vs bilinear prototypes: ladder LP4/HP4 ≤ 0.001 dB (res 0, 0.5, 0.9),
  SVF LP/BP ≤ 0.001 dB, HP ≤ 0.008 dB, over 30 Hz–18 kHz where the response is above −60 dB.
- Self-oscillation from silence at 220 Hz / 1 kHz / 5 kHz: ladder (res 1.1) at the cutoff ±0 Hz,
  peak ≈ 0.146; SVF BP (res 1.05) within 2 Hz, peak ≈ 0.463.
- Alias power (sum of non-harmonic bins re fundamental, 4.1 kHz sine, drive 8): tanh naive −17.0 dB,
  ADAA −25.0 dB; hard clip naive −14.9, ADAA1 −23.3, ADAA2 −30.6 dB. Worst single alias: tanh
  −19.5 → −25.6 dB. Less than the "~20 dB" folklore: the loudest aliases come from harmonics just
  above Nyquist, where the ADAA kernel's attenuation is weakest.
- Docs clips: first-10 ms peak never exceeds the later peak (no warm-up spike); zdf-svf starts at
  0.006 and builds up.

## Open

- ADAA tanh order 2 (needs Li2), ADAA inside the ladder loop (stages saturating per stage).
- Per-stage tanh ladder variant (closer to evieve's "full" ladder) with a vector Newton solve.
