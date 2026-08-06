# The K2 Analysis-API Completion Backend (AA sidecar)

**Audience:** a developer who needs to understand, debug, or extend Field's Kotlin autocomplete.
**Status:** AA (the out-of-process K2 Analysis-API backend) is the **default** completion backend as of
commit `fd8f660b`. This doc is the architecture map. For the migration *narrative* (how we got here,
stage-by-stage), see [`completion_k2_migration_plan.md`](completion_k2_migration_plan.md); for the older
K1 engine mechanics (preambles, `prop<>` macros, `@exported` aliases) see
[`COMPLETION_ENGINE.md`](COMPLETION_ENGINE.md). This doc links to those rather than repeating them.

---

## TL;DR

When you type in a Kotlin box, the editor sends a `complete` RPC to the Field server. The server calls
`KotlinInterface2.complete()`, which — when the AA backend is active (the default) — POSTs the box's
REPL context to an **out-of-process K2 Analysis-API sidecar** (`/complete`). The sidecar computes
candidates with the Kotlin Analysis API and returns them as structured rows. The Field server enriches
them with KDoc/AI docs and ships them to the browser, where `fieldSuggest.js` does the ranking — and the
grouping, the category titles and the documentation pane; see
[`completion_popup_cover.md`](completion_popup_cover.md) for that half and the test that covers it. If the
sidecar is absent, slow, erroring, or returns nothing, the request transparently falls back to the
compiler-grade **K1** delegate — so AA is never *worse* than K1 in coverage.

```
browser editor
   │  "complete" RPC (CompletionRequest: code, cursor, box uid, property)
   ▼
Server.kt  handlers["complete"]                                  (Server.kt:2162)
   ▼
execution.kt  KotlinExecution.complete(inside, req, reply)       (execution.kt:2757)
   │  runs on the main runloop: inside.ki.complete(allCode, position)   (execution.kt ~3123)
   ▼
KotlinInterface2.complete(s, cursor)                             (KotlinInterface2.kt:552)
   │  backend == AA ?
   ├── AA ──► AaCompletionClient.complete(...)                   (KotlinInterface2.kt:566, AaCompletionClient.kt:84)
   │            │  HTTP POST /complete  (snippet, cursor, preamble, declarations,
   │            │                         implicitReceivers, classpathRoots)
   │            ▼
   │          sidecar process  POST /complete                    (CodeUseSemanticSidecar.kt:194)
   │            ▼  K2CompletionAnalyzer.complete()               (CodeUseSemanticSidecar.kt:658)
   │            ▼  AaCompleter.complete()  → Analysis API        (AaCompletion.kt:295)
   │            ▲  List<CompletionCandidate>
   │          (empty / error / no-URL / timeout) ─────────────┐
   └── K1 (or fallback) ──► k1CompletionDelegate().complete() ◄─┘   (KotlinInterface2.kt:577)
   ▼
execution.kt  enrich with KDoc / AI docs, categorize            (execution.kt:2886+)
   ▼  reply(Message("complete").payload(Completion(...)))
browser editor  fieldSuggest.js ranks (fuzzy + category + recency + contextScore)   (fieldSuggest.js:685)
```

---

## 1. Why

### K1 vs K2 / the Analysis API

Field's Kotlin *execution* backend is K2, but historically completion was **~100% K1**:
`KotlinInterface2.complete()` delegated to a K1 `KotlinInterface` (`k1CompletionDelegate`). K1's IDE
services give compiler-grade completion, but K1 is a dead-end: JetBrains is retiring it in favour of the
**Analysis API** (AA), the same stable public API the IntelliJ Kotlin plugin now uses. The REPL-native
AA completer JetBrains ships is K1-only in our compiler fork (KTNB-916 is unfinished upstream), so Field
builds its own AA candidate generation. See `completion_k2_migration_plan.md` §1 for the full backstory.

### Why out-of-process

