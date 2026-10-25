# Make parallel marching cubes deterministic

- **Handoff:** `ec2afaf8-afba-4188-94fd-216d978eea48` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_life3d_render/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close ec2afaf8 "what happened"`

Rule 25 sharp edge on `public/docs/chain_life3d_render.md`, the warning admonition:

> !!! warning "`parallel = true` is nondeterministic"
> The readout above uses `parallel = false`. Marched repeatedly on one field, the parallel path varies in triangle count, in volume, and in how many components it finds; the sequential path returns the same three numbers every time. ... Take it when the output is going to a screen; take the sequential one when a number off it is going to be read.

Fix in code: make the parallel marching path (`marchingCubes` / `asDensityField` march with `parallel = true`) deterministic, e.g. by resolving marginal cells per fixed-order chunk and merging chunks in index order, so it returns the same mesh as the sequential path. Add a test that marches one field parallel and sequential and compares triangle count, volume and component count. Delete the warning admonition from the page in the same change.

## Also filed from other pages (merged duplicates)

The same fix removes these paragraphs too; delete each in the same change.

### 5c4f5d3d — Make Solid.levelSet(parallel = true) deterministic or remove it

Filed from https://openendedgroup.com/field4/chain_photo_to_solid/.

Rule 25 sharp edge on `public/docs/chain_photo_to_solid.md`, section "`Solid.levelSet(parallel = true)` is not reproducible":

> `parallel = true` does not [give the same answer], even on the first row, where the plaque is a single solid block. It is worst on the most fragmented row. ... `parallel = true` can also agree with itself on the wrong answer: at some values of `floor` it finds one component on every run where the sequential path finds two. So use `parallel = false` for any level set whose numbers you are going to read or count. `Solid.levelSetOf` already defaults to it.

A flag that returns wrong, nondeterministic geometry is a bug. Fix it in code: make the parallel path deterministic (likely the Kotlin SDF callback is not thread-safe or the Manifold parallel marcher races on the callback), or remove/refuse `parallel = true` in `Solid.levelSet` until it is. In the same change delete that section from the page (and the second half of the comparison box that measures it, which is a fence/capture change).
