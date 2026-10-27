# Implement or forbid VOLUMETRIC centre for 3-D cutting planes

- **Handoff:** `19811dca-dad4-485c-804d-2ad5d70d8fae` (project)
- **Filed from:** https://openendedgroup.com/field4/lp_curves/
- **Filed by:** human, 2026-10-27 18:21
- **Close with:** `python tools/docs_notes.py handoff-close 19811dca "what happened"`

`public/docs/lp_curves.md` (section "In three dimensions" / "Cutting planes") says:

> In three dimensions the centre is `ANALYTIC`, `CENTROID` or `CHEBYSHEV` (the centre of the largest ball inside), as in the plane; `VOLUMETRIC` is not implemented for polytopes and is refused.

This is a sharp edge (rule 25): `LpCenter.VOLUMETRIC` is accepted by the type but refused at runtime by `LpPolytope.cuttingPlanes` (`src/jvmMain/kotlin/afield/geometry/spatial/lp_polytope.kt`). Fix in code: either implement Vaidya's volumetric centre for 3-D polytopes, or give the 3-D method its own centre enum without `VOLUMETRIC` so the call cannot be written. In the same change delete the "`VOLUMETRIC` is not implemented for polytopes and is refused" clause from the page and from the 3-D reference table row.
