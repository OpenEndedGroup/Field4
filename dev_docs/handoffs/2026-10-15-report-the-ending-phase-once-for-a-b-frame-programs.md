# Report the ending phase once for a + b frame programs

- **Handoff:** `3f98a796-0fd0-43c3-9ae4-6478d5a76b2c` (project)
- **Filed from:** https://openendedgroup.com/field4/animation_time_model/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 3f98a796 "what happened"`

`public/docs/animation_time_model.md`, aside "Running two frame programs at once with `a + b`", says:

> One rough edge is left, and it is cosmetic: all three end phases of `(a.end + b.end).end` route
> through the same hook pair, so the box can report `"ending"` more than once for one stop.

Fix in `src/jvmMain/kotlin/afield/runtime/lifecycle/Phased.kt` (`Frames.plus` forwarding of
`onAtEndStart`/`onAtEndFinish`): report "ending" once per stop (e.g. only the outermost end phase
fires the hooks, or the launcher de-duplicates). Add a `FramesSemanticsTest` case, and delete that
paragraph from the page in the same change.
