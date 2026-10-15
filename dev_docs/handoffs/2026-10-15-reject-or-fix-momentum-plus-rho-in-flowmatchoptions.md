# Reject or fix momentum plus rho in FlowMatchOptions

- **Handoff:** `8bd1895f-032b-4f6f-a773-4a7f1513cfc2` (project)
- **Filed from:** https://openendedgroup.com/field4/flow_matching/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close 8bd1895f "what happened"`

`public/docs/flow_matching.md`, "Settings that change the result":

> `momentum` should stay at zero unless `rho` is also zero. Smile applies momentum on top of the RMSProp-normalised gradient without the `(1 - β)` factor Adam uses, so enabling both multiplies the effective step by about ten and the run diverges.

A combination of options that is known to diverge should not be accepted silently. Fix in `FlowMatchOptions` / `flowMatch`: either `require(!(momentum > 0 && rho > 0))` with a message saying why, or scale the momentum term by `(1 - momentum)` so the combination behaves. Delete this paragraph in the same change.
