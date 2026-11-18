# Box bytecode passes

Field rewrites every class kotlinc emits for a Kotlin box before the snippet is evaluated. The
runaway-box interrupt (`dev_docs/RUNAWAY_BOX_INTERRUPT.md`) was the first rewrite. This note is the
shared contract it was generalised into, and the decisions for the four passes planned on top of
it: call-site anchors, line heat, per-function cost, and thread-ownership guards.

Source: `src/jvmMain/kotlin/afield/runtime/bytecode/` (`BoxBytecode.kt`, `SnippetSites.kt`,
`CallTargets.kt`). Tests: jvmTest `afield.runtime.bytecode.BoxBytecodeTest`, with
`BoxBytecodeTestSupport` for every pass's own tests; the interrupt keeps
`afield.runtime.lifecycle.BoxInterruptInstrumentationTest` and the smoke
`runtime.runaway-box-interrupt.smoke`.

## The pipeline

`KotlinInterface2.evaluate*` calls `BoxBytecode.instrument(script, origin)` just before
`evaluator.eval`, where the interrupt used to call `BoxInterrupt.instrument`. For each `.class`
entry of the snippet's `KJvmCompiledModuleInMemory.compilerOutputFiles`:

1. A read-only **scan** (`ClassReader` with `SKIP_FRAMES`) records, per method, the call
   instructions in order (owner, name, descriptor, line, and their ordinal and count among calls
   to the same `owner.name` on that line), the distinct lines, the number of backward jumps, and
   the instruction count: `MethodFacts`, on `MethodScope.facts`. A pass can decide before it emits
   anything (a "leaf" method with no calls and no loops; how many `history(` calls share a line).
2. The **rewrite**: one `ClassWriter(reader, COMPUTE_MAXS)` and, per method with code, a chain of
   method visitors, one per wanted pass, headed by a line tracker that keeps `MethodScope.line`
   equal to the last `LINENUMBER` seen.
3. The class is recorded in `SnippetClasses` against its `SnippetOrigin`, and each pass is told
   (`classRewritten`); the interrupt records its culprit label there.

`AFIELD_BOX_BYTECODE_DUMP=<dir>` (or `-Dafield.boxBytecode.dump`) writes each rewritten class
before and after, with the compiled source, so `javap -c -l` shows exactly what a pass did.

### The one rule: inserted code is straight-line and stack-neutral

The writer never recomputes stack-map frames. `COMPUTE_FRAMES` would call
`getCommonSuperClass`, which loads classes through a loader that may not see the snippet's
classes, at rewrite time, on whatever thread is evaluating. So every pass inserts only:

- constants (`LDC`, `SIPUSH`), or a `DUP` of a single-slot value already on top of the stack, and
- a static call that consumes exactly what was pushed and returns `void`.

No jump, no label, no `try`/handler, no new local, no frame. A decision ("is this thread
allowed?", "is the counter table big enough?") goes inside the static method.

*Where* matters as much as *what*. Insert at `visitCode`, or immediately before an original
instruction (from its `visit*Insn` override). Never insert from `visitLabel`, `visitLineNumber`
or `visitFrame`: `ClassReader` visits an offset as label, line number, frame, instruction, so
code inserted at the line number lands between a branch target and its frame. The first framework
test did exactly that, a probe after each `LINENUMBER`, and every `catch` block failed
verification ("Expecting a stackmap frame at branch target"). `BoxMethodVisitor.atLineStart(line)`
is the safe start-of-line point: it fires just before the line's first instruction.
`BoxBytecodeTestSupport.rewriteAndLoad` links the rewritten class, which runs the verifier, so a
pass that breaks either rule fails its unit test.

This rule is why the cost pass has no `try/finally` (below), and it is the answer to "which passes
may need `COMPUTE_FRAMES`": none. A future pass that truly needs a branch in the snippet's code
must bring its own frame computation with a `getCommonSuperClass` that reads class bytes through
`CallTargets` rather than loading, and must say so here first.

### Chain order and what a pass sees

`BoxBytecode.passes` fixes the order once: interrupt poll, thread guard, call-site anchor, line
heat, cost. The first is outermost. A pass sees the original instructions plus whatever the passes
before it inserted, so a pass that inspects calls skips owners in `ClassScope.isInserted` (each
pass declares the owners it calls in `inserts`). Before-a-call inserts from outer passes come
earlier in the output, so the anchor's slot is armed immediately before the target is entered.

The four planned passes are named, not referenced (`planned("afield.runtime.bytecode.LineHeatPass")`),
so each can be built on its own branch without editing `BoxBytecode.kt`: a name with no class yet
is skipped. Each pass is a Kotlin `object` with exactly that name implementing `BoxBytecodePass`.
When all four have landed the names become references.

### Per-module bookkeeping: rewrite from the originals

`wants(origin)` is asked at every evaluation. The module is rewritten from its **original**
compiler output with the passes that want it; the originals are kept weakly with the module. If the
wanted set is the one the module already carries, nothing happens: that is what stops the rerun
compile cache's re-evaluation (`RerunCompileCache.kt`) from instrumenting twice. If it differs (line
heat switched on for the box since), the next evaluation gets the new set, and switching a pass off
really removes it. A class already loaded keeps the code it was loaded with, and a module's lazily
loaded nested classes may carry a newer set than its main class; every pass is per-class
independent, so that mix is harmless. Consequence for the interrupt: re-evaluating a cached
snippet now follows the **interruptible box code** switch as it is then (it used to keep whatever
the first evaluation got).

