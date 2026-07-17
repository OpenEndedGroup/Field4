# Code-use telemetry

Field has a first-pass code-use telemetry pipeline for answering questions like:

- which snippets were compiled, evaluated, executed, launched, or continued;
- which document, box, property, and strand produced that code;
- whether compile/evaluate succeeded and what runtime type or exception resulted;
- which compiler errors and runtime exceptions happened for a source blob;
- which likely `src/` symbols the snippet referenced, plus unresolved analyzer misses.

The first version is intentionally an event store, not a perfect call graph. Kotlin snippets can rely on extension receivers, implicit receivers, `fromContext()`, lambdas, reflection, and live REPL state, so static resolution is best-effort even with K2/FIR. Hot paths only enqueue immutable records; all SQLite writes and later analysis happen off the compiler/evaluator path.

## Runtime hooks

Low-latency capture points:

- `KotlinInterface2.compile(...)` records source text, synthetic source name, diagnostics, elapsed time, and box/property context.
- `KotlinInterface2.evaluate(...)` records success/failure, runtime script class, returned value type, exceptions, diagnostics, and elapsed time.
- `execution.kt` records execute, launch, continue, and running-state lifecycle events with document/box/property/strand metadata.

These hooks call `CodeUseTelemetryService` and return immediately after enqueueing. If the event queue fills, events are dropped and counted. Source-bearing events are then queued to a separate low-priority analyzer worker. If SQLite initialization or analysis fails, Field execution continues.

## SQLite store

The default database path is:

```text
~/.afield/code-use/field-code-use.sqlite
```

Override with either:

```text
-Dafield.codeUse.path=/path/to/field-code-use.sqlite
AFIELD_CODE_USE_PATH=/path/to/field-code-use.sqlite
```

The service enables WAL mode and stores full source text content-addressed by SHA-256. Core tables:

- `source_blobs`
- `snippets`
- `compile_events`
- `eval_events`
- `launch_events`
- `contexts`
- `symbols`
- `symbol_uses`
- `unresolved_uses`
- `classpath_entries`

The symbol tables are populated by a background analyzer worker. The current resolver tokenizes immutable snippet snapshots and resolves likely call/property/constructor references against the local Kotlin source index, falling back to a lightweight source scan if the index has not been built yet. A single unqualified match writes `symbol_uses` with `source-index-unique` confidence; a receiver-qualified single match writes `source-index-qualified`; ambiguous candidates are dropped (no `symbol_uses` row) and recorded in `unresolved_uses` instead. `source-index-match` is a legacy confidence value the store now prunes at startup, not live output. It does not touch live K2 REPL state.

## Browser/API

Open the monitor from `/welcome` under AI -> Code-use telemetry, or directly:

```text
/code-use.html
/code-use
```

Read-only JSON APIs:

```text
GET /api/code-use/summary
GET /api/code-use/snippets?limit=100
GET /api/code-use/launches?limit=100
GET /api/code-use/symbols?limit=100
GET /api/code-use/unresolved?limit=100
POST /api/code-use/analyze
```

`/api/code-use/summary` includes `analyzerBackend`, `analyzerMessage`, `analyzerQueueSize`, and `droppedAnalysisJobs`. As of this pass the backend is `source-index`: Field can report whether the public K2 Analysis API class files are visible, whether they are actually loadable by the JVM, and whether a standalone/session-provider runtime is present. That status is shown on `/code-use.html`.

`POST /api/code-use/analyze` accepts `CodeUseSemanticAnalyzeRequest` JSON:

```json
{
  "code": "CodeUseTelemetryService.summary()",
  "sourceName": "AgentSnippet.kt",
  "limit": 5,
  "callersPerSymbol": 5,
  "sourceRadiusLines": 24
}
```

Field enriches the answer with local SQLite graph evidence and returns `CodeUseSemanticAnalyzeResult`: `backend`, `message`, `resolvedUses`, `unresolvedUses`, ranked `references`, and `relatedSources` with source excerpts, usage counts, timestamps, and peer Field snippets. If the semantic sidecar is not configured or cannot answer within its timeout, this endpoint still returns a `source-index-fallback` result using the local source index and code-use graph.

AI4 box chat, inline chat, cursor/rewrite/error helpers, and the simple chat input also expose a read-only tool:

```text
CodeUseQueryRequest(kind="failures", limit=20, document="", box="")
```

