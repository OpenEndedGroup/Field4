# Split output_panel_sizing into a user page and internals

- **Handoff:** `038e37e2-d23b-4b8b-b779-ac9e5eb7cb0d` (project)
- **Filed from:** https://openendedgroup.com/field4/output_panel_sizing/
- **Filed by:** human, 2026-10-03 12:10
- **Close with:** `python tools/docs_notes.py handoff-close 038e37e2 "what happened"`

output_panel_sizing.md is almost entirely implementation detail: dozens of `outputareas2.js` / `Main.kt` citations, two copies of a predicate, the screenshot-prep feedback loop. Its readers are people writing iframe outputs, plus us. A user who hits a wrong-size panel needs about a screen: the symptoms table, "give your iframe a pixel width, or use `autosize=fixed`", and the fill-vs-fixed figure.

Proposal: keep a short user page (symptoms, the fix, the figure) in the outputs section, and move the measurement internals (the screenshot pass as a second sizer, the two `isFillWidthIframe` copies, ResizeObserver vs mouseup) into an internals page or `dev_docs/OUTPUT_PANEL_SIZING_COVERAGE.md`. The citations move with the prose, so citationGates needs re-running.
