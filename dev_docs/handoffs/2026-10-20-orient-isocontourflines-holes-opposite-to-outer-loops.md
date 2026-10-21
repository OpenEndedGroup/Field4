# Orient isoContourFLines holes opposite to outer loops

- **Handoff:** `ff08f441-11a6-4b60-bd20-7d93836743b0` (project)
- **Filed from:** https://openendedgroup.com/field4/alife_examples/
- **Filed by:** human, 2026-10-20 11:09
- **Close with:** `python tools/docs_notes.py handoff-close ff08f441 "what happened"`

Rule 25 sharp edge on `public/docs/alife_examples.md`, section 10:

> !!! warning "Contour loops carry no winding information"
>     An annulus contours to two loops, an outer and an inner, and **both come back wound the same way**. ... `loops.sumOf { flineSignedArea(...) }` reads 67 % high for a shape with a hole ... Anything that fills, booleans or exports these curves has to find the hole by containment or by area, not by orientation.

Fix in code: make `Img.isoContourFLines` (marching squares in `afield/geometry/path/contours.kt`) return holes wound opposite to outers (orient by containment nesting), add a test that the signed-area sum of an annulus matches its true area, and delete this warning paragraph in the same change.
