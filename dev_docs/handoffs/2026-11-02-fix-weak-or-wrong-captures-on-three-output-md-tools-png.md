# Fix weak or wrong captures on three_output.md (tools.png)

- **Handoff:** `d6497a1d-f6c6-42b6-a685-245def273786` (project)
- **Filed from:** https://openendedgroup.com/field4/three_output/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close d6497a1d "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/three_output/tools.png` — weak-illustration, low

**Problem.** The example exists to show a custom tool, but the capture shows only the 'Reference axes' button and one line in a mostly empty viewer. The axes are never toggled on, so the tool's effect is never shown.

**Fix.** Click the Reference axes button before capturing (with a drive step) so the axes overlay appears next to the line.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page three_output.md` and look at each new asset at column width before closing.
