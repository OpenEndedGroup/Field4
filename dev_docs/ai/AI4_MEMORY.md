# AI4 memory between sessions

2026-11-07. This implements Anthropic's account of agent memory,
<https://www.anthropic.com/research/components-of-agent-memory> (2026-11-06), for AI4. Stages 1
(notes, history, index) and 2 (dreaming) are built. Stage 3 is planned below.

## The article's layers, mapped onto Field

| Layer | In Field |
|---|---|
| Transcripts, a searchable event log | The saved AI4 session trees (`ai4_session`), read by `ChatArchive`. `ChatHistory` (`afield.ai.memory`) offers them to every AI4 turn as `HistorySearchRequest` / `HistoryReadRequest`, cited as `chat@address` |
| Memory notes, written by the model | `UserMemory`: one Markdown note per fact in `~/.afield/ai4/memory/`, written through `MemoryWriteRequest` and its siblings |
| Representations (wikis) | Lenses over chats exist already (`registerChatlogTools`). Nothing new yet |
| Injected context | The index of notes, one line each, sent as the volatile `memory.index` fragment on every turn. The contract, which says what memory is for, is the stable `memory.contract` tool advert |
| Per-document memory | Unchanged: the document's guides (`HANDOFF.md`, `DECISIONS.md`) |

## Decisions in stage 1

- **The memory belongs to the person, not the workspace.** It lives in one folder for every workspace
  and document. History is per workspace, because that is where the chats are.
- **The instructions say what memory is for, not how to keep it.** The article measured fixed rules
  ("record only failures") costing Opus 5 −0.234 of its memory gain, so `MEMORY_CONTRACT` is four
  sentences of purpose. The one structural ask is `status: verified|unconfirmed` plus `sources`. That
  is what the article's best runs did unprompted, and it gives stage 2 something to check.
- **The index is generated from the notes' front matter, not written by the model.** It cannot drift
  from the notes. It is capped at `MAX_INDEX_CHARS` (4000), listing person, trap, idiom, project and
  other notes, newest first within each kind, and ends with a count of the notes that did not fit.
- **The index is volatile.** VOLATILE fragments go in a per-turn prefix (Anthropic) or the
  `instructions` field (OpenAI), so a note written mid-chat shows on the next turn without breaking
  the prompt cache.
- **Writes are revision-checked**, as the guides are, so a note a person edited by hand is not
  overwritten. Deleting a note requires a reason.
- **The folder is its own git repository.** Every change is committed by "Field AI4", which makes it
  reviewable and revertible the way `agent_lessons.md` is. The child process's `GIT_*` variables are
  cleared and its hooks are off.
- **Automation never touches the person's notes.** `automation_log_environment()` sets
  `AFIELD_AI4_MEMORY_DIR` to `build/field-automation-logs/ai4_memory` for every managed server.
  `AgentTaskRequest.memory` defaults to none, accepts `on`, or takes a folder.
- **Switches:** `AFIELD_AI4_MEMORY=off` turns it off. `AFIELD_AI4_MEMORY_DIR` relocates it.

## Stage 2: dreaming

`afield.ai.memory.Dreaming` runs an AI4 turn with nobody present. The turn's context has only
`userMemory` and `chatHistory`, so it is offered the memory and history tools and none of the document
or box tools. The server side (`afield/app/server/ai4Memory.kt`) adds the `ai4-memory-dream`
message, sent by the palette's **Consolidate AI4 Memory** and by the backdoor
(`raw-send ai4-memory-dream '{}' --reply ai4-memory-dream`), and starts the idle watcher.

- **What it reads.** The turns saved in the workspace since its watermark. The first pass looks back
  seven days. The brief lists up to 60 turns as `chat@address` with the request, newest kept.
- **What it is told** (`Dreaming.INSTRUCTIONS`):
  - check notes against the chats, and fix, downgrade or delete;
  - record what the new turns taught that no note holds, corrections and stated preferences first;
  - merge duplicates;
  - add the sources a note written mid-chat could not cite (stage 1's gap: a turn's blocks have no
    address until the turn is saved).

  It also says that a few strong notes beat many weak ones and that changing nothing is a good
  outcome. This is the article's guard against a memory that grows without abstracting anything (its
  1.77× wiki).
- **One commit per pass.** The turn writes through a `UserMemory` that does not commit
  (`commitEachChange = false`). At the end, `commitAll` records the pass with its log,
  `dreams/<yyyy-MM-dd-HHmmss>.md`, which holds the trigger, model, turns read, created, updated and
  deleted notes, and the model's own account.
- **The watermark moves only after a pass that finished**, so a failed pass (no key, a provider error)
  reads the same turns next time. It is kept in `.dream-state.json`, which is gitignored, with the
  time of the last attempt.
- **When.** Idle (`Dreaming.idleDue`): no AI4 turn for 30 minutes, at least 3 new turns, and 6 hours
  since the last attempt. The watcher checks every 10 minutes and skips quiet mode. One pass runs at
  a time across Field.
- **Switches.** `AFIELD_AI4_DREAM=off|manual|idle` (default `idle`). Automated servers get `manual`
  from `automation_log_environment()`, so a test server never spends a dream on the person's key.
- **Not done.** The article suggests aiming dreaming at what changes and tuning its cadence by how
  often a note is used. Field does not count note reads yet, so every pass looks at every note.

## Stage 3: measure it

`afield.ai.agent.MemoryEval`, run with the backdoor's `memory-eval`. It follows the article's
CL-Bench setup: a fresh session for each question, with the memory folder the only thing carried
over.

- **Scenarios.** Each is two or three sessions, each in a fresh headless document and conversation.
  In an early session the person says something AI4 cannot otherwise know; the last session asks for
  work that depends on it without repeating it. Only the last session is scored.
  - `widget-price`: a fact, with an explicit "remember".
  - `kelvin`: a correction about units, with no request to remember.
  - `result-prefix`: a house style, stated as a correction.
  - `price-change`: a fact that changes in session two; session three needs the new value. This is
    the drift case dreaming exists for.
  - `control`: nothing carries over. A drop here would mean memory got in the way.
- **Arms.**
  - `none`: memory off.
  - `memory`: one fresh folder per scenario, shared by its sessions.
  - `dream`: as `memory`, plus a `Dreaming.run` after every session but the last.
- **Transcripts for the dream arm.** A headless task saves no chat, so the runner records each
  session as one (`MemoryEval.recordSession`): every request, the tool calls each turn made, and each
  turn's reply. This is why `AgentTaskResult` gained `replies` and `turnToolCalls`. The record is
  written by the harness from the task's result, not by the chat panel, and is thinner: no images,
  and arguments are cut at 300 characters.
- **Report.** Last sessions passed per arm and per scenario, plus every session's verdict, its
  memory-tool calls, and the notes in the folder after it. It also lists every dream pass. Everything
  stays under the run's `--dir`.

### First live run

(pending)
