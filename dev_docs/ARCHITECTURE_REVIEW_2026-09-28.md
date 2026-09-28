# Architecture review, 2026-09-28 — five responses, five plans

An outside orchestrator agent reviewed Field·4 and made five points. This note records what the
code actually does on each (from a read-only pass over `main` at `5d730d2db`), where the review
was right, where it undersold the problem, and a staged plan for each. Line numbers drift; the
symbols are the durable references.

The through-line: the principle worth protecting is *everything stays inspectable and alterable
from inside a running document*. Four of the five plans are that principle applied to a place
where Field currently resolves, swaps, or owns something silently.

---

## 1. "Why did this value resolve here?"

### What's there

The review saw `fromContext<T>()`. There are four walkers, not one, and they disagree:

| Mechanism | Where | Order within a box | Starts at | Own box's REPL vars? |
|---|---|---|---|---|
| `fromContext<T>` / `fromContextWithNameOrNull` | `four/modules/Mesh.kt` | properties, then vars | self | **skipped** (self-pinning fix, `bafa9ee5f`) |
| `InheritanceFromContext<T>` (what `@exported` reads through) | `stdlib/stdlib.kt` `getValue` | **vars, then** properties | self | **checked first** — the self-pinning fix was never applied here |
| `Inheritance<T>` / `prop` / `rootProp` | `stdlib/stdlib.kt` `inheritedPropertyWalk`, `stdlib/props.kt` | properties only | self | n/a |
| Monaco "inherited from" hover | `execution.kt` `resolveInheritedPropertyWithSource` | properties only | self | n/a — so it can name a *different* supplier than the delegate uses |

Plus the two text channels:

- **`@exported`** splits a value in two: the value crosses at runtime (`setPropertyRaw("_x")` +
  var injection, `KotlinInterface2.kt` `ContextUpdater`), the type crosses as a
  `val Box._x by InheritanceFromContext<T>() /**/` line (`upsertExportedDeclaration`). The type
  text and the value can come from different boxes → compiles, then `ClassCastException`.
- **Preambles** (`macros/macros.kt` `gatherPreambleUpwards`, `checkPreambleAndExecute`): inherited
  `import` and `/**/` lines are merged and then **written permanently into the child's own
  `preamble`** (end of `checkPreambleAndExecute`). After a disconnect the inherited declaration
  stays, compiles, throws `MissingInheritedPropertyException` on read, and keeps propagating to
  grandchildren because it still ends in `/**/`. Two ancestors exporting `_x` with different `T`
  both land; which wins is REPL shadowing order (unverified: probably the *farthest*).

Tie-breaking: `ki.context.vars` is a `HashMap` (`KotlinScriptEngine.kt`), so two same-typed vars
in one parent resolve in hash order; equidistant ancestors resolve in `parents`-set order, which
`inheritedPropertyWalk`'s own KDoc calls arbitrary. All resolution is eager/pull: `connect` and
`disconnect` bump `topologyGeneration` and nothing Kotlin-side listens (`TopologyWatcher` is only
used by the Pd/Datalog/Python/Clojure/Morpho runtimes).

### Response

Agreed, and it's stronger than stated: Field doesn't have *a* resolution rule to explain, it has
four that differ in order and self-inclusion, and one explainer (the hover) that uses a fifth.
"Explain" is not implementable honestly until there's one walk to explain.

### Plan

1. **One walker.** `ContextResolution.walk(box, query, policy)` returning
   `Resolution(mechanism, supplier, path, matchKind, shadowed: List<Candidate>)`. `query` is
   by-type or by-name; `policy` carries the self-inclusion and props/vars order. Port
   `fromContext*`, `inheritedPropertyWalk`, `InheritanceFromContext.getValue` and
   `resolveInheritedPropertyWithSource` onto it. `inheritedPropertyWalk`'s KDoc already asks for
   this.
2. **Decide the policy, once**, and record it in `dev_docs/stdlib_behaviour_changes.md`:
   - apply the self-pinning rule to `InheritanceFromContext` (own vars skipped for inherited reads);
   - make ties deterministic (`LinkedHashMap` for vars; equidistant ancestors in a stable order —
     edge insertion or uid) and make a same-depth tie a *reported* ambiguity, not a silent pick.
   Characterization tests first (`ContextSearchOrderLedgerSmokeTest` is the ledger to extend).
