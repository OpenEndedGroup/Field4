# Make Solid.levelSet(parallel = true) deterministic or remove it

- **Handoff:** `5c4f5d3d-274b-4551-8f2d-e1a05b232b6f` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_photo_to_solid/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 5c4f5d3d "what happened"`

Rule 25 sharp edge on `public/docs/chain_photo_to_solid.md`, section "`Solid.levelSet(parallel = true)` is not reproducible":

> `parallel = true` does not [give the same answer], even on the first row, where the plaque is a single solid block. It is worst on the most fragmented row. ... `parallel = true` can also agree with itself on the wrong answer: at some values of `floor` it finds one component on every run where the sequential path finds two. So use `parallel = false` for any level set whose numbers you are going to read or count. `Solid.levelSetOf` already defaults to it.

A flag that returns wrong, nondeterministic geometry is a bug. Fix it in code: make the parallel path deterministic (likely the Kotlin SDF callback is not thread-safe or the Manifold parallel marcher races on the callback), or remove/refuse `parallel = true` in `Solid.levelSet` until it is. In the same change delete that section from the page (and the second half of the comparison box that measures it, which is a fence/capture change).
