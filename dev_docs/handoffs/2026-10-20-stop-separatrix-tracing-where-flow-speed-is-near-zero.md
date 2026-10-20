# Stop separatrix tracing where flow speed is near zero

- **Handoff:** `13066231-6d6b-421d-94f3-19b75b8011a2` (project)
- **Filed from:** https://openendedgroup.com/field4/vector_field_topology/
- **Filed by:** human, 2026-10-20 11:08
- **Close with:** `python tools/docs_notes.py handoff-close 13066231 "what happened"`

`public/docs/vector_field_topology.md` (after the camera-removed eagle figure) carries a paragraph that exists only to explain a limitation:

> The separatrices still run out past the bird, because the flow around it is close to zero rather than exactly zero, and a streamline follows direction whatever the speed. Both pictures are the same footage read in two frames of reference.

Fix in code: let separatrix (and streamline) integration in `afield.geometry.topology.vectorfield.VectorFieldTopology` stop where speed falls below a threshold (reuse the `minSpeed` that `streamlines(...)` already takes, e.g. a `minSpeed` on `compute`), then use it in the `vector-field-eagle-bird` fence and recapture `assets/vector_field_topology/eagle_bird.png`. In the same change delete the first sentence of that paragraph (keep "Both pictures are the same footage read in two frames of reference.").
