# Make showMesh topology fact survive flatShaded meshes

- **Handoff:** `c95a9943-8f02-4fa0-9ccd-65ed6061d149` (project)
- **Filed from:** https://openendedgroup.com/field4/typography_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close c95a9943 "what happened"`

Sharp edge (rule 25) on `public/docs/typography_examples.md`, aside "`flatShaded()` and the figure's fact line":

> It un-welds every triangle into its own three vertices ... `showMesh` computes `topology()` on whatever you hand it and prints the answer *as fact* in the figure's fact line. Pass it a flat-shaded `o` and that line reads `open (N boundary loop(s))` beside a title stating the mesh is closed.

Fix in code: `showMesh`'s fact line should compute topology on a position-welded view of the mesh (or report "unwelded / flat-shaded" instead of "open"), so a flat-shaded closed mesh is not reported as open. Add a test with `toMesh(...).flatShaded()`. Delete the aside in the same change.
