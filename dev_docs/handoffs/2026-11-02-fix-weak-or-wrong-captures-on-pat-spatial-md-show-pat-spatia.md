# Fix weak or wrong captures on pat_spatial.md (show_pat_spatial.png)

- **Handoff:** `0b1f910c-adb6-4dfa-9e13-de8a58d9e381` (project)
- **Filed from:** https://openendedgroup.com/field4/pat_spatial/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 0b1f910c "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/pat_spatial/show_pat_spatial.png` — broken-or-error, medium

**Problem.** In the waveform panel's header, 'Waveform (Stereo)' is drawn over another string and comes out garbled. The waveform itself is a flat line: peak 0.036 drawn on a ±1 scale, so you cannot see the four-note line playing. Only the spectrum half carries information.

**Fix.** Fix the overlapping header label in showPatSpatial's waveform view. Auto-scale the waveform to its peak, or raise the example's amplitude, so the notes are visible.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page pat_spatial.md` and look at each new asset at column width before closing.
