# Code-use telemetry

Field records compile, evaluation, execution, launch, continue, and running-state
events so failures and observed source usage remain queryable. The authoritative
queue, persistence, analyzer, and fallback contract is in the KDoc on
`CodeUseTelemetryService`.

## Code map

- `CodeUseTelemetryService.kt`: event queues, SQLite schema/queries, source-index
  analysis, sidecar client, and related-source enrichment.
- `KotlinInterface2.kt`: compile/evaluation capture.
- `execution.kt`: execution and launch lifecycle capture.
- `ai/core/Tools.kt`, `OpenAiIdeCodexBridge.kt`, and `Ai4Installer.kt`: read-only
  AI query schemas and their adapters.
- `src/commonMain/resources/code-use.html`: browser.
- `tools/code-use-semantic-sidecar/`: optional isolated K2 analyzer.

The database defaults to:

```text
~/.afield/code-use/field-code-use.sqlite
```

Override it with `-Dafield.codeUse.path=...` or `AFIELD_CODE_USE_PATH`.
SQLite uses WAL mode and stores full source once by SHA-256. Context, snippet,
compile, evaluation, launch, symbol-use, and unresolved-use rows refer to that
content-addressed source.

Capture and analysis are best effort. Full event or analyzer queues increment
drop counters; SQLite or analyzer failure must not fail compilation or execution.

## Browser and HTTP API

Open `/code-use` (also served as `/code-use.html`). The page has Timeline,
Failures, Launches, Symbols, and Unresolved views plus the normal AI chat panel.

```text
GET  /api/code-use/summary
GET  /api/code-use/snippets?limit=100
GET  /api/code-use/launches?limit=100
GET  /api/code-use/failures?limit=100
GET  /api/code-use/symbols?limit=100
GET  /api/code-use/unresolved?limit=100
POST /api/code-use/analyze
```

The POST body is `CodeUseSemanticAnalyzeRequest`. It analyzes the supplied code;
it is not a request to record a new execution event. Results contain resolved
and unresolved uses, ranked source references, and related-source excerpts with
local usage evidence.

## Evidence levels

- `k2-resolved`: returned by the optional semantic sidecar.
- `source-index-unique`: one unqualified local-source match.
- `source-index-qualified`: one receiver-qualified local-source match.
- `textual`: string/token fallback, not semantic resolution.

Ambiguous source-index candidates are recorded as unresolved rather than chosen
arbitrarily. Callers and documentation must preserve these confidence labels.

## AI tools

`CodeUseQueryRequest` supports `summary`, `failures`, `snippets`, `launches`,
`symbols`, `unresolved`, `line-callers`, `references-for-code`,
`related-source-for-code`, `semantic-analyze`, `hot-symbols`, and
`early-document-symbols`. Targeted request classes in `ai/core/Tools.kt` wrap the
same queries so agents do not need stringly typed kinds.

Use semantic analysis for a concrete Kotlin snippet, failures for recent
compiler/runtime evidence, line callers for observed users of a source line,
and semantic retrieval—not this event store—for natural-language discovery over
the broader workspace and documentation corpora.

## K2 sidecar

Configure the sidecar client with:

```text
AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL=http://127.0.0.1:17841
AFIELD_CODE_USE_SEMANTIC_SIDECAR_TIMEOUT_MS=2500
```

The equivalent JVM properties are
`afield.codeUse.semanticSidecar.url` and
`afield.codeUse.semanticSidecar.timeoutMs`. `field.sh` and `field.ps1` normally
manage a sidecar for the active checkout; set
`AFIELD_CODE_USE_SEMANTIC_SIDECAR=off` to disable launcher-managed startup.

Field posts to the configured URL (adding `/analyze` unless already present) and
includes the database/workspace/source-root context. Sidecar timeout, HTTP
failure, malformed JSON, or process failure degrades to
`source-index-fallback` and must not block live code.

## Verification

```bash
uv run --project . --group backdoor python tools/run_field_tests.py --id examples.code-use.telemetry-store
```

The smoke stores events in a temporary database and checks hashing, retained
failures, analyzer rows, line callers, related source, hot symbols, and early
document symbols.
