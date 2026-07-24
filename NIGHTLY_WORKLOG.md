# Nightly worklog — 2026-07-24 / kotlinjs-completion-jvm-filter

Kind: B (ergonomics/defect)
Branch: `nightly/2026-07-24/2-kotlinjs-completion-jvm-filter` (base d8f9eccb)
Mission: filter JVM-only members out of **kotlinjs-scoped** AA completion (roadmap item 4, Stage 2b).

## Plan

### Problem
`mainLanguage="kotlinjs"` boxes compile Kotlin→JS and run in a browser/vision head with NO JVM. AA
completion is scoped to the kotlin-stdlib JAR (via `AaCompletionClient.kotlinStdlibRoots()`), but that
JAR is the **JVM** stdlib, so `.` on a `List`/`Collection`/array/`String` surfaces JVM-platform-only
members (`stream()`, `parallelStream()`, `spliterator()`, `toArray()`, …) that do not exist in the
Kotlin/JS stdlib — they either fail to compile to JS or throw at runtime.

### Signal design
Today "kotlinjs mode" is only implicit (stdlib-only classpath). I add an explicit `jsTarget: Boolean`
flag threaded **client → sidecar request → AaCompleter**, defaulting `false`. The filter is gated on
`jsTarget == true`, so:
- Regular Kotlin completion (`KotlinInterface2` path, full classpath, no flag) is byte-for-byte unaffected.
- The corpus gate (`completion_diff.py` → `runSpike` → `AaCompleter.complete` with no flag) is unaffected.
- Only the kotlinjs callers (`KotlinJs.kt` live path + the 3 kotlinjs smokes) pass `jsTarget = true`.

### Files to touch
- `tools/.../AaCompletion.kt` — add `jsTarget` param to `complete()`/`completeAt()`; apply a curated
  JVM-only member deny-filter to MEMBER + EXTENSION dot candidates when `jsTarget`.
- `tools/.../CodeUseSemanticSidecar.kt` — `CompletionRequest.jsTarget` field; pass to completer.
- `src/jvmMain/.../AaCompletionClient.kt` — `Req.jsTarget` field + `complete(..., jsTarget=false)` param.
- `src/jvmMain/.../remotejs/KotlinJs.kt` — pass `jsTarget = true` (live kotlinjs box path).
- `src/jvmMain/.../examples/KotlinJs{Completion,ToneSurface,RemoteArSurface}*Test.kt` — pass `jsTarget = true`.
- New assertions in `KotlinJsCompletionSpikeTest.kt`: (a) denied members ABSENT in jsTarget mode on
  List/IntArray/String; (b) still PRESENT in non-jsTarget mode.

### Deny-list grounding
First empirically confirm which JVM-only members AA returns on `List<Int>`/`IntArray`/`String` under
stdlib-only scope (run the spike scoped to the stdlib jar). Deny only members that genuinely do NOT
exist in the Kotlin/JS stdlib. (See "Empirical confirmation" below.)

## Empirical confirmation

Ran the AA spike (`runSpike`) scoped to the **stdlib-only** classpath Field's kotlinjs boxes use
(`kotlin-stdlib-2.4.0.jar`, from `AaCompletionClient.kotlinStdlibRoots()`), the same code path the live
sidecar exercises for a kotlinjs box. Bug reproduces — JVM-only members leak into completion:

| receiver | JVM-only members returned | return type (tell) |
|---|---|---|
| `listOf(1,2,3)` (`List<Int>`) | `stream`, `parallelStream`, `spliterator`, `toArray` | `java/util/stream/Stream`, `java/util/Spliterator` (toArray → `kotlin/Array`) |
| `mutableListOf(...)` (`MutableList<Int>`) | `stream`, `parallelStream`, `spliterator`, `toArray` | same |
| `Collection<Int>` | `stream`, `parallelStream`, `spliterator`, `toArray` | same |
| `"hi"` (`String`) | `chars`, `codePoints`, `getChars` | `java/util/stream/IntStream` (getChars → `kotlin/Unit`) |
| `intArrayOf(...)` (`IntArray`) | (none — clean) | — |

These come from Kotlin's JVM built-ins customizer augmenting `kotlin.collections.*` / `kotlin.String`
with JDK members; none exist in the Kotlin/JS stdlib. Full dump: `/tmp/jvmfilter-aa.json`.

### Deny rule (chosen: hybrid — principled java-signature rule + tight curated list)
A dot MEMBER/EXTENSION is JVM-only (in jsTarget mode only) when EITHER:
1. its return type or any value-parameter type references a `java.`/`javax.` package (a JVM-platform
   type with no JS equivalent — catches `stream`/`parallelStream`/`spliterator`/`chars`/`codePoints`
   and anything future), OR
