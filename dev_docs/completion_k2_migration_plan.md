# K2 Analysis-API Completion Migration — Continuation Plan

**Status (2026-07-19):** Phase 0 done + merged to main; Phase 1 (feasibility spike) done — all three
completion positions proven on the K2 Analysis API, wart-free. **Stage A done** — spike hardened into a
reusable `AaCompleter`/`AaSymbolIndex` module (structured `AaCandidate`, disk-cached index, `@Metadata`
typealiases, safe-dot, clean JVM exit); corpus holds at dot 14/22 + name/arg 9/9. **Stage B done** —
K1-vs-AA differential (`tools/completion_diff.py`), VERDICT PASS (recall parity, AA anti-goals clean).
**Stage C done** — sidecar `/complete` endpoint + REPL-context modelling (inherited preamble as local
stubs + `with(receiver)` scopes). **Stage D done — CUTOVER COMPLETE:** live KI2→sidecar E2E green
(p50 22ms, named-args + inherited context + K1 fallback all verified), differential gate PASS, and the
**default completion backend is now AA** (`activeCompletionBackend`, K1 fallback on error/timeout/empty,
`-Dafield.kotlin.completion=k1` to force legacy). The migration is functionally complete; remaining items
are follow-ups (GPU-DSL dynamic receivers, wider preamble coverage, CI gate). This doc is a cold-start
handoff: read it top to bottom to resume.

Related memory: `completion-regression-coverage.md`, `wither-plugin-in-ki2.md`.
Reference source tree: `~/temp/kotlin` is `2.4.255-SNAPSHOT` — the SAME version as field's embedded
compiler — and contains the Analysis API source + `KJvmReplCompleter.kt`. Use it to look up any AA API.

> **Now that the cutover is complete, the architecture map is [`completion_aa_sidecar.md`](completion_aa_sidecar.md)** —
> the end-to-end request path (editor → server → `AaCompletionClient` → sidecar `/complete` → `AaCompleter`),
> the sidecar process/lifecycle, backend selection + K1 fallback, and the verification tooling, all with
> `file.kt:line` citations. This document below is the migration *narrative* (why + stage-by-stage log).

---

## 1. Why

Field's Kotlin completion is **~100% K1 today**: `KotlinInterface2.complete()` delegates to a K1
`KotlinInterface` (`k1CompletionDelegate`), and error-suggestions (`execution.kt suggestResolvableSymbols`)
are K1-backed too. There is a fully-formed **dead** K2 path (`computeK2Completion`, zero callers,
backed out in git `6a6b006f` "Backed out to K1 auto-complete in K2 mode"). JetBrains wants the
Analysis API; the REPL-native completer is blocked (KTNB-916 backlog, and absent from our fork —
our `kotlin-scripting-ide-services` jar only has the **K1** `KJvmReplCompleter`). So we build
candidate-generation on the Analysis API ourselves — which is the unbuilt half of KTNB-916.

There is also a **semantic code-use sidecar** (`tools/code-use-semantic-sidecar/`) — the only existing
K2 Analysis-API user in the tree (out-of-process, for code-use telemetry). The completion spike was
built inside that module because the standalone AA classpath "must not be mixed into the live-coding
runtime" (its README).

## 2. What is proven (Phase 1 spike)

