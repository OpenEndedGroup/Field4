# History timeline should not show 0 boxes while counts load

- **Handoff:** `92e9ce52-dbdd-4ec0-84d9-f218598d041f` (project)
- **Filed from:** https://openendedgroup.com/field4/document_history/
- **Filed by:** human, 2026-11-02 10:30
- **Close with:** `python tools/docs_notes.py handoff-close 92e9ce52 "what happened"`

document_history.md, "Reading the timeline", says:

> Counts and diffs for older versions arrive a moment after the list, so an entry
> can briefly read `0 boxes`.

The history view (src/commonMain/resources/history.html) renders a placeholder count of 0 before the per-version stats arrive. Show a loading state (blank or ellipsis) instead of `0 boxes`, then delete this sentence in the same change.
