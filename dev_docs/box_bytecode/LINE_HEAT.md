# Line heat

Per-line execution counts for the box open in the editor, painted in the editor's line-number
gutter as a heat that cools and disappears when the line stops running. One of the box bytecode
passes (`dev_docs/BOX_BYTECODE_PASSES.md`); it keeps that note's rules (straight-line, stack-neutral
inserts at `atLineStart`, no frames).

Source: `src/jvmMain/kotlin/afield/runtime/bytecode/LineHeat.kt` (`LineHeatPass`, `LineHeat`, the
`line-heat-watch` handler), payloads `LineHeatWatch` / `LineHeatUpdate` / `LineHeatLine` at the end
of `rpc.kt`, the `line-heat` receiver in `afield/app/client/Main.kt`, and the gutter in
`src/commonMain/resources/lineHeatmap.js`. Tests: jvmTest `afield.runtime.bytecode.LineHeatPassTest`;
smoke `runtime.line-heat.smoke` (`LineHeatSmokeTest`).

## Using it

In the editor's command palette, **Toggle Live Line Heat**. It is off by default and remembered per
browser (`localStorage` `afield.lineHeatmap.liveLineHeat.visible`), like the churn and execution-history
heatmaps beside it. While it is on, the editor tells the server which box and property it has open
(`line-heat-watch`, re-sent when the selection changes), and that box is instrumented **from its next
run**: the code already loaded keeps running as it was compiled. Hovering a line number shows its
count since watching began.

The ramp is the heatmap file's own (blue, green, orange, red), log-scaled against the hottest line;
a line's heat decays with a 0.8 s time constant against a reference that decays over 4 s, so a line
that stops running cools through the ramp and is gone a few seconds later. A line that never ran is
not painted.

## How it works

**The probe.** `LineHeatPass.wants` is true only for a watched box and property. At each
`atLineStart` for a line in `1..sourceLineCount` it inserts `LDC site; INVOKESTATIC
LineHeat.hit(I)V`. One site per class and line (a `ClassScope.attachment`). Skipped:

- lines past the snippet's source (inlined library code reporting SMAP lines);
- a snippet whose source text is unknown (no line could be placed);
- in a K2 snippet class (one with `$$eval`): its constructor, whose only line number is the class
  header on line 1 (which made the `//%-` of a second fragment count once a run), and its leaf
  `get*`/`set*`/`is*` accessors, which carry a top-level property's declaration line and run when
  other snippets read it;
- a second `LINENUMBER` for the line already being executed when no stack-map frame lies between
  them (so no jump can land there): kotlinc emits those around inlined lambda bodies, and
  `repeat(4) { n++ }` counted 9 before this rule, 5 after (the call, then each body).

A count is the number of times execution *entered* the line. A `for` header counts n+1 (the test
before each iteration and the last one). Re-entering a line from another (a multi-line call whose
argument sits on the next line) counts again.

**The counters.** `hit` increments a plain `int` in a chunked table (1024 per chunk) indexed by site
id. Chunks are never replaced, only the outer array grows, so JIT-compiled code still holding an old
outer array counts into the same chunks; `register` makes a site's chunk at rewrite time. No
volatile read, no atomic: two threads counting one line at once may lose increments, which a heat
display tolerates. Counters wrap at 2^31; the publisher takes differences, which survive that.

**The publisher.** A `Runloop.preRun` hook (installed with the first watch, `setUnowned`) runs every
250 ms while anything is watched. For each watch it diffs the box's site counters against what it
last accounted, maps changed sites to box lines, and sends one `line-heat` (`LineHeatUpdate`: uid,
property, the FNV-1a hash of the box text it mapped onto, and per line the delta and a running
total) to that watch's websocket session. A watch whose session has gone is dropped. Box code that
blocks the Runloop delays the update until it returns.

**Mapping.** `Site.boxLine()` (by text, `SourceLineMap`) first. A line the macro pass rewrote in
place (`println(` compiles as `_printlnHere(n+1, `) is not proven by text, so it is placed between
the nearest proven lines above and below (within 12), which must agree on one offset; at the edge
of the compiled text one side is enough. This is the call-site anchors' `bracketed` rule, without a
call text to check, so it also refuses a blank or comment-only box line. When the box text changes,
the watch's totals restart.

**The client.** `lineHeatmap.js` applies an update only if it is for the box and property the
editor shows and the update's hash equals the hash of the editor's text (`codeHash`, the same
FNV-1a over UTF-16 units with `\r\n` as `\n`); any edit clears the painted heat. The decorations use
`lineNumberClassName` (`afield-line-heat-live-N`), so they share the line-number cell with the churn
heatmap; if both are on, churn's colour is drawn over live heat. `LineHeatmap.liveState()` reports
what is painted, for probes.

## Measured

- **Real K2 box code** (`runtime.line-heat.smoke`, scenario C, best of three on this machine): a
  `for` over 2e8 iterations with `s += i.toLong() xor (s ushr 7)` on its own line, 295 ms unwatched,
  439 ms watched (x1.49), 308 ms after the watch was switched off again (the probes really go at the
  next run).
- **Worst case** (`LineHeatPassTest.theProbeCostOnATightLoopIsMeasured`, test compiler, poll pass on
  in both): `s += i` over 2e7, 8 ms without, 42 to 44 ms with (about x5). A loop the JIT would
  vectorise or reduce loses that, because every iteration now stores to the heap. This is why line
  heat is opt-in and limited to the one box the editor has open.
- Rewrite cost is the shared pipeline's; the pass allocates one site per class and line.

## Verified

- `LineHeatPassTest` (11): loop body counts each iteration, the constructor's property initialiser
  counts (probes before and after `super()` verify); a `catch` block's first line verifies under
  the poll pass and counts; one site per class and line, a lambda body counts per call; no site past
  the source (inlined `repeat`), a cut-short source gets probes only on its lines, no source gets
  none; `wants` only for the watched box and property; publisher deltas and totals across two runs,
  restart on a text change; a watch started late does not count earlier runs; a macro-rewritten line
  is placed between its neighbours; the pinned FNV values the JS shares.
- `runtime.line-heat.smoke`: an unwatched run carries no sites; watched and run twice, a box with a
  `//%-` break gets totals `{1=2, 2=2, 4=2, 5=16, 6=14, 8=2}` (line 6 is the loop body in the second
  fragment; 2 and 8 are `println` lines placed between neighbours; the `//%-` line gets nothing),
  every update naming the hash of the box's text.
- In a browser (backdoor, `--with-playwright`, `editor-open` before the run, port 9479): the gutter
  painted after the run with the expected counts (the `if` and `return` of a function called 40
  times red, the `hits++` run 14 times amber, once-run lines blue), faded after 2.5 s; an edit
  cleared it, and a late update carrying the old text's hash was refused.

## Not done

- Counts arrive only when the Runloop is free: a single long blocking loop shows its heat after it
  returns, not during. Animated boxes (work per frame) update live.
- Lines inside a K2 snippet's own nested classes, objects and local functions count; lines in
  library code the box calls do not (only box bytecode is rewritten).
- A box's top-level `fun getX()` with no calls in it is skipped as an accessor.
- The client does not re-send its watch periodically; after a websocket reconnect, line heat starts
  again at the next selection change or edit.
