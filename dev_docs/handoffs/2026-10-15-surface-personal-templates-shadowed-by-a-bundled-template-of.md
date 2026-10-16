# Surface personal templates shadowed by a bundled template of the same name

- **Handoff:** `2374601a-03af-4263-a128-729f431e8116` (project)
- **Filed from:** https://openendedgroup.com/field4/installation/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close 2374601a "what happened"`

`public/docs/installation.md` (Personal templates) warns about a silent shadowing trap:

> When two templates share a name, a bundled one wins, then one in `src/personal`, then one in your own directory, so give yours distinctive names.

A personal template whose name collides with a bundled one simply vanishes from the Templates list. Fix in `src/jvmMain/kotlin/afield/document/workspace/dir.kt` (personal template discovery): list a shadowed personal template anyway (e.g. suffixed with its source) or report the collision on the Welcome Templates list / log. Cover it in `PersonalTemplatesTest`. In the same change, delete "so give yours distinctive names" (and the precedence sentence if no longer needed) from installation.md.
