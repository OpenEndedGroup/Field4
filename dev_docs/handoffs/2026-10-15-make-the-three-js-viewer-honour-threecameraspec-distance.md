# Make the Three.js viewer honour ThreeCameraSpec.distance

- **Handoff:** `db14dbf7-55c3-43a4-ba14-4290353acd57` (project)
- **Filed from:** https://openendedgroup.com/field4/three_output/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close db14dbf7 "what happened"`

Sharp edge (rule 25) on three_output.md, aside "In the viewer, `distance` is a padding factor":

> So `distance` reaches the viewer as `max(1.02, distance / 2.7 × 1.06)`. Below about 2.6 that clamps to the floor and every value frames identically...

`ThreeCameraSpec.distance` is documented as "multiples of the bounding-sphere radius" but the viewer treats it as a fit padding with a floor, so the same camera frames differently on the two renderers. Fix: make the viewer honour `distance` as the rasterizer does (or rename/split the field so the name says what it does). Delete the aside and the "Framing differs" bullet in the same change.