Supported `kind` values are `summary`, `failures`, `snippets`, `launches`, `symbols`, `unresolved`, `line-callers`, `references-for-code`, `related-source-for-code`, `semantic-analyze`, `hot-symbols`, and `early-document-symbols`. Prefer `kind="failures"` when a recent compiler error or runtime exception may explain a user's request or point to an API/documentation growth point. Prefer `kind="semantic-analyze"` when an agent is interrogating Field box code and needs the best available source resolution plus relevant `src/` excerpts, observed call frequencies, and peer snippets that used the same routines. Use `related-source-for-code` when you explicitly want the local source-index fallback. The `document` and `box` filters are case-insensitive substrings for row types that carry document/box metadata.

AI4 also registers targeted request schemas so box chat and inline chat do not need to remember string `kind` values:

```text
CodeUseCallersForSourceLineRequest(sourcePath="src/...", line=123, limit=20)
CodeUseReferencesForCodeRequest(code="...", limit=5)
CodeUseRelatedSourceForCodeRequest(code="...", limit=5, callersPerSymbol=5, sourceRadiusLines=24)
CodeUseSemanticAnalyzeCodeRequest(code="...", sourceName="AgentSnippet.kt", limit=5, callersPerSymbol=5, sourceRadiusLines=24)
CodeUseHotSymbolsRequest(window="week", limit=20)
CodeUseEarlyDocumentSymbolsRequest(document="optional substring", earlyWindowMs=600000, limit=20)
```

These return compact telemetry rows:

- `line-callers`: recent Field snippets that call or textually mention the source symbol enclosing a `src/` line, including timestamps, box/document metadata, compile/eval status, return type, runtime class, and recent launch lifecycles.
- `references-for-code`: likely `src/` files/properties/methods referenced by a provided code block.
- `related-source-for-code`: a source bundle for each likely reference: bounded numbered source excerpt, call-site frequency, first/last seen timestamps, and other Field snippets that called the same routine.
- `semantic-analyze`: a live query for a new code block. Field sends the snippet to an optional standalone semantic sidecar, then enriches whatever it gets back with the local graph. Without a sidecar it returns the same graph-backed fallback shape, marked `source-index-fallback`.
- `hot-symbols`: observed source-symbol usage for `all`, `week`, `day`, or `month`.
- `early-document-symbols`: symbols used in the first window of a document's observed telemetry and then rarely afterward.

Rows include a `confidence` field. `source-index-unique` / `source-index-qualified` mean the background analyzer populated `symbol_uses` from the Kotlin source index (unqualified vs. receiver-qualified single match); `k2-resolved` comes from the semantic sidecar; `textual` is a fallback from snippet text. The isolated K2/FIR resolver adds stronger resolved edges without changing the API shape. Textual fallback deliberately suppresses highly ambiguous simple names so hot lists are not dominated by common helpers like `error` or `point`.

The browser has four views:

- Timeline: recent snippets and compile/eval outcomes.
- Failures: compiler diagnostics, evaluator exceptions, and lifecycle errors. These rows are intentionally retained because failed code is often the evidence that Field's APIs, stdlib, diagnostics, or documentation need to grow.
- Launches: execute/launch/continue lifecycle events and watermarks.
- Symbols: source-index-backed symbol-use counts from the background analyzer.
- Unresolved: analyzer misses, including empty-index, no-match, and ambiguous-match rows.

The same page also includes an AI4 general-chat panel. It connects to Field's normal `chat_input` handler over `/ws`, so it has the same code-use tools as box chat and inline chat. The prompt starters bias the conversation toward recent failures, hot symbols, open-time helpers, and the related-source workflow.

## Semantic sidecar

The K2/FIR semantic analyzer should run out-of-process. Field configures it with:

```text
-Dafield.codeUse.semanticSidecar.url=http://127.0.0.1:17841
AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL=http://127.0.0.1:17841

-Dafield.codeUse.semanticSidecar.timeoutMs=2500
AFIELD_CODE_USE_SEMANTIC_SIDECAR_TIMEOUT_MS=2500
```

The normal launchers, `./field.sh` on Unix and `.\field.ps1` on Windows, manage this automatically: they check `<url>/health` for the same `workspaceRoot`, start `tools/code-use-semantic-sidecar` if the sidecar is down or points at another checkout, export the correct URL, and then start Field. Generated-worktree restarts also go through these launchers, so each worktree gets a sidecar for its own source tree. Set `AFIELD_CODE_USE_SEMANTIC_SIDECAR=off` to disable launcher-managed startup.

