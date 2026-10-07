# Make marf_demo a starting point and link it from marf.md

- **Handoff:** `cb2fade9-9995-4d21-8a47-4d4962d5e4ec` (project)
- **Filed from:** https://openendedgroup.com/field4/marf/
- **Filed by:** human, 2026-10-07 06:09
- **Close with:** `python tools/docs_notes.py handoff-close cb2fade9 "what happened"`

`marf.md` ends with "Running it", which tells the reader to launch
`./field.sh dev_docs/agents/demos -d marf_demo` (a bash fence). DOCS_WRITING_RULES rule 19: a runnable
document is a starting point on the welcome screen, linked from the page, never a workspace path.

Do: register `marf_demo` as a starting point, replace the bash fence with a link to it (fence edit:
check `tools/completion-corpus/rank-carets.tsv`), and keep the sentence after it describing the four boxes.
