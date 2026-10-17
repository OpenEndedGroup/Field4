# Remove stale remote_js sidecar mapping and bound metadata backups

- **Handoff:** `53ae1d9b-985c-4bc5-9308-78a76ae1fad0` (project)
- **Filed from:** https://openendedgroup.com/field4/document_on_disk/
- **Filed by:** human, 2026-10-15 13:50
- **Close with:** `python tools/docs_notes.py handoff-close 53ae1d9b "what happened"`

Rule 25 sharp edges on public/docs/document_on_disk.md:

> `remote_js` looks like a sidecar-backed property but is not one: it is the name of the RemoteJS/WebXR language root box in `templates/remote/metadata.json`. ... no `*_remote_js_.js` sidecar is ever written.

`Box.ioSeparateProperties` (and AGENTS.md section 7) still map `remote_js` -> `js`. Fix: remove the stale mapping (or make it real), update AGENTS.md section 7, and delete the paragraph.

Also: `backups/metadata_backup_*.edn` is never pruned (`checkDifference(metadataPath, ednText)` passes no `maxBackups`), which the page states twice as "the only artifact ... with no bound". Decide on a bound or a compaction policy compatible with the history panel; then remove those caveats. Marc may prefer this as a decision.
