# PostScript box language — internal notes

User page: `public/docs/postscript.md`. Code: `src/jvmMain/kotlin/afield/lang/postscript/`.
Language half after John Earnest's "Let's Write a PostScript Interpreter"
(https://beyondloom.com/blog/postscript.html, licence unstated, so reimplemented, not copied).

- Why not ForthInterpreter: PostScript's typed objects, first-class executable arrays, literal names with
  `def`, and the dictionary stack do not fit Forth's colon-compile model; a parallel dialect inside it would
  have been the larger change.
- Paths are built directly as device-space FLines (CTM applied at each operator, arcs as one cubic per
  quarter turn in user space, then transformed — exact under any affine CTM). y is negated on the way out.
  Line width is scaled by sqrt|det CTM|.
- Step budget: `stepBudget` (20M) is counted in both `exec` and `runProc`. Counting only in `exec` let
  `{ } loop` spin forever (2026-11-15: a test JVM ran 34 min).
- Ghostscript check: `PostScriptInterpreterTest.treeBoundingBoxMatchesGhostscript` compares the tree's
  bounds with `gs -sDEVICE=bbox` (within 2 pt; gs adds half the line width). Skipped without `gs`.
- PostScript's default black is invisible on Field's dark lines panel; examples set a colour.
- Missing: text (`show`, fonts), clipping, dashes, even-odd fill (eofill = fill), `stop`/`stopped`,
  and the Vulkan renderer path (drawing goes to `Box.lines` only).
