# Make isoContourFLines wind holes opposite to outer loops

- **Handoff:** `afa910a0-f48a-41da-8401-cc02ff0be13d` (project)
- **Filed from:** https://openendedgroup.com/field4/alife_examples/
- **Filed by:** human, 2026-10-27 18:20
- **Close with:** `python tools/docs_notes.py handoff-close afa910a0 "what happened"`

`public/docs/alife_examples.md`, section 10, carries this warning:

> !!! warning "Contour loops carry no winding information"
>     An annulus contours to two loops, an outer and an inner, and **both come back wound the same way**. ... `loops.sumOf { flineSignedArea(...) }` reads 67 % high for a shape with a hole ... Anything that fills, booleans or exports these curves has to find the hole by containment or by area, not by orientation.

This is a sharp edge (rule 25). Fix in code: make `Img.isoContourFLines` / the marching-squares extractor in `src/jvmMain/kotlin/afield/geometry/path/contours.kt` return outer loops and holes with opposite winding (e.g. orient by containment depth), add a test that an annulus's signed areas sum to the ring area, and delete the warning paragraph from the page in the same change.
