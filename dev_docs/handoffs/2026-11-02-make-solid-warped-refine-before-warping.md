# Make Solid.warped refine before warping

- **Handoff:** `c31d843c-5204-413e-8184-9188131afe52` (project)
- **Filed from:** https://openendedgroup.com/field4/manifold/
- **Filed by:** human, 2026-11-02 10:31
- **Close with:** `python tools/docs_notes.py handoff-close c31d843c "what happened"`

`public/docs/manifold.md`, "Solids from a field", warns about an ordering trap:

> `Solid.warped { }` is the same mechanism for moving every vertex: a bend, a twist, a taper. It does
> **not** re-mesh, so put `refined()` in front of it or a 12-triangle cube's eight corners move and
> nothing looks different.

Fix in code: give `Solid.warped` a refinement parameter (e.g. `warped(maxEdge = ...) { }` refining first by default), or have it warn when the input has too few triangles for the warp to show. Delete the paragraph's warning sentence in the same change.
