# Stop FileWatchManager silently dropping external metadata.edn edits

- **Handoff:** `8cfebb3f-39d6-48fb-9e85-a6ceb6585dd4` (project)
- **Filed from:** https://openendedgroup.com/field4/document_on_disk/
- **Filed by:** human, 2026-10-15 13:50
- **Close with:** `python tools/docs_notes.py handoff-close 8cfebb3f "what happened"`

Rule 25 sharp edges on public/docs/document_on_disk.md, "Editing the directory under a running Field":

> *changing a property of a box that is already open* does not — that edit is not applied, and the next save overwrites it from memory. Hand-edit the sidecar instead.

> An external edit that happens to leave the file exactly the same length, moments after Field's own save, is therefore invisible.

Fix in `FileWatchManager`: (a) `reconcileMetadata` should apply property changes to boxes already in the session (or at least warn loudly instead of silently overwriting on next save); (b) `isInternalSaveEvent` should compare a content hash of Field's own write rather than size+mtime windows. Delete both warnings from the page in the same change.