2. its simple name is in a small curated set that escapes (1) by returning Kotlin types
   (`toArray`, `getChars`, plus `parallel`/`sequential` for a `Stream`), AND its receiver/container is
   a `kotlin.*` stdlib type (so a user's own `fun stream()` in a kotlinjs box is never hidden).

The AA API does not cleanly expose "JVM-platform-only declaration" as a symbol flag in the standalone
session, so a purely principled rule isn't fully sufficient (e.g. `toArray` returns `kotlin/Array`);
the hybrid above is principled where it can be and curated (well-commented) only for the residue.

Observed-but-NOT-denied (honest residue): the Java-21 `SequencedCollection` members `getFirst`/`getLast`/
`addFirst`/`addLast`/`removeFirst`/`removeLast` also surface on List. Left in deliberately — Kotlin 2.x
common stdlib has moved on these names and I could not confirm they are absent in the vendored Kotlin/JS
stdlib without risking hiding a legit member. Candidate for a future data-driven (JS-klib-diff) pass.

## Execution

Files changed (7):
- `tools/code-use-semantic-sidecar/src/main/kotlin/afield/tools/codeuse/AaCompletion.kt`
  — `JVM_ONLY_STDLIB_MEMBERS` set + `isJvmOnlyForJsTarget(AaCandidate)` helper; `jsTarget: Boolean = false`
  param on `complete()`/`completeAt()`; `jsFilter` applied to `out` in BOTH the dot and name branches.
- `tools/code-use-semantic-sidecar/src/main/kotlin/afield/tools/codeuse/CodeUseSemanticSidecar.kt`
  — `CompletionRequest.jsTarget` field; passed to `completer.complete(jsTarget = req.jsTarget)`.
- `src/jvmMain/kotlin/afield/AaCompletionClient.kt` — `Req.jsTarget` field + `complete(..., jsTarget=false)`
  param threaded into the request.
- `src/jvmMain/kotlin/afield/remotejs/KotlinJs.kt` — live kotlinjs box completion path passes `jsTarget = true`.
- `src/jvmMain/kotlin/afield/examples/KotlinJsCompletionSpikeTest.kt` — `completeWithRetry(jsTarget=true)`;
  new Stage 2b both-directions assertions (List + String).
- `src/jvmMain/kotlin/afield/examples/KotlinJsToneSurfaceSmokeTest.kt` — pass `jsTarget = true`.
- `src/jvmMain/kotlin/afield/examples/KotlinJsRemoteArSurfaceSmokeTest.kt` — pass `jsTarget = true`.
- Docs: `dev_docs/kotlinjs_status_and_roadmap.md` item-4 bullet → "Stage 2b: DONE".

Design: `jsTarget` defaults `false` everywhere. Only the kotlinjs callers set it true. The corpus-gate
spike path (`CompletionSpike` → `AaCompleter.complete`) never sets it → its AA dump is unchanged. The
sidecar session cache key is (classpathRoots) only — jsTarget is applied per-request, so no extra warmup.

## Verification (all observed, not assumed)

Sidecar built + launched on explicit free port 19191 (`--workspace <WT> --port 19191`); Field test
server on explicit ports 9401/9402/9477 (no cross-worktree reuse).

1. **Empirical (curl, stdlib-only roots, warmed session)** — `listOf(1,2,3).`:
   - `jsTarget=false`: 22 cands, JVM-only present `[parallelStream, spliterator, stream, toArray]`.
   - `jsTarget=true`: 18 cands, JVM-only present `[]`; legit `size`/`iterator`/`get`/`contains`/… kept.

2. **Smoke `remotejs.kotlinjs-completion`** (via `run_field_tests.py --id … --port 9401`, sidecar 19191):
   `[1/1] PASS remotejs.kotlinjs-completion (7172 ms)`. Its own stdout:
   - `List 'xs.'  kotlinjs → JVM-only gone [stream, parallelStream, spliterator, toArray]; normal → still present [stream, parallelStream, spliterator, toArray]`
   - `String 's.'  kotlinjs → JVM-only gone [chars, codePoints, getChars]; normal → still present [chars, codePoints, getChars]`

3. **Corpus gate (STANDING CONSTRAINT)** — `python3 tools/completion_diff.py --run --gate`:
   `RECALL: K1 23/31  AA 23/31`; `REGRESSIONS: 2 [field_probes#0 .vec4s, .buffer]` — both KNOWN-HARD
   (dynamic GPU-DSL, Stage C), `UNEXPECTED: 0`; `AA anti-goal leaks 0`, `AA kw-after-. 0`.
   **`VERDICT: PASS  (AA recall 23 >= K1 23; AA anti-goals clean; unexpected regressions 0)`** — gate
   exit code 0. Regular-Kotlin completion is unchanged (identical to pre-change baseline).

4. **Touched kotlinjs smokes still green** — `remotejs.kotlinjs-remotear-surface` PASS,
   `remotejs.kotlinjs-tone-surface` PASS (jsTarget=true doesn't disturb THREE/Tone façade members,
   which aren't `kotlin.*` stdlib receivers).

## Risks / recommendation

Risk (one line): the filter denies by member name on `kotlin.*` receivers, so a hypothetical user
kotlinjs class exposing a member literally named `toArray`/`getChars` on a value whose *displayed
container* is a kotlin stdlib type would be over-filtered — implausible in practice (kotlinjs boxes are
small graphics/audio scripts) and the java-signature half of the rule is fully principled.

Residual (documented, not a defect): Java-21 `SequencedCollection` members (`getFirst`/`getLast`/
`addFirst`/`addLast`/`removeFirst`/`removeLast`) still surface on List — left in deliberately because I
could not confirm they're absent from the vendored Kotlin/JS stdlib without risking hiding a legit
member. A future data-driven pass (diff the actual JS klib's member set) would make the deny-list
self-maintaining and close this.

**Recommendation: MERGE.** The mission target (stream/parallelStream/spliterator/toArray + String's
chars/codePoints/getChars) is fully filtered in kotlinjs mode, regular-Kotlin completion is proven
byte-for-byte unaffected (corpus gate PASS), and all kotlinjs smokes are green. Small, well-scoped,
low-risk.
