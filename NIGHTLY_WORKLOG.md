# Nightly worklog — 2026-07-21 / 2-crossbox-context-audit

**Kind:** B (feature / correctness / ergonomics)
**Mission:** Audit and regression-test cross-box context inheritance (`fromContextWithNameOrNull` and the box-level context lookup).

## Plan
1. Map every resolution path and every caller of `fromContextWithNameOrNull` / `fromContext`.
2. Determine, per site, whether it relies on inherited *properties* (broken pre-07-19) or *ki-vars* (always worked), and whether the 07-19 `.value` fix changed behavior.
3. Add a JVM smoke test that reproduces the cross-box property-inheritance scenario end-to-end through the REAL engine (parent sets a property; child childed to parent reads it), asserting the value arrives — plus a negative sibling case. Model on `DemoTranscriptExecSmokeTest`.
4. Verify the test goes RED if the `.value` fix is reverted, then GREEN restored.
5. Fix any sharp edge surfaced; if callers are correct post-fix, that is a valid finding.

## Audit findings (per site)

### Definition — `src/jvmMain/kotlin/afield/four/modules/Mesh.kt`
- `fromContext<A>(defaultValue)` (line 29): iterates `f.properties.values` returning the value directly (line 33-35) — **never had the bug**. Then ki-vars, then parent-walk. Correct throughout.
- `fromContextWithNameOrNull<A>(name, defaultValue)` (line 64): checks (a) current box properties, (b) current box ki-vars, (c) parent-walk properties, (d) parent-walk ki-vars.
  - Property paths (line 70 current-box, line 90 parent-walk) previously returned the `Map.Entry` (`return a as A`) rather than `a.value` — cast to `A` silently. **Fixed 2026-07-19 in commit 7cf85f08** (both lines now `return a.value as A`), confirmed by `git blame`.
  - ki-var paths (line 72-77, line 91-96) returned `it.value.value` all along — **never broken**.
- Net: cross-box *property* inheritance via this helper had NEVER worked before 07-19; ki-var inheritance always worked.

### The `two_box_context` box-level lookup — SAME mechanism, not different
- The child box code is `val inherited = fromContextWithNameOrNull<String>("agent_context_value")` — the identical `afield.four.modules` top-level helper, imported into the box's Kotlin. There is **no separate box-level context resolver**; `Box.context` (a `ThreadLocal<Box>` in `Boxes.kt:392`, set around execution via `withBoxContext`/`withBoxContextNow`) supplies `f` and the helper walks `Box.parents`. Parent sets the value with `setPropertyRaw` (the *property* path — exactly the one that was broken).

### Caller — `magic.kt:282` `linkBuffer` / `BufferParameters`
- Used by `Module.uboResolver`/`ssboResolver`. In real workspaces a box declares `val myBuf = BufferParameters(...)` (a top-level val → **ki-var**), resolved by a child module box. Relies on the **ki-var** path → **unaffected** by the 07-19 fix. No production site stashes a `BufferParameters` via `setPropertyRaw` (grep: zero hits). `defaultValue=null` handled.

### Caller — `Module.kt:106` `accelerationStructureResolver` / `HasAccelerationStructure`
- Same shape: acceleration structures are declared as vals (ki-vars) in ray-tracing workspaces and resolved by name. Relies on the **ki-var** path → **unaffected**. Emits a `warning,graphics` on null, so a genuinely-missing structure degrades gracefully.

### Conclusion of audit
The 07-19 `.value` fix corrected a path that had **no production caller** — only the hand-test workspace `two_box_context` exercised property inheritance. The two graphics callers use the ki-var path and were always correct. So the deliverable is: (a) this written audit, and (b) a regression test that actually asserts the inherited value (the missing guard) so the property path can never silently regress again.

### Pre-existing coverage gap + refined failure mode
`DemoTranscriptExecSmokeTest` (`examples.demo-transcript.exec`) already lists `two_box_context` in its `EXECUTE` set, asserting `run.success` + zero error-kind output lines.

Refinement from the red-check (see Verification): with the `.value` fix reverted, a **typed** property read does NOT silently return null — the `return a as A` casts the `LinkedHashMap$Entry` to the reified `A` (`String`), which throws `java.lang.ClassCastException: LinkedHashMap$Entry cannot be cast to String` at eval time, surfacing as an **error-kind** output line. So `DemoTranscriptExecSmokeTest`'s error-line check WOULD catch a regression in `two_box_context` today — but only because that demo happens to be classified into its `EXECUTE` set. The new test is stronger and more targeted: it (a) pins the exact inherited value (guards against a future "safe-cast returns null" style silent regression), (b) covers Int + ki-var paths, and (c) adds the negative sibling isolation case — none of which the demo test does. It also does not depend on the demo-transcript classification machinery.

## What changed (files)
- **Added** `src/jvmMain/kotlin/afield/examples/CrossBoxContextInheritanceSmokeTest.kt` — new jvm-main smoke test `examples.context.cross-box-inheritance`. Builds parent+child(+sibling) via `OperationTranscript.runMacro`, runs the real compile/eval pipeline, asserts: child inherits String property (the 07-19 fix), Int property (type-filtered), parent ki-var; sibling (root-only) resolves null; no error output.

## Verification status (all VERIFIED)
- Disk: `df -h /` shows 53% used, ~11Gi avail — captures/JVM safe.
- **GREEN:** `python3 tools/run_field_tests.py --id examples.context.cross-box-inheritance` → `1 passed`. Captured box output confirms the real engine ran:
  - `ctx_child: child string=value from parent number=42 kivar=kivar from parent`
  - `ctx_sibling: sibling string=null`
- **RED (did-it-go-red):** reverted BOTH `.value` sites in `Mesh.kt` (lines 70, 90) → `1 failed`, exit 1, with the exact bug surfaced: `error diagnostic(s): ctx_child:40 ... LinkedHashMap$Entry cannot be cast to String`, plus the two value assertions (`child string=value from parent`, `number=42`) failing. Then `git checkout` restored the fix and re-ran → GREEN again. So the test provably catches the regression it targets.
- Not verified: full `:jvmTest`/clean build (ran the module-scoped smoke via the canonical runner, which is the meaningful signal for a jvm-main smoke); the two graphics callers were audited by reading, not by a live GPU render (out of scope for a 2am headless run and unaffected by the fix).

## Risks
- Low. Only additive: one new smoke test + worklog; no production code changed (Mesh.kt is byte-identical to `main` after the red-check restore — confirmed by `git diff`). The new test relies on the transcript `create` op auto-parenting boxes to root (verified in `operation_transcript.kt:1337`) and on top-level vals registering as ki-vars (verified in `KotlinInterface*.kt`); both are stable engine behaviors.

## Recommendation
- **MERGE (without prejudice).** The 07-19 `.value` fix is correct and now guarded by a targeted end-to-end regression test that provably fails without it. The audit found no additional sharp edge: the two graphics callers use the always-correct ki-var path and need no change. Optional future follow-up (not blocking): consider whether `fromContextWithNameOrNull`'s `as A` should be a checked cast that logs+returns null rather than throwing, so a type-mismatched property degrades like the ki-var/no-name paths — but current throw-on-mismatch is arguably the safer contract.
