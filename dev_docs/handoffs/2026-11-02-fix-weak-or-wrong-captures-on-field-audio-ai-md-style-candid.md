# Fix weak or wrong captures on field_audio_ai.md (style_candidates.png)

- **Handoff:** `ae4c0e23-7fdf-40d1-ac6f-247394d9e19e` (project)
- **Filed from:** https://openendedgroup.com/field4/field_audio_ai/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close ae4c0e23 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/field_audio_ai/style_candidates.png` — broken-or-error, low

**Problem.** The 16 candidate waveforms are drawn, but every panel still shows its 'loading waveform' badge: the capture fired before the badges cleared.

**Fix.** Wait for the waveform-loaded state instead of a fixed wait=4. Widen the capture (w=1120 is too narrow for the three-column grid) so the right column is not clipped. Consider capturing only the first row of candidates so the figure stays legible at column width.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page field_audio_ai.md` and look at each new asset at column width before closing.
