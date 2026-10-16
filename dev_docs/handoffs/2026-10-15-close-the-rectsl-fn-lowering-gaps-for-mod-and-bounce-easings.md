# Close the RectSL Fn lowering gaps for mod and bounce easings

- **Handoff:** `854917a3-7cb9-4814-be9d-4d30f0901a7f` (project)
- **Filed from:** https://openendedgroup.com/field4/rectsl_inverse_animation/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 854917a3 "what happened"`

Rule 25 known-gap list on rectsl_inverse_animation.md ("Refusals" table): `mod` and `bounce-*` are marked closable, "not written yet". Write the lowerings in afield/lang/rectsl/RectSLFn.kt (bounce via the clamped-ramp sum; mod with an exact remainder construction) and remove those rows in the same change, leaving only the pattern-track refusal.
