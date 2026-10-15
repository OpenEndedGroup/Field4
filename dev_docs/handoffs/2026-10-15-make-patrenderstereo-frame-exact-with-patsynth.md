# Make patRenderStereo frame-exact with patSynth

- **Handoff:** `cce95ea4-0b90-4530-8c5f-6e3aa4e69f7f` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_pattern_particles/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close cce95ea4 "what happened"`

`public/docs/chain_pattern_particles.md` says (rule 25, a sharp edge):

> The offline renderer, `patRenderStereo`, works in seconds rather than frames, so a note it plays can start or end one frame away from the same note played by `patSynth`.

Fix: make `patRenderStereo` schedule notes on the same integer frame stamps `PatSynthFeeder` uses (or render offline through `PatSynthBank`), so offline and realtime renders of a pattern are sample-identical; add a test comparing the two. Delete the paragraph in the same change.

Related hazard in the page's aside "Why the figure is a still, and silent": a box must choose between returning the launch and calling `PatSynthLaunch.advanceOffline` by checking `FieldAudioEngine.outputActive`, and "doing both would advance every voice twice". Consider making `advanceOffline` refuse (or no-op) while the launch is attached to an active engine, so the trap cannot be hit; then the aside can drop that sentence.
