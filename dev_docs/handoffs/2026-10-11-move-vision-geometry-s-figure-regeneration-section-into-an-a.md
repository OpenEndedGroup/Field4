# Move vision_geometry's figure-regeneration section into an aside or dev_docs

- **Handoff:** `299f1be4-b18c-4034-a685-55735882591f` (project)
- **Filed from:** https://openendedgroup.com/field4/vision_geometry/
- **Filed by:** human, 2026-10-11 12:28
- **Close with:** `python tools/docs_notes.py handoff-close 299f1be4 "what happened"`

`public/docs/vision_geometry.md` ends with "## Where the pictures come from": how the page's figures are regenerated (the `vision.geometry-preview` FIELD_TEST, a bash fence with `VISION_GEOMETRY_PREVIEW_DIR`, a warning about writing into the working tree, and what the test asserts). That is for contributors, not readers (DOCS_WRITING_RULES: how a page was made goes in an aside or dev_docs). The prose pass could not fold it into a `??? aside` because that indents the bash fence, which changes fence bytes. Move the section (fence included) into a collapsed aside or into a dev_docs note linked from the page, keeping one sentence for readers about the winding assertion if wanted. Check CitationContractTest's citation floor for the page (6) still holds.
