# Make SolidFit.deadParameters catch parameters that never move

- **Handoff:** `76173fa5-4cd4-4e01-b2d3-47dbc6c7407f` (project)
- **Filed from:** https://openendedgroup.com/field4/solid_notation/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close 76173fa5 "what happened"`

Rule 25 sharp edge on public/docs/solid_notation.md ("When only half the model can fit"):

> !!! note "A cube hidden inside the bar gets zero gradient"
> Seed `cubeSize` at `0.40` against a bar of radius `0.55` and the fit reports `cubeSize 0.400000 -> 0.400000` after 372 iterations. ... `fit.deadParameters` reports `none` here, because it only looks at the gradient at the seed, and there the cube is invisible to the loss rather than stationary in it.

Fix in code (afield.lang.solidsl, SolidFit): make `deadParameters` (and `SolidCloudFit.unconstrainedParameters`) also report parameters whose gradient stayed exactly zero over the whole descent / never moved from the seed, not only at the seed, so a swallowed child is named. Delete or reduce the note in the same change.
