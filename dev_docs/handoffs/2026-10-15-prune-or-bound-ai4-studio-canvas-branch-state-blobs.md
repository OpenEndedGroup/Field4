# Prune or bound AI4 Studio canvas branch-state blobs

- **Handoff:** `9e2c4c18-4c8d-4275-bf55-55b2fbe3c8b5` (project)
- **Filed from:** https://openendedgroup.com/field4/ai4_painting/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 9e2c4c18 "what happened"`

Rule 25 sharp edge on `public/docs/ai4_painting.md`, the note "A canvas is large":

> Each saved state of the Studio's canvas is about 28 MB on disk, kept in the document's `ai4_blobs/state`
> folder. Every request that changes the painting adds one, and none is deleted yet. The kept tools
> are small beside it, some 20 kB a request.

"None is deleted yet" means the document grows without bound. Fix: give AI4 branch-state blobs a retention or pruning policy (for example, keep states reachable from named forks and the latest N per branch, or an explicit prune command like `PruneStudyBuildsRequest`), and/or compress the saved canvas. Delete the note in the same change, or reduce it to a one-line fact if a size still matters to users.
