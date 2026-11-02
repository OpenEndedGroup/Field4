# Keep version names stable when backup files are deleted

- **Handoff:** `b4cdbddd-e840-4b37-911b-c7764e0df0d3` (project)
- **Filed from:** https://openendedgroup.com/field4/document_history/
- **Filed by:** human, 2026-11-02 10:30
- **Close with:** `python tools/docs_notes.py handoff-close b4cdbddd "what happened"`

document_history.md, "Where versions come from", says:

> A document that has been saved only once has nothing earlier to show. Deleting backup files
> renumbers every later version, and version names follow the numbers, so leave `backups/` alone if
> you have named versions.

Version names in `versions.edn` are keyed by backup index, so deleting a backup silently reattaches names to the wrong versions. Fix in code (afield/document/history): key names by a stable identity (backup filename or content hash/timestamp), or detect gaps and keep the numbering. Delete the warning sentence in the same change (keep the first sentence).
