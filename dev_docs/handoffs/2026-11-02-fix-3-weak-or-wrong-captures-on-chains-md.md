# Fix 3 weak or wrong captures on chains.md

- **Handoff:** `e1c81849-a86a-4b17-b5cb-ea0d2272470e` (project)
- **Filed from:** https://openendedgroup.com/field4/chains/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close e1c81849 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/agent_transcripts_gallery/stacktrinket_feedback_pulse_ring.png` — weak-illustration, medium

**Problem.** The card says StackTrinket programs read their previous frame 'so a few words leave trails, fog and bloom'. The picture is a single static soft white ring on black, with no trail or feedback visible.

**Fix.** Capture a feedback preset after it has run a few seconds, so trails or smeared afterimages are visible, or pick a gallery frame that shows the feedback.

## `assets/chain_lambda_sound/one_reduction.png` — not-what-it-says, low

**Problem.** The alt text says 'Agent and reducible-pair counts over twenty-six interaction-net reactions'. The plot is titled 'three two: after each of its 86 reactions' and its x axis runs to about 90.

**Fix.** Change the alt text to eighty-six reactions, or recapture the intended 26-reaction term.

## `assets/agent_transcripts_gallery/madp_score.png` — legibility, low

**Problem.** At card width the box labels and the orange/blue bar panel of the score canvas are unreadable specks. The playhead is visible but not what the boxes do.

**Fix.** Capture a tighter crop around the playhead and the two lit boxes with their output panel.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page chains.md` and look at each new asset at column width before closing.
