# Forward facts from showMeshGrid to threeGrid

- **Handoff:** `b00913a9-b18e-4f73-bb02-eac21453ea1b` (project)
- **Filed from:** https://openendedgroup.com/field4/three_output/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close b00913a9 "what happened"`

Sharp edge (rule 25) on three_output.md, threeGrid section:

> `facts = true` is **off by default**, and `showMeshGrid` does not turn it on. If you want the numbers under each panel, either call `threeGrid` directly as above, or use the `List<ThreeMeshPanel>` overload...

Fix in code: give `showMeshGrid` a `facts` parameter forwarded to `threeGrid` (same default), so the delegate does not silently lose an option. Delete the paragraph in the same change.
