# Make docs capture digests cover the stdlib source a fence exercises

- **Handoff:** `f7ca20a8-e53b-41f2-b63f-11c2ba7eff2d` (project)
- **Filed from:** https://openendedgroup.com/field4/spatial_geometry_examples/
- **Filed by:** human, 2026-10-15 13:45
- **Close with:** `python tools/docs_notes.py handoff-close f7ca20a8 "what happened"`

Sharp edge (rule 25) on `public/docs/spatial_geometry_examples.md`, aside "Agents: how these figures are captured":

> A `diffstatic_runner` fence is re-executed only when the build decides its capture is out of date, and that decision comes from a fixed digest list (`RUNNER_DIGEST_SOURCE_FILES` in `public/docs_hooks.py`) which covers the runner's own machinery and no stdlib source at all. So editing `contours.kt` leaves every digest unchanged and an incremental build is a cache hit. These figures were generated with `FIELD_DOCS_RUNNER_MODE=always`, which forces them; `dev_docs/docs_capture_staleness.md` has the rest.

Fix: let a page (or fence) declare the source files its captures depend on (e.g. a front-matter `capture-sources:` list, or derive from the page's `Source:` line), and fold their digests into the capture key in `public/docs_hooks.py`, so `incremental`/`strict` rebuild these figures when `contours.kt`, `voronoi.kt`, etc. change. Delete that paragraph from the page in the same change.

## Also filed from other pages (merged duplicates)

The same fix removes these paragraphs too; delete each in the same change.

### 39d5df56 — Make the docs capture digest cover the stdlib a page calls

Filed from https://openendedgroup.com/field4/mesh_examples/.

Sharp edge (rule 25) on public/docs/mesh_examples.md, aside "Agents: where the pictures come from":

> Neither kind of figure refreshes itself when `mesh_*.kt` changes: the runner's capture digest (`RUNNER_DIGEST_SOURCE_FILES` in `public/docs_hooks.py`) covers no stdlib source, so use `FIELD_DOCS_RUNNER_MODE=always`, and re-run `mesh.gallery` for the committed PNGs.

Fix: let incremental capture invalidate when source a page depends on changes (e.g. a per-page `capture-sources` front-matter list or digest of src/jvmMain/kotlin/afield/geometry/mesh/), and have the mesh.gallery committed PNGs flagged stale by a check. Delete that paragraph in the same change.

### bc62be15 — Include scene bridges like MeshFromDisk.kt in the docs capture digest

Filed from https://openendedgroup.com/field4/geometry_from_disk/.

geometry_from_disk.md (aside "Agents: forcing these captures to re-run") says:

> A fence is re-executed only when the build decides its capture is stale, from a digest over the runner machinery plus the **stdlib** source files the fence's code names. `MeshFromDisk.kt` is in `afield.graphics.scene`, not `afield.geometry.mesh`, so editing *it* does not invalidate these captures on its own. Force them with `FIELD_DOCS_RUNNER_MODE=always` after changing the bridges. `mesh_examples.md` carries the same caveat.

Fix: make the capture digest in public/docs_hooks.py resolve the source files of every symbol a fence calls (e.g. via the code-use index / package map after the refactor), so `meshFromAnyFile` in src/jvmMain/kotlin/afield/graphics/scene/MeshFromDisk.kt is covered. Delete this aside (and the matching one in mesh_examples.md) in the same change.
