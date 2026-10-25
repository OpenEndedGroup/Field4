# Existing path helpers in drawing and painting lessons

Baseline `8784d46dd`. Eleven findings addressed across ten authored sources.
[Reviewed edits](changes.json), [source proof](verification.json), [runtime harness](rehearse.py),
and [evidence](evidence.tar.gz) record the changes and their limits.

Mark translation uses toFLine before translating, preserving world-space rotation. Rectangle
construction uses FLine.rect; the watercolor adapter is named rectBetween to make its endpoint
arguments explicit. The ordinary global rect constructs a Rect, so paint-area calls explicitly
use FLine().rect. The exact-boolean lesson uses the existing circle helper for outlines and dots.
Sampled paths use breakNext or data, retaining existing closure and styling. The flow helpers
retain empty-list behavior with an explicit guard before the data command's required move point.

The actual import pass and ktfmt cover seventy Kotlin bodies/states, including fifty-nine Markdown
states; eight collection factories become literals. One untrimmed raw string is restored after
formatting so its exact contents remain intact. The mechanical wrapper performs that preservation
step reproducibly. Token, raw-string, fragment and container proofs pass; the explicit ten-file
idiom/collection audit is clean.

All three affected top-level transcripts pass together through their real execution classification:
drawn paint, exact FLine booleans and inertial movement. Actual helper comparisons cover the oval,
star, reticle, packing frame, and open/closed flow paths (empty, single-point and multiple-point
inputs). Coordinates agree within 0.0001 and styles are unchanged. The cubic circle approximation
agrees within 0.0001; the former hand-drawn tiny dots differ from the standard circle by less than
0.03 units along sampled curves. This is an intentional standardization, not exact node identity.
The typography lesson's actual rings have identical node fingerprints before and after.

An actual notation stroke rotated by pi/2 then translated has its expected vertical world endpoints,
which the previous raw-point translation discarded. Its real SVG output was captured. The full
paint-area body initially failed because the private JVM searched only its worktree's empty native
build directory. The machine already had the library in the primary checkout. Selecting it through
the documented afield.paint.library override makes the actual work/stipple/outline example run and
render successfully (engine 5). Its real output was captured and visually inspected. See the
[corrected native evidence](native-paint-recheck/README.md). The original failed execution remains
in the first evidence archive as history; it is not a current availability limitation. The harness
now runs the paint case instead of retaining an old unavailable verdict.

Harness repairs are also retained: extraction originally stopped at the first lambda in dots,
and typography fold directives had to be removed before execution. Matching successful independent
programs are reused after those concrete repairs rather than rerun wholesale.
