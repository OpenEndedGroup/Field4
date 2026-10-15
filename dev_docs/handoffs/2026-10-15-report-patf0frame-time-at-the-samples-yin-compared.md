# Report PatF0Frame time at the samples YIN compared

- **Handoff:** `0977bfba-102d-49e1-9137-19ab2a351130` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_fn_sound_roundtrip/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close 0977bfba "what happened"`

`public/docs/chain_fn_sound_roundtrip.md` carries a workaround note:

> `patF0Frames` attributes each estimate a little late, by an amount that depends on the pitch: nothing at `minHz`, and up to half of `tauMax` well above it. ... When timing matters to the sample, centre a frame at `startSample + (spanSamples − tauMax + sampleRate / hz) / 2`, the way `FnSoundDocs` does.

The body text above it also explains that `PatF0Frame.centerSample` is `window + tauMax` centred while YIN only compared `window + tau` samples.

Fix in code: make `PatF0Frame.centerSample` (or a new, clearly named field) report the centre of the samples actually compared at the chosen `tau`, and drop the manual re-centring in `afield.audio.synth.FnSoundDocs`. Recapture `chain-fn-delays`. In the same change, delete the `!!! note "Reading a pitch track against time"` and rewrite the "second delay" paragraph to say the pitch track is reported at the instant it measured.
