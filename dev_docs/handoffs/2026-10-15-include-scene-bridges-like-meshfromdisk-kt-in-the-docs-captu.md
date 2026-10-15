# Include scene bridges like MeshFromDisk.kt in the docs capture digest

- **Handoff:** `bc62be15-e962-4b30-b30e-706073812628` (project)
- **Filed from:** https://openendedgroup.com/field4/geometry_from_disk/
- **Filed by:** human, 2026-10-15 13:45
- **Close with:** `python tools/docs_notes.py handoff-close bc62be15 "what happened"`

geometry_from_disk.md (aside "Agents: forcing these captures to re-run") says:

> A fence is re-executed only when the build decides its capture is stale, from a digest over the runner machinery plus the **stdlib** source files the fence's code names. `MeshFromDisk.kt` is in `afield.graphics.scene`, not `afield.geometry.mesh`, so editing *it* does not invalidate these captures on its own. Force them with `FIELD_DOCS_RUNNER_MODE=always` after changing the bridges. `mesh_examples.md` carries the same caveat.

Fix: make the capture digest in public/docs_hooks.py resolve the source files of every symbol a fence calls (e.g. via the code-use index / package map after the refactor), so `meshFromAnyFile` in src/jvmMain/kotlin/afield/graphics/scene/MeshFromDisk.kt is covered. Delete this aside (and the matching one in mesh_examples.md) in the same change.
