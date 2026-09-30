# Add an offline audio renderer for the Boolean network music examples

- **Handoff:** `e08b3a1e-ec13-48b6-9cec-633b058acd58` (project)
- **Filed from:** https://openendedgroup.com/field4/boolean_network_music_examples/
- **Filed by:** agent:claude, 2026-09-30 20:48
- **Close with:** `python tools/docs_notes.py handoff-close e08b3a1e "what happened"`

## The notes that led here

- `21983d5d` (public/docs/boolean_network_music_examples.md:5): Some rendered audio examples would be good
  > Boolean networks become musically useful once their states acquire time. Give each network step a duration and the state stream can be read as notes, labels, triggers, and sample hits. The bridge lives inside afield.four.synth.

## What was asked

Note 21983d5d on `public/docs/boolean_network_music_examples.md` (line 5, the page's opening
paragraph): "Some rendered audio examples would be good." The page is about turning Boolean
network states into sound, and it has no sound on it. Every figure is a piano roll or a step
grid, and the one fence that would make a noise (the live Dirt player, the last section) is a
static `diffstatic` block that nothing runs.

## Why this is bigger than a docs edit

A docs `audio=` fence is not a `.wav` you commit. `DocsAudioProvenance.audit` re-renders every
published fence on every build through `Class.forName(renderer).INSTANCE.renderWav(spec, path)`
and fails when the render fails or differs from the committed bytes. So a clip on this page needs:

1. **An offline renderer object** with `fun renderWav(name: String, outPath: String)`, like
   `CaSoundDocs` (`src/jvmMain/kotlin/afield/four/synth/CaSoundDocs.kt`), which the
   `chain_ca_sound.md` page uses as
   `audio="assets/chain_ca_sound/gasket-bar.wav" audio-spec="gasket-bar" audio-renderer="afield.four.synth.CaSoundDocs"`.
   Nothing like it exists for Boolean networks. The page's examples produce a `NoteProcess` or
   `Iterator<ScheduledNote>` (`BooleanNetworkSequencing.kt`), and I found no offline
   NoteProcess → PCM path in `afield/four/synth/`. The offline renderers that do exist go through
   `patRenderStereo(pattern, cycles, cps, voice)` (`SoundRenderPathDocs.kt`, `PatSynthDocs.kt`) or
   hand-built `Signal` graphs (`DoughDspPlotGallery.kt`).
2. **Registry edits, each pinned:** an `ORIGIN_BY_RENDERER` entry
   (`src/jvmMain/kotlin/afield/four/synth/DocsAudioProvenance.kt`, the map near the top),
   `EXPECTED_FENCE_COUNT` (currently 58) and the per-`Origin` counts in
   `src/jvmTest/kotlin/afield/four/synth/DocsAudioProvenanceGuardTest.kt`, and the
   `docs-audio-producer` marker's `declaredIn` in `SoundPathMap.kt`. `sound.md` and
   `sound_paths.md` also state the corpus size in words.
3. **Committed, deterministic inputs.** The Dirt examples cannot be rendered as written: they
   need a Dirt-Samples directory (`dirt("/absolute/path/to/Dirt-Samples")`) that the repo does not
   commit, so an offline render would be a red build on a clean clone.

This is new code (a renderer plus a FIELD_TEST smoke) and edits to pinned guards, so it is
engineering rather than prose.

## Proposed approach

Add `BooleanNetworkSoundDocs` in `afield.four.synth`, shaped like `CaSoundDocs`:

- Scenes that mirror the page's figures, with the same seeds and step durations so each clip
  matches its piano roll:
  - `attractor-roll`: the first fence (the first `kauffmanNetwork(n=5,k=2)` seed with period ≥ 6,
    `RISING_EDGE`, 0.25 s steps, 24 steps), each note played through a simple pitched voice.
  - `braid`: the three-layer braid (seeds 27/11/42). It is worth hearing *because* of what the
    prose says is wrong with it: the shimmer stops at 4 s and the snare fires only at t=0.
  - `driven`: the oscillator-driven network.
  - The Dirt-token example: either synthesise the tokens with drum-like `Signal` voices (and label
    the clip as a stand-in *voice* for the same real trigger stream, not as Dirt), or leave it
    without a clip and say why on the page.
- Rendering: either (a) write a small offline `NoteProcess`/`ScheduledNote` → stereo mixer over
  the existing voices, which would also be useful beyond this page; or (b) convert each lane's
  triggers into a `PatPattern` and reuse `patRenderStereo`. (a) is the more direct reading of the
  page's API; (b) reuses a renderer the provenance audit already trusts.
- A `FIELD_TEST` block (`runner: jvm-main`) that renders every scene to a temp dir and checks it,
  with no committed writes; `args[0]` regenerates the page's `.wav`s.
- Update the pinned counts and the `ORIGIN_BY_RENDERER` entry; add an `Origin` if none fits.

## Questions for a human

- Should there be a general offline `NoteProcess` renderer (option a), or is a docs-only
  renderer via `patRenderStereo` (option b) enough?
- For the Dirt examples: do we use synthesised drum voices clearly labelled as such, or no clip?
  Committing a few Dirt samples is a third option, if the licence allows it.
- Which voice should the pitched examples use (a `patVoice` preset, a Faust voice, a plain
  `Signal`)?

## What the page needs once this is done

After each of the first three `diffstatic_runner` fences, a hidden `embed=true` audio fence (the
`chain_ca_sound.md` shape) that calls the renderer's scene, plus one sentence saying what to
listen for: the relock every 0.375 s in the braid, the shimmer dropping out at 4 s, and the forced
clock lane under the driven network.