The standalone Analysis API pulls a large **IntelliJ-style classpath** (`analysis-api-standalone-for-ide`,
`analysis-api-k2-for-ide`, and friends — see `tools/code-use-semantic-sidecar/build.gradle.kts:14+`).
Mixing those IntelliJ platform classes into Field's live-coding runtime JVM is undesirable (class
conflicts, memory, startup). So the AA work runs in a **separate JVM** — the *code-use semantic sidecar*
— and Field talks to it over a tiny local HTTP contract. This is the same design already used for
code-use telemetry (`/analyze`); completion just added a second endpoint (`/complete`) to the same
process. The in-runtime side (`AaCompletionClient`) is a thin `java.net.http` bridge with **no** AA
classes on its classpath (`AaCompletionClient.kt:19`).

---

## 2. The request path, end-to-end

### 2.1 Editor → server

The browser editor issues a `complete` RPC. `Server.kt` registers the handler
(`Server.kt:2162`) and decodes the payload into a `CompletionRequest`
(`Server.kt:2164`) carrying the full code buffer, caret `position`, box uid, and the `property` being
edited (`code`, a shader stage, `metadata`, …).

### 2.2 Server dispatch (`execution.kt`)

`KotlinExecution.complete(inside, completionRequest, reply)` (`execution.kt:2757`, declared on the
`BoxExecution` interface at `execution.kt:86`) routes by property: YAML `metadata`, GLSL/Slang shader
stages (`GlslCompletionSupport`, `Ai4GlslAutocomplete`), or — for ordinary Kotlin `code` — the language
engine. For Kotlin it calls, **on the main runloop**:

```kotlin
inside.ki.complete(allCode, position)     // execution.kt ~3123
```

The main-runloop hop matters: the K1 fallback delegate is not re-entrancy safe, so completion must run on
the same non-reentrant dispatcher used elsewhere (see the `warmCompletion` note below and the
`completion-capture-runloop-pump` memory).

### 2.3 `KotlinInterface2.complete()` — backend switch

`KotlinInterface2.complete(s, cursor)` (`KotlinInterface2.kt:552`) is the fork:

- If `KotlinEngineConfig.activeCompletionBackend == AA` (`KotlinInterface2.kt:561`):
  1. gather the box's inherited preamble: `normalizePreambleText(gatherPreambleUpwards(box))`
     (`KotlinInterface2.kt:562`; `gatherPreambleUpwards` in `macros/macros.kt:93`,
     `normalizePreambleText` in `stdlib/shaders.kt:1861`).
  2. collect implicit receiver type names (e.g. `afield.Box`).
  3. call `AaCompletionClient.complete(s, cursor, completionPreamble, receiverTypes)` on
     `Dispatchers.IO` (`KotlinInterface2.kt:565-566`).
  4. If AA **served** the result, log `backend=AA … (detail)` and return it (`KotlinInterface2.kt:569-571`).
  5. Otherwise log `backend=K1 … (AA→K1: detail)` and fall through (`KotlinInterface2.kt:573`).
- Otherwise (backend `K1`, or after an AA miss) it calls `k1CompletionDelegate().complete(s, cursor)`
  (`KotlinInterface2.kt:577`).

Every request logs which backend actually served it on the `completion` log channel, so the source of any
given completion is never a mystery.

### 2.4 `AaCompletionClient` — the HTTP bridge

`AaCompletionClient` (`src/jvmMain/kotlin/afield/AaCompletionClient.kt:21`) is an `object` with no AA
dependencies. `complete()` (`:84`):

- resolves the endpoint (`endpoint()`, `:65`): prefer `-Dafield.completion.sidecarUrl`, else
  `AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL`; append `/complete` if needed. **No URL → return
  `Outcome(null, "no-sidecar-url")`** (a K1-fallback signal).
- computes the running JVM's binary classpath once from `java.class.path`
  (`classpathRoots`, `:60`) — this includes afield's own compiled output, which the sidecar needs to
  resolve Field's own types.
- POSTs a `Req { snippet, cursor, preamble, declarations, implicitReceivers, classpathRoots,
  limit=200 }` JSON
  (`:95-108`) with a bounded timeout (default 4 s).
- maps the response to `CompletionResult.Success` (`:133`), mirroring the K1 delegate's `extraInfo`
  (`receiverType`, `receiverQualifiedName`, `:125-130`) so the server's doc-enrichment pipeline attaches
  KDoc to AA dot-members too.