Rewriting is serialised per module, so different boxes rewrite in parallel.

### Origins, sites and box lines

`SnippetOrigin` is one compiled snippet: box id (box held weakly), source property, label, the text
the compiler compiled (`BoxBytecode.noteSource` from `compileLocked`), the engine's loader (weakly),
and a reader for the box's code *now*.

**Snippet lines are not box lines**, and `locationForTextStringOnStack` is wrong to assume they are:

- a selection run is padded with `"\n" * exec.line`, so its lines do match;
- a box with `//%-` breaks (or an implicit break before `moduleHere`) is compiled one fragment at a
  time, so line 1 of the second fragment is a later line of the box
  (`standardExecutionPractice` and the launch path rebase compile diagnostics with
  `rebaseFragmentDiagnostics`, but nothing rebases runtime line numbers). Measured in scenario F
  of the runaway smoke: a frame on box line 4, the second line after a `//%-` on line 3, reports
  bytecode line 2, and `boxLine` maps it back to 4;
- macro expansion may change lines (an `Insert.start`/`afterImports`, a multi-line `here`);
- inlined library code reports lines past the end of the source (the SMAP range);
- the user edits the box while it runs.

So `SnippetOrigin.boxLine(line)` maps by **text**: a compiled line maps to a box line only when the
two are identical after trimming trailing space, the profiling timeline's rule
(`mappedProfileLine` in `profileTimelineModel.js`). `SourceLineMap` finds the dominant offset from
lines unique on both sides, walks forward, re-anchors within ±12 lines on a distinctive line (never
on a lone `}`), and answers -1 when nothing is proven. It is computed once per version of the box's
code. A display must still check the box's text has not changed since (the client does, as the
profile gutter does).

`SnippetSites.allocate(...)` hands out a dense int **site id** per (class, method, line, kind,
detail). A pass bakes the id into the bytecode as a constant before its static call; the runtime
side calls `SnippetSites[id]` (a lock-free array read) to learn the box, class, method and line, and
keeps its own per-site data in arrays indexed by id. Sites are never freed; they live as long as the
REPL history that keeps the snippet's classes alive.

`SnippetClasses.boxFrame(stack)` turns a stack (a failure path's, the watchdog's) into the innermost
box frame with a proven box line, for reports.

### Facts about call targets without loading classes

`CallTargets.annotations(loader, owner, name, desc)` reads the owner's `.class` **as a resource**
from the snippet's loader, parses it with `SKIP_CODE`, and walks superclass and interfaces until it
finds the method, caching per loader and owner. JDK and Kotlin stdlib owners are never read. A call
to a default-arguments stub `f$default` reports the annotations of `f` (the stub carries none).
Annotations need `BINARY` retention only. This is how a pass learns that a call's target is
`@StackAware` or `@ThreadOwned` without initialising anything.

### Gates

A pass that is off inserts nothing: `wants` is the gate, read at evaluation. Runtime gates inside a
probe (sampling, a per-box switch read per call) follow the interrupt's measured rule: an *opaque*
read (`getOpaque`, or a plain field), never a volatile read on a back-edge or per-line path, which
stopped the JIT vectorising a loop (~20x, `RUNAWAY_BOX_INTERRUPT.md`).

## The four passes

Each pass is built on its own branch from this one and documents itself in its own note under
`dev_docs/box_bytecode/`, linked here once it lands.

| pass | object | branch | note |
|---|---|---|---|
| call-site anchors | `CallSiteAnchorPass` | `asm/callsite-anchors` | `dev_docs/box_bytecode/CALLSITE_ANCHORS.md` |
| line heat | `LineHeatPass` | `asm/line-heat` | `dev_docs/box_bytecode/LINE_HEAT.md` |
| per-function cost | `BoxCostPass` | `asm/box-cost` | `dev_docs/box_bytecode/BOX_COST.md` |
| thread-ownership guards | `ThreadOwnershipPass` | `asm/thread-ownership` | [`dev_docs/box_bytecode/THREAD_OWNERSHIP.md`](box_bytecode/THREAD_OWNERSHIP.md) (built; on by default; a guarded on-thread call in a loop 0.35 ns/call, same as unguarded; smoke `runtime.thread-ownership-guards.smoke`) |

### Call-site anchors: `@StackAware`

The user's idea is right, with one change to the mechanism. `Marker.history`/`histogram`/`stats`
(and `toggle`, `beep`, `bop`, `liveNumber`, `distribution`, `pianoRoll`, `pat`) call
`locationForTextStringOnStack` on every call: `Exception().stackTrace`, a filter on `fileName`
containing the box id, a split of the box's code, a text search forward from the frame's line.
Every frame, and wrong after a `//%-` break or a line-shifting macro.

