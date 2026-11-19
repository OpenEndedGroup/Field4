# Call-site anchors

The box helpers that draw next to the line that called them (`history`, `histogram`, `stats`,
`toggle`, `beep`, `bop`, `liveNumber`, `distribution`, `pianoRoll`, `pat`) used to find that line
on every call with `locationForTextStringOnStack`: `Exception().stackTrace`, a filter on frames
whose file name contains the box id, a split of the box's code, and a forward text search from the
frame's line. That is slow, and wrong after a `//%-` break, because a fragment's bytecode lines are
fragment-relative. The call-site anchor pass gives them the line from bytecode instead.

It is one pass of the box-bytecode pipeline; the shared rules are in
[`../BOX_BYTECODE_PASSES.md`](../BOX_BYTECODE_PASSES.md).

Source: `src/jvmMain/kotlin/afield/runtime/bytecode/CallSiteAnchors.kt`. Tests: jvmTest
`afield.runtime.bytecode.CallSiteAnchorPassTest` (targets in `AnchorTargets.kt`), and scenario G
of the smoke `runtime.runaway-box-interrupt.smoke`
(`src/jvmMain/kotlin/afield/runtime/lifecycle/RunawayBoxInterruptSmokeTest.kt`).

## How it works

- `@StackAware` (`BINARY`, functions) marks a helper whose first act is `CallSites.take`.
- `CallSiteAnchorPass` (`id = "anchors"`) asks `CallTargets` about every call in box code, from the
  scan's `MethodFacts` before emitting anything, so a method with no anchored call gets no visitor.
  For a call to a `@StackAware` target (a `$default` stub counts as its function; owners in
  `ClassScope.isInserted` are skipped), it allocates a site and inserts
  `LDC site; INVOKESTATIC CallSites.arm(I)V` from `visitMethodInsn`, immediately before the call:
  after the arguments are evaluated, so a `@StackAware` call inside an argument takes its own slot
  first. No jump, label, local or frame.
- The site's detail is a `CallSiteAnchor`: the target name without `$default`, and the call's
  ordinal among the calls *of that name* on its compiled line (not of that `owner.name`: the
  column is found by text, and `history(x)` and `history(x, 50)` call `history$default` and
  `history` but are both the text `history`).
- `CallSites.arm` writes the site id into a per-thread slot (`ThreadLocal` holding a mutable int).
  `CallSites.take(box, name, text, end, offset)` reads and clears it, refuses a site of another
  box or another target name (counted in `CallSites.refused`), and answers a `Location`: the box
  line, and the column of the ordinal-th occurrence of `text` on that line, positioned exactly as
  `locationForTextStringOnStack(text, box, end = end, offset = offset)` positions its match. Null
  sends the helper to the stack search (`anchoredLocation` in `decorations.kt` does both).
- The answer is cached on the site per version of the box's code (identity of the code string), so
  a helper called every frame from an unchanged box costs a `ThreadLocal` read, an array read and
  an identity compare.

### Which line

`Site.boxLine()` first: the compiled line maps to a box line only if the two texts are identical.
That fails on two common lines, so the anchor adds one rule of its own (`CallSiteAnchor.bracketed`):

- a line a macro rewrote. `println(` compiles as `_printlnHere(4+1, `, so
  `println(m.liveNumber(7.5))` is never text-identical to its box line (seen in smoke G: the
  site's compiled text is `_printlnHere(4+1, "anchored live=" + m.liveNumber(7.5)); m.beep()`);
- a line the user is editing, which for `liveNumber` is the point: `liveNumber(7.5)` edited to
  `liveNumber(8.5)` must be read at once, without a recompile.

Such a line is placed by its nearest proven neighbours above and below (within 12 lines): both must
give the same offset (one suffices when the other side has none), and the box's line must still
contain the call's text at the ordinal. Otherwise the anchor answers null and the stack search
runs. `pat` asks for the line only, but passes its text `pat(` so this check applies to it too.

An unanchored take returns null and the helper falls back: calls from library code (a `chords(...)`
that calls `pat` inside), Python, the pass switched off, inlined code (an SMAP line past the
compiled text), a line whose call text is gone, a box compiled from a property other than `code`
(every helper reads `box.code`).

### The stale slot

Nothing runs between `arm` and the target's `take` but the call itself, except when that call
throws first: a default argument that throws (evaluated in the `$default` stub), a parameter null
check, a target class that fails to initialise. The slot then stays armed. The next `arm`
overwrites it; a take for another box or another target name refuses it. What is not refused is a
stale slot of the same box and the same target taken by an uninstrumented call of that target,
which then reports the line of the call that threw. That needs a throw-before-entry followed by a
library-internal call of the same helper for the same box, with no anchored call in between.

## Switch

On by default. `AFIELD_BOX_CALLSITE_ANCHORS=off` (or `-Dafield.boxBytecode.callSiteAnchors=off`)
at startup, or `CallSiteAnchorPass.enabled = false` at run time, turns it off for code compiled
from then on (`wants` reads it at each evaluation); the helpers fall back to the stack search.

## Measurements

Per call of `history()`, live, in scenario G of the runaway smoke (a K2 box on the Runloop thread,
20,000 calls after 5,000 warm-up calls, the whole `Marker.history`, including its summary buffer
and rate-limited SVG output): **707 ns** anchored, **13,372 ns** with the pass off (the stack
search). An earlier run measured 748 and 11,407 ns.

The lookup alone, in `CallSiteAnchorPassTest.perCallCostOfTheAnchorAgainstTheStackSearch` (a
target that does nothing but find its location, 43-frame stack, 205- to 224-line box): 45-52 ns per
call anchored, 16,600-35,000 ns per call by stack search across three runs. The stack search grows
with stack depth and box length; the anchor does not.

Rewrite: 419 us per class for the test's box-code class (six anchored calls, warm `CallTargets`
cache, `rewriteClass` with this pass only); the class grew from 2,020 to 2,132 bytes. Each anchored
call adds an `LDC` and an `INVOKESTATIC` (5-6 bytes) and a site (about 100 bytes, never freed).

## Not done

- A `@StackAware` target cannot be an inline function; an inline wrapper's own call to one carries
  an SMAP line and falls back.
- Nested same-name calls (`m.history(m.history(x))`) are numbered in evaluation order, inner first,
  while the text is numbered left to right, so their columns are swapped. Lines are right.
- `Marker.bop(text)` is not annotated (it does not find a location); `BoppableString.bop` is.
- `.patHighlight(` (`patHighlightHintLine` in `TidalPatterns.kt`) still uses the stack search; it
  was not in the brief.
