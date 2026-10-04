# AI4 OpenAI efficiency and request recovery

Verified 2026-10-02 against the direct OpenAI Responses path. These changes extend
AI4's existing bridge, chat and document-authored tool registry.

## Context and tools

Direct OpenAI GPT-6 requests enable server-side compaction with a default
96,000-token threshold. This is Field's adjustable starting policy, not an OpenAI
recommendation for every workload. `previous_response_id` carries the chain;
Field sends only new input and does not manually prune the provider's history.
Saved Field chat and durable document guides remain available separately.

General chat sends the user request once. Current source and generated request
instructions are appended again only when changed. Deduplication state advances
after the initial model request succeeds, so a failed request does not lose new
context. A new provider thread still resumes from saved chat.

Tool definitions have stable ordering. GPT-6 uses hosted tool search and defers
most function parameter schemas; a small discovery/memory set stays immediately
available. The same document tools and execution handlers run after discovery.
Tool search is added only when an available deferred tool exists; empty and immediate-only registries omit it. Other provider/model paths keep eager definitions. A forced final answer retains
the tool catalogue and sets `tool_choice=none`, preserving the cached prefix.

Once hosted search returns a function, Field remembers its name in the conversation
state and includes that discovered subset eagerly in later requests. Unused tools
stay deferred; the current registry still controls scope and definitions. This is
a defensive measure following a live failure where a compaction response searched
for already-loaded tools three times, returned empty lists, and abandoned a final
write despite both tools remaining in the request catalogue. The trace establishes
that sequence, not the provider's internal cause. Promoting a discovered schema
changes the tool prefix once and can reduce cache reuse at that boundary. It avoids
depending on rediscovery of a schema the provider regards as already loaded.
Search arguments and returned tool names are now recorded even when the same
response also contains a final message.

Geometry inspection returns compact hierarchy/identity data by default, retaining
the full restorable state and actual picture. Full provenance remains explicit via
`includeMetadata`; `camera` and `target` change framing without inventing a partial
restore state. This removes repeated ancestor leaf lists from routine inspection.

OpenAI's implicit caching already applies. Cached tokens still count toward TPM;
cache hits do not solve excessive context size. Per-response `model_usage` events
record provider input, cache-read, cache-write, output and reasoning token counts,
plus compaction-item counts. Token counts are numbers, not character estimates.

Compaction does not make indiscriminate large user messages cheap. In an isolated
live test, 32,134 tokens dominated by archive text in a user message still yielded
32,241 input tokens on the next request. With the same archive represented as tool
output, compaction reduced the next request from 32,207 to 234 input tokens while
retaining the marker and clearance constraint. These are fixture results, not a
promise of equivalent savings or task quality in every conversation. Large saved
transcripts resumed as one user context, broad source reads, and repeated tool
results still deserve attention.

## Request recovery

One visible loop owns model-request retries; SDK retries are disabled on that
path. A temporary rate limit gets exponential backoff with positive jitter, a
10-minute elapsed budget, and a 120-retry ceiling. `Retry-After` is a minimum, even
when longer than the local backoff cap. The chat displays its automatic countdown.

Brief connection failures and HTTP 408, 409 and 5xx retain two retries inside that
same loop. Quota/billing failures and requests larger than the whole TPM limit
are not retried as rate limits. Cancellation propagates. A temporary rate-limit event after text has been
delivered now has an explicit interruption boundary and visible countdown before
retrying the same unfinished model request. This requires a progress callback;
callers without one still receive a retained-partial-answer error. Other partial
stream failures remain terminal. Structured
failed Responses are checked before advancing the saved response ID. Retries
repeat a model request, never a completed tool invocation or the entire turn.

Independent Field instances and other applications still share the account's
quota. This recovery loop is not an account-wide scheduler.

## Configuration

| Setting | Default / meaning |
| --- | --- |
| `AFIELD_AI_COMPACT_THRESHOLD` / `afield.ai.compactThreshold` | 96,000; supported range 16,000–400,000 |
| `AFIELD_AI_DEFER_TOOLS` / `afield.ai.deferTools` | `true` for direct OpenAI GPT-6 |
| `AFIELD_AI_RATE_LIMIT_RETRIES` / `afield.ai.rateLimitRetries` | 120 |
| `AFIELD_AI_RATE_LIMIT_BUDGET_MS` / `afield.ai.rateLimitBudgetMs` | 600,000 |
| `AFIELD_AI_RATE_LIMIT_MAX_DELAY_MS` / `afield.ai.rateLimitMaxDelayMs` | 30,000 backoff cap; does not shorten server delays |

The machine-local `~/.afield/ai-models.properties` accepts
`openai.reasoningEffort=low`, `medium` or `high`. An absent/invalid value retains
the previous medium default; explicit per-call effort still wins. This setting
does not choose another model. Existing bridge instances need to be recreated to
pick up a file edit. General-chat output allowances include reasoning and tool
arguments as well as prose; lower them based on measured usage and incomplete
responses, not on the length of the final displayed answer alone.

## Verification and remaining work

49 scoped JVM/protocol/agent checks passed, including real SDK streaming HTTP 429
and 503 responses, Retry-After, more than five transient limits, cancellation,
partial-output refusal, context deduplication, tool-call identity, and retained
final-answer tool definitions. An actual Astra low run discovered and used
document tools, completed an isolated tool-reuse check, and saved durable notes.
The live compaction fixture exercised the installed SDK and provider.

