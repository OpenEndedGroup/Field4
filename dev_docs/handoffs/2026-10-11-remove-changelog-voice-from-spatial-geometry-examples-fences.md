# Remove changelog voice from spatial_geometry_examples fences

- **Handoff:** `885f13f3-78cd-4466-9496-3a619d19ed76` (project)
- **Filed from:** https://openendedgroup.com/field4/spatial_geometry_examples/
- **Filed by:** human, 2026-10-11 12:57
- **Close with:** `python tools/docs_notes.py handoff-close 885f13f3 "what happened"`

`public/docs/spatial_geometry_examples.md` carries changelog voice inside capture fences, which the prose pass cannot touch (editing a fence changes its capture digest and rank-carets pins):

- `grid_corner.png` fence: the comment "Before the fix in this branch, the first returned an empty list and the third came back severed into four disconnected arcs", and captions ending "(was 0 contours)" / "(was 4 arcs)". Restate the comparison as "a strict sign-change test returns ..." or drop it, then recapture that figure.
- `contours.png` fence comment: "measured, 0 contours, and correctly so"; `linkages.png` fence: "Measure rather than assume."; `voronoi3d_slice.png`: "Stating it as a number rather than an adjective is the only way it is a check." Lab-notebook voice for a reader.
- Reference section, Field integration table: the `VoronoiDiagram.toFLines(...)` row appears twice; remove one.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... --page spatial_geometry_examples.md` and look at each image.
