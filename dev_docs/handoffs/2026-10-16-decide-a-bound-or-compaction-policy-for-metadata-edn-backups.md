# Decide a bound or compaction policy for metadata.edn backups

- **Handoff:** `159925f3-f221-4e23-960a-fe0902471a76` (project)
- **Filed from:** https://openendedgroup.com/field4/document_on_disk/
- **Filed by:** human, 2026-10-16 10:00
- **Close with:** `python tools/docs_notes.py handoff-close 159925f3 "what happened"`

Split out of the handoff to remove the stale remote_js sidecar mapping and bound metadata backups by the 2026-10-16 handoff session. The remote_js part is done: the mapping had already gone from `Box.ioSeparateProperties`, AGENTS.md §7 now says `customization` → `js`, and the page paragraph is gone.

Still open, as a decision: `backups/metadata_backup_*.edn` is never pruned. The `metadata.edn` write calls `checkDifference(metadataPath, ednText)` with no `maxBackups`, so every save that changes the file adds a backup, with no limit. `public/docs/document_on_disk.md` says twice that this is the only artifact in the directory with no bound.

The history panel reads those backups (`versions.edn` transitions index into them), so a plain `maxBackups = N` would break history scrubbing past N. Choose one: (a) a count or age bound, with versions.edn rewritten to drop the transitions it removes; (b) compaction, for example keep every backup from the last day, then one per hour, then one per day, with versions.edn remapped; or (c) leave it unbounded on purpose and turn the page caveat into a statement of that policy.
