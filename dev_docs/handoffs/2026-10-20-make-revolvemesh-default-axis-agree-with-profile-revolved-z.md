# Make revolveMesh default axis agree with Profile.revolved (Z)

- **Handoff:** `9cc22c29-e30b-405e-8a97-750a9c3a8e15` (project)
- **Filed from:** https://openendedgroup.com/field4/solid_notation/
- **Filed by:** human, 2026-10-20 11:10
- **Close with:** `python tools/docs_notes.py handoff-close 9cc22c29 "what happened"`

`public/docs/solid_notation.md`, "Axis conventions", says:

> `Profile.revolved()` turns about **Z**, profile at `x ≥ 0`, counter-clockwise from `+X`. The mesh
> lathe `revolveMesh` defaults to Y; pass `axis = RevolveAxis.Z` to get the same solid from it.

The second sentence exists only to warn that two revolve operations disagree on their default axis.
Fix in code: make `revolveMesh`'s default axis agree with SolidSL/Manifold (Z), or require the axis
explicitly, migrating callers and docs fences. Delete the second sentence in the same change.
