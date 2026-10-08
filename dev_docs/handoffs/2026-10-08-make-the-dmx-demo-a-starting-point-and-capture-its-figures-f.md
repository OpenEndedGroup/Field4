# Make the DMX demo a starting point and capture its figures from the runner

- **Handoff:** `a96e2452-692b-456f-b8c6-76ae6bbb9e4d` (project)
- **Filed from:** https://openendedgroup.com/field4/dmx_lighting/
- **Filed by:** human, 2026-10-08 06:10
- **Close with:** `python tools/docs_notes.py handoff-close a96e2452 "what happened"`

`public/docs/dmx_lighting.md` tells readers to "Open `public/docs/assets/dmx_lighting_demo/` as a
document", a workspace path (DOCS_WRITING_RULES rule 19). Its pictures were made by hand through
the backdoor on 2026-09-27, not by the docs runner (rule 14), and the aside says so.

1. Make the DMX demo bundle (checked by `DmxLightingDemoBundleTest`) a starting point on the
   welcome screen, and replace the path in the page's second paragraph with a link to it.
2. Replace the five hand-made images under `public/docs/assets/dmx_lighting/` with runner
   captures. The aside says the blocker is a capture needing two launched boxes exchanging
   packets; either teach the runner that (a transcript capture of the demo document) or make each
   figure's box send to itself over loopback, as `osc.md` does. Then cut the aside.