Spike: `tools/code-use-semantic-sidecar/src/main/kotlin/afield/tools/codeuse/CompletionSpike.kt`
(+ a `runSpike` JavaExec task in that module's `build.gradle.kts`). It builds a
`buildStandaloneAnalysisAPISession` over field's binary classpath + a dummy source module, wraps each
fixture snippet in `fun __c(){ … }` with default imports, finds the element at the caret, and produces
candidates. Results against the Phase 0 corpus + hand fixtures:

| Position | Result | How |
|---|---|---|
| **Dot** `recv.pre` | 14/22 | `recv.expressionType` → `KaType.scope.getCallableSignatures` (members) + applicable extensions via `createExtensionCandidateChecker(...).computeApplicability` over candidates from scope + implicit receivers + the ASM name index |
| **Name** `pre` | 5/5 | scope callables + implicit-receiver members + top-level callables (index→`findTopLevelCallables`) + classifiers (index→`findClassLike`) |
| **Argument** `f(x=1,\|)` | 2/2 | `resolveToCallCandidates()` → union `partiallyAppliedSymbol.signature.valueParameters` across overloads − already-supplied names |

**Every K1 wart the Phase 0 corpus caught is addressed by AA:** no keywords-after-dot, no
synthetic-class leak, correct substituted return types, `vec()` factory surfaces (K1 buried it at #41),
and named-arg completion (K1 has none).

## 3. Hard-won knowledge (do not re-derive)

- **AA version:** the sidecar uses `analysis-api-*-for-ide:2.4.20-Beta1` + IntelliJ `242.8057`, JDK 25
  toolchain. Builds are ~2–9s once the classpath is cached (it is, from normal `field.sh` use).
- **Classpath recipe (the #1 gotcha):** the AA session needs field's full binary classpath OR receiver
  types don't resolve ("Symbol not found", operator "Ambiguity"). `jvmRuntimeClasspath` gives deps but
  **NOT the project's own output** — you must also add `build/classes/kotlin/jvm/main`. Regenerate the
  classpath file with:
  ```
  ./gradlew -q --init-script tools/print_field_classpath.init.gradle.kts printFieldCp \
    | sed -n 's/^CP\t//p' > /tmp/fieldcp.txt
  ```
  (The spike appends `build/classes/kotlin/jvm/main` itself.) Requires `./gradlew compileKotlinJvm`
  first so `build/classes` exists.
- **Binary prefix-enumeration is the hard core.** Standalone AA can NOT enumerate a binary package's
  members by prefix — `packageScope.callables(nameFilter)` AND `getPossibleCallableNames()` both return
  empty for binary packages. But `findTopLevelCallables(pkg, exactName)` and `findClassLike(ClassId)`
  resolve fine. So we build a **name index** by ASM-scanning facade classes once (`buildIndex` in the
  spike): `*Kt` classes → top-level function names (callables); other top-level `.class` files →
  classifier names. ASM ships as `org.jetbrains.org.objectweb.asm` in the kotlin-compiler jar.
- **Type aliases have no `.class`** so the ASM index misses them (`Vec2`/`Vec3`); their target classes
  (`Vector2f/3f/4f`) do appear. Needs `@Metadata` reading to fix (Stage A).
- **AA APIs used** (all `KaSession` members unless noted; call inside `analyze(ktFile){ }`):
  `KtExpression.expressionType`, `KaType.scope` (`@KaExperimentalApi`), `KaTypeScope.getCallableSignatures`,
  `KaScope.callables/classifiers`, `KtFile.scopeContext(pos)` / `importingScopeContext` /
  `KaScopeContext.compositeScope()` / `.implicitReceivers`, `findTopLevelCallables` (import
  `…symbols.findTopLevelCallables`), `findClassLike(ClassId)`, `createExtensionCandidateChecker(originalFile,
  nameExpression, explicitReceiver).computeApplicability(sym)` → `KaExtensionApplicabilityResult` (`@KaIdeApi`,
  `@KaK1Unsupported`), `KtElement.resolveToCall()/.resolveToCallCandidates()`, `successfulFunctionCallOrNull()`.
  Opt-ins: `@OptIn(KaExperimentalApi::class, KaIdeApi::class, KaK1Unsupported::class)`.
- **Kotlin gotcha:** don't mix `Sequence` and `List` in a `+` merge — inference collapses to `List<Any>`
  and `.sorted()` fails. Make every sub-result `List<String>`.

## 4. How to resume cold (sanity check the spike still runs)

```
cd <this worktree>
./gradlew compileKotlinJvm --console=plain                       # ensure build/classes exists
./gradlew -q --init-script tools/print_field_classpath.init.gradle.kts printFieldCp \
  | sed -n 's/^CP\t//p' > /tmp/fieldcp.txt
./gradlew -p tools/code-use-semantic-sidecar runSpike --console=plain \
  -PspikeArgs="--workspace $PWD --corpus tools/completion-corpus/scraped.tsv --classpath-file /tmp/fieldcp.txt"
# expect: "[spike] index: ~2638 callables, ~1018 classifiers" then per-fixture ✓/✗ and 14/22.
# name/arg fixtures: --corpus tools/completion-corpus/name-fixtures.tsv  → 7/7.
```
Run backgrounded (AA session boot is ~1 min); read the log file, don't foreground-block.

## 5. Remaining stages (detailed)

### Stage A — harden the spike into a reusable AA completion module — **DONE (2026-07-19)**
Goal: one function `complete(preamble, snippet, cursor) -> List<Candidate>` good enough to diff against K1.
Delivered as `AaCompletion.kt` (`AaCompleter` + `AaSymbolIndex` + structured `AaCandidate`); `CompletionSpike.kt`
is now a thin harness over it. Corpus after Stage A: **dot 14/22 (no regression), name/arg 9/9** (incl. the new
safe-dot + type-alias fixtures). Every runSpike now **exits cleanly** (was leaking ~300MB JVMs per run).
- [x] **Cache the ASM name/classifier index to disk** — `AaSymbolIndex.buildOrLoad(roots, packages, cacheDir)`,
      keyed by a stat-only fingerprint (SHA-256 over root paths + jar size/mtime + dir newest-.class-mtime/count
      + `FORMAT_VERSION`). Stored at `build/completion-corpus/aa-symbol-index-<hash>.json` (gitignored, ~56KB).
- [x] **`@Metadata` type aliases** — `MetadataVisitor` reads each `*Kt` facade's `kotlin.Metadata` (k/d1/d2) and,
      for file facades (k==2), decodes the package proto via the bundled `JvmProtoBufUtil.readPackageDataFrom`
      to surface top-level typealias names (no `kotlinx-metadata-jvm` dep needed). 44 aliases indexed; `Vec2/3/4`
      now surface, resolved via `findTypeAlias(ClassId)`. (`.expandedType` shown as the tail.)
- [x] **Trim empty-prefix noise** — global classifiers/top-level callables only emitted once a prefix is typed
      (carried over from the spike; kept in the module).
- [x] **Safe-dot** `foo?.bar` — handled by keying the dot branch off the shared `KtQualifiedExpression`
      interface (covers `.` and `?.`). Fixture `hand#safe-dot` (`s?.len` → `length`) passes.
- [ ] **Operator-expression receivers** — the `stretch*` misses: `(sin(0.5) / all).st` doesn't resolve the
      receiver to `Fn` headless (operator ambiguity because the enclosing `with(Functions)` scope from the
      real box isn't present). Deferred to Stage C: `AaCompleter.complete` already takes a `preamble` param;
      Stage C must supply the box's inherited `with(...)` receivers so the receiver resolves. Verify then.
- [ ] Accept the field_probes GPU-DSL misses (`defaultBuffer{}`, `ComputeMesh`) as hard/dynamic for now.

### Stage B — K1-vs-AA differential harness — **DONE (2026-07-19)**
Goal: an automated, repeatable comparison + regression gate.
Because K1 runs in-process (jvm-main) and AA runs out-of-process (sidecar), the differential is a
**two-dump + one-diff** design rather than a single jvm-main test:
- [x] **K1 dump** — new jvm-main FIELD_TEST `kotlin.completion-diffdump.probe`
      (`src/jvmMain/kotlin/afield/CompletionDiffK1Probe.kt`) runs the real `KotlinInterface2.complete`
      (K1, intro-warmed) over both TSV corpora → `build/completion-corpus/k1-candidates.json`.
- [x] **AA dump** — spike `--json-out` (+ comma-separated `--corpus`) dumps raw AA candidates →
      `build/completion-corpus/aa-candidates.json`.
- [x] **Single runner** — `tools/completion_diff.py` reads both dumps, matches per engine's candidate
      shape, and reports RECALL (intended member present, rank shown) + ANTI-GOALS (synthetic-wrapper
      leak, keyword-after-dot, return-type eyeball) as a side-by-side + machine summary
      (`build/completion-corpus/diff-summary.json`) + a VERDICT. `--run` regenerates both dumps first
      (compile → classpath → AA dump → K1 dump); `--gate` exits nonzero on a failing verdict (Stage D);
      report-only (exit 0) by default.
- [x] **Acceptance met.** Over 31 shared fixtures: **recall K1 23 / AA 23** (parity); AA anti-goals
      **clean** vs K1's **2 synthetic-class leaks + 4 keyword-after-dot** fixtures; AA **wins named-arg**
      completion (`x`,`y`) that K1 lacks entirely. Only 2 regressions, both the known-hard dynamic
      GPU-DSL receivers (`field_probes#0 .vec4s`/`.buffer`) — allow-listed in the runner
      (`KNOWN_HARD_REGRESSIONS`) and deferred to Stage C. **VERDICT: PASS.**

Run it: `python3 tools/completion_diff.py --run` (or omit `--run` to diff existing dumps).

### Stage C — productionize / wire into field — **DONE (impl; live E2E pending) (2026-07-19)**
Decision already made: **out-of-process** (the AA classpath must stay out of the live runtime).
- [x] **`/complete` endpoint** on the code-use sidecar (`K2CompletionAnalyzer` in `CodeUseSemanticSidecar.kt`).
      Its own cached AA session per classpath-root set built from Field's **binary** classpath (the proven
      spike path — the code-use source-root session is kept separate), + `AaSymbolIndex` + `AaCompleter`,
      serialized under a `ReentrantLock`. Request: `{snippet,cursor,preamble,imports,implicitReceivers,
      classpathRoots,limit}`.
- [x] **REPL context modelled** (the real gap vs the spike). `AaCompleter.complete` now takes `preamble`
      + `implicitReceivers` + `extraImports`. **Key finding:** a dangling file's own TOP-LEVEL declarations
      do NOT enter the completion scope — only imports, implicit receivers, and LOCAL declarations do. So
      `splitPreamble` re-emits the inherited preamble as *local* stubs inside the completion function
      (name + type preserved; extension receiver / `by Delegate<Type>()` stripped so they're local-legal),
      and implicit receivers are modelled as nested `with(param)` scopes. Verified over HTTP against the
      `CompletionContextProbe` forms: `prop<Int>(inheritedCount)` → `inheritedCount: kotlin.Int` ✓,
      `@exported _exportedGreeting` → `: kotlin.String` ✓, bare `_` → `_exportedGreeting` ✓, plus normal
      dot (`moveTo`) / name (`Vec3`) intact.
- [x] **KI2 `complete()` behind a flag** — `KotlinEngineConfig.activeCompletionBackend = K1 | AA`
      (`-Dafield.kotlin.completion`). When AA, `AaCompletionClient` (`AaCompletionClient.kt`) POSTs
      `/complete` on `Dispatchers.IO` and returns a `CompletionResult.Success`; **any** failure / timeout /
      absent-sidecar / **empty result** returns null → falls through to the K1 delegate (so AA is never
      worse than K1). Sidecar URL from `-Dafield.completion.sidecarUrl` or
      `AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL`; classpath from the live JVM's `java.class.path`.
- [x] **AA → `SourceCodeCompletionVariant`** mapping (`text`/`displayText`/`tail`=return type/`icon`):
      methods `name(`+`…`, params `name = `, classes/typealiases `icon=class`, val/var by symbol kind.
- [x] **Live KI2→sidecar E2E** — `CompletionAaE2EProbe.kt` (FIELD_TEST `kotlin.completion-aa-e2e.probe`,
      SKIPs without a sidecar URL). Verified the real KI2→client→sidecar→AA chain: named-arg completion
      (`x = : kotlin.Float`, which proves AA — K1 has none), inherited `prop<Int>(inheritedCount)`:Int +
      `@exported _exportedGreeting`:String, ordinary dot/name, and K1 fallback on a bad URL.
- [x] Latency: warm per-keystroke over HTTP measured at **p50 22ms / avg 23ms / max 34ms** — well within
      per-keystroke budget (session + index cached; analysis serialized by a `ReentrantLock`).

### Stage D — acceptance + cutover — **DONE (2026-07-19)**
- [x] Differential over the corpus (scraped 22 + name/arg 9): `python3 tools/completion_diff.py --gate`
      → **VERDICT PASS**, exit 0 (AA recall 23 ≥ K1 23, AA anti-goals clean, 0 unexpected regressions).
      Context expectations (`@exported`/`prop`) hold — verified live by `CompletionContextProbe` (K1) +
      `CompletionAaE2EProbe` (AA); they need a real box, so they live in the probes, not the TSV corpus.
- [x] **Default flipped to AA** (`KotlinScriptEngine.kt`, `activeCompletionBackend` default `aa`), K1
      fallback wired. Only engages when a sidecar URL is present (else instant K1, no latency cost);
      falls back to K1 on any error/timeout/empty. Force legacy with `-Dafield.kotlin.completion=k1`.
- [x] The corpus + `tools/completion_diff.py --gate` doubles as the acceptance test for KTNB-916 if
      JetBrains ships the REPL-native completer.

**Live-testing polish (2026-07-19, done after in-app trial):**
- Non-blocking session build — first request returns empty (→ instant K1) while the ~1-min AA session
  builds in a background thread; then AA takes over. No per-keystroke stall during warmup.
- Empty-prefix noise: drop star-import scopes (`KaScopeKind.ImportingScope`) until a prefix is typed
  (was flooding `f(‸` with every stdlib top-level fn); exclude the `with(...)` `TypeScope` from
  `compositeScope` so receiver members aren't double-listed; filter synthetic `__`-names (`__r0`/`__c`).
- Argument position (`f(‸`) with empty prefix: suppress implicit-receiver members so the call's params
  aren't buried (was showing 50 `Box` members over `x/y/z`); a typed prefix restores them.
- Dedup key includes the icon, so a property and a same-named/typed method both survive (Vector3f's
  `var x: Float` was being clobbered by `fun x(): Float`).
- Candidate richness / doc parity with K1: methods render real params (`lineTo(v: Vec3): FLine`, types
  shortened + typealias-aware — `{Vec3=} Vector3f`→`Vec3`); members/extensions carry `receiverType` +
  `receiverQualifiedName` in `extraInfo`, so the server's member-KDoc / AI-draft-doc / receiver-doc
  enrichment attaches to AA candidates the same way it does for K1 (previously dot completions came back
  doc-less and param-less). `(import) …` suggestions suppressed at dot positions (server-side, both
  backends).
- Imported extension functions at name position (e.g. `Box.threeLines` when `Box` is the implicit
  receiver): these fell through every path — a binary package's star-import scope doesn't enumerate (the
  reason the index exists) and `topCallables` excluded extensions — so they were invisible to AA and only
  appeared via K1 fallback. Now: resolve prefix-matched index extensions and keep those whose
  extension-receiver type an implicit receiver `isSubtypeOf` (container = that receiver type, so KDoc
  resolves). Verified: `thre`/`threeL` both surface `threeLines` with a `Box` receiver.
- Observability: every completion logs its backend on channel `completion` —
  `completion backend=AA box=… 22ms (12 candidates)` when AA serves, or
  `completion backend=K1 box=… (AA→K1: <reason>)` on fallback (reasons: `no-sidecar-url`, `no-classpath`,
  `http-<code>`, `server-fail: …`, `empty: session building …`, `error …`). The sidecar logs its session
  lifecycle to stderr: `building AA session for N roots …` → `AA session ready in Nms (index: …)`.
  `AaCompletionClient.complete` returns an `Outcome(result, detail)` so the reason is always known.

**Follow-ups (not blocking cutover):** the 2 known-hard GPU-DSL dynamic receivers
(`field_probes#0 .vec4s`/`.buffer`); wider real-world preamble coverage in `splitPreamble` (regex
best-effort today, K1-fallback-safe); a periodic `--gate` run in CI as a regression guard.

## 6. Artifacts (all persisted in the repo tree, not scratchpad)
- **Reusable module (Stage A):** `tools/code-use-semantic-sidecar/.../AaCompletion.kt` — `AaCompleter`
  (`complete(snippet,cursor,preamble,implicitReceivers,extraImports)` + `splitPreamble`),
  `AaSymbolIndex` (disk cache + `@Metadata` typealias reader), `AaCandidate`.
- Spike harness: `tools/code-use-semantic-sidecar/.../CompletionSpike.kt` + `runSpike` task (+ `--json-out`).
- **Differential (Stage B):** `tools/completion_diff.py`; K1 dump `src/jvmMain/kotlin/afield/CompletionDiffK1Probe.kt`
  (FIELD_TEST `kotlin.completion-diffdump.probe`).
- **Endpoint + client (Stage C):** `/complete` + `K2CompletionAnalyzer` in `CodeUseSemanticSidecar.kt`;
  `src/jvmMain/kotlin/afield/AaCompletionClient.kt`; flag in `KotlinScriptEngine.kt`
  (`KotlinEngineConfig.activeCompletionBackend`, **default AA**); wired in `KotlinInterface2.complete()`.
- **Live E2E (Stage D):** `src/jvmMain/kotlin/afield/CompletionAaE2EProbe.kt` (FIELD_TEST
  `kotlin.completion-aa-e2e.probe`) — start the sidecar, write its URL to
  `build/completion-corpus/sidecar-url.txt`, run via `run_field_tests --id kotlin.completion-aa-e2e.probe`.
- Corpus: `tools/completion-corpus/scraped.tsv` (22 dot fixtures, on main), `name-fixtures.tsv` (name+arg).
- Scraper: `tools/scrape_completion_corpus.py`. Classpath generator: `tools/print_field_classpath.init.gradle.kts`.
- Phase 0 probes (on main): `kotlin.completion-corpus.probe`, `kotlin.completion-docscrape.probe`,
  `kotlin.completion-context.probe` (`src/jvmMain/kotlin/afield/Completion*Probe.kt`).
- Docs: `dev_docs/COMPLETION_ENGINE.md` (context-dependent expectations).

## 7. Note on branch state
This branch (`claude/wither-compiler-plugin-07c6d4`) also carries the **Wither compiler-plugin experiment**
(KI2 `-Xplugin`, `libs/wither-*.jar`, `WitherSmokeTest.kt`) — intentionally NOT merged to main. The Phase 1
completion spike is independent of Wither. Consider committing the spike + this plan to the branch so the
WIP survives (currently uncommitted).