The `Outcome(result, detail)` type (`:80`) is the fallback contract: **non-null `result` = AA served;
`null` = caller should fall back to K1.** Crucially, an **empty** AA candidate list is treated as a miss
(`:116`) — if the headless session can't model some preamble/receiver form, the request falls through to
K1 rather than showing the user nothing.

### 2.5 Sidecar `/complete` → `K2CompletionAnalyzer` → `AaCompleter`

The sidecar's HTTP server (`CodeUseSemanticSidecar.kt:183`) exposes `/health` (`:190`), `/analyze`
(telemetry, `:212`) and `/complete` (`:194`). `/complete` decodes a `CompletionRequest`
(`CodeUseSemanticSidecar.kt:143`) and calls `K2CompletionAnalyzer.complete()` (`:658`). Any thrown
exception is caught and returned as `CompletionResponse(success=false, …)` with HTTP 200 (`:204-209`) —
so the client sees a clean "server-fail" and falls back to K1, never an HTTP error.

`K2CompletionAnalyzer.complete()` (`:658`):

1. resolves `classpathRoots` on disk; empty → failure response.
2. looks up (or lazily builds, in the background) a cached AA **session** for that classpath root set
   (`cachedSession`, `:683`). **The session build takes ~1 minute**, so it is kicked off on a background
   single-thread pool and the current request returns an *empty* success immediately
   (`:664-667`) — the client reads empty as "fall back to K1", so the user gets K1 completions during
   warmup, then AA takes over transparently once the session is ready.
3. once warm, runs `cached.completer.complete(...)` under a per-session lock (`:668-676`) — the FIR
   caches aren't concurrency-safe, so analysis over a shared session is serialized. Snippet turnaround is
   milliseconds, so this doesn't meaningfully limit throughput at the sidecar's small thread count.
4. maps each `AaCandidate` to a wire `CompletionCandidate` via `AaCandidate.toVariant()` (`:746`):
   methods get a trailing `(` in `text` and a rendered param list in `displayText`; the return type goes
   in `tail`; members/extensions carry the receiver type (`recvSimple`/`recvQual`) for KDoc enrichment.

#### What `AaCompleter` does with the Analysis API (`AaCompletion.kt`)

`AaCompleter.complete()` (`AaCompletion.kt:295`) reconstructs the box's editing context as a synthetic,
compilable, **in-memory dangling `KtFile`** and asks the Analysis API what's in scope at the caret:

- **Preamble → local stubs.** A dangling file's own *top-level* declarations don't enter completion
  scope, so `splitPreamble()` (`:349`) splits the inherited preamble into (a) import lines kept at file
  top level and (b) every `prop<>`/`@exported`/plain declaration re-emitted as a *local* stub (name +
  type preserved, receiver/delegate stripped) inside the completion function. This is the load-bearing
  invariant that surfaces inherited REPL names — see `COMPLETION_ENGINE.md`.
- **Synthetic declarations → file scope plus discovery stubs.** Language runtimes such as KotlinJS
  can supply JVM-analysis-safe type models separately from the inherited REPL preamble. Classes and
  objects remain at file scope, including nested classifiers such as `THREE.Vector3`; top-level
  callables/properties are additionally stubbed locally so bare-name discovery remains reliable.
- **Implicit receivers → `with(...)` scopes.** Each implicit receiver type becomes a `with(__rN) { … }`
  wrapper (`:320-328`) so receiver members resolve as bare names and dot-completion on the receiver works.
- **Analysis** (`completeAt`, `:381`) runs inside `analyze(file) { … }`:
  - **Dot completion** (`recv.` / safe `recv?.`, `:401`): resolves the receiver's expression type, then
    lists substituted member callables via `type.scope.getCallableSignatures(...)`, nested classifiers
    via `getClassifierSymbols(...)`, plus **applicable extensions** filtered through the IDE's
    extension-applicability checker and receiver-conformance fallback.
  - **Name / argument position** (`:436`): scope callables (dropping importing scopes on an empty prefix
    to avoid flooding), implicit-receiver members, index-backed global symbols once a prefix is typed,
    receiver-applicable extension functions, classifiers, and — at an argument position — the enclosing
    call's not-yet-supplied **named parameters** (`:442-453`). Named-argument completion is AA-only; the
    K1 delegate produces none (this is what the E2E probe asserts to prove AA actually served).
  - **The expected type at the caret** — see below.

