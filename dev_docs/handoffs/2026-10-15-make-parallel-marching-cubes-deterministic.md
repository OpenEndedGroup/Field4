# Make parallel marching cubes deterministic

- **Handoff:** `ec2afaf8-afba-4188-94fd-216d978eea48` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_life3d_render/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close ec2afaf8 "what happened"`

Rule 25 sharp edge on `public/docs/chain_life3d_render.md`, the warning admonition:

> !!! warning "`parallel = true` is nondeterministic"
> The readout above uses `parallel = false`. Marched repeatedly on one field, the parallel path varies in triangle count, in volume, and in how many components it finds; the sequential path returns the same three numbers every time. ... Take it when the output is going to a screen; take the sequential one when a number off it is going to be read.

Fix in code: make the parallel marching path (`marchingCubes` / `asDensityField` march with `parallel = true`) deterministic, e.g. by resolving marginal cells per fixed-order chunk and merging chunks in index order, so it returns the same mesh as the sequential path. Add a test that marches one field parallel and sequential and compares triangle count, volume and component count. Delete the warning admonition from the page in the same change.
