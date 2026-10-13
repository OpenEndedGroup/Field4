# Restructure the MorphoHDL page: usage first, search study to its own page

- **Handoff:** `e484d99b-822f-48a5-88db-675dd2f9daf6` (project)
- **Filed from:** https://openendedgroup.com/field4/morpho_examples/
- **Filed by:** human, 2026-10-11 06:10
- **Close with:** `python tools/docs_notes.py handoff-close e484d99b "what happened"`

`morpho_examples.md` reads as a research report. After the language sweep's prose pass (2026-10-11):

- **Usage is in the middle.** "Using it" (as a language, from Kotlin) sits below the depth and 3D-layout sections. Move it directly under "What it looks like", so a reader learns to write a morpho box first.
- **The second half is a study, not a user page.** "Searching for circuits nobody wrote" (search drivers, seeds-per-cell tables, the width-family `or-reduce` finding, the recursive-adder negative result) and "Bringing in another language" (a how-to for hosting a foreign runtime) are written for us. Decide: move the search into its own page under the languages/research section, and move "Bringing in another language" to `dev_docs/` (or a contributor page), leaving a see-also.
- **Fences carry internal commentary** (`// The check goes in the panel TITLE, not through println: the docs runner captures ...`, `// The whole layer in four lines ...`). Rewriting those comments changes pinned fence bytes and captures, so it belongs here.
