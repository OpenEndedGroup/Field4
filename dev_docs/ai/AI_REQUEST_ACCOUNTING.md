# AI4 request accounting

Every updated Field JVM writes its AI4 model requests to
`~/.afield/logs/ai_usage/<UTC-day>-<pid>-<instance-id>.jsonl`: the Responses API
(OpenAI and OpenRouter, `OpenAiIdeCodexBridge`) and the Messages API (Anthropic,
`AnthropicBridge`). All documents, chat/tool follow-ups, forced summaries, and AI4
completions using these bridges are covered. No running server is needed to inspect
the records:

```sh
python3 tools/ai_usage.py --hours 24
python3 tools/ai_usage.py --hours 72 --group process
python3 tools/ai_usage.py --hours 24 --json
```

The default report excludes localhost protocol-test endpoints. `--include-local`
includes them. `--root` reads an alternate directory. Writers can use
`AFIELD_AI_USAGE_LOG_PATH` or `-Dafield.ai.usageLog.path=...`; all processes must use
the same directory for a complete machine-wide view.

Each event identifies the JVM instance and PID, process start time, checkout,
configured port, provider, endpoint, model, document/file key, conversation, turn,
phase (`initial`, `followup`, `forced_final`), logical request, and attempt number.
Both bridges write the same attribution fields. The file key is retained when no
document path is available (for example autocomplete and a stand-alone agent
evaluation).

- `attempt_started`: persisted before invoking the provider; an unfinished record
  is evidence of an interrupted/unobserved outcome, not proof the process is alive.
- `response_usage`: provider-reported input, cached input, cache write (when
  available), output, reasoning and total tokens; includes failed/incomplete
  Responses objects that supply usage.
- `attempt_finished`: success/failure, duration, error class, HTTP status,
  structured model error code (on Anthropic, the error type such as
  `rate_limit_error`), rate-limit figures parsed from the provider error, and an
  allowlist of diagnostic HTTP headers available on HTTP exceptions (including
  Anthropic's `request-id` and `anthropic-ratelimit-*`).
- `retry_wait`: retry number and intended delay; retries share a logical request ID.

Cached input is a subset of input; reasoning is a subset of output. Do not add
these subsets again. Missing usage is unknown, never zero. The inspector reports
unknown-usage attempts separately and deduplicates usage by provider/endpoint/response
ID. Usage events are not a billing estimate or an exact reconstruction of the
provider's token bucket. Provider headers on successful responses are not currently
captured; error headers are recorded when supplied by the SDK exception.

Anthropic's `input_tokens` leaves out the prompt tokens read from or written to the
cache, so the Anthropic record adds them back: `inputTokens` is `input_tokens +
cache_read_input_tokens + cache_creation_input_tokens`, `cachedInputTokens` is the
cache read and `cacheWriteTokens` the cache creation, and the uncached remainder is
`inputTokens - cachedInputTokens - cacheWriteTokens`. Anthropic reports no total, so
`totalTokens` is input plus output; it reports no reasoning count either (thinking is
inside output), so `reasoningTokens` is null. Its `stopReason` (`end_turn`,
`tool_use`, `max_tokens`, ...) takes the place of the Responses `responseStatus`.

An attempt is one call into the provider SDK. The OpenAI bridge turns the SDK's own
retries off, so there an attempt is one HTTP request and every rate limit has its
record. The Anthropic client keeps the SDK's retries (two, for 408, 409, 429 and 5xx,
honouring `retry-after`) inside the call, so an Anthropic attempt can be several HTTP
requests and a limit the SDK recovered from leaves only a longer `elapsedMs`. The
bridge's own rate-limit retries are recorded as on OpenAI.

The ledger contains no prompts, tool arguments/results, images, API keys,
Authorization headers or raw exception text. Endpoint user-info/query/fragment are
removed. It is independent of the optional verbose tool-I/O logger. Files are
append-only and separated by process/day to avoid cross-JVM write interleaving.
A write failure emits a diagnostic and does not retry an already executed request.

Coverage starts after launching the updated binary; Anthropic requests are recorded
only by binaries built after 2026-10-09. Older binaries, other apps,
other machines, and custom user code calling an SDK directly are not instrumented
by this bridge. Historical `ai4_tool_io` logs are still useful but lack consistent
process attribution, attempt events and usage coverage; they cannot prove the
absence of other activity.

Verification: local HTTP fixtures exercise streaming and nonstreaming HTTP 429
recovery through the real SDK/bridge, asserting tokens, attempt identity, diagnostic
headers and exclusion of private content. `AnthropicBridgeContractTest` drives the
Anthropic bridge through a fake client across a rate-limited attempt and its retry,
the follow-up and forced-final phases, and a failed request, asserting attribution,
cache-adjusted input, null for unreported figures, error status and headers, and the
same exclusions. Python tests aggregate multiple process identities, duplicate
responses, unknown outcomes, and partially written records. No paid model call is
required for these tests.

## Keeping ordinary tool turns small

Build orchestration returns a projection of an attempt, not its frozen source inputs.
Start, inspect-by-job, wait, and cancel retain structured problems, qualification
facts, result notes, artifact paths/hashes, the attempt directory and live currency.
The complete original input remains in the attempt's `input.json`. A current
proposal may still have failing qualification facts; neither `success` (tool
execution) nor `SUCCEEDED` (worker completion) certifies those facts.

Use `StartStudyBuildRequest(waitSeconds=600)` for ordinary sequential work. It starts
exactly one attempt and streams progress while waiting, then returns its outcome and
currency in the same tool result. The default zero preserves nonblocking callers;
use it when independent work is available. If the observation times out, continue
with `WaitStudyBuildRequest` on the same job; its default wait is now 600 seconds.
Edits can invalidate a completed attempt, and the returned `currency.isCurrent`
reflects the live stage when observed. Downstream starts still enforce fingerprints.

`AttachmentRequest` defaults to 6,000 UTF-16 code units rather than 24,000. A literal,
case-sensitive `find` searches from `offset` and returns bounded context around the
first match, with `matchOffset` and `searchMatched`. A miss returns no source text
and explicitly reports `searchMatched=false`. Continue ordinary reads with
`nextOffset` and `expectedSourceHash`; the hash always covers the whole source.
Larger explicit reads remain available up to 65,536 code units. Excerpts are never
valid whole-file replacements.

Regression coverage exercises one-call build completion, retained failure/qualification
information, stale results after edits, progress during waits, no frozen-input leak,
source search, Unicode-safe pagination, and refusal to mix source versions. These
changes reduce bytes and avoid orchestration round trips; actual token savings must
be measured from the usage ledger during representative work.
