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

### First live run (2026-11-07, openai gpt-6.1-sol, one repeat)

All 33 sessions and 6 dream passes took 15 minutes and 3.27M input tokens. Most of those tokens are
AI4's instructions and tool schemas, resent every round.

| scenario | none | memory | dream |
|---|---|---|---|
| widget-price | fail | pass | pass |
| kelvin | pass* | pass | pass |
| result-prefix | fail | pass | pass |
| price-change | fail | pass | pass |
| control | fail† | fail† | fail† |
| **last sessions passed** | **1/5** | **4/5** | **4/5** |

- **Without memory, AI4 still tried to remember.** It saved the widget price and the kelvin
  convention to the document's `DECISIONS.md`, a guide that a fresh document never sees. The next
  session then printed "cost of 12 widgets: unknown (unit price not specified)".
- **With memory, every scenario's knowledge carried.** Each first session wrote exactly one note,
  with no request to remember in `kelvin` or `result-prefix`, and each last session read it
  (`MemoryReadRequest`) before answering. In `price-change`, session two replaced the note's 4.50
  with 5.00 on its own, so session three used 5.00.
- **Dreaming added nothing measurable here**, because in-session memory already handled the one
  drift case. Each pass updated its scenario's note to add sources, and one removed
  document-specific detail ("removed the document-specific `Quote` test result"). None created or
  deleted a note. A scenario where the in-session note goes wrong, and only a later re-read of the
  transcript catches it, would be needed to show what dreaming adds.
- \* The `kelvin` check was too lenient. The no-memory session printed
  "273.15 K (0 °C, 32 °F)", hedging into a pass. The check now also requires that the box's code
  contain no °C or °F.
- † `control` failed in every arm for the same reason, which had nothing to do with memory. The box
  printed 1, 4, 9, 16 and 25 on five lines, and the output check saw only the last three. The
  scenario now asks for one printed line, the sum of the squares (55).

### Second run, the two fixed scenarios (same day, same model)

12 sessions and 2 dream passes took 3.8 minutes and 0.89M input tokens.

| scenario | none | memory | dream |
|---|---|---|---|
| kelvin | fail | pass | pass |
| control | pass | pass | pass |

With the tighter check, the no-memory `kelvin` session failed for the reason the check now
catches: it printed "0 °C (32 °F, 273.15 K)". With memory it printed kelvin only. `control` passed
in every arm, so the control-scenario notes ("short replies") did not get in the way.

Taking the three unchanged scenarios from the first run and the two fixed ones from this run, last
sessions passed **none 1/5 (the control only), memory 5/5, dream 5/5**. That is one repeat on one
model. `--repeats` and a second provider are the next things to run before quoting these numbers
anywhere. A scenario built to show what dreaming adds was added next.

### `missed-correction`: what only re-reading the transcript can fix

The memory arms start with a note that was true once, seeded before the first session: "Gizmos cost
3.00 each". Session one says "Gizmos went up to 3.60 each" in a session with no memory tools, in
every arm, as a chat where memory was unavailable. Nothing writes the change down. Session two asks
for the cost of 10 gizmos (36). Two harness changes came with it:

- **The history tools now search the arm's recorded sessions** (`AgentTaskRequest.history`), as they
  would search a workspace's earlier chats. Before this, they searched the session's empty scratch
  workspace, so the earlier runs' sessions could not look back.
- **Every session is sealed** (`MemoryEval.WITHHELD`): the corpus's sealed tools plus the repository
  tools. Each scored session is also checked for a leak (`MemoryEval.leak`), and a pass becomes
  `leaked` when a tool result other than memory or history held an earlier session's words. The
  first run of this scenario needed both. The no-memory arm passed both repeats by running
  `RepoSearchRequest` for "gizmos" and reading the scenario's own text in `MemoryEval.kt`. In the
  earlier runs, the only scored session that used a search tool was no-memory `widget-price`, which
  failed anyway, so those results stand.

Sealed, two repeats (openai gpt-6.1-sol, 12 sessions, 2 dream passes, 0.78M input tokens):

| arm | passed | last session: rounds, input tokens | how it got 3.60 |
|---|---|---|---|
| none | 0/2 | 10–11, ~150K | it didn't: "unit price not provided" |
| memory | 2/2 | 6, ~97K | read the stale note, then searched and read the history, found the change, and fixed the note itself |
| dream | 2/2 | 3, ~54K | the dream pass had already fixed the note; one `MemoryReadRequest` |

So with history searchable, a session that doubts a note can do the dream's work itself, and passes.
What dreaming changed was where that work happens: the correction moved out of the person's session,
which took half the rounds and a little over half the tokens. The dream pass's own tokens are not
recorded yet, so this does not show that the total was lower. One unscored no-memory session failed
when the provider stayed unavailable for ten minutes. That did not change the scored result, because
that arm had no record of the price either way.

