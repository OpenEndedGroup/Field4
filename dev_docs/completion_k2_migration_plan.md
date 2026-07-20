# K2 Analysis-API Completion Migration — Continuation Plan

**Status (2026-07-19):** Phase 0 done + merged to main; Phase 1 (feasibility spike) done — all three
completion positions proven on the K2 Analysis API, wart-free. What remains is productionization,
not proof. This doc is a cold-start handoff: read it top to bottom and you can resume without the
prior conversation.

Related memory: `completion-regression-coverage.md`, `wither-plugin-in-ki2.md`.
Reference source tree: `~/temp/kotlin` is `2.4.255-SNAPSHOT` — the SAME version as field's embedded
compiler — and contains the Analysis API source + `KJvmReplCompleter.kt`. Use it to look up any AA API.

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

### Stage A — harden the spike into a reusable AA completion module
Goal: one function `complete(preamble, snippet, cursor) -> List<Candidate>` good enough to diff against K1.
- [ ] **Cache the ASM name/classifier index to disk**, keyed by a hash of the classpath entries (paths +
      mtimes). Classpath rarely changes, so this makes cold starts instant. Store under
      `build/completion-corpus/` (gitignored) or the sidecar's scratch dir.
- [ ] **`@Metadata` type aliases** — read the package facade/`.kotlin_module` `@Metadata` to add typealias
      names (`Vec2`, `Vec3`) to the classifier index. (ASM `visitAnnotation` for `kotlin/Metadata`, or use
      `kotlinx-metadata-jvm`.)
- [ ] **Trim empty-prefix noise** — at name/arg position with empty prefix, don't emit global classifiers;
      keep local scope + params + implicit-receiver members. (Ranking is the client's job, but the engine
      shouldn't dump thousands on an empty prefix.)
- [ ] **Safe-dot** `foo?.bar` — same as dot; confirm `KtSafeQualifiedExpression` handling.
- [ ] **Operator-expression receivers** — the `stretch*` misses: `(sin(0.5) / all).st` doesn't resolve the
      receiver to `Fn` headless (operator ambiguity because the enclosing `with(Functions)` scope from the
      real box isn't present). Likely fixed once Stage C supplies the real inherited preamble (`with`
      context). Verify.
- [ ] Accept the field_probes GPU-DSL misses (`defaultBuffer{}`, `ComputeMesh`) as hard/dynamic for now.

### Stage B — K1-vs-AA differential harness
Goal: an automated, repeatable comparison + regression gate.
- [ ] Single runner that, per corpus fixture, gets K1 candidates (from the existing jvm-main probes,
      `kotlin.completion-*.probe`) AND AA candidates (spike), and diffs: recall of the intended member,
      plus the anti-goals (no keywords-after-dot, no `Corpus_completion_` leak, correct return types).
- [ ] Emit a readable side-by-side + a machine summary. Wire as a FIELD_TEST (report-only first).
- [ ] Acceptance target: **AA matches-or-beats K1** on recall for the corpus, and passes all anti-goals.

### Stage C — productionize / wire into field
Decision already made: **out-of-process** (the AA classpath must stay out of the live runtime). So:
- [ ] Add a `/complete` endpoint to the **code-use sidecar** (reuse its cached `StandaloneAnalysisAPISession`
      + `KtPsiFactory.contextual` dangling-file trick — see `CodeUseSemanticSidecar.analyze`). Move the
      spike's `completeAt` + `buildIndex` into the sidecar proper.
- [ ] **Model the REPL context** — this is the real gap vs the spike. Field completion needs the box's
      *inherited preamble* (`gatherPreambleUpwards(box)`), the implicit `Box` receiver, `@exported` `_name`
      aliases, and `prop<Foo>(bar)` macro expansions (see `dev_docs/COMPLETION_ENGINE.md` "Context-dependent
      completion expectations" + `CompletionContextProbe`). The synthetic-source wrap must include the
      preamble and wrap the snippet so those are in scope (e.g. inside the box's `with(...)` receivers).
- [ ] KI2 `complete()` → behind a flag (mirror `KotlinEngineConfig.activeBackend`, e.g.
      `activeCompletionBackend = k1 | aa`), call the sidecar; fall back to K1 on timeout/failure. Keep K1
      as default until Stage D passes.
- [ ] Map AA candidates → `SourceCodeCompletionVariant(text, displayText, tail, icon)` so `fieldSuggest.js`
      ranks them (client owns ranking — see `completion-ranking-clientside`). `tail` = return type.
- [ ] Latency: per-keystroke over HTTP to a warm session should be OK (the session caches), but measure;
      the sidecar already serializes analysis per session (a `ReentrantLock`).

### Stage D — acceptance + cutover
- [ ] Run the Stage B differential over the full corpus (scraped 22 + name/arg + context). AA ≥ K1 recall,
      all anti-goals pass, context expectations (`@exported`/`prop`) hold.
- [ ] Flip `activeCompletionBackend` default to AA; keep K1 fallback wired.
- [ ] The corpus doubles as the acceptance test for KTNB-916 if JetBrains ships the REPL-native completer.

## 6. Artifacts (all persisted in the repo tree, not scratchpad)
- Spike: `tools/code-use-semantic-sidecar/.../CompletionSpike.kt` + `runSpike` task.
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
