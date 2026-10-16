# Resolve slideshow templates after the declaring fragment

- **Handoff:** `dd242386-481a-4f84-a79b-94864f2b7b02` (project)
- **Filed from:** https://openendedgroup.com/field4/interactive_guides/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close dd242386 "what happened"`

`public/docs/interactive_guides.md`, "Add callbacks and live template values", says:

> A variable becomes visible to templates once the code declaring it has finished running, so the `//%-` line after the declarations matters here: it runs `version`, `clickCount` and `userChoice` as their own fragment before `show.start()` renders the first note. Without it the first note shows those slots empty.

This is an ordering trap. Fix in the slideshow runtime: resolve `{{...}}` templates lazily (at render time, after the current fragment completes) or re-render the open note when the fragment finishes, so a single-fragment box works. Then remove the `//%-` explanation (the fence is byte-pinned; a fence edit belongs in the same change) and delete the paragraph.
