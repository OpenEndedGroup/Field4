# Stop asDensityField smoothing a sparse automaton to an empty surface silently

- **Handoff:** `e4924d12-fea6-44cb-8909-bc07abb3531d` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_life3d_render/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close e4924d12 "what happened"`

Rule 25 sharp edge on `public/docs/chain_life3d_render.md`, the note admonition:

> !!! note "Set `smoothRadius = 0` for a sparse automaton, or the surface comes back empty"
> `asDensityField`'s default smooths the occupancy grid by a voxel before marching ... The surface comes back **empty**, from both marchers, with no error.

Fix in code: a silent empty surface is the bug. Either have `asDensityField` choose its smoothing from the occupancy (skip smoothing when the smoothed field never reaches the iso), or warn/fail loudly when the smoothed field has no crossing of the iso while the raw occupancy does. Delete the note from the page in the same change (keep one clause explaining why the clusters are voxel-faceted if `smoothRadius = 0` stays in the fence).
