# Prune branchState snapshots that no branch can reach

- **Handoff:** `7c6dc1d2-2195-4f19-860d-de2cc0e5138f` (project)
- **Filed from:** https://openendedgroup.com/field4/ai4_experiments/
- **Filed by:** human, 2026-10-16 08:55
- **Close with:** `python tools/docs_notes.py handoff-close 7c6dc1d2 "what happened"`

Refiled from the code batch on 2026-10-16, not started. Snapshots are content-addressed blobs (AiStateBlobs, written by AgentExtensionRegistry.snapshotOne/snapshotBranchStates in afield/ai/tools/AgentExtensions.kt), referenced by sha from AiSessionTree turns (`branchStates`). Pruning deletes user data, so it has to be a mark-and-sweep over every session tree in the workspace that can reference a blob, not just the open one. It also needs a dry-run or report mode and a test with two documents sharing a blob.


`public/docs/ai4_experiments.md`, "Where it stops", says:

> State a box hands to the conversation is saved after every request that changes it, and nothing deletes old copies yet. A canvas the size of the Studio's takes about 28 MB each.

This is a sharp edge (rule 25): `branchState` snapshots (see `afield/ai/conversation/AiSessionTree.kt`) grow without bound. Fix in code: prune or deduplicate snapshots no longer reachable from any branch (or cap/compress them), then delete this bullet from the page in the same change.

## Also filed from other pages (merged duplicates)

The same fix removes these paragraphs too; delete each in the same change.

### 9e2c4c18 — Prune or bound AI4 Studio canvas branch-state blobs

Filed from https://openendedgroup.com/field4/ai4_painting/.

Rule 25 sharp edge on `public/docs/ai4_painting.md`, the note "A canvas is large":

> Each saved state of the Studio's canvas is about 28 MB on disk, kept in the document's `ai4_blobs/state`
> folder. Every request that changes the painting adds one, and none is deleted yet. The kept tools
> are small beside it, some 20 kB a request.

"None is deleted yet" means the document grows without bound. Fix: give AI4 branch-state blobs a retention or pruning policy (for example, keep states reachable from named forks and the latest N per branch, or an explicit prune command like `PruneStudyBuildsRequest`), and/or compress the saved canvas. Delete the note in the same change, or reduce it to a one-line fact if a size still matters to users.
