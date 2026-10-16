# Decide whether to rename history() or the History Lens

- **Handoff:** `f4ff38a4-fe6c-4ee7-a655-a98b95611bb7` (project)
- **Filed from:** https://openendedgroup.com/field4/history_lens/
- **Filed by:** human, 2026-10-16 01:57
- **Close with:** `python tools/docs_notes.py handoff-close f4ff38a4 "what happened"`

Split from handoff f4592ed7 (History Lens shortcut), 2026-10-16. The shortcut part is done (`alt+shift+h`).

Still open, and yours to decide: the editor's `history()` visualizer (`m.history(x)` in running code) and the History Lens (version history for a line or selection) share a name. `public/docs/history_lens.md` carries a whole section, "A different "history" in the editor", only to tell them apart. Should one be renamed (for example the visualizer to `trace()` / `timeline()`, or the lens to "Code History")? If so, the section shrinks to a cross-link.

## Decision, 2026-10-16 (Marc)

**Rename the History Lens to "Code History".** The `history()` visualizer keeps its name. Rename the UI labels (palette/action names in `BootstrapMonacoActions.kt`, panel titles in `history.kt`) and the docs. "A different "history" in the editor" on `history_lens.md` shrinks to a cross-link. Filed as a code handoff.