Field posts to `<url>/analyze` unless the configured URL already ends in `/analyze`. The request is the same `CodeUseSemanticAnalyzeRequest` accepted by `/api/code-use/analyze`, enriched with `databasePath`, `workspaceRoot`, `sourceRoots`, and `timeoutMs` if the caller omitted them. Field does not send its full JVM classpath by default; `classpathRoots` is opt-in until the sidecar has a stronger story for mixed IntelliJ/compiler binary ABI compatibility. The sidecar may query the SQLite database directly, but it does not have to: a minimal first sidecar can return only `resolvedUses` and `unresolvedUses`, and Field will map those to ranked references, source excerpts, usage counts, timestamps, and peer snippets.

Sidecar response contract:

```json
{
  "success": true,
  "backend": "k2-sidecar",
  "message": "resolved 3 use(s)",
  "sourceName": "AgentSnippet.kt",
  "resolvedUses": [
    {
      "symbolName": "afield.CodeUseTelemetryService.summary",
      "callableId": "afield/CodeUseTelemetryService.summary",
      "kind": "fun",
      "sourceFile": "src/jvmMain/kotlin/afield/CodeUseTelemetryService.kt",
      "line": 459,
      "useKind": "call",
      "snippetLine": 1,
      "snippetColumn": 25,
      "confidence": "k2-resolved"
    }
  ],
  "unresolvedUses": [],
  "references": [],
  "relatedSources": []
}
```

Timeouts, HTTP errors, malformed JSON, and sidecar crashes degrade to `source-index-fallback` and log a warning. They must not block snippet compilation/evaluation or disable Field.

## Analyzer

The analyzer runs against immutable source snapshots in a separate low-priority worker. It must not touch the live `K2ReplCompilationState` or any hot REPL objects. The current source-index pass writes:

- call/reference/property/constructor candidates;
- source-index-backed symbol rows;
- symbol-use rows with line/column/use-kind/confidence;
- unresolved rows with line/column/reason.

The intended semantic backend is an isolated K2 Analysis API worker:

1. Vendor or depend on the standalone Analysis API implementation that matches Field's custom Kotlin `2.4.255-SNAPSHOT` compiler. The current checkout only has `analysis-api` public classes plus `analysis-api-fir-generator`; the public API can also fail to load if its unshaded IntelliJ platform dependencies are absent while only the compiler-embeddable relocated `org.jetbrains.kotlin.com.intellij...` classes are present. `AnalyzeKt.analyze(...)` ultimately asks the IntelliJ `Project` for a `KaSessionProvider`, so both loadable public API classes and a provider implementation are required.
2. Build one low-priority analysis project from immutable inputs: Field source roots, current compile classpath, default imports, snippet text, preamble hash, and captured implicit-receiver hints. Do this outside the live REPL state.
3. Parse each snippet into a synthetic `KtFile` and run `analyze(ktElement) { ... }` over PSI nodes such as calls, name references, property accesses, and constructor callees.
4. For each resolved call or reference, extract the callable/class/property symbol, receiver type where available, callable id or fully qualified name, PSI source location if it points into `src/`, and snippet line/column.
5. Persist concrete semantic edges with confidence `k2-resolved`. If a symbol resolves to a library or generated location that cannot be mapped to `src/`, keep the edge but leave source-file fields empty or fall back to the source index. If resolution fails, write `unresolved_uses` with a K2 reason.

The storage and browser API already support this stronger backend: `symbol_uses.confidence` can distinguish `k2-resolved`, the source-index values (`source-index-unique` / `source-index-qualified`), and `textual`, while existing `line-callers`, `related-source-for-code`, and hot-symbol queries prefer persisted symbol edges before falling back to text.

## Smoke test

The smoke test is a `FIELD_TEST` JVM entrypoint:

```bash
uv run --project . --group backdoor python tools/run_field_tests.py --id examples.code-use.telemetry-store
```

It sets a temporary `afield.codeUse.path`, enqueues compile/eval/launch events, polls the public service API until SQLite contains them, and verifies source hashing, failure retention, analyzer-populated symbol rows, source-line callers, related source bundles, hot symbols, and early-document symbols.