3. **Preamble lines carry their source.** Tag inherited lines with the supplier uid in the
   marker (`/*from:<uid>*/…/**/`). `checkPreambleAndExecute` then drops (or flags) inherited lines
   whose supplier is no longer an ancestor, instead of persisting them forever. Same mechanism
   detects two suppliers for one name with different types — a diagnostic, not a REPL
   shadowing lottery.
4. **Surface it.** Fix the hover to use the walker (and match `val` exports); add `why <name>` to
   the backdoor and an AI4/MCP tool beside `LineNoteTools`; later, Kotlin line notes via
   `LastRunNotes` (Kotlin is the one language that never calls `LastRunNotes.record`) with
   `meta` = supplier + path and `volatile` when the path crossed an edge.
5. **Later:** a Kotlin `TopologyWatcher` that marks boxes whose last resolutions crossed a changed
   edge as stale (badge, not auto-rerun).

---

## 2. Shader interface transitions

### What's there

Reflection (SPIRV-Cross / Slang → `Introspected`) is turned into **generated preamble text**
(`insertForShader`, `buildShaderStructurePreamble`), which resolves to **name-keyed runtime
lookups** (`FromUniform`, `Mesh.input`, `Structure.FromStructure`). Two reload paths:

- **Update** (`updateShader`, alt-shift-enter): a full ABI signature check (`ShaderAbi.kt`);
  refuses any interface change. Sound, but the refusal is posted on `code`, not the shader line.
- **Full reload** (`reloadShader` and its compute/mesh/RT siblings, alt-r) is where the problems are:
  - **Confirmed bug:** it rewrites the preamble *before* building the module, ignores
    `ModulePointer.reload`'s Boolean, then calls `clearShaderErrorsForBaseProperties` — wiping the
    error the module's handler just posted — and returns `true`. A module that compiles but
    fails to link leaves the old module running under a preamble describing the new interface,
    with no error.
  - UBOs carry over **by name with no size/layout check** (`Module.reload`); a grown UBO keeps the
    old buffer. `Structure.writeToBuffer` writes by the *Kotlin runtime type*, not the reflected
    one, so `vec3`→`float` with Kotlin still setting a `Vector3f` overwrites the next member.
  - Removing every push constant leaves stale offsets (`setMergedPushMembers` skips an empty list).
  - UBO values and lambda `bind`s are lost; the old bytes survive by accident.
  - The old `Module` is not `cleanup()`ed — it waits for GC + `AutoDisposer`.
  - Unknown `Structure` member: a stdout `println`, value stored anyway.
  - No Kotlin-side marker ties a vanished binding to the lines that use it.
- **No test covers full-reload interface change.**

### Response

Right, and today the answer to "what happens when the interface changes" is "one careful path
that refuses, and one path that half-succeeds quietly." The machinery for doing it well already
exists — `ShaderAbi` computes exactly the signature diff needed — it just gates the update path
instead of informing the reload path.

### Plan

1. **Make full reload a transaction** (fix first, small): build the new `Module` before touching
   the preamble; on failure keep old preamble, old module and the error, return `false`; on
   success swap and `cleanup()` the old module through the deferred queue. Same change in the
   compute/mesh/RT variants.
2. **Diff, don't just refuse.** Compute `ShaderAbi` old→new on every reload and classify each
   binding: *kept*, *added*, *removed*, *renamed* (same set/binding/layout, new name), *retyped /
   resized*. Carry-over policy per class: kept → carry buffer and values; resized/retyped →
   reallocate, zero-init, re-apply values that still type-check against reflection; removed →
   retire; added → zero-init.
