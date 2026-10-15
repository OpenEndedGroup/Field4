# Give the History Lens a shortcut; settle the history() name clash

- **Handoff:** `f4592ed7-7b0f-4b8a-a630-9fd401c67fb9` (project)
- **Filed from:** https://openendedgroup.com/field4/history_lens/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close f4592ed7 "what happened"`

`public/docs/history_lens.md` says:

> The lens opens as a small zone directly beneath your selection. There is no keyboard shortcut for it.

and a whole section "A different "history" in the editor" exists because the `history()` visualizer and the History Lens share a name ("the two are easy to confuse").

Rule 25: (1) bind the `history-lens-selection` editor action to a key in the keybindings YAML and drop "There is no keyboard shortcut for it" (say the key instead); (2) Marc to decide whether the name clash warrants a rename; if so, the section shrinks to a cross-link.