#### The type expected at the caret (`expectedTypesAt` / `enumerableValuesOf`)

At `d.scheme = ‸` the answer is the three entries of the `Scheme` enum, and until this existed completion
answered with every name in scope instead, because neither backend asked what type belonged there. AA can
be asked — `KaExpressionTypeProvider.expectedType` — which is why this is AA-only: K1 has no equivalent,
so `InfixCompletion` (`execution.kt:751`) had to *guess* the analogous thing for infix operands by
regexing the source index.

Three pieces, all in the non-dot branch of `completeAt`:

1. **A dummy name at the caret** (`CARET_MARKER = __fieldCaret__`, spliced in `complete()`). An empty slot
   has no expression to ask about, and the parse is worse than empty: `d.scheme =` followed by
   `display(…)` on the next line parses as `d.scheme = display(…)`, because a newline does not end an
   assignment's RHS. Splicing `__fieldCaret__` gives both a `KtExpression` to query and the intended
   parse. This is what IntelliJ does with `IntellijIdeaRulezzz`. It is gated to *untyped* expression slots
   (`wantsCaretMarker`: after `=`, `(`, `,`, `[`, `->`, `?:`, `&&`, `||`, `return`) — a typed prefix
   already *is* a name expression, and outside those slots the marker would only risk the PSI recovery the
   other paths depend on. The marker starts with `__`, so `AaCandidate.isSynthetic` guarantees it can
   never itself be offered.
2. **`expectedTypesAt()`** returns `expectedType` on that expression, with a fallback for argument slots:
   the parameter types from `resolveToCallCandidates()` unioned over overloads (named argument matched by
   name, positional by index). The fallback matters because `expectedType` needs the call to resolve, and
   code being typed usually doesn't. Expectations too weak to act on are dropped
   (`isUsefulExpectation`: `Any`, `Any?`, `Unit`, `Nothing`, error types — `errorType.isSubtypeOf(x)` is
   vacuously true, the same trap the dot path documents).
3. **`enumerableValuesOf()`** turns an expected type whose inhabitants are enumerable into candidates:
   an enum's entries, a sealed type's inheritors (`sealedClassInheritors` — works for binary sealed types
   too), and `Boolean` → `true`/`false`. Everything else gets no values, only the fit flag.
   - Entries are **displayed bare** (`BLEND`) and **inserted qualified** (`FabricateAlphaMode.BLEND`, via
     `AaCandidate.insertText`), because Kotlin only resolves a bare entry from the expected type inside a
     `when` subject. `qualifierFor()` picks the shortest form that actually compiles in the file's import
     context, falling back to fully qualified.
   - A binary enum's entries are **not** in `declaredMemberScope** — that comes back empty, and they are in
     the static scopes. All three are unioned, the same lesson `staticScopeCandidates` learned on the dot
     path.

Every other candidate the branch produces is flagged `fitsExpected` when its (substituted) return type
`isSubtypeOf` the expectation, so a local, a member or a factory function of the right type ranks with the
literals. Named parameters at an argument position are flagged too — at `f(‸` the parameter names *are* the
answer, and the collapse below must not hide them behind type-fitting values.

Because the candidate list is name-sorted before `limit` is applied, the sidecar puts fitting candidates
first *before* truncating (`CodeUseSemanticSidecar.kt`) — otherwise `UD`/`SD_*` fall off the end of a
200-row cut behind every `Box` member.

**Server side** (`execution.kt`): a fitting candidate arrives as `extraInfo["expectedTypeMatch"]`
(`AaCompletionClient.EXPECTED_TYPE_MATCH`) and gets `InfixCompletion.OPERAND_MATCH_BOOST`, the same boost
the infix path uses. And with **nothing typed yet**, the reply is collapsed to *only* the fitting
candidates (`typeFitFilterActive`) — that is what makes the panel at `d.scheme = ‸` three rows instead of
three hundred, regardless of how the client ranks or how old its `fieldSuggest.js` is. One keystroke
restores the full list with the fits merely boosted, so nothing is permanently hidden, and the collapse
needs ≥2 fits (≥3 for the infix path) so a bad resolution can never empty the panel.

