# Fix the canonical_graphics lessons that fail as saved

- **Handoff:** `3d576481-c933-447c-87e1-adbc30467b7f` (project)
- **Filed from:** https://openendedgroup.com/field4/afield_four_graphics/
- **Filed by:** human, 2026-10-11 12:28
- **Close with:** `python tools/docs_notes.py handoff-close 3d576481 "what happened"`

`public/docs/afield_four_graphics.md` documents three saved lessons in `public/docs/assets/canonical_graphics/` that do not do what their names suggest when run as saved, and one naming collision the page explains instead of fixing (rules 10 and 11 of DOCS_WRITING_RULES.md):

1. Multi-texture lesson: binds two image views against a `sampler2D ta[3]` declaration, so it fails at link time ("can't link texture resource ta of length 2 but the shader expects 3"). Fix the saved box (third view, or `ta[2]`).
2. printf lesson: the guard `gl_VertexIndex == 10` never fires on a three-vertex draw. Change the saved guard to one that fires (e.g. `gl_VertexIndex == 0`).
3. glTF lessons: model paths are absolute paths into the author's checkout. Consider a relative path or a clear placeholder.
4. "Camera matrices in a UBO": the uniform block named `camera` generates a `Camera` class that shadows `afield.four.util.Camera`, forcing the fully qualified name and a paragraph of explanation. Either rename the block in the lesson/fence (fence change: recapture) or change the generator so a generated class cannot shadow the preamble's imports.

After fixing, update the page's prose (multi-texture paragraph, printf paragraph, "Where these examples come from", the Camera paragraph). Also: the Slang section previously said "These two historical lesson captures have not yet been regenerated" (removed in the language pass); check whether the Slang lessons should get runner captures.
