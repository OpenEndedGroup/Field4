# Enforce non-suspending profileScope and measure blocks

- **Handoff:** `aa992b3d-f846-4e3c-8e95-69cb3149cdb8` (project)
- **Filed from:** https://openendedgroup.com/field4/profiling_timeline/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close aa992b3d "what happened"`

Rule 25 sharp edge on public/docs/profiling_timeline.md (stated twice: "Complete frames and named scopes" and "Explicit application frames and nested work"):

> `measure` and `profileScope("name") { … }` label synchronous work. Do not suspend or move threads inside these scopes.

Nothing enforces this. Make `measure`/`profileScope` take a non-suspending block (so the compiler refuses a suspension), and detect a thread change at scope exit (report it in the capture rather than silently mis-attributing). Delete the "Do not suspend..." sentences in the same change.
