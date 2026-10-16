# Make SimdVerlet.step use the published constraint set

- **Handoff:** `38cabe43-4a5b-4568-b3c2-999162e4df77` (project)
- **Filed from:** https://openendedgroup.com/field4/physics/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close 38cabe43 "what happened"`

Rule 25 sharp edge on public/docs/physics.md (section "`Cloth`"):

> If you drive the simulation yourself rather than through `cloth.step(...)`, you must pass it: `sim.step(dt, damping, gravity, iterations, sim.constraints4)`. The no-constraints overload solves nothing, and the sheet falls apart with no error.

Fix in code (`afield/simulation/physics/SimdVerlet.kt`): make the no-constraints `step` overload use `sim.constraints4` (or `constraints`) when one is published, or fail loudly when a published set is being ignored. Delete the "you must pass it ... falls apart with no error" sentences from physics.md in the same change.
