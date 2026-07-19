# Document-history testing

The automated browser contract is the `history-panel` case in
`tools/run_ui_regressions.py`; the overall test map is
`dev_docs/testing/TESTING_STRATEGY.md`. Current labels and user workflows belong in
`public/docs/document_history.md`, not in this checklist.

Run the focused regression on an isolated port:

```bash
uv run --project . --group backdoor python tools/run_ui_regressions.py \
  --id history-panel --port 9680 --timeout 45
```

For a manual destructive test, use a disposable workspace and
`./gradlew runJvm --args="--workspace /tmp/field-history-test"`. Exercise these invariants rather than matching
incidental colors or prose:

1. Repeated changed saves create ordered `backups/metadata_backup_*.edn` entries; unchanged saves
   do not invent content versions.
2. `/history?file=<document>` lists backups followed by the current `metadata.edn` and remains usable
   while lazy statistics arrive.
3. A full restore first preserves the state being left through the normal save/backup path, restores
   target properties, tombstones boxes absent from the target, and appends a transition to
   `versions.edn`.
4. Cherry-pick changes only the selected boxes.
5. Naming, search, line history, diffs, screenshots, reconnect, keyboard focus, and invalid requests
   fail without corrupting the document.

Inspect the saved EDN and server log when an invariant fails. The exact restore implementation is
documented on `HistoryManager.restoreVersion` in
`src/jvmMain/kotlin/afield/history.kt`.
