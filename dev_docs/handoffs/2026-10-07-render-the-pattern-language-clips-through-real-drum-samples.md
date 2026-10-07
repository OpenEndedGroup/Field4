# Render the pattern_language clips through real drum samples

- **Handoff:** `d22058f4-1171-41d8-b82a-ceebcc866d4b` (project)
- **Filed from:** https://openendedgroup.com/field4/pattern_language/
- **Filed by:** human, 2026-10-07 03:10
- **Close with:** `python tools/docs_notes.py handoff-close d22058f4 "what happened"`

`pattern_language.md` is a page about rhythm, and every clip on it (the mini-notation cards and the
longer `.wav` scenes) is rendered through the stand-in `patVoice` synth: a sine kick and filtered
noise for snare and hats. The writing rules say audio pages need real audio made with real samples.

- Re-render the page's clips through the pinned Dirt-Samples subset (as `sound_paths.md` now does),
  via the generator behind `PatMiniNotation.markdownSection` and the page's audio fences.
- Then drop the sentence in the opener that apologises for the synth.
