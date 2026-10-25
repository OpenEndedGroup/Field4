# Make vision.geometry-preview safe to write captures into the working tree

- **Handoff:** `1d17e427-cfba-48bc-98b9-4119c662327f` (project)
- **Filed from:** https://openendedgroup.com/field4/vision_geometry/
- **Filed by:** human, 2026-10-15 13:47
- **Close with:** `python tools/docs_notes.py handoff-close 1d17e427 "what happened"`

The "Agents: where the pictures come from" aside on `public/docs/vision_geometry.md` carries a sharp-edge warning (rule 25):

> Render to a temp directory and copy the files in afterwards. Writing them straight into the working
> tree, while the runner's Field server is watching it, takes the child process down mid-run.

Fix in code: have the `vision.geometry-preview` FIELD_TEST (or `tools/run_field_tests.py`) render to its own temp directory and copy the finished PNGs into `public/docs/assets/vision_geometry/` after the child exits (or exclude the asset dir from the server's file watch), so `VISION_GEOMETRY_PREVIEW_DIR` can point straight at the asset folder. Delete the paragraph (and the temp-dir instruction) in the same change.
