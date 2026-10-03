# Restructure three_output: move late-viewer replay and viewer tools out of the user flow

- **Handoff:** `07148c8a-f23f-41e7-af33-b97a6bb82718` (project)
- **Filed from:** https://openendedgroup.com/field4/three_output/
- **Filed by:** human, 2026-10-03 12:14
- **Close with:** `python tools/docs_notes.py handoff-close 07148c8a "what happened"`

three_output.md has three structural problems prose edits cannot fix:

1. The last section, "Data colours and late viewers", ends with a paragraph on how Kotlin `display` panels are retained and replayed to a late browser (128 panels, eight million characters). That is about all box output, not three.js. It belongs on the outputs page, or in an aside.
2. "Optional viewer tools" (ES-module tool descriptors, snapshot/validate/restore, `FieldViewer.tools.register`) is written for developers and agents, and sits between the gallery and the last user section. Move it to the end of the page, under an internals heading, or onto its own page.
3. The page is long (around 4,000 prose words). "Which renderer draws this" is a site-wide map of Field's renderers. A reader of this page needs a short "three or CPU rasterizer" choice, and the full table could live on a graphics overview page that other pages link to. Inbound anchor: output_panel_sizing.md links `#which-renderer-draws-this`.