- `@StackAware` (`BINARY`, on functions) marks a target. The pass asks `CallTargets` about every
  call in box code; for an annotated target it allocates a site (detail: target name, the call's
  ordinal and count among same-named calls on its line, from the scan) and inserts
  `LDC site; INVOKESTATIC CallSites.arm(I)V` immediately before the call.
- The target's first act is `CallSites.take(box, "history")`: it reads and clears this thread's
  slot, checks the site's box and target name (a stale slot left by a call that threw before entry
  is refused), and returns the `Location` (proven box line, column of the ordinal-th occurrence).
  Null falls back to `locationForTextStringOnStack`, which stays for uninstrumented callers
  (library helpers, Python, a disabled pass).
- Not a synthetic overload with an extra parameter: rewriting the call's descriptor needs a twin
  of every target and breaks on `$default` stubs, interface dispatch and the mask arguments; the
  slot needs nothing from the target but its first line.
- Inline functions cannot be targets (the call vanishes into the snippet); an inline wrapper's own
  call to a `@StackAware` function carries an SMAP line past the source, so it falls back.
  Extension functions are static on their `*Kt` facade and resolve like any static.

### Line heat

Per-line counters for the box open in the editor, painted as a fading gutter heat. `wants` is true
only for the watched box, so every other box carries nothing; enabling takes effect at the box's
next run (the module is rewritten from its originals). A probe `LDC site; INVOKESTATIC
LineHeat.hit(I)V` at `atLineStart` for each line in the snippet's own range, one site per class and line;
counts live in an int array indexed by site id; a Runloop-side publisher diffs them a few times a
second, maps sites to box lines, and sends them to the editor, reusing `lineHeatmap.js`'s gutter
rendering.

### Per-function cost

No `try/finally` and no frames: `BoxCost.enter(site)` at method entry and `BoxCost.exit(site)`
before every return, with a per-thread shadow stack. An exceptional exit is reconciled when an
outer frame's `exit` finds frames above it (they are popped and charged up to now) and at the
per-box run boundary, which resets the stack. Leaf methods (no calls, no loops, from the scan) are
not instrumented, so their cost is their caller's self time. Off by default, opted into per box;
per-box totals need no ASM (the boundary measures them). Feeds the Profiling panel and the
watchdog's stall report (`Watchdog.onStall`).

### Thread-ownership guards

Annotation-driven like the anchors: `@ThreadOwned` on owned API (`Signal.render`, the renderer
calls that must stay on the Runloop), found through `CallTargets`; the pass inserts
`LDC site; INVOKESTATIC ThreadOwnership.check(I)V` (or `DUP` the last argument first, when the
annotation asks for it, so the check can see the `SynthContext`) before each such call in box code.
The bug that motivated it (`Param.modulate(SineOsc(...))`, sampled by `GraphBindingRuntime`) was
*library* code, which no box-code rewrite sees, so the pass ships with a library-side check in
`Signal.render`'s existing cache-miss branch, and both report through one place that names the box
line (`SnippetClasses.boxFrame`).

Built as above, with these decisions (details, the full allow/deny list and measurements in
[`box_bytecode/THREAD_OWNERSHIP.md`](box_bytecode/THREAD_OWNERSHIP.md)):

- Ownership is declared by the context: `SynthContext` is a `ThreadOwnedContext` whose owner
  `FieldAudioEngine`'s thread claims at start. An offline context has no owner and is never checked.
  The library check also holds a node to the owner of the context it was rendered under in the last
  two blocks, which is what catches the Runloop sampler using a context of its own.
- `DUP` is only emitted for a single-slot reference last argument. A wide or other primitive last
  argument, or a call through a `$default` stub (whose top of stack is the marker), is refused at
  rewrite time and guarded by owner alone.
- A pass report names the guarded call's own line, and gives the compiled line when that is not
  proven, rather than the next proven frame out, which would be a caller's line; only the library
  check, which has no site, uses `boxFrame`. The two dedupe on the compiled line.
- RUNLOOP means `Runloop.mainThread`; with no Runloop (jvm-main) nothing is checked.

## Open risks

- **Line identity.** `SourceLineMap` is heuristic. It refuses rather than guesses, but a box with
  many identical lines and a fragment break could be mapped at the wrong offset if the unique-line
  vote is thin. Tests pin fragments, padding and macro shifts.
- **Re-evaluation semantics.** A re-evaluated cached snippet now gets the current pass set. The
  rerun cache refuses to re-evaluate the snippet evaluated last (its class is reused), so a pass
  switched on reaches a box at its next real reload, not instantly.
- **Memory.** Original class bytes are kept per rewritten module, and sites forever; both shadow
  the REPL history, which already keeps every snippet.
- **Read-only modules.** If a future compiler makes `compilerOutputFiles` immutable, every pass
  stops at once (logged once, `warn,execution,bytecode`).
- **Resource reads.** `CallTargets` assumes Field's classes are readable as resources from the
  engine's loader. If a loader serves classes but not resources, annotations are not found and the
  anchor and guard passes silently insert nothing (the anchor then falls back).
