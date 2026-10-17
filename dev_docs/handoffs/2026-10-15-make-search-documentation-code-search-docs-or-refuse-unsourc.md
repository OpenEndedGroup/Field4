# Make Search Documentation & Code search docs or refuse unsourced answers

- **Handoff:** `1b3595ec-5ec3-4125-b0b0-96fb6f8034eb` (project)
- **Filed from:** https://openendedgroup.com/field4/semantic_index/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 1b3595ec "what happened"`

Rule 25 sharp edge on public/docs/semantic_index.md, section "Conversational code search is separate":

> **Search Documentation & Code** (`ctrl/command-shift-D`) opens a generated-answer surface in the documentation pane. The label mentions documentation, but its retrieval store covers Field's indexed Kotlin roots and query-selected GLSL include libraries, not the local Lucene corpora above. With no remote vector store configured it still writes an answer, but one with no sources behind it, so check what it tells you against the code.

Two defects: (1) the command's name promises documentation retrieval it does not do — either rename it or have it retrieve from the local `documentation` corpus of the semantic index; (2) with no vector store configured it produces an unsourced answer — it should refuse, or fall back to the local semantic index, and say so. Delete the paragraph's caveats in the same change.
