# PatMuse: weighted sequencer with loop lock and improvisation

Source: `src/jvmMain/kotlin/afield/audio/synth/PatMuse.kt`; tests `PatMuseTest`; user docs in
`public/docs/pattern_language.md` ("Weights, then lock what you liked"); demo transcript
`dev_docs/agents/transcripts/muse_sequencer.yaml`; audio scenes `muse-free`,
`muse-locked-improvised` in `PianoDocs`.

Method after the norns script Muses (https://llllllll.co/t/muses/75854, license unstated; no code
taken, built from the forum description only).

## Design decisions

- Output is `PatPattern<String>` (MIDI atoms, `gain`, `channel`, `ornament`, `improvised`
  annotations), so `Piano.play`, `showPatRollOf`, `notes()` and transforms need nothing new. No
  parallel pattern system.
- Draws are `splitmix64` hashes of (seed, channel name, event index, purpose), the same hashing as
  `degradeBy`/`sometimesBy`. The stream is a memoised prefix walk (event i starts when i-1 ends);
  a version stamp over seed, scale, root and the three tables clears it on any change, so the
  stream is a pure function of seed and weights.
- Lock copies events, scale and root: weights moved after a lock change the free stream only.
- `PatLoop.pattern()` snapshots the loop (`copy()`). Without that, a "plain" pattern taken before
  setting `improvise` silently improvised too (seen in the first docs capture).
- Improvisation per (seed, pass k, position j): p = `improvise` → velocity jitter, then 0.7 chance
  of an ornament from the eligible set (length >= 2 steps; passing needs next note >= a third
  away). Ornaments decorate the top note of a chord; other notes sustain. Grid: 4 ticks a step.
- `afield.math.oracle.Improviser` was considered and not reused: it recombines a continuous
  sample memory, not discrete weighted events.

## Not done / next

- Weights change on generation, not "from now on" while playing: a live `respond`-driven player
  that regenerates only the future would need the stream to stop clearing its past.
- No drawn-histogram control; weights are slider rows (`ControlGroupBuilder.weights`).
- Slider drags in the demo were not driven by automation; the boxes were run and their captures
  inspected.
