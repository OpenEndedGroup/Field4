# Introduce fline_examples signature fences before showing them; merge the two data() sections

- **Handoff:** `8d82e616-b64f-4c30-9674-4b3241fa40b6` (project)
- **Filed from:** https://openendedgroup.com/field4/fline_examples/
- **Filed by:** human, 2026-10-02 06:25
- **Close with:** `python tools/docs_notes.py handoff-close 8d82e616 "what happened"`

`fline_examples.md` (≈3000 lines) breaks rule 6 throughout its reference half: most `###` headings from "Building paths" onward are followed directly by a signature fence, with the sentence explaining it placed after. It also documents the `data(...)` mini-language twice ("Terser construction" and "The tiny path mini-language" near the end), against rule 16.

Do: for each signature section, move the one-line description above the signature fence (or fold the signature into the existing `field-reference` tables, as the later sections already do), and merge the two `data(...)` sections into one. Prose moves only; fence bytes are pinned, so check `tools/completion-corpus/rank-carets.tsv` and the docs runner after. Consider whether the page should split (2D drawing basics vs geometry operations reference) — that would be a navigation decision for Marc.
