# Report SimdVerlet divergence instead of going silent on NaN

- **Handoff:** `ab360386-e7cc-404b-ac40-bcdc049abaa2` (project)
- **Filed from:** https://openendedgroup.com/field4/physics/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close ab360386 "what happened"`

Rule 25 sharp edge on public/docs/physics.md ("Divergence at the high end"):

> There is no automatic clamp and nothing prints a warning: a diverging run simply goes quiet, because a `NaN` coordinate draws nothing.

Fix in code: have `SimdVerlet.step` detect a non-finite coordinate (or peak strain past ~100 %) and report it once through the box's `error()`/a log channel, naming the stiffness x iterations x dt^2 product. No clamp needed (clamps guard failure, not range), just a message. Rewrite the paragraph in the same change to describe the warning.
