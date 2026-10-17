# Make SlugBuffer teardown order-independent

- **Handoff:** `f3c06c4e-fe4b-4d62-9a00-ca1ae75d767d` (project)
- **Filed from:** https://openendedgroup.com/field4/slug_paths/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close f3c06c4e "what happened"`

Sharp edge (rule 25) on public/docs/slug_paths.md, "Geometry and appearance":

> The demo removes its consuming mesh before closing the buffer when the box stops
> or runs again. Follow that ordering in your own code. `close()` is idempotent,
> but a closed buffer cannot be bound or replaced.

Box code should not have to order teardown by hand. Fix in code: have
`SlugBuffer.close()` (afield/graphics/text/slug/SlugBuffer.kt) defer its release
until meshes consuming it are removed, or tie its lifetime to the box's stop
release so the mesh is detached first automatically. Delete this paragraph from
the page in the same change.
