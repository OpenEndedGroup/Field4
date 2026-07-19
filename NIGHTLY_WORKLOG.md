# Nightly worklog — 2026-07-19 / kind B — demo-transcript exec harness

Branch: `nightly/2026-07-19/2-demo-transcript-exec-harness` (based on `2d3011d0`)
Worktree: `/Users/marc/field4-worktrees/2026-07-19-2-demo-transcript-exec-harness`

## Mission

Close the verification-integrity gap that let `fn_differentiable_fit_demo.yaml` ship
on 2026-07-18 with TWO compile errors in its box body (`Domain(...)` unresolved inside
`with(Functions)`; overload-ambiguous `plot(...)`). Nothing in CI *executes* a shipped
demo transcript's box Kotlin — `OperationTranscriptMacroSmokeTest` only *applies* ops and
diffs the `code` string. Build a FIELD_TEST that runs/compiles every shipped demo's box
bodies end-to-end and asserts no diagnostics.

## What I learned about the real engine path (study, step 1)

- Shipped demos live in `dev_docs/agents/transcripts/*.yaml`; each is a `name/description/operations:`
  doc — the SAME shape `OperationTranscript.runMacro` already loads (`loadMacroOperations` accepts a
  map with an `operations:` key). So a transcript can be replayed by copying it into
  `<document>/macros/<name>.yaml` and calling `OperationTranscript.runMacro(documentPath, name)`.
- `runMacro` -> `applyMacroOperation`: `execute`/`execute-fragment` ops call
  `target.ke.executeFragmentAndWait(...)` (execution.kt) -> `executeInBox` -> the REAL
  compile+eval pipeline `standardExecutionPractice` (macros/macros.kt). Compile errors, eval
  errors and runtime exceptions all surface as `Message("output")` with `Output(kind="error")`
  (dispatchExecutionOutput, execution.kt:989). `captureMacroOutput` collects those into
  `MacroRunResult.output` with `kind`. **Key finding: a box compile error does NOT throw and does
  NOT set `run.success=false`** — it only shows up as an output line with `kind=="error"`. So the
  fn-fit bug is caught by scanning `run.output` for `kind in {error,err}`, not by `run.success`.
- `executeFragmentAndWait` schedules onto the runloop (`inMain{...}.join()`), and `box.ki` first
  access (`makeKI`, Boxes.kt:1075) schedules the stdlib-intro bootstrap on `inMain` behind a
  readiness gate. **So the runloop MUST be pumping** for either execution OR compilation of a box —
  there is no runloop-free path. A jvm-main entrypoint runs in a FRESH subprocess (backdoor
  `run-jvm-main` -> AgentJvmMainLauncher, no server), so the harness must start its own runloop.
