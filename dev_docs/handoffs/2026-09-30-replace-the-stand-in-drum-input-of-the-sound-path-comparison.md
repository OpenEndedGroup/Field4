# Replace the stand-in drum input of the sound-path comparison

- **Handoff:** `2621cb8b-c3f1-48c6-83cf-f32ce69e082f` (project)
- **Filed from:** https://openendedgroup.com/field4/sound_paths/
- **Filed by:** agent:claude, 2026-09-30 20:33
- **Close with:** `python tools/docs_notes.py handoff-close 2621cb8b "what happened"`

## The notes that led here

- `cc122e7c` (public/docs/sound_paths.md:187): these are starting with something that sounds like white noise. Our stand in sample set for this is complete trash, we can't use it.
  > Here are three of the paths on bit-identical input.

## What was asked

On `public/docs/sound_paths.md`, "One buffer, three paths" (the section at `## One buffer, three paths`),
the human wrote: "these are starting with something that sounds like white noise. Our stand in sample
set for this is complete trash, we can't use it."

## Why this is bigger than a docs edit

The three clips are not prose. They are rendered by `afield.four.synth.SoundPathMap`
(`src/jvmMain/kotlin/afield/four/synth/SoundPathMap.kt`, `theBeat()` / `dryBeat()` /
`throughSignalGraph` / `throughFaust` / `throughStandInVoice`), which renders
`pat("bd ~ sd ~, hh*8")` through `patRenderStereo` → `patVoice`, the stand-in drum map in
`PatSoundDocs.kt` (`enum class PatWave`, and the drum map KDoc: `sd`/`cp` mid-band noise, `hh` high
short noise). The dry clip's centroid is 10.9 kHz because the hats are 6.2 kHz noise — that is the
"white noise" the human hears. The numbers in the page's table (centroids, 0.9584 similarity) are
printed and asserted by the `synth.sound.path.map` smoke, and `dev_docs/SOUND_PATH_MAP_AGREEMENT.md`
records the tolerances. Changing the input changes the renderer, the smoke's assertions, the agreement
doc, three committed WAVs under `public/docs/assets/sound_path_map/`, and the page's table.

## Proposed approach

Pick a musically legible, device-free input that every path can take bit-identically, e.g.:
1. a `signal-graph` synth phrase (saw/square notes with an envelope) rendered offline — rich in
   harmonics, so a 900 Hz lowpass is audible as darkening rather than "the noise goes away";
2. `dirt-samples` real drums via `patRenderStereoDirt` — but that needs the Dirt-Samples quark on the
   build machine, so the capture would not regenerate everywhere;
3. a short committed recording (a `.wav` in assets) loaded with `WavFloatArrayLoader` — real material,
   deterministic, but it is no longer "a pattern in Field".

Then re-derive the table numbers from the smoke and rewrite the section's two prose paragraphs about
hats/kick.

## Questions for a human

- Which input? (Recommendation: option 1, a pitched synth phrase from `signal-graph`.)
- Should `patVoice`'s stand-in kit be improved generally, since other pages (Sound, CA/Lambda sound
  docs) use it too? That would be a separate, wider project.

## What the page needs afterwards

Rewrite the paragraph under `## One buffer, three paths`, the three bold "Path:" captions, the
"hats go and the kick stays" paragraph and the centroid table to describe the new input; keep the
peak-normalization aside.