The subsequent discovery-retention change passed 27 focused bridge, geometry-review and
wire-name tests. The standard viewer passed 25 live checks, including closest-surface picking,
saved evidence, stale-note protection and compact inspection with explicit camera arguments.
A separate live Astra fixture discovered two tools, crossed a real compaction boundary, and
called the required previously discovered tool afterward. This tests continuation, not the
quality of a long conversation's recall. Evidence is retained under
`build/ai4-live-discovery-compaction` and `build/ai4-geometry-review-compact-20261002`.

Long derived builds now have `WaitStudyBuildRequest`: an observation lasting at most 30 seconds,
returning compact status rather than repeated frozen inputs. A timeout leaves the same job
running. This avoids spending model rounds on rapid polling; success still requires checking
the stage's current result and its domain-specific evidence.

`AgentTaskRequest.maxToolRounds` also reaches the ordinary AI4 panel through `AiAssist` and
`IdeChatHelpers`, clamped to 1–64. Previously a task could request 64 while the panel silently
used 24. The panel now retains actual round count and cap status separately from its prose,
and an exhausted task reports `budget-exhausted` even when a trivial completion check passed.
The default remains 24. The final focused panel/bridge/wait test pass covered 23 cases.

Further opportunities: namespaced tool discovery, append-only catalogue updates,
compact tool-result envelopes, role-preserving saved-chat resumption, and broader
quality evaluations across compaction. The small live fixture establishes
continuation and a measured reduction for tool output; it does not certify
long-task recall or geometry correctness.

### A round limit belongs to one turn

A real long tool-editing conversation reached its limit, then the next user turn
incorrectly answered that tools were still unavailable. The forced-final request
had appended a developer **input item** saying to stop tools. That item remained
in the response chain. This was an integration failure, not a new rate limit.

The forced-final instruction now uses the request-scoped `instructions` field,
with `tool_choice=none` only on that request. Normal initial and follow-up requests
use `auto` and explicitly scope earlier budget stops to their original turn.
Current permissions and read-only restrictions still apply. The stateless path
also retains aborted-call outputs and the final answer, so its next request does
not contain dangling function calls.

Local SDK protocol tests exercise a capped turn, serialize/restore its state, and
execute a tool on the next turn for both direct OpenAI and OpenRouter. They verify
that the aborted call never runs and the stop instruction is absent from retained
input items. The combined bridge and build-state test run passed 26 tests. The
private workflow retains separate live continuation evidence; protocol fixtures
alone do not establish model behavior.

The repaired runtime was then exercised by the same native AI4 conversation:
a fresh turn made 40 real tool rounds after the earlier stop, and another turn
completed tool repair, evidence publication and source-specific curation. This
establishes live continuation for that workflow; it does not remove per-turn
budgets or prove arbitrary long-conversation quality. A separate slow tool was
subsequently found doing quadratic mesh bookkeeping on the UI loop. Model wait
time and local tool execution time need distinct diagnostics.

This follows the Responses API's distinction between saved input items and
[request-scoped instructions](https://developers.openai.com/api/docs/guides/migrate-to-responses#3-update-multi-turn-conversations),
which are not inherited through `previous_response_id`.

## Official guidance

- [Prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching)
- [Compaction](https://developers.openai.com/api/docs/guides/compaction)
- [Rate limits](https://developers.openai.com/api/docs/guides/rate-limits)
- [Tool search](https://developers.openai.com/api/docs/guides/tools-tool-search)
- [Conversation state](https://developers.openai.com/api/docs/guides/conversation-state)
- [Latest model guidance](https://developers.openai.com/api/docs/guides/latest-model)
- [Cost optimization](https://developers.openai.com/api/docs/guides/cost-optimization)

## Streaming event recovery — 2026-10-03

Explicit `error`, `response.failed` and `response.incomplete` events are handled
before the SDK accumulator. Otherwise its missing-completed-response exception
hides the provider reason. Incomplete responses never dispatch function calls or
advance the saved response cursor. Temporary limits use the existing bounded
retry loop; a fresh accumulator drops unfinished calls and a durable progress
boundary distinguishes the retained partial text from the next answer. Completed
tool outputs and the previous response ID are identical on the retry.

25 focused tests pass against the real SDK with local protocol fixtures, including
a rate-limit event following partial text and an unfinished tool call, explicit
failed/incomplete events, quota rejection, cancellation and transcript ordering.
This establishes source behavior, not deployment to an already-running JVM.
A live traced house continuation recovered several ordinary pre-output TPM limits;
that trace does not establish the cause of every earlier partial-stream failure.
The change follows the explicit streaming event contract in the
[OpenAI streaming guide](https://developers.openai.com/api/docs/guides/streaming-responses).

The streaming recovery change was loaded into the live Field runtime after all
builds and chat were idle; the authored document and checked results were re-read
unchanged. A further feedback change keeps generated reasoning summaries out of
execution progress. Actual tool/build events and user-facing streamed commentary
remain. Its regression passed with the bridge suite (26 tests total); that last
presentation change awaits the next safe runtime reload.
