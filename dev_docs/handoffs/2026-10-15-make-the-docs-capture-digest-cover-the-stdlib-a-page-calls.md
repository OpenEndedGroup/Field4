# Make the docs capture digest cover the stdlib a page calls

- **Handoff:** `39d5df56-dd99-4041-b884-73bcd99ab104` (project)
- **Filed from:** https://openendedgroup.com/field4/mesh_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close 39d5df56 "what happened"`

Sharp edge (rule 25) on public/docs/mesh_examples.md, aside "Agents: where the pictures come from":

> Neither kind of figure refreshes itself when `mesh_*.kt` changes: the runner's capture digest (`RUNNER_DIGEST_SOURCE_FILES` in `public/docs_hooks.py`) covers no stdlib source, so use `FIELD_DOCS_RUNNER_MODE=always`, and re-run `mesh.gallery` for the committed PNGs.

Fix: let incremental capture invalidate when source a page depends on changes (e.g. a per-page `capture-sources` front-matter list or digest of src/jvmMain/kotlin/afield/geometry/mesh/), and have the mesh.gallery committed PNGs flagged stale by a check. Delete that paragraph in the same change.
