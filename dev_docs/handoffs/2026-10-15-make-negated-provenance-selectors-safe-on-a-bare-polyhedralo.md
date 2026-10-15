# Make negated provenance selectors safe on a bare PolyhedralObject

- **Handoff:** `f2dc9a00-ea18-45f7-bdad-f04217fec0d5` (project)
- **Filed from:** https://openendedgroup.com/field4/polytope_examples/
- **Filed by:** human, 2026-10-15 13:45
- **Close with:** `python tools/docs_notes.py handoff-close f2dc9a00 "what happened"`

Sharp edge (rule 25) on `public/docs/polytope_examples.md`, section "Selectors":

> In the last row, `vertexDegree(4)` matches the six apexes kis raised. On the bare object
> `provenanceRole(...)` matches nothing, so its negation matches everything and the combined selector
> still finds all six; the transform result correctly finds none. Use negated provenance selectors
> only on a transform result.

Fix in `afield.geometry.polytope` (`Parameters.kt`, `ElementSelectors`): a provenance selector evaluated
without lineage should not silently be "false" so that `!` flips it to "true". Options: make provenance
selectors three-valued (unknown without lineage, and `!unknown` stays unknown/false), or throw a clear
error when a provenance selector runs on a context with no lineage. Re-capture the selector table and
delete the paragraph's last two sentences (the warning) in the same change.
