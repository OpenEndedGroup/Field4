# Make docs capture digests cover the stdlib source a fence exercises

- **Handoff:** `f7ca20a8-e53b-41f2-b63f-11c2ba7eff2d` (project)
- **Filed from:** https://openendedgroup.com/field4/spatial_geometry_examples/
- **Filed by:** human, 2026-10-15 13:45
- **Close with:** `python tools/docs_notes.py handoff-close f7ca20a8 "what happened"`

Sharp edge (rule 25) on `public/docs/spatial_geometry_examples.md`, aside "Agents: how these figures are captured":

> A `diffstatic_runner` fence is re-executed only when the build decides its capture is out of date, and that decision comes from a fixed digest list (`RUNNER_DIGEST_SOURCE_FILES` in `public/docs_hooks.py`) which covers the runner's own machinery and no stdlib source at all. So editing `contours.kt` leaves every digest unchanged and an incremental build is a cache hit. These figures were generated with `FIELD_DOCS_RUNNER_MODE=always`, which forces them; `dev_docs/docs_capture_staleness.md` has the rest.

Fix: let a page (or fence) declare the source files its captures depend on (e.g. a front-matter `capture-sources:` list, or derive from the page's `Source:` line), and fold their digests into the capture key in `public/docs_hooks.py`, so `incremental`/`strict` rebuild these figures when `contours.kt`, `voronoi.kt`, etc. change. Delete that paragraph from the page in the same change.
