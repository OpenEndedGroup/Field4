# Prune branchState snapshots that no branch can reach

- **Handoff:** `7e62f770-2bc4-4583-bbca-e8e6f52a6377` (project)
- **Filed from:** https://openendedgroup.com/field4/ai4_experiments/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 7e62f770 "what happened"`

`public/docs/ai4_experiments.md`, "Where it stops", says:

> State a box hands to the conversation is saved after every request that changes it, and nothing deletes old copies yet. A canvas the size of the Studio's takes about 28 MB each.

This is a sharp edge (rule 25): `branchState` snapshots (see `afield/ai/conversation/AiSessionTree.kt`) grow without bound. Fix in code: prune or deduplicate snapshots no longer reachable from any branch (or cap/compress them), then delete this bullet from the page in the same change.
