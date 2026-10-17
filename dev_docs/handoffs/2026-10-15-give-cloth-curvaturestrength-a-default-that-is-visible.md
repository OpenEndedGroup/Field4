# Give Cloth.curvatureStrength a default that is visible

- **Handoff:** `f5fa7e95-aadd-4e05-b480-41b536b772a1` (project)
- **Filed from:** https://openendedgroup.com/field4/physics/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close f5fa7e95 "what happened"`

Rule 25 sharp edge on public/docs/physics.md ("Curvature and the size of its default"):

> The trap is that `curvatureStrength` defaults to **0.05**, and it accumulates into the *same* `ax/ay/az` the constraint solve does, so it is competing against `stiffness × iterations`, not against 1.

and "Next to springs this stiff, the default does almost nothing. If you turned curvature on, expected the sheet to flatten, and saw nothing happen, that is why."

Fix in code (`afield/simulation/physics/Cloth.kt`): express curvature relative to the stiffness x iterations product (or pick a default that is visible at the shipped product of 960), so the default does something. Requires recapturing `assets/physics/curvature.png` and the tuning ladder captions; delete the "trap" paragraphs in the same change.
