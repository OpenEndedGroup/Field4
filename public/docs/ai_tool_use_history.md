# AI tool-use history

Field records structured traces of its AI bridge so recent tool failures and
unproductive call patterns can be reviewed.

## What is logged

Each `OpenAiIdeCodexBridge.send(...)` call normally creates one JSONL file under
`~/.afield/logs/ai4_tool_io/`. Records include session metadata and the prompt,
response shape, tool calls, tool results, tool exceptions, and tool-round
limits. Large string fields are clipped rather than written without bound.

Logging is enabled by default. Set `AFIELD_AI4_TOOL_IO_LOG=0` to disable it,
`AFIELD_AI4_TOOL_IO_LOG_PATH` to choose another directory, or
`AFIELD_AI4_TOOL_IO_LOG_MAX` to change the per-string clipping limit.

These files can contain code, prompts, tool arguments, and tool results. Treat
the directory as sensitive local diagnostic data, not as an anonymized event
log.

## Review and proposal runs

The **Agent tool use** card on [`/welcome`](history_overview.md) offers:

- **Review logs**: runs `improveAiToolUseDryRun` against at most the 12 most
  recent logs. Source edits occur only in an in-memory overlay. The task writes
  `build/ai-tool-use-improvement-report.md` and, when edits are proposed,
  `build/ai-tool-use-improvement-dry-run.patch`.
- **Generate proposal**: creates one or more generated git worktrees and runs
  `improveAiToolUse` separately in each. The task's edits are restricted to
  `src/`; successful proposal changes are committed in their generated
  worktree, leaving the current checkout untouched.
- **Mark reviewed**: moves the 12 most recent JSONL files into a timestamped
  `archived-reviewed` directory. It does not delete them.

![The AI tool-use improvement page](assets/ai-tool-use.png)

The detail page at `/ai-tool-use-improvement.html` shows log counts and size,
run state, report and patch paths, task output, and generated-worktree controls.
Status changes are broadcast over `/welcome-events`.

The review CLI summarizes tool-call counts, failed results, exceptions, and
max-round events before asking the configured model for a small source-level
improvement grounded in those logs. Use
`AFIELD_TOOL_USE_IMPROVEMENT_MODEL` to override its model.

## Code-use telemetry is separate

The **Code-use telemetry** card opens `/code-use`. This is a local SQLite record
of Field execution activity: snippets, compile/evaluation outcomes, launch
lifecycle events, symbol evidence, and related source. It is not part of the AI
tool-I/O JSONL log and has its own storage and retention behavior.

![The code-use telemetry browser](assets/code-use.png)

See [Semantic retrieval](semantic_index.md) for how local workspace and source
indexes differ from both histories, and [AI documentation polish](ai_doc_polish.md)
for the separate generated-completion-doc workflow.
