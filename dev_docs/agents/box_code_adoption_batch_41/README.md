# Visible labels, native chart clipping and private keyframe folders

Baseline `25e96457c`. Three frozen findings addressed:

- `cf8a600bbd52-01`: label generation no longer turns arbitrary font/layout failures into an empty layer. The actual graph renders all seven node numbers and retains eight stated / 34 implied edges. A controlled failure while generating label 3 reaches the box's error verdict; it is not accepted as a label-free successful figure.
- `28e3ddccbba4-01`: remove the private Liang–Barsky clipper and stale clipping/wrapping history. Pass complete contours to Chart, explicitly appending their first point to close the final edge. Actual Chart mark geometry remains within plotRect; every input contour is closed. The real Manifold level sets and TetGen study run, yielding 23,418 elements and volume 24.000000000000174, with zero outward box-boundary error. Both the corner magnification and convergence plot are inspected.
- `2f08a46f3b63-01`: replace the machine-global recursively deleted folder with a fresh private temporary parent and readable glow-looks leaf. Two full example runs each produce four keys, four versions and three slots. Their parents differ; all 12 key/history files from the first run remain byte-identical after the second.

Actual import/ktfmt covers 30 Kotlin states across the three pages, with no additional collection conversions and no GLSL edits. Reviewed-token, raw-string, prose and fold/fragment checks pass; the explicit collection/idiom audit is clean. Runtime coverage is the changed figures and probes, not all independent page states.

Published reachability.png, sharp_edge.png and browser.png are genuinely regenerated through the normal docs pipeline, including native prerequisites, output validation and atomic media/metadata handling. Final images are inspected. No capture is synthesized or replaced with a stand-in.

Retained diagnostics include the harness initially waiting on the wrong FLine channel (corrected to lines), the initially missing worktree TetGen wrapper (built from its pinned cached sources), and Field's elementwise List<Vec2> plus overload rejecting unequal lengths. The final closed-contour helper uses explicit mutable-list append, and all actual native tests pass after that repair. The intentional label failure is recorded separately as successful negative coverage.

Reusable harnesses, preservation records and evidence.tar.gz retain exact verdicts and coverage. The full corpus sweep remains incomplete.