3. **Say it where the user is looking.** One transition summary on the shader line ("uniform
   `tint` removed; `Light` grew 16→32 bytes, buffer reallocated"), plus Kotlin-side line notes on
   the preamble consumers of removed/retyped names, with an offer to re-run. Promote the unknown
   member `println` to a box warning; type-check `Structure.set` against the reflected type.
4. **Tests.** A reload-transition smoke matrix — one case per row: compile error, link failure,
   remove, rename, resize, retype, add, all-push-constants-removed, stage add/remove — asserting
   module state, preamble, carried buffers and the diagnostic.

This also closes the one place where shader reload writes compiler state (the preamble) as a side
effect; it becomes an explicit step of a committed transition, which matters for plan 5.

---

## 3. Studies and document-authored tools

### What's there

Four studies — function (`FunctionStudy`), StackSL, RectSL, stack.auto slot — plus line notes.
Shared by design: `SourceStudy<T>` (revisions/CAS edits), `BoxStudies` (cards, writeback, focus),
`KnobNames`, `StudyKeys`, and the `AgentToolExtensions` registry (library and document tools are
the same registry, different scope). Duplicated per domain:

- two identical rasterizer interfaces (`StackSLRasterizer`, `RectSLRasterizer`) plus a third
  render path for stack.auto (`ModuleDestinations`);
- `compare` twice, `sweep` three times (four with Kratos), `tryVariants/adoptVariant` twice;
- three knob types mapped into `KnobNames.Knob` in four places; three `NumberEdit` classes;
- `FunctionStudy` carries a private older copy of `SourceStudy`;
- four near-identical `install*StudyTools` files; `StackSLStudyEvidence` is used by RectSL
  (43 call sites) from the StackSL package; `StudyReport` lives in `FunctionStudy.kt`.

There is no study interface. And the **`aiTool{}` DSL the review credits is not on `main`**: it
is commit `657a040b4`, alone on `claude/document-box-ai4-tools-fc6cab` since 2026-07-24. What a
document can use on `main` is `AgentToolExtensions.register` — which can add a tool but cannot
reuse any study's observe/compare verbs.

### Response

The review's question — does each domain improve the shared machinery or copy it — has a
measurable answer, and it is "mostly copy": RectSL is a structural mirror of StackSL, and
stack.auto, the newest, is the one missing Compare/Variants because it didn't get the copy.
That's the signal to extract now, while there are four and not eight.

### Plan

1. **Decide `aiTool{}`**: rebase and land `657a040b4` (it is one commit), or retire it. If it lands,
   its `BoxTool` becomes the document-side face of the shared registrar in step 3.
2. **Extract, in this order:**
   1. move `StudyEvidence` (from `StackSLStudyEvidence`) and `StudyReport` into `stdlib`; mechanical;
   2. port `FunctionStudy` onto `SourceStudy` (removes the known duplicate);
   3. `Frames<P>` (one render/observe interface → `CpuRaster`) replacing both rasterizer
      interfaces; stack.auto's in-shader capture is its third implementation;
   4. generic `compare`, `sweep`, `Variants<T>` on `Frames` + `SourceStudy`;
   5. one `StudyKnob` + `NumberEdit`; domains supply extraction and `withNumbers` only;
   6. a `LiveStudy` interface and `registerStudyTools(prefix, gate, studyOf)` that generates the
      standard verbs; domains register only their extras (Timeline, Trace modes).
3. **First real port: stack.auto** — smallest, and gaining Compare/TryVariants for free proves
   the abstraction isn't StackSL-shaped. Then fold RectSL and StackSL together.
4. **Guard:** a checker that fails when a new `install*StudyTools` file defines request classes
   the shared registrar already provides. That turns "keep asking the question" into a gate.
5. **Documents get the same verbs**: `registerStudyTools` callable from box code, so a
   document-authored study is as capable as a library one.

---

## 4. AGENTS.md rules → executable contracts

### What's there

Of ~38 corrective rules in AGENTS.md (707 lines): 14 are already enforced by code and can be
reduced to a line or a pointer; 7 are partly enforced; 12 are prose-only with a cheap fix; the
rest are genuine judgment or architecture and should stay. About 240 lines are removable now
(the backdoor section duplicates `dev_docs/agents/FIELD_BACKDOOR.md`; docs build commands appear
three times; several "since 2026-09-14 / before that" histories), ~350 lines total after the fixes.

Stale prose found: the "silently attaching to a stale dev server" line (the backdoor now refuses
stale/foreign servers — the remaining risk is a *fresh* same-checkout 9092); "smoke runner
defaults to 9477" (it picks the first free port ≥ 9477); `--port 9490 --timeout 45` (9490 is the
default, timeout defaults to 30); `FIELD_DOCS_RUNNER_PORT=9477` everywhere (already the default);
the self-heal list implies `run_ui_regressions.py`/`build_zensical_docs.py` self-heal (they
don't); a hardcoded `Server.kt:1758` in the probe example.

### Response

Agreed; the direction is right and the remaining conversions are mostly small. The rule of
thumb going forward: a corrective paragraph in AGENTS.md is a bug report against a tool, and it
stays in the guide only until the tool enforces it.

### Plan

1. **Quick wins (each S):**
   - backdoor/MCP default to an automation port like the smoke runner; 9092 requires `--attach-existing`;
   - `open <name>` defaults to `--must-exist`, `--create` to opt in;
   - track which boxes ran before Playwright attached / before `editor-open`; screenshot of those
     fails or warns;
   - `page-eval --body` without `return` warns;
   - screenshot results carry image pixel size + DPR;
   - `--attach-existing` fails closed when `serverWorkspace` is null;
   - `failOnDynamicVersions()` / `failOnChangingVersions()` in `build.gradle.kts`;
   - `tools/check_module_imports.py` for bare `send(`/`sendWithReply(` in module JS, in the
     pre-commit guard;
   - `ensure_repo_python()` in `run_ui_regressions.py` and `build_zensical_docs.py`;
   - `AgentJvmMainLauncher` checks its required opens and exits with "use run-jvm-main";
   - a checked-in agent deny for `killall` / `pkill -f java` (needs Marc's call — it's config).
2. **Medium:** Gradle honours `.field-backdoor/verification.lock` (wait/fail unless spawned by the
   owner); the `fromContext<Renderer>()` failure detects an async caller and names the
   capture-before-`start` fix; a debug assertion that a stateful synth node is rendered from only
   one `SynthContext` owner (`GraphBindingRuntime` still renders on `Runloop.preRun`).
3. **Rewrite AGENTS.md** to the nine-section shape (what it is / run / verify / backdoor
   essentials / architecture / graphics / on-disk / docs authoring / working safely), moving
   dated histories into `dev_docs/agents/AGENT_SURFACE_TRUTHFULNESS.md`. Do it *after* step 1 so
   the removed prose is actually backed by code.
4. **Keep it from regrowing:** a check that fails when AGENTS.md cites a `file:line`, and a
   convention that a new "do not" paragraph names the tool issue that will retire it.

---

## 5. Lifecycles: ownership, invalidation, disposal

### What's there

No unifying owner. The closest pieces are `Box.jobs`, `launchEpoch`/`runGeneration` and
`stopHooks` (one production user: `streamProcess`). Each subsystem cleans up its own way:
`disposeKi`, `disposeBoxRenderer`, the GPU retire queue + `AutoDisposer`, per-handler `finally`
for audio, a global slider map, the `/ws` `finally`, `FileWatchManager.stop`, Playwright globals.

Confirmed or well-evidenced boundary bugs:

1. **Deleting a running box doesn't stop it.** `-delete-box` (`Boxes.kt`) tombstones, disconnects
   and `disposeKi()`s — no `stop`. History restore and external metadata removal tombstone with
   neither. Loops, audio patches, processes and renderer modules keep running on a disposed engine.
2. **Last client leaving disposes but doesn't stop.** `SessionInformation.cleanup()` calls
   `disposeBoxRenderer` + `disposeKi` per box; ordinary running boxes are never stopped, and the
   renderer-owner path skips `stopTrackedJobs` (stop hooks never drain). Contradicts the
   `keepRunningWhenDetached` KDoc.
3. **Engines leak on replacement.** `execute-all` (`server/executing.kt`), the `reset` action and
   `copyBox` assign `box.ki = makeKIHere(...)` without disposing the old K2 engine. `disposeKi`
   clears `_preamble` but not `_introduction`.
4. **Resources owned by nobody.** `patSynth` starts its feeder at construction; only the
   return-value handler closes it, so the KDoc's own `val sc = patSynth(...).scope(); audio { sc }`
   leaks a feeder per run. `FieldAudioEngine.register` keys patches by UUID with no box and
   registers before any owner exists. Slider callbacks (global, run on `GlobalScope`),
   `renderer += module`, string-keyed `Runloop.preRun` hooks, and `box.jobs +=` outside the
   epoch fence all outlive stop and pin old snippet classloaders.
5. **"Stopped" means different things per subsystem**: jobs unwind later on the runloop, end
   phases run after stop returns, renderer teardown waits 2 s then disposes anyway.

Also: shader full reload swaps modules without retiring the old one (plan 2), and output proxies
capture the originating socket, so after a browser reload a running launch's `output` goes to a
dead socket.

`Server.kt` is 5721 lines with `server()` as one ~3200-line function; `execution.kt` is 4254
lines, of which ~1500 are completion/hints rather than lifecycle. The review is right that size
isn't the argument; the argument is that stop, delete, close and reset each assemble their own
partial teardown.

### Response

This is the most important of the five and the review's instinct is right. It undersells it: the
failures aren't only at the boundaries between lifecycles, they're that four user-level events
(stop, delete, re-run/reset, close) each tear down a different subset.

### Plan

1. **Fix the three cheapest leaks now, independent of any redesign:**
   - `-delete-box`, history-restore tombstoning and file-watch removal call stop before dispose;
   - `SessionInformation.cleanup()` stops every box (respecting `keepRunningWhenDetached`) before
     disposing, and drains stop hooks for renderer owners;
   - one `Box.replaceKi()` that disposes the old engine and clears
     `_preamble`/`_introduction`/`_attic`; use it at the three replacement sites.
   Each with a smoke that asserts nothing is running or registered afterwards (jobs, audio
   patches, slider callbacks, preRun keys, render-target modules).
2. **A lifecycle ledger before the abstraction:** a debug `lifecycle-census` backdoor command that
   counts, per box, live jobs, audio patches, feeders, slider callbacks, preRun/postRun keys,
   render-target modules, K2 engines and snippet classloaders. It's what makes the next step
   verifiable, and it's the inspection principle applied to Field's own runtime.
3. **`RunScope`:** one owner per (box, strand, run) holding a `SupervisorJob`, an LIFO list of
   `AutoCloseable`s, and a session-independent output sink. Opened in
   `launchAll`/`continueAll`/`executeInBox`, closing the strand's previous scope first; passed to
   `ReturnValueRegistry` handlers instead of `(inside, strand, generation, epoch)`, so handlers
   `own(handle)` instead of writing their own `finally`. `patSynth`, `slider`, `renderer += m`,
   `streamProcess`, `FieldAudioEngine.register` and `Runloop` hooks register into the current
   scope automatically. `stopHooks` and the ad-hoc renderer owner-job clearing fold into it.
4. **One teardown chain:** stop = close runs; delete / tombstone / session close = close runs →
   dispose renderer → `replaceKi`/`disposeKi`, in that order, everywhere.
5. **Only then** carve `server()` along these seams (session lifecycle, controls, Playwright,
   RPC handlers) — the refactor falls out of the ownership model rather than preceding it.

---

## Order of work

Fastest value, lowest risk first:

1. Plan 2 step 1 (reload transaction) and plan 5 step 1 (delete/close/replace leaks): confirmed
   bugs, small fixes, each needs a smoke.
2. Plan 4 quick wins, then the AGENTS.md rewrite.
3. Plan 1 steps 1–3 (one walker, policy, sourced preamble lines) — with characterization tests
   first, because it changes resolution behaviour.
4. Plan 5 steps 2–4 (census, `RunScope`, teardown chain).
5. Plan 3 extraction, starting after the `aiTool{}` decision.
6. Plan 2 steps 2–4 and plan 1 step 4–5 (diagnostics surfaces) — these share the line-note
   display path, so do them together.
