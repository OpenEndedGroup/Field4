# Move Running code in a box out of Start Here

- **Handoff:** `74aa20f0-5e89-439c-8410-a993f1d04613` (project)
- **Filed from:** https://openendedgroup.com/field4/running_code/
- **Filed by:** human, 2026-10-02 03:09
- **Close with:** `python tools/docs_notes.py handoff-close 74aa20f0 "what happened"`

`running_code.md` ("Running code in a box") sits in Start Here, but its own opening says a reader does not need it to start writing boxes: it covers macro expansion, the preamble, `Inheritance` delegates, `@exported` aliasing and generated GLSL accessors. Rule 17 of `DOCS_WRITING_RULES.md` keeps internals out of Start Here. Move it to a later section (beside `why.md` or the Kotlin overview material), and keep a short "see also" from the Survival Guide area. The page's long fences (the `Onceing` source, the macro-not-expanded stub) are also internals and could move into a collapsed section; that is a fence change.
