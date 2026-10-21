# Apply metadata.edn connection edits to open boxes

- **Handoff:** `cdeb94d8-32ad-4081-a725-dc4c73c224f8` (project)
- **Filed from:** https://openendedgroup.com/field4/editing_from_outside/
- **Filed by:** human, 2026-10-20 11:09
- **Close with:** `python tools/docs_notes.py handoff-close cdeb94d8 "what happened"`

Rule 25 sharp edge on `public/docs/editing_from_outside.md` ("Edit `metadata.edn` itself"):

> Field does not rewire the connections of a box already open from an edit to `metadata.edn`, and it ignores deletion of the file.

Fix in `FileWatchManager` / metadata reconcile: apply `children`/`parents` changes from a hand edit of `metadata.edn` to open boxes (and decide what deleting `metadata.edn` should do). Delete that sentence from the page in the same change.
