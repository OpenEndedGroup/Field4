# Make shader inputs and tile geometry easier to edit

Baseline `7910450cc`. Three findings addressed:

- `9b47909795cf-01`: the first native triangle removes the inactive camera push-constant example and explains that positions are already in clip space. Attribute/varying locations and executable shader tokens are unchanged.
- `26520a320a82-01`: Petri sparks names perBurst and perRing next to slots, documents the matching SLOTS/PER/DUST shader constants and tells the reader to change them together before relaunching. Values remain 24/1400/2400, giving 40,800 particles.
- `36a34426dbf8-01`: WFC trace-art directions each have an explicit branch and loop, with the two adjacent pixel writes on separate lines. The function remains top-level and the two-pixel edge thickness is preserved.

Actual imports/ktfmt cover 13 Kotlin bodies and convert 15 collections. Actual GLSL formatting covers the triangle and spark shader properties. Reviewed executable tokens, containers, comments, raw strings and fragment markers are checked against the drafts. The selected idiom/collection audit is clean.

Both top-level transcript gate routes pass (petri_sparks compiles and wfc_worked_example executes); the nested triangle capture transcript is verified separately with its actual renderer. The original and revised WFC body execute against the real seeded solver: all resolved tile IDs and every generated atlas pixel have equal before/after hashes. The native triangle and sparks render, and the sparks box is launched again against its real parent. Native captures before/after rerun are inspected: the colored triangle and three-ring particle scene are visible. A real fragment checks the named counts and 40,800-particle formula. Native scenes are stopped afterward. Counts were not changed, so this is not evidence of arbitrary shader/host count edits being safe.

`changes.json` retains the reviewed edits. `verification.json` records source preservation. Reusable formatting and runtime harnesses plus exact gate results, before/after hashes, and native images are retained in `evidence.tar.gz`. These private verification images are not substituted for published docs captures.
