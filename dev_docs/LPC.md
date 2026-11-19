# Linear predictive coding (LPC)

Source: `src/jvmMain/kotlin/afield/audio/synth/Lpc.kt` (analysis, audio processors),
`src/jvmMain/kotlin/afield/geometry/path/FLineLpc.kt` (strokes), `LpcDocs.kt` (docs clips),
tests in `src/jvmTest/kotlin/afield/audio/synth/LpcTest.kt`. User docs: the "Linear prediction"
section of `public/docs/spectral_examples.md`; demo boxes in the third column of
`dev_docs/agents/transcripts/spectral_examples.yaml`.

## Design

- One Levinson-Durbin implementation, complex (`Lpc.complexLevinsonDurbin`); the real one passes a
  zero imaginary autocorrelation. Convention `A(z) = 1 + Σ a_k z⁻ᵏ`, residual `e = A·x`, update
  `a_i += k_m · conj(a_{m-i})`.
- Audio processors (`LpcVocoder`, `LpcWhiten`) run on the audio thread like any `SignalProcessor`:
  `LpcTracker` keeps a ring of `frameSize` samples, refits every `hop`, and the lattice filters
  interpolate reflection coefficients linearly across the hop. A lattice with `|k| < 1` stays stable
  under interpolation; direct-form coefficient switching does not guarantee that.
- Tracker autocorrelation is divided by Hann's mean square (0.375) so `gain` tracks unwindowed
  RMS, and multiplied by a Gaussian lag window (`exp(-½(2πi/1200)²)`, ~40 Hz at 48 kHz) so
  formant bandwidths do not collapse into whistles. The lag window is in samples, so at other
  sample rates its width in Hz scales.
- Vocoder level: the carrier is divided by the carrier tracker's prediction gain (whitened) or its
  frame RMS (`whiten = false`), then multiplied by the modulator's prediction gain. Dividing an
  unwhitened impulse train by its prediction error spiked: with one pulse in a 1024-sample frame the
  error is near zero (found in the robot clip, first 0.5 s at 20x the level of the rest). The first
  fit is also adopted directly rather than glided to from zero.
- Strokes: `FLine.lpc` resamples by arc length (dense `samplePoints` then linear walk), models the
  *steps* not the positions (positions are non-stationary; steps of a curl are a rotation), with no
  window, so the ends count.

## Not done / ideas

- Multipulse and CELP excitation search (the mission's stretch); `Lpc.quantizeReflection`
  (arcsine-domain) exists and is tested, but no page section yet compares bits against envelope error.
- Pitch detection to drive the robot voice at the source's own pitch (would make the "robot"
  clip a true LPC-10 style resynthesis).
