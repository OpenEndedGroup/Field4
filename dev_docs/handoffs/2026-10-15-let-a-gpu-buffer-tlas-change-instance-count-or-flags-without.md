# Let a GPU-buffer TLAS change instance count or flags without a fresh owner

- **Handoff:** `ca2cfc95-0875-4da8-921b-7629d9a693d5` (project)
- **Filed from:** https://openendedgroup.com/field4/computed_ray_geometry/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close ca2cfc95 "what happened"`

`public/docs/computed_ray_geometry.md` ("Builds, validation and ownership") carries this trap:

> Use a fresh TLAS owner: the first GPU-buffer build fixes its instance count and flags. Transform/address edits within those records are supported; recreate the TLAS and defer retiring the old one if its count or flags must change.

Fix in code: `AccelerationStructure.buildTopLevel()` with a GPU instance buffer should either reallocate (with deferred retirement) when count/flags change, or fail loudly with a clear message, instead of requiring the caller to know to recreate the owner. Delete that paragraph's warning sentences in the same change.
