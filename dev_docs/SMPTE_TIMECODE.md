# SMPTE timecode (`afield.timecode`) — design notes

Added 2026-10-08 (nightly). User page: `public/docs/smpte_timecode.md`. Demo:
`dev_docs/agents/transcripts/smpte_timecode.yaml` (EXECUTE bucket). Tests:
`src/jvmTest/kotlin/afield/timecode/TimecodeTest.kt`.

## Shape
- `Timecode` stores a frame count; fields and labels are derived. Drop-frame conversion lives only
  in `Timecode.of` / `Timecode.fieldsOf` (17982 frames per 10 min, 1798 per dropped minute).
- `LtcEncoder` places bit edges at `round(k * samplesPerBit / 2)` from a running half-bit counter,
  so 29.97 renders never drift. Hard edges (no SMPTE 25 µs rise shaping) — fine for decoders,
  possibly harsh into some hardware readers; untested against hardware.
- `LtcDecoder`: DC blocker (20 Hz) → envelope peak tracker (50 ms decay) → hysteresis at 0.3 × envelope,
  transition time taken from the interpolated zero crossing → interval classification against a
  tracked bit period (short < 0.75 T). The period is learned from the first ≥ 32 intervals but only
  once both clusters (half/whole bit) are present: a frame of zeros (00:00:00:00, user bits 0)
  shows only whole bits for its first ~60 intervals, and the first version guessed `2 × lo` there and
  decoded nothing at all at 25 fps. Gaps > 1.6 T drop sync; > 4 T re-learn.
- False-sync rejection: the polarity bit makes every genuine frame's count of ones even, and the 80
  bits must span 80 × T within −20 %/+25 %. Without these a DC step at the start produced a garbage
  frame that passed BCD validation.
- Known costs: the first frame is spent learning; the last frame of a finite buffer is not reported
  (its last bit ends on a transition that never comes). No `flush()` yet.
- Rate: LTC carries only the drop flag. Without a hint the decoder picks 24/25/30 from the measured
  period, which is wrong under ±4 % speed error — pass the rate.
- MTC: `MtcDecoder` adds 2 frames on a completed forward cycle (subtracts 2 in reverse — the reverse
  semantics follow the common reading of the spec and are not checked against a real device).
- `TimecodeClock` anchors on each frame and extrapolates on `System.nanoTime` at the measured speed;
  followed sources freewheel `freewheelSeconds` then hold.

## Not done
- No live audio-input hook: nothing yet feeds `feedLtc` from a sound card. The natural owner is the
  audio input path of `FieldAudioEngine` (audio thread, per AGENTS.md "Runloop vs audio loop").
- No LTC generator as a `Signal` for the synth graph, no MTC sender scheduler (the caller sends
  quarter frames every two frames itself), no keyframer chase-lock.
- Not verified against hardware (desk / video server / DAW). MIDI paths verified only in-process.
