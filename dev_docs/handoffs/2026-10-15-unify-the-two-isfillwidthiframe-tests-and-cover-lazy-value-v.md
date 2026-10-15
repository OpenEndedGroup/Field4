# Unify the two isFillWidthIframe tests and cover lazy/value-viewer frames

- **Handoff:** `9c8e92d8-df02-4d31-b715-582f323701d8` (project)
- **Filed from:** https://openendedgroup.com/field4/output_panel_sizing/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 9c8e92d8 "what happened"`

Rule 25 sharp edge on public/docs/output_panel_sizing.md. The page carries two asides that exist only because the fill-frame test is implemented twice:

> The JS version routes the inline width through `hasConcreteCssSize`; the Kotlin version at `Main.kt` `private fun isFillWidthIframe` accepts empty, `auto`, or a `%` suffix and nothing else. They agree on every width Field emits, and would disagree about `width:fit-content` or `width:0`.

> The lazy-output and value-viewer frames are not under any geometry check, and the two `isFillWidthIframe` copies are never compared.

Fix: make the screenshot-prep pass in `src/jsMain/kotlin/afield/app/client/Main.kt` call the single JS `isFillWidthIframe` in `src/commonMain/resources/outputareas2.js` (export it, or mark the panel with a data attribute the JS sets), so there is one test. Add the lazy-output and value-viewer frames to the `h2-panel-sizing` / `output-panel-shrink` regressions in `tools/run_ui_regressions.py`. Delete the "The two fill-frame tests differ" aside and the last two sentences of "What the regression suite checks" in the same change.
