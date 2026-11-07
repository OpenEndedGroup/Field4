# AI4 memory between sessions

2026-11-07. This implements Anthropic's account of agent memory,
<https://www.anthropic.com/research/components-of-agent-memory> (2026-11-06), for AI4. Stage 1 is
built. Stages 2 and 3 are planned below.

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

## Stage 2: dreaming (not built)

A consolidation turn over the notes and the transcripts, run when Field is idle (modelled on
`SemanticRetrievalService`'s idle rebuild) and from the palette.

- Watermark: the newest session-tree timestamp already consolidated, kept in the memory folder.
  Dreaming runs only when there are newer turns, and under a daily budget.
- The turn gets the memory and history tools only. Its brief:
  - re-check each note against its `sources`, and fix, downgrade to unconfirmed, or delete a note
    the transcript contradicts;
  - mine new turns for corrections the person made that no note records;
  - merge duplicates.
- Backfill `sources`. A note written during a chat cannot cite that chat, because a turn's blocks get
  addresses only once the turn is saved. In the first live test the note came out with
  `sources: []`. Dreaming can find the block in history and add it.
- One commit per pass, with a dream log, so a pass can be reviewed and reverted whole.
- From the article: aim it at what changes (traps, project notes) more than at stable person notes.
  Watch for growth that abstracts nothing (its 1.77× wiki).

## Stage 3: measure it (not built)

An `agent-eval` pair of tasks in fresh sessions, where the second depends on a correction made in
the first, each given its own memory folder (`AgentTaskRequest.memory = <dir>`). Run each pair
without memory, with memory, and with memory plus a dreaming pass between the two tasks.
`AgentEvalRequest` needs a `memory` pin to pass through, as it has `lessons`.
