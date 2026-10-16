# Make SlugBuffer teardown order-independent

- **Handoff:** `2b8a5d9f-7439-40ac-84b9-4200a8aa0405` (project)
- **Filed from:** https://openendedgroup.com/field4/slug_paths/
- **Filed by:** human, 2026-10-16 07:24
- **Close with:** `python tools/docs_notes.py handoff-close 2b8a5d9f "what happened"`

Refiled from the code batch on 2026-10-16 with the analysis:

- `SlugBuffer.close()` already releases through `cleanup()`, Field's deferred GPU cleanup, so frames already in flight are safe. The hazard is the next frame: a mesh still bound calls `getGPUAddress`, which does `check(!isClosed)` and throws.
- Making `getGPUAddress` after close return a released address would be worse than throwing. The options that make teardown order-independent need a GPU check: (a) after close, serve a tiny empty placeholder atlas whose header makes the slug shader draw nothing; (b) count consumers (the mesh registers when it binds the buffer as a uniform or pointer, and unregisters on removal) and let `close()` defer its cleanup until the count reaches zero; (c) have the slug helper own both the mesh and the buffer and release them in order on the box's stop (the RunScope RUNNING policy).
- (c) is the least invasive if the documented demo already builds both through one helper. Verify with the slug GPU regression (`SlugShaders` consumers) by stopping the box with the buffer closed first.


Sharp edge (rule 25) on public/docs/slug_paths.md, "Geometry and appearance":

> The demo removes its consuming mesh before closing the buffer when the box stops
> or runs again. Follow that ordering in your own code. `close()` is idempotent,
> but a closed buffer cannot be bound or replaced.

Box code should not have to order teardown by hand. Fix in code: have
`SlugBuffer.close()` (afield/graphics/text/slug/SlugBuffer.kt) defer its release
until meshes consuming it are removed, or tie its lifetime to the box's stop
release so the mesh is detached first automatically. Delete this paragraph from
the page in the same change.
