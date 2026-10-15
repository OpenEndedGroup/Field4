# Warn when Tetgen targetEdgeLength is coarser than the input

- **Handoff:** `0c9efa60-cdd7-4ffb-aeba-89c0c04aa881` (project)
- **Filed from:** https://openendedgroup.com/field4/tetgen/
- **Filed by:** human, 2026-10-15 13:45
- **Close with:** `python tools/docs_notes.py handoff-close 0c9efa60 "what happened"`

public/docs/tetgen.md, "Sizing":

> TetGen keeps every triangle it is given, and this bracket's 618 triangles are already about 10 mm across, so asking for larger elements than that changes nothing and gives no warning. Check `cellCount` rather than assuming the size you passed took effect.

A silently ignored argument is a sharp edge. Fix in afield.geometry.mesh.tetgen.Tetgen.tetrahedralize: when targetEdgeLength exceeds the input surface's typical edge length (e.g. median input edge), emit a box-visible warning (or return a flag on TetMesh) saying the surface already sets a finer size. Then delete the "gives no warning. Check cellCount ..." sentences from the page in the same change.
