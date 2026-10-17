# Remove the unused EndedException from Phased.kt

- **Handoff:** `91479687-1dd3-44ed-80e1-86cfbd64fa01` (project)
- **Filed from:** https://openendedgroup.com/field4/animation_time_model/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 91479687 "what happened"`

`public/docs/animation_time_model.md`, "Every name in `Phased.kt`" table, says:

> `type EndedException` | A bare `Throwable` subclass declared alongside `Frames`. Nothing in the tree throws or catches it.

Dead public type. Remove `EndedException` from `src/jvmMain/kotlin/afield/runtime/lifecycle/Phased.kt`
(check transcripts/templates for uses first) and delete the row in the same change. The same table
notes "No callers in the tree" for `forever` and `Frames.plus`; decide whether that note belongs on a
user page at all and drop it with this change.
