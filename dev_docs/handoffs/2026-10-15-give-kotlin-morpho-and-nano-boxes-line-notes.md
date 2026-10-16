# Give Kotlin, Morpho and Nano boxes line notes

- **Handoff:** `d436563a-05f7-4a8d-9447-bcfe306622a6` (project)
- **Filed from:** https://openendedgroup.com/field4/language_extensions/
- **Filed by:** human, 2026-10-15 13:47
- **Close with:** `python tools/docs_notes.py handoff-close d436563a "what happened"`

Rule 25 sharp edge on `public/docs/language_extensions.md` (section "Line notes"):

> Kotlin boxes have no notes of their own yet, because only a Kotlin box's final value comes back from a
> run. Inside a Kotlin box, a `#stacksl` or `#rectsl` string still gets its language's notes. Morpho and
> Nano boxes have none.

Fix in code: record per-top-level-statement results for Kotlin box runs (the K2 REPL snippet already
evaluates statements in order; report each one's value to the line-note provider the way Python/Clojure
do), and register line-note providers for Morpho and Nano boxes. Add rows to the page's language table.
Delete this paragraph in the same change.
