# Fix the braided Boolean rhythm fence so all three layers play

- **Handoff:** `6fca166f-dcd8-4bc3-baac-1ce9a5231f70` (project)
- **Filed from:** https://openendedgroup.com/field4/boolean_network_music_examples/
- **Filed by:** human, 2026-10-09 03:09
- **Close with:** `python tools/docs_notes.py handoff-close 6fca166f "what happened"`

The "Braiding several Boolean rhythms together" fence on `public/docs/boolean_network_music_examples.md`
does not do what its heading promises: the shimmer layer runs 64 sixteenth-steps (four of the eight
seconds), and the snare layer is chosen with `maxByOrNull { it.period }` and no minimum period, so it
lands on a fixed point and fires once at time zero. The page currently explains these faults in prose.

Fix the fence instead (rule 10): filter seeds by period as the first example does, and size the shimmer
to the full eight seconds. Then recapture the piano roll and the audio fence that follows it, and cut the
prose that describes the faults (the paragraph after the fence and the first sentences of the listening
paragraph). Fence bytes are pinned by `tools/completion-corpus/rank-carets.tsv`; update it.
