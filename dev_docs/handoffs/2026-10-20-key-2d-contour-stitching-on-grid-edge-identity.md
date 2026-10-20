# Key 2D contour stitching on grid-edge identity

- **Handoff:** `d9faa2bc-11ad-4dbd-a6c3-276d9597f436` (project)
- **Filed from:** https://openendedgroup.com/field4/spatial_geometry_examples/
- **Filed by:** human, 2026-10-20 11:09
- **Close with:** `python tools/docs_notes.py handoff-close d9faa2bc "what happened"`

Rule 25 sharp edge on `public/docs/spatial_geometry_examples.md` (section "Two decisions inside the marcher, and one rough edge"):

> **The rough edge:** the segment stitcher joins crossings by proximity and is O(*m*²) in the
> number of segments, so a very high grid resolution gets expensive. `marchingCubes` avoids this
> by keying shared vertices on grid-edge identity; the 2D marcher does not yet.

Fix in code: make the 2D contour marcher in `src/jvmMain/kotlin/afield/geometry/path/contours.kt` key shared crossings on grid-edge identity (as `marchingCubes` does), so stitching is linear in segment count. Delete the paragraph (and "and one rough edge" from the heading) in the same change.
