# Replace _print in the RectSL JPG frames fence

- **Handoff:** `378f1663-67f2-4dde-89a3-ca3c79da8553` (project)
- **Filed from:** https://openendedgroup.com/field4/rectsl_examples/
- **Filed by:** human, 2026-10-11 12:54
- **Close with:** `python tools/docs_notes.py handoff-close 378f1663 "what happened"`

`public/docs/rectsl_examples.md`, section "Rendering JPG frames": the fence calls `_print(result)`, and `_print` is gone (DOCS_WRITING_RULES: prefer `print`). Change it to `print(result)` (check rank-carets.tsv pins and any capture digest), and confirm the snippet still runs.
