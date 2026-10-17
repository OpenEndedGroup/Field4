# Give showMeshGrid a facts option so panels carry their numbers

- **Handoff:** `2097d476-83b2-4060-a2fb-bab2e35bb4b9` (project)
- **Filed from:** https://openendedgroup.com/field4/mesh_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close 2097d476 "what happened"`

Sharp edge (rule 25) on public/docs/mesh_examples.md, "Modelling with a field":

> Its panels carry a label and no fact line. The counts, extent, χ, genus and volume that the single-mesh `showMesh` and the inline card print are not there, because `showMeshGrid` calls `threeGrid` with `facts` left off. To get them under every panel, call `threeGrid(..., facts = true)` directly, ...

Fix: add `facts: Boolean` (probably default true, matching showMesh) to `Box.showMeshGrid` in src/jvmMain/kotlin/afield/geometry/mesh/mesh_field.kt and pass it through to `threeGrid`. Delete this paragraph in the same change (recaptures of mesh_examples figures may then change).

## Also filed from other pages (merged duplicates)

The same fix removes these paragraphs too; delete each in the same change.

### b00913a9 — Forward facts from showMeshGrid to threeGrid

Filed from https://openendedgroup.com/field4/three_output/.

Sharp edge (rule 25) on three_output.md, threeGrid section:

> `facts = true` is **off by default**, and `showMeshGrid` does not turn it on. If you want the numbers under each panel, either call `threeGrid` directly as above, or use the `List<ThreeMeshPanel>` overload...

Fix in code: give `showMeshGrid` a `facts` parameter forwarded to `threeGrid` (same default), so the delegate does not silently lose an option. Delete the paragraph in the same change.
