# evieve-family filters (2026-11-15)

Source: https://github.com/steadykammer/evieve (Pete Dowling, GPL-3.0; some embedded files carry
their own licence, e.g. the Wurtz diode ladder is MIT). Its filters live as GenExpr inside
`code/evi_gendsp/**.gendsp` codebox JSON plus `code/evi_genexpr/evi_libraries/*.genexpr`.

Added to `src/jvmMain/kotlin/afield/audio/synth/Filters.kt`, written from the cited methods
(Zavalishin, Pirkle) rather than transcribed: `TptOnePole` (LP/HP/AP), `TiltEQ`, `Korg35` (LP/HP,
tanh on the solved loop node), `Resonator` (constant-peak two-pole), and `impulseResponse`
(responses are its `fft(...).magnitudeDbOneSided()`, one bin per hertz over one second).

Facts worth keeping:
- Every Field filter already evaluates its Params per sample and `Param.mod` adds a Signal per
  sample, so "audio-rate modulation" needed no Param change.
- The existing `DiodeLadder` is a *linear* ZDF four-pole ladder (Zavalishin solve); it is not a
  diode topology. evieve's `evi_ladder_diode` (Wurtz) is the real one and was not ported.
- A TPT filter equals its analog prototype under the prewarped bilinear transform exactly, so
  `TptFiltersTest` can hold measured responses to ~1e-3 dB. Nonlinear filters are measured with a
  1e-4 impulse to stay in the tanh's linear range.
- Narrow resonator peaks need the peak frequency on the FFT grid and an impulse response long enough
  for the decay; one second at 1 Hz bins covers a 0.5 s ring at 1 kHz.

Not ported yet: Wurtz diode ladder, DFM, Buchla LPG, Weaver/Hilbert frequency shifters, crossovers,
the `evi_rcfilter` Newton-solved nonlinear one-pole.

Clips: `TptFilterDocs.renderWav` via the page's `audio=` fences; the response plots are
`showSignals` box fences captured by the docs runner. Demo: `dev_docs/agents/demos/evieve_filters/`.
