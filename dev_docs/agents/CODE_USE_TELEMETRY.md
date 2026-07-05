# Code-use telemetry

Field has a first-pass code-use telemetry pipeline for answering questions like:

- which snippets were compiled, evaluated, executed, launched, or continued;
- which document, box, property, and strand produced that code;
- whether compile/evaluate succeeded and what runtime type or exception resulted;
- which compiler errors and runtime exceptions happened for a source blob;
- where future static symbol-resolution edges should be persisted.

The first version is intentionally an event store, not a perfect call graph. Kotlin snippets can rely on extension receivers, implicit receivers, `fromContext()`, lambdas, reflection, and live REPL state, so static resolution is best-effort even with K2/FIR. Hot paths only enqueue immutable records; all SQLite writes and later analysis happen off the compiler/evaluator path.

## Runtime hooks

Low-latency capture points:

- `KotlinInterface2.compile(...)` records source text, synthetic source name, diagnostics, elapsed time, and box/property context.
- `KotlinInterface2.evaluate(...)` records success/failure, runtime script class, returned value type, exceptions, diagnostics, and elapsed time.
- `execution.kt` records execute, launch, continue, and running-state lifecycle events with document/box/property/strand metadata.

These hooks call `CodeUseTelemetryService` and return immediately after enqueueing. If the queue fills, events are dropped and counted. If SQLite initialization fails, the service disables itself and Field execution continues.

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

The symbol tables are present so a K2/FIR analyzer can be added without changing the browser/API shape. They are not populated by the event-store-only pass.

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
```

AI4 box chat, inline chat, cursor/rewrite/error helpers, and the simple chat input also expose a read-only tool:

```text
CodeUseQueryRequest(kind="failures", limit=20, document="", box="")
```

Supported `kind` values are `summary`, `failures`, `snippets`, `launches`, `symbols`, `unresolved`, `line-callers`, `references-for-code`, `related-source-for-code`, `hot-symbols`, and `early-document-symbols`. Prefer `kind="failures"` when a recent compiler error or runtime exception may explain a user's request or point to an API/documentation growth point. Prefer `kind="related-source-for-code"` when an agent is interrogating Field box code and needs the relevant `src/` excerpts, observed call frequencies, and peer snippets that used the same routines. The `document` and `box` filters are case-insensitive substrings for row types that carry document/box metadata.

AI4 also registers targeted request schemas so box chat and inline chat do not need to remember string `kind` values:

```text
CodeUseCallersForSourceLineRequest(sourcePath="src/...", line=123, limit=20)
CodeUseReferencesForCodeRequest(code="...", limit=5)
CodeUseRelatedSourceForCodeRequest(code="...", limit=5, callersPerSymbol=5, sourceRadiusLines=24)
CodeUseHotSymbolsRequest(window="week", limit=20)
CodeUseEarlyDocumentSymbolsRequest(document="optional substring", earlyWindowMs=600000, limit=20)
```

These return compact telemetry rows:

- `line-callers`: recent Field snippets that call or textually mention the source symbol enclosing a `src/` line, including timestamps, box/document metadata, compile/eval status, return type, runtime class, and recent launch lifecycles.
- `references-for-code`: likely `src/` files/properties/methods referenced by a provided code block.
- `related-source-for-code`: a source bundle for each likely reference: bounded numbered source excerpt, call-site frequency, first/last seen timestamps, and other Field snippets that called the same routine.
- `hot-symbols`: observed source-symbol usage for `all`, `week`, `day`, or `month`.
- `early-document-symbols`: symbols used in the first window of a document's observed telemetry and then rarely afterward.

Rows include a `confidence` field. `resolved` means the future analyzer populated `symbol_uses`; `source-index-match` and `textual` are fallbacks from the local Kotlin source index and snippet text. Textual fallback deliberately suppresses highly ambiguous simple names so hot lists are not dominated by common helpers like `error` or `point`.

The browser has four views:

- Timeline: recent snippets and compile/eval outcomes.
- Failures: compiler diagnostics, evaluator exceptions, and lifecycle errors. These rows are intentionally retained because failed code is often the evidence that Field's APIs, stdlib, diagnostics, or documentation need to grow.
- Launches: execute/launch/continue lifecycle events and watermarks.
- Symbols: placeholder for resolved symbol-use counts once the analyzer runs.
- Unresolved: placeholder/debug view for analyzer misses.

The same page also includes an AI4 general-chat panel. It connects to Field's normal `chat_input` handler over `/ws`, so it has the same code-use tools as box chat and inline chat. The prompt starters bias the conversation toward recent failures, hot symbols, open-time helpers, and the related-source workflow.

## Next phase: analyzer

The analyzer should run against immutable source snapshots and an isolated compiler-analysis environment. It must not touch the live `K2ReplCompilationState` or any hot REPL objects. Start with one low-priority worker and write:

- imports;
- local declarations;
- call expressions;
- property accesses;
- constructor calls;
- receiver types where available;
- resolved callable FQNs and confidence;
- unresolved call text with line/column/reason.

Confidence levels should distinguish concrete K2 resolution from source-index matches and textual fallbacks. Treat unresolved rows as useful analyzer feedback, not as runtime failures.

## Smoke test

The smoke test is a `FIELD_TEST` JVM entrypoint:

```bash
uv run --project . --group backdoor python tools/run_field_tests.py --id examples.code-use.telemetry-store
```

It sets a temporary `afield.codeUse.path`, enqueues compile/eval/launch events, polls the public service API until SQLite contains them, and verifies source hashing plus recent row queries.
