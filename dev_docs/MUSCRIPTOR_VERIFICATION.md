# MuScriptor: what has actually been checked

The integration record behind `audio_ai_muscriptor.md`. The page states the limitations a caller has
to know about; this file is what was run, what it found, and what changed as a result.

## The first headless job

The small checkpoint completed a headless job on an 8.192-second Sonic Pi recording. The first
adapter returned 48 notes in about 2.6 seconds; MIDI, MessagePack, preview and repeat-request
caching were all verified.

Comparison against the waveform and spectrum found useful pulse timing and support for several
pitches, but did **not** establish an accurate score. Two caveats on that inspection, both of which
are on the page as limitations rather than here:

- piano and bass labels on electronic sounds are model hypotheses, not source instrumentation;
- the archived filename alone does not prove which version of the Sonic Pi code made the audio.

## Out-of-range notes, and worker v2

That inspection also found two onsets after the recording ended and five offsets past it.

Worker v2 discards out-of-range notes and clips surviving offsets to the source duration, in both
the numerical artifacts and the performance MIDI. Its new revision invalidates old cache entries.

Re-running the same recording with v2 produced 46 notes. All numerical note boundaries and all MIDI
events were verified to lie within 8.192 seconds, with matching note counts between the two
artifacts. Performance MIDI uses source-relative timing, with no added bar-alignment delay.

## Not exercised

MuScriptor 0.3.0 does not expose beat quantization, so the optional quantized artifact is absent
from every result so far and the handle for it is untested against real output.

None of the above is a guard. There is no test in the build that re-runs a checkpoint; the opt-in
`./scripts/test-muscriptor-worker.sh --model small` is the way to reproduce any of it.