Not done on **dot** positions: `d.scheme = foo.‸` could boost members returning `Scheme`, but the dot
branch resolves from the qualified expression and was left byte-for-byte alone.

#### The ASM symbol index (`AaSymbolIndex`)

Standalone AA **cannot** enumerate a *binary* package's members by prefix
(`packageScope.callables(nameFilter)` returns empty for binary packages), but it *can* resolve them by
exact name (`findTopLevelCallables`, `findClassLike`, `findTypeAlias`). So `AaSymbolIndex`
(`AaCompletion.kt:90`) scans the binary classpath roots once with ASM to learn which callable /
classifier / type-alias names exist per imported package, then AA resolves them on demand by exact name.
The index is disk-cached under the sidecar scratch dir, keyed by a stat-only classpath fingerprint
(`buildOrLoad`, `:112`; fingerprint `:130`), so a recompile transparently rebuilds it and an unchanged
classpath loads it instantly. Type aliases (which have no `.class`) are read from each file facade's
`@Metadata` proto (`MetadataVisitor`, `:238`).

### 2.6 Results back → enrichment → client-side ranking

Back in `execution.kt`, the `CompletionResult` metadata is enriched: KDoc is resolved from
`extraInfo["kdoc"]`, then `KotlinSourceIndex.getKDocForSymbol(...)` (using the receiver type for
dot-members), categorized, and infix operand type-matches get a `contextScore` boost
(`execution.kt:2886+`, ~`3012` for enrichment; `InfixCompletion` boost ~`3060`). The server then replies
`Message("complete").payload(Completion(boundsStart, boundsEnd, metadata))`.

**Ranking is client-side.** The server returns everything; `fieldSuggest.js` scores each item as
`fuzzy + categoryScore + recency + aiLateBonus + memberScore + contextScore − originalIndex*0.012`
(`src/commonMain/resources/fieldSuggest.js:685-701`) and sorts (`:712`). The AA backend deliberately does
**not** rank — it returns a de-duplicated, name-sorted candidate set and lets the client decide order
(`completion-ranking-clientside` memory).

---

## 3. The sidecar: process & lifecycle

**Module:** `tools/code-use-semantic-sidecar/` — a *separate* Gradle project (own `settings.gradle.kts`),
intentionally outside the main Field build so the IntelliJ-style AA classpath never mixes into the
runtime. Entry point `afield.tools.codeuse.CodeUseSemanticSidecarKt` (`build.gradle.kts:54`).

**Launch.** `field.sh` manages it (`ensure_semantic_sidecar`, `field.sh:71`):
- The port is derived from the checkout path so each worktree gets its own sidecar:
  `17841 + (cksum(path) % 1000)` (`derive_sidecar_port`, `field.sh:19-24`), overridable with
  `AFIELD_CODE_USE_SEMANTIC_SIDECAR_PORT`.
- It health-checks any configured/running sidecar (`sidecar_status` verifies the reported
  `workspaceRoot` matches this checkout), and if none is serving *this* checkout it starts one:
  `nohup ./gradlew -p tools/code-use-semantic-sidecar run --args="--port <p> --workspace <dir>"`
  (`field.sh:102`), logging to `build/code-use-semantic-sidecar/sidecar-launcher.log`.
- It exports `AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL` for the Field JVM (which `AaCompletionClient` reads).
- Disable launcher-managed startup with `AFIELD_CODE_USE_SEMANTIC_SIDECAR=off`.

Run it manually:
```bash
./gradlew -p tools/code-use-semantic-sidecar run --args="--port 17841 --workspace $PWD"
```

**Transport:** plain `com.sun.net.httpserver.HttpServer` on `127.0.0.1` (default port 17841,
`SidecarConfig`, `CodeUseSemanticSidecar.kt:249`), daemon thread pool of 2 (`--threads`, 1-8).

