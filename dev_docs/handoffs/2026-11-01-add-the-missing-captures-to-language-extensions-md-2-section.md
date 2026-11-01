# Add the missing captures to language_extensions.md (2 sections)

- **Handoff:** `4881119b-9b5c-47a1-940d-dedeb5c2896a` (project)
- **Filed from:** https://openendedgroup.com/field4/language_extensions/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 4881119b "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### RectSL sketch (`public/docs/language_extensions.md:114`, high, docs-runner)

- **Point made:** A registered extension detects #rectsl triple-quoted strings inside Kotlin and gives them their own highlighting and diagnostics.
- **Capture:** An editor screenshot (editor-open <box> --property code, then screenshot-window) of a Kotlin box holding a #rectsl string, showing the embedded highlighting and a diagnostic squiggle.

### Flow and context lenses (`public/docs/language_extensions.md:189`, high, needs-capture-system-work)

- **Point made:** Flow lenses draw stack and track relationships into the text, and the context badge describes the word under the cursor.
- **Capture:** An editor capture with the RectSL flow-lens decorations visible and the lower-left context badge populated for a cursor position, driven by a page-eval or drive script that sets the cursor.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