- Compile-only seam (for graphics demos we won't run): the real macro expander
  `ki.applyMacros(box, code)` (KotlinInterface2:2461) expands `moduleHere`/`prop`/`println`/… and,
  crucially, `prop<Renderer>(renderer)` writes `var Box.renderer by Inheritance<Renderer>() /**/`
  into the box's `preamble` (via `applyInsertToPreamble`). `/**/`-suffixed preamble lines propagate
  to child boxes through `gatherPreambleUpwards`, so a child box's `renderer` resolves *at compile
  time* once (a) the parent's macros have been applied and (b) the child's preamble has been eval'd
  (`checkPreambleAndExecute`, safe — it only evals property-delegate declarations, never a Window).
  `ki.compileSnippetAndWait(expanded, listOf(box), onCompileError)` (KotlinInterface2:2748) then
  compiles WITHOUT evaluating — no GLFW window, no render loop.

## Plan

New jvm-main FIELD_TEST `afield.examples.DemoTranscriptExecSmokeTest` under
`src/jvmMain/kotlin/afield/examples/`:

1. Headless bootstrap: start `Runloop.runLoop.start()` on a **daemon** thread; wait for the first
   frame. (No GLFW, no WebSocket server — none of the graphics demos are launched.)
2. Iterate the shipped `dev_docs/agents/transcripts/*.yaml`. Each demo is classified EXECUTE vs
   COMPILE-ONLY by a curated, documented map (whether a demo opens a Window is a semantic property,
   not safely inferable):
   - **EXECUTE** (pure-compute, no Window/Renderer): replay the FULL transcript via the real
     `OperationTranscript.runMacro` (create/connect/execute/await/wait) in a fresh ephemeral
     session, then assert `run.success` AND zero output lines with `kind in {error,err}`. These
     genuinely run — compile + eval + runtime.
     - `fn_differentiable_fit_demo`, `linalg_thumbnails_demo`, `hello_display`,
       `hello_display_capture`, `two_box_context`.
   - **COMPILE-ONLY** (open Window/Renderer or depend on a running renderer's context; unsafe to
     spin GLFW headless at 2am): apply only `create`+`connect` ops to build the box graph, then for
     each box, parent-first, reuse the real pipeline compile-only — `applyMacros` (populates
     preamble) -> `checkPreambleAndExecute` (evals preamble, safe) -> `compileSnippetAndWait`
     (compile, no eval). Assert zero ERROR diagnostics. GLSL shader props (`vertex`/`fragment`/
     `compute`) are not Kotlin and are excluded (they are exercised by the graphics render smokes).
     - `graphics_renderer_launch`, `afield_four_traditional_triangle`, `recstl_playground`,
       `threebox`.
3. Print a per-demo PASS/FAIL line + verb (execute/compile). Exit 0 with
   `DemoTranscriptExecSmokeTest ok` only if every demo passed; else exit non-zero with the
   diagnostics.
4. Natural exit: daemon runloop thread; `thisSessionInfo.remove()`; rely on AgentJvmMainLauncher's
   lingering-thread report + exitProcess safety net.

**Faithfulness note:** no toy compiler. EXECUTE demos go through the identical path a user/agent
uses (`runMacro` -> `executeFragmentAndWait`). COMPILE-ONLY reuses the real macro expander,
preamble machinery and K2 compiler; only the terminal eval is skipped (that is the whole point).
The hot live execution path (`standardExecutionPractice`) is NOT modified.

**Empirical caveat (to verify):** if a COMPILE-ONLY graphics child box cannot be compiled without a
*running* renderer (some context symbols are published only at eval time), I will narrow that demo's
compile coverage to the boxes that faithfully compile and document exactly which boxes are covered —
an honest gap beats a false green or a false red.

## Verification plan (step 3)

- `python3 tools/run_field_tests.py --id examples.demo-transcript.exec` — read FULL output; every
  shipped demo must pass.
- Deliberate-break proof: inject a bad box body into a COPY of a demo (temp/worktree only), observe
  the harness FAIL with the real diagnostic, restore. Report both observations.

---

## Results (step 5)

### Files changed
- **`src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt`** (new) — the harness /
  FIELD_TEST `examples.demo-transcript.exec`. Boots a daemon runloop headless, iterates the shipped
  transcripts, EXECUTEs the pure-compute ones via `OperationTranscript.runMacro` and COMPILE-checks
  the graphics ones via the real macro-expand + preamble + K2 compile pipeline (eval skipped). Exits
  0 only if every demo passes.
- **`src/jvmMain/kotlin/afield/four/modules/Mesh.kt`** — bug fix (see "First catch" below).
- **`dev_docs/agents/transcripts/README.md`**, **`dev_docs/testing/TESTING_STRATEGY.md`** — document
  the harness and the EXECUTE/COMPILE_ONLY classification; "shipping a demo" now means its boxes
  run/compile in CI.
- **`NIGHTLY_WORKLOG.md`** (this file).

### Per-demo treatment (and why)
- **EXECUTE** (pure compute, safe to run to completion headless — asserted: `run.success` AND zero
  `kind=="error"` output lines): `fn_differentiable_fit_demo`, `linalg_thumbnails_demo`,
  `hello_display`, `hello_display_capture`, `two_box_context`.
- **COMPILE-ONLY** (construct a GLFW `Window`/`Renderer` or depend on a running renderer's context —
  a render loop is inappropriate/risky in unattended 2am CI; asserted: zero ERROR compile
  diagnostics per box, eval skipped so no window opens): `graphics_renderer_launch`,
  `afield_four_traditional_triangle`, `recstl_playground`, `threebox`. Notably the context-dependent
  child boxes `tb_compute`/`tb_draw` (bare `renderer`/`outputPositions`) DO compile clean, because
  `prop<T>()` writes `/**/`-suffixed delegate declarations into the parent preamble that
  `gatherPreambleUpwards` propagates to children — so this is a real, faithful compile, not a
  narrowed subset.

### What I ran and observed
- Green run (`python3 tools/run_field_tests.py --id examples.demo-transcript.exec`): **PASS** —
  `DemoTranscriptExecSmokeTest ok — 9 demos verified (5 executed, 4 compiled)`, exitCode 0. Every
  demo PASS, incl. the once-buggy `fn_differentiable_fit_demo`; every graphics box compiled clean.
- Deliberate-break proof: injected `val dom = Domain(0.0, 1.0)` (the exact fn-fit class — an
  unresolved reference) into `fn_differentiable_fit_demo.yaml`'s execute code, reran → **FAIL**,
  exitCode 1, diagnostic: `fn_differentiable_fit_demo: box execution emitted 1 error diagnostic(s):
  fn_fit_demo:8 Unresolved reference 'Domain'.` Restored the yaml (`git checkout`), reran → **PASS**
  again. A verification test I watched fail on the real regression it is meant to catch, then pass
  once fixed.

### First catch — a real bug the harness surfaced
Executing `two_box_context` (child reads a parent-set property via
`fromContextWithNameOrNull<String>(...)`) threw `class LinkedHashMap$Entry cannot be cast to String`.
Root cause: `fromContextWithNameOrNull` (Mesh.kt) did `return a as A` where `a` was a `Map.Entry`,
not `a.value` — its property-inheritance path had **never** worked (only the ki-var path did). Fixed
both occurrences (`return a.value as A`). This is precisely the class of latent breakage the harness
exists to catch, found on its first real run.

### Bootstrapping detail
A jvm-main entrypoint runs in a fresh subprocess with no server, and box KI init + fragment execution
both schedule onto the runloop (`inMain`). The harness starts `Runloop.runLoop.start()` on a daemon
thread and waits for the first frame; teardown relies on the daemon runloop + AgentJvmMainLauncher's
exitProcess safety net. Observed: the test process exits (run completes in ~17s; runner reports PASS).

### Risks
Low. The harness is additive test infra; the one production change (`fromContextWithNameOrNull`)
strictly fixes a code path that always threw, so no working caller can regress. Compile-only demos do
not open windows. Residual: the harness does not *run* graphics render loops (by design) — GLSL and
render-loop behaviour stay covered by the graphics/render smokes; and it depends on the runloop
starting within 30s (guarded with a clear error).

### Recommendation
**Merge.** Closes the documented verification-integrity gap (nothing ran shipped demo box Kotlin),
proven to catch the exact fn-fit regression, and already caught a second real bug
(`fromContextWithNameOrNull`) as a bonus. No loadable demo artifact is produced (kind B, test infra).
