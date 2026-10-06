# Restructure tetgen.md so the meshing recipe leads and the measurement sweeps close the page

- **Handoff:** `5e41fdad-af98-4559-b0c5-3919a7a6b51b` (project)
- **Filed from:** https://openendedgroup.com/field4/tetgen/
- **Filed by:** human, 2026-10-04 21:13
- **Close with:** `python tools/docs_notes.py handoff-close 5e41fdad "what happened"`

`tetgen.md` reads as a measurement report: after the first mesh, most sections are sweeps measured
on one laptop (quality bound, sizing, `preserveSurface`, malformed inputs, float32 precision,
promotion timings), with the numbers pinned by `TetgenPageMeasurementTest` / `TetgenPageProbeTest`.
A reader who wants to mesh a part has to get through all of it before reaching "Into Kratos" and
`order = 2`, which is the advice they most need (bending wants 10-node elements).

Restructure (DOCS_WRITING_RULES rules 2, 3, 9): lead with mesh → look at it → choose `order` and
`targetEdgeLength` → hand to Kratos; then "Checking a mesh" (the two quality measures, slivers, input
surfaces that are refused or silently accepted); move the sweep tables, the Boolean-order
determinism aside, the float32 precision section and the promotion timings to a closing
"Measurements" section. Update the page tests' expectations if any anchor or sentence moves. The
2026-10-04 language pass only edited prose in place.
