# Add real audio (and maybe video) captures to the Petri nets rhythm examples

- **Handoff:** `143c4d44-d367-4054-bb46-dd9d702eccff` (project)
- **Filed from:** https://openendedgroup.com/field4/petri_nets/
- **Filed by:** agent:claude, 2026-10-31 14:10
- **Close with:** `python tools/docs_notes.py handoff-close 143c4d44 "what happened"`

## The notes that led here

- `417f941a` (public/docs/petri_nets.md:19): no video? no wav file?
  > This net has one token, two places and two transitions. Each transition plays a sound when it fires. The token can only be in one place at a time, so the two transitions take turns, and that alternation is the rhythm. The example draws the net and prints the first few steps.

## What was asked

On `public/docs/petri_nets.md`, section "A net that plays a rhythm" (lines 17–57), the human noted
"no video? no wav file?". The page says the net is a rhythm — each transition plays `bd` or `sd` —
but the reader only sees a drawing and a text timeline. Nothing on the page can be heard.

## Why this is more than a prose edit

The docs runner makes audio with a hidden fence carrying `audio="..."`, `audio-spec="..."` and
`audio-renderer="afield.audio.synth.<X>SoundDocs"` (for example
`public/docs/boolean_network_music_examples.md:75`, `public/docs/chain_drawn_score.md:127`). Each
renderer is a Kotlin object in `src/jvmMain/kotlin/afield/audio/synth/` (`BooleanNetworkSoundDocs.kt`,
`PatSoundDocs.kt`, `CaSoundDocs.kt`, ...). There is no Petri renderer, and `petri_nets.md` has no
audio fence. Adding one means new `src/` code and a real offline render. The page's own captures
cannot be faked (AGENTS.md §3).

## Proposed approach

`net.emits()` is already a `PatPattern` (petri_nets.md:9-13), so the cheapest route is probably to
reuse `PatSoundDocs` with a spec that builds the two-place net and renders its `emits()` through the
Dirt-Samples offline renderer (`requires="dirt-samples"`). If `PatSoundDocs` cannot take a pattern
built from a Petri source, add a small `PetriSoundDocs` beside it instead. Then add hidden audio fences
after the first example, and maybe after the polyrhythm example (line 76).

Another option: a short video of the net animating while it plays (token moving, transitions lighting
up). This costs more and needs a frame capture path for `showPetriNet`.

## Questions for the human

- Is audio alone enough, or do you want the animated drawing (video) as well?
- Which examples should get sound: just the first one, or every musical one on the page?

## What the page needs afterwards

One hidden audio fence per chosen example, placed right after its figure, and a sentence pointing to
it ("listen to the first four bars").
