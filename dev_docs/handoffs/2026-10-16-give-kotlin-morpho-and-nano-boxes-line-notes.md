# Give Kotlin, Morpho and Nano boxes line notes

- **Handoff:** `bcbe70d5-3102-45c7-975d-67947b56b3fb` (project)
- **Filed from:** https://openendedgroup.com/field4/language_extensions/
- **Filed by:** human, 2026-10-16 07:02
- **Close with:** `python tools/docs_notes.py handoff-close bcbe70d5 "what happened"`

Refiled from the code batch on 2026-10-16; not started. These are three separate integrations, and the Kotlin one is a REPL change:

- Line notes are produced inside each language's interpreter. LuaLanguage.kt, for example, records assigned names and run counts when AFIELD_LUA_LINE_NOTES is on. Python has PythonLineNotes.kt; Clojure, Forth and FieldML have their own. There is no generic provider a box type can register, so Morpho (afield/lang/morpho) and Nano (afield/lang/nano) each need their own recording.
- Kotlin needs per-statement values out of the K2 REPL snippet (`afield/runtime/kotlin/KotlinInterface.kt`, execution.kt), which today returns only the final value.


Rule 25 sharp edge on `public/docs/language_extensions.md` (section "Line notes"):

> Kotlin boxes have no notes of their own yet, because only a Kotlin box's final value comes back from a
> run. Inside a Kotlin box, a `#stacksl` or `#rectsl` string still gets its language's notes. Morpho and
> Nano boxes have none.

Fix in code: record per-top-level-statement results for Kotlin box runs (the K2 REPL snippet already
evaluates statements in order; report each one's value to the line-note provider the way Python/Clojure
do), and register line-note providers for Morpho and Nano boxes. Add rows to the page's language table.
Delete this paragraph in the same change.
