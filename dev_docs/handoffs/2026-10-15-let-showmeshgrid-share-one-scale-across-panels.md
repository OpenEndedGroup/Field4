# Let showMeshGrid share one scale across panels

- **Handoff:** `ca6c591e-4f49-46fa-bfb6-0346ea5c8241` (project)
- **Filed from:** https://openendedgroup.com/field4/mesh_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close ca6c591e "what happened"`

Sharp edge (rule 25) on public/docs/mesh_examples.md, "Subdivide, and smooth without shrinking":

> The picture will not settle the two smoothed balls for you: look at the printed volumes, not at the panels. Each panel of a `showMeshGrid` frames its own bounds and zoom-to-fits, so the Laplacian ball's 20% smaller extent is normalised straight back out ...

Fix: give showMeshGrid / threeGrid a shared-bounds option (e.g. `sharedScale = true`) so panels comparing sizes render at one scale, and use it in the smoothing example. Delete the paragraph in the same change and recapture.
