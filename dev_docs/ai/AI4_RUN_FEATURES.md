# AI4 run controls, durable events and context inspection

This extends the existing AI4 harness, not a second agent runtime. Public instructions and three
independently runnable box examples are in `public/docs/aiFeatures.md` (the `ai4-demo` markers).
`tools/capture_ai4_run_features.py` extracts and executes those exact examples with a real configured
model in its own temporary workspace and independent browser. It never attaches to the person's
Electron tab. `--capture` writes images only after the runtime assertions for that example pass.

## Controls

`AiAssist.delivery` defaults to `steer` for compatibility; `follow-up` keeps a pending message out of
`AiTurnInbox.drainForModel`. The existing end-of-turn dispatcher starts remaining messages as the
next turn. A burger floats inside the composer's lower-right corner and opens Correct this run,
Do next, Stop, Recall queued, Context and Open Garden. It shares the visualizers' popup styling in
`arrayControls.css`; browser Popover provides top-layer placement and dismissal, not native menu
rendering. Return sends a correction; Alt–Return sends a follow-up. Garden uses Back to chat in its
existing toolbar instead of reserving a permanent row in linear chat. Recall and Stop return full `AiAssist`
drafts, including attachments, not the rendered queue labels. Stop closes the old inbox, requests
coroutine cancellation and makes dispatch refuse further tool calls. It is cooperative: a blocking
provider request or tool may finish before cancellation can take effect. Existing edits and launched
boxes are not undone. Queues are intentionally in-memory; they are not restart-safe.

The Garden keeps its frozen-ancestry queue and does not accept linear-chat steering. Garden turns
use the same inbox and registry event instrumentation; these controls do not redefine its dispatch.

## Durable events

Every box-panel run owns an `AiRunJournal`, including Garden runs. It appends versioned JSONL under
`<document>/ai4_events/<sha256(box-id)>.jsonl`, with a run ID, timestamp and event type. Each line is
flushed and synced. Arguments, serializable results, prompt assemblies, incoming images and local
history-reduction snapshots are gzip-compressed, content-addressed blobs. Snapshot publication is
atomic. A serialization failure records an unknown verdict rather than changing a tool's result.
These files can contain sensitive conversation content and are not pruned automatically.

Instrumentation surrounds central `ToolRegistry.dispatch`, so withheld/refused tools and nested
program calls are visible too. Started, completed, thrown failure and structured verdict are
separate events. Parent-call correlation follows synchronous nesting on the dispatch thread; run IDs
still correlate calls that cross threads. A standalone headless task without a panel inbox does not
yet get this per-box journal. There is no replay API and no automatic world restoration.

The existing session tree, transcript and Garden remain authoritative for their existing purposes.
This is the first typed event layer, not a migration that reconstructs all three from one log.

## Context

`/context` and Context read the recent event tail and the last available fragment report, including
tool schema names and box-tool source revisions. Reports are recorded before provider work and again
with final usage/tool metadata on successful completion. `/context <sha256>` opens a bounded,
read-only snapshot; it never sends that text to the model. The full local blob is retained.

The OpenAI Responses bridge records before/after snapshots for stateless turn-boundary settling and
context-window fitting. Provider-managed compaction is recorded as opaque: Field cannot claim to
recover hidden provider state. Anthropic records its prepared input and placement, not a fabricated
local compaction. This does not add model-generated summarization, manual compact-and-restore or
cross-provider context replay.

## Verification

```
./gradlew fastTest -PfastTestRoots=src/jvmFastTest --tests 'afield.ai.conversation.AiRunControlsTest'
./gradlew jvmTest --tests 'afield.ai.conversation.AiTurnInboxTest' --tests 'afield.ai.conversation.ContextPlacementTest'
uv run python tools/capture_ai4_run_features.py --demo controls --demo events --capture
# With a configured stateless Responses provider:
uv run python tools/capture_ai4_run_features.py --demo context --capture
```

The live runner spends model usage only after its owned server starts. It refuses reuse of an
existing server. If a provider or real capture fails, keep that failure visible rather than
substituting a scripted transcript or synthesized figure.