**Warmup / session lifecycle:** an AA session (`buildStandaloneAnalysisAPISession`, `:713`) indexes the
JDK + Field's binary classpath and costs ~1 min to build. It is built **once per classpath-root set**,
in the background, and held for the process lifetime (never disposed — `build()`, `:708-709`). Startup
progress is logged to the sidecar's stderr: `"[k2-completion] building AA session …"` then
`"[k2-completion] AA session ready in Nms (index: …)"` (`:689-694`). During the build, `/complete`
returns empty-success so the client uses K1; after it's ready, AA serves.

---

## 4. Backend selection & fallback

**Selection** lives in `KotlinEngineConfig` (`KotlinScriptEngine.kt`):

```kotlin
enum class CompletionBackend { K1, AA }                               // KotlinScriptEngine.kt:697
var activeCompletionBackend = when (System.getProperty(
        "afield.kotlin.completion", "aa").lowercase()) {              // :699
    "k1" -> CompletionBackend.K1
    else -> CompletionBackend.AA                                      // DEFAULT = AA
}
```

- **Default:** AA. Force legacy K1 with `-Dafield.kotlin.completion=k1`.
- This is independent of the *execution* backend (`activeBackend`) — completion and execution are
  separate knobs.

**Fallback to K1** happens (each logged as `AA→K1: <detail>` on the `completion` channel) when:

| Condition | Where | `detail` |
|---|---|---|
| No sidecar URL configured | `AaCompletionClient.kt:91` | `no-sidecar-url` |
| Empty classpath | `:92` | `no-classpath` |
| HTTP non-200 | `:110` | `http-<code>` |
| Sidecar reported failure | `:112` | `server-fail: …` |
| **Empty AA result** (incl. warmup) | `:116` | `empty…` |
| Timeout / network / any exception | `:143` | `error <Exception>: …` |

So AA is strictly additive: worst case you get exactly the K1 experience. The K1 delegate itself is
warmed ahead of time (`warmCompletion`/`warmCompletionAsync`, `KotlinInterface2.kt:587,603`) so the first
real request — and every fallback — is fast and doesn't re-enter the compiler mid-analysis.

---

## 5. Verification tooling

### `CompletionAaE2EProbe` — live end-to-end (Stage D)

`src/jvmMain/kotlin/afield/CompletionAaE2EProbe.kt` — FIELD_TEST `kotlin.completion-aa-e2e.probe`
(runner `jvm-main`, entrypoint `afield.CompletionAaE2EProbe`). Sets `activeCompletionBackend = AA`,
builds a parent→child box pair with an inherited preamble, and drives the **real**
`KotlinInterface2.complete()` chain against a running sidecar. It asserts AA-only signals (named-argument
completion, inherited `prop<>`/`@exported` names), measures warm latency, and verifies that a bad sidecar
URL falls back to a non-empty K1 result. **It SKIPs cleanly (exit 0, prints `AA E2E DONE`) when no
sidecar URL is configured**, so it never fails CI where no sidecar is up. Needs a live sidecar (URL via
`-Dafield.completion.sidecarUrl`, the env var, or `build/completion-corpus/sidecar-url.txt`).

Run: `python3 tools/run_field_tests.py --id kotlin.completion-aa-e2e.probe`.

### `CompletionDiffK1Probe` — the K1 side of the differential (Stage B)

`src/jvmMain/kotlin/afield/CompletionDiffK1Probe.kt` — FIELD_TEST `kotlin.completion-diffdump.probe`.
Runs the **real production K1 path** over the shared corpora and dumps raw candidates per fixture to
`build/completion-corpus/k1-candidates.json`. The AA side is dumped out-of-process by the sidecar spike
(`runSpike --json-out … aa-candidates.json`).

### `tools/completion_diff.py` — the K1-vs-AA differential

Reads both dumps and diffs them per fixture on **recall** (is the intended member present?) and
**anti-goals** (no synthetic-wrapper leak, no keyword-after-dot). Prints a side-by-side table and a
verdict. Report-only by default; `--gate` exits nonzero on a failing verdict (for CI); `--run`
regenerates both dumps first (recompiles, regenerates the Field classpath, runs the AA spike and the K1
probe). A small `KNOWN_HARD_REGRESSIONS` set (dynamic GPU-DSL receivers) is tolerated so the gate flags
only *unexpected* regressions.

