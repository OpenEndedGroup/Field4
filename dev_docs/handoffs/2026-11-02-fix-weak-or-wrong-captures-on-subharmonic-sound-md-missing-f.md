# Fix weak or wrong captures on subharmonic_sound.md (missing-fundamental.png)

- **Handoff:** `36361e9e-bee5-43fc-9da5-74841276bbf2` (project)
- **Filed from:** https://openendedgroup.com/field4/subharmonic_sound/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 36361e9e "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/subharmonic_sound/missing-fundamental.png` — legibility, medium

**Problem.** The image is almost entirely black. There are three short grey bars and one blue bar, with tiny orange ticks and no frequency axis labels. At column width you cannot read which bar is at 220, 330 or 550 Hz, or that the output bar is at 55 Hz. The 110 Hz point is unmarked, and that missing fundamental is the whole point of the figure.

**Fix.** Make the plot shorter with taller bars, add Hz labels on the x axis (55, 110, 220, 330, 550), and mark the empty 110 Hz slot.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page subharmonic_sound.md` and look at each new asset at column width before closing.
