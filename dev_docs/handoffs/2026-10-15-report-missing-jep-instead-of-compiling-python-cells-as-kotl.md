# Report missing JEP instead of compiling Python cells as Kotlin

- **Handoff:** `b9135711-442e-4258-b4db-ff86cc9b455b` (project)
- **Filed from:** https://openendedgroup.com/field4/agent_transcripts/
- **Filed by:** human, 2026-10-15 18:35
- **Close with:** `python tools/docs_notes.py handoff-close b9135711 "what happened"`

Page `public/docs/agent_transcripts.md`, Python scene entry:

> This one has a prerequisite the other scenes do not — Field must have found an interpreter with
> JEP's native library in it *before it started*, or the four cells stay Kotlin and fail to compile.

A Python cell silently falling back to Kotlin and producing Kotlin compile errors is a sharp edge. Fix in code: when `mainLanguage` asks for Python and no JEP-capable interpreter was found at startup, the box should report one clear error ("Python unavailable: no interpreter with JEP found at startup; see python.md") instead of compiling Python as Kotlin. Delete the warning sentence in the same change.
