# Implement or type out VOLUMETRIC for 3-D cutting planes

- **Handoff:** `a296a67a-66fe-44cb-bcb3-99d3d38c7e18` (project)
- **Filed from:** https://openendedgroup.com/field4/lp_curves/
- **Filed by:** human, 2026-10-20 11:09
- **Close with:** `python tools/docs_notes.py handoff-close a296a67a "what happened"`

Sharp edge (rule 25) on public/docs/lp_curves.md, "In three dimensions" / "Cutting planes":

> In three dimensions the centre is `ANALYTIC`, `CENTROID` or `CHEBYSHEV` ... as in the plane; `VOLUMETRIC` is not implemented for polytopes and is refused.

Fix in code: implement the volumetric centre for `LpPolytope.cuttingPlanes` (src/jvmMain/kotlin/afield/geometry/spatial/lp_polytope.kt), or give the 3-D method its own centre enum without VOLUMETRIC so the refusal is a type error. Delete the clause (and update the 3-D reference table row) in the same change.