```bash
python3 tools/completion_diff.py            # diff existing dumps, report only
python3 tools/completion_diff.py --run       # regenerate both dumps, then diff
python3 tools/completion_diff.py --run --gate # regenerate + fail on regression (CI gate)
```

### Corpus

`tools/completion-corpus/` holds the shared fixtures both sides consume:
`scraped.tsv` (real completion sites) and `name-fixtures.tsv`. Fixtures are `‸`-caret-marked, base64
snippet + intended member; the K1 probe and AA spike line up by fixture id.

### What a real run looks like

Running the canonical single runner on this branch:

```text
$ python3 tools/completion_diff.py --run --json-summary build/completion-corpus/diff-summary.json
... (compileKotlinJvm, regenerate classpath, AA sidecar spike, K1 jvm-main probe) ...
FIXTURE                           POS  MEMBER          K1        AA          NOTES
...
arg#Vector3f-empty                NAME x               ✗         ✓#7           ⟵ win
arg#Vector3f-afterX               NAME y               ✗         ✓#8           ⟵ win
hand#safe-dot                     DOT  length          ✓#1       ✓#1         type K1[Int] AA[kotlin/Int]
────────────────────────────────────────────────────────────────────────────
RECALL:   K1 23/31   AA 23/31
REGRESSIONS (K1 had, AA lost): 2  ['field_probes#0 .vec4s', 'field_probes#0 .buffer']
  ├─ known-hard (dynamic GPU-DSL, Stage C): 2  ['field_probes#0 .vec4s', 'field_probes#0 .buffer']
  └─ UNEXPECTED: 0  []
WINS (AA has, K1 lacked):      2  ['arg#Vector3f-empty', 'arg#Vector3f-afterX']
ANTI-GOAL leaks:      K1 2 [...]   |   AA 0 []
ANTI-GOAL kw-after-.: K1 4 [...findAttractors(field,file,finally)...]   |   AA 0 []
════════════════════════════════════════════════════════════════════════════
VERDICT: PASS  (AA recall 23 >= K1 23; AA anti-goals clean; unexpected regressions 0)
```

That run is real (captured 2026-07-20 on this branch): AA matches K1 recall (23/31), adds the two
named-argument wins K1 can't produce, and stays clean on both anti-goals (K1 leaks the two synthetic
arg-position candidates and emits four `keyword-after-dot` warts that AA never does). The two AA
"regressions" are the documented `KNOWN_HARD_REGRESSIONS` — dynamic GPU-DSL receivers that only resolve
inside a live box runtime, not the headless spike.

---

## 6. Where to look when completion misbehaves

- **"Why did I get K1?"** — grep the `completion` log channel for `AA→K1: <detail>` (the table in §4
  decodes each reason). Common: `no-sidecar-url` (sidecar not launched), `empty…` (session still warming
  or a preamble form the headless session can't model).
- **Sidecar not starting** — `build/code-use-semantic-sidecar/sidecar-launcher.log`; check the derived
  port with `derive_sidecar_port` and `lsof -i`.
- **Wrong/stale candidates for Field's own types** — the ASM index is classpath-fingerprint cached; a
  recompile rebuilds it, but confirm `classpathRoots` (the client sends `java.class.path`) points at the
  fresh `build/classes`.
- **Named-args / inherited names missing** — that's the AA-specific surface; check the preamble
  (`gatherPreambleUpwards`) and implicit receivers actually reached the sidecar (they're logged), and see
  `AaCompleter.splitPreamble`.

## 7. Related docs

- [`completion_k2_migration_plan.md`](completion_k2_migration_plan.md) — migration narrative & stage log.
- [`COMPLETION_ENGINE.md`](COMPLETION_ENGINE.md) — preambles, `prop<>` macros, `@exported` aliases.
- `tools/code-use-semantic-sidecar/README.md` — running the sidecar; the `/analyze` telemetry contract.
