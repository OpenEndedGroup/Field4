# Shorter path construction on the FLine and animation pages

Baseline `1f6931722`. Four findings addressed: sampled rings, ribbons, callback rings, star and
projected helix use explicit path starts; the transform square uses rect; the boolean circle and
node dots use the standard circle helper; the animation border/disc use rect/circle with 28 polygon
segments retained. Explicit move/line operations remain in the sections teaching those operations.

The actual import/ktfmt pass visits 125 Kotlin states: 122 are formatter-eligible; three explicit
ellipsis/pseudocode excerpts are retained after parse refusals. Source tokens, fragments, raw
strings and surrounding content pass the reviewed-source proof; the two-page idiom audit is clean.
Six full authored geometry bodies have identical original/revised node fingerprints. Circle/star
and two dot helpers are sampled against their originals (circle/star tolerance 0.0001; tiny dots
0.03). The polygon disc drops a duplicate closing point, with sampled geometry/style preserved;
the border retains node geometry/style. Those differences are deliberate, not exact-node claims.

All 73 runnable page bodies compile through the real box compiler. That wider check exposed
existing overload ambiguities involving collection literals: direct drawing argument lists now
use the vararg API; overloaded plus/data calls use named, explicitly typed collection literals.
Six remaining failed bodies were repaired and recompiled; the other 67 retain exact matching
previously compiled source. Geometry calculations, point order and styles remain unchanged.

The initial incremental capture audit retained 73 committed assets; it was not runtime evidence.
Three affected drawings and the eight-second animation were then forced through the actual docs
runner: three screenshots, one video, no cache hits/reused assets/failures. The images and the
animation's final stopped red state are inspected. Other existing captures are retained.

Harness repairs are recorded too: an empty direct-formatter path list selected the broad corpus;
all 151 unintended tracked-file changes were archived and restored before continuing. The wrapper
now skips that call when no direct paths exist. Geometry tests caught the polygon's duplicate
closing point, and an over-broad vararg conversion was corrected to preserve five list concatenations.
No such intermediate changes enter this batch. The source proof, real compiler and capture passes
refer to the final source. See `changes.json`, `verification.json`, reusable harnesses and
`evidence.tar.gz` for exact programs, captures and verdicts.
