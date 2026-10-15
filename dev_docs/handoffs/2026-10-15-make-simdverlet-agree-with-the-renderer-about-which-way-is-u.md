# Make SimdVerlet agree with the renderer about which way is up

- **Handoff:** `7966cc9f-3ead-432e-84e6-d12eb45dfbaa` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_physics_render/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close 7966cc9f "what happened"`

`public/docs/chain_physics_render.md` carries this warning (rule 25, a sharp edge):

> !!! warning "The two subsystems disagree about which way is up"
> Gravity is **+y** in `SimdVerlet`, which is why the sphere sits at `y = 0.78` *below* a sheet laid out at `y = 0`, and why the floor is a plane with normal `-y`. The path tracer has +y up. Negate `y` once, where the positions are read out, rather than mirroring the camera and the floor to compensate: a mirrored camera reverses the winding of everything it sees.

Fix: make `afield.simulation.physics.SimdVerlet` (and its colliders/floor defaults) agree with the renderers' +y-up convention, or give it an explicit, named up/gravity axis that defaults to the house convention, so cloth positions feed `afield.graphics.scene.fabricated` with no negation. Update the transcript `dev_docs/agents/transcripts/physics_manifold_path_trace.yaml` and the page's code fence, recapture, and delete the warning in the same change.
