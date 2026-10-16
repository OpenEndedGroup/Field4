# Rename the History Lens to Code History

- **Handoff:** `57bd2f66-e98c-46d4-ab4b-25dcfe103a31` (project)
- **Filed from:** https://openendedgroup.com/field4/history_lens/; also affects keybindings.md, history_overview.md, document_history.md
- **Filed by:** human, 2026-10-16 09:11
- **Close with:** `python tools/docs_notes.py handoff-close 57bd2f66 "what happened"`

Decided 2026-10-16 (Marc, handoff f4ff38a4; see `dev_docs/handoffs/2026-10-16-decide-whether-to-rename-history-or-the-history-lens.md`): rename the History Lens to **Code History**. The editor's `history()` visualizer keeps its name.

Work:

1. UI: rename the action/palette labels in `src/jsMain/kotlin/afield/app/editor/BootstrapMonacoActions.kt` and any panel titles or strings in `src/jvmMain/kotlin/afield/document/history/history.kt`. Keep the `alt+shift+h` shortcut and the action ids (or alias the old ids) so keybindings.yaml overrides keep working. Update `HistoryLineTraceTest.kt` if it asserts the label.
2. Docs: retitle `history_lens.md` to "Code History", including its H1 and nav title. Keep the file name/URL unless renaming it is cheap (renaming means `generateCodebaseReference` plus the anchor check). Replace "History Lens" in document_history.md, funFeatures.md, staging.md, keybindings.md and history_overview.md.
3. Shrink the section "A different "history" in the editor" on that page to one cross-link sentence to the `history()` visualizer.
4. Recapture any figure on those pages whose UI shows the old label.
