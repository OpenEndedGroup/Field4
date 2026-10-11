# History docs audit (2026-10-11)

What the code says about document history, as checked against the user pages
`public/docs/document_history.md`, `history_lens.md`, `history_overview.md` and `ai_tool_use_history.md`.

- `metadata.edn` backups are never pruned. `SavingContext` saves the document through `checkDifference(metadataPath, ednText)`, and that passes `maxBackups = null`. Sidecars (`toFile`) keep 10 backups.
- A version's index is its position in the sorted `backups/metadata_backup_*.edn` list. `versions.edn` names and transitions are keyed by that index, so deleting a backup file renumbers every later version and moves its name.
- Only a full restore (`restoreVersion`) records a transition. A cherry-pick records none.
- `AnthropicBridge` writes the ai4_tool_io JSONL through `AiToolIoLogger`, the same as the OpenAI bridge.

Two bugs fixed, both covered by `HistoryLineTraceTest`:
1. The History Lens could not show a line's oldest value. `blameLineHistory` lists only the versions that *changed* the line, and the value from before the earliest change was in none of them. `blameLineTrace` now also returns the version before that change (`original`), and `selectionTraceVersions` includes it. The trace stops at the version that inserted the line.
2. The lens header read "0 boxes" for every version. `historyVersionDtos` reported lazily-filled `boxIds` for versions requested by index. It now loads them.
