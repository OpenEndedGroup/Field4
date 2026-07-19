# Document history: code ownership

User behavior belongs in `public/docs/document_history.md`. The backend contract is colocated with
`HistoryManager` and `installHistoryHandlers()` in `src/jvmMain/kotlin/afield/history.kt`.

Ownership map:

- `src/jvmMain/kotlin/afield/history.kt` — version discovery/cache, restore and cherry-pick,
  diffs/search/line history, metrics, names, screenshots, and all history RPC handlers.
- `src/jvmMain/kotlin/afield/saving.kt` — backup rotation used by ordinary saves and restores.
- `src/commonMain/kotlin/rpc.kt` — serialized request and response types.
- `src/commonMain/resources/history.html` — history UI and client-side message handling.
- `src/jvmMain/kotlin/afield/Server.kt` — serves `/history` and `/history-screenshot`.
- `tools/run_ui_regressions.py` — the `history-panel` browser regression.

On disk, ordered `backups/metadata_backup_*.edn` files plus the live `metadata.edn` form the
version sequence. `versions.edn` stores restore transitions and user-assigned version names. These
formats are implementation-owned and should be changed together with the parser, RPC types, public
documentation, and regression.

See `dev_docs/HISTORY_TESTING_GUIDE.md` for the focused verification command.
