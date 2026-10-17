# Guard cloth index ranges against removals below them

- **Handoff:** `b013b075-36e7-4891-a52c-2106b4ad3fa7` (project)
- **Filed from:** https://openendedgroup.com/field4/physics/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close b013b075 "what happened"`

Rule 25 sharp edge on public/docs/physics.md ("`Cloth`"):

> **A cloth is a contiguous index range and does not survive removals below it.** ... `applyQueuedRemovalsSwap` compacts and renumbers. Appending particles after a cloth is fine; deleting particles from underneath one is not.

Fix in code: have `applyQueuedRemovalsSwap` either refuse (error) a removal inside or below a registered cloth's range, or re-base the cloth handle. Delete the warning in the same change.
