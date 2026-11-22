# Capture the solidsl_gallery output areas on solid_notation's Open this section

- **Handoff:** `3b32dc9c-06f3-486e-aa08-f6baea190a82` (project)
- **Filed from:** https://openendedgroup.com/field4/solid_notation/
- **Filed by:** agent:claude, 2026-11-22 10:14
- **Close with:** `python tools/docs_notes.py handoff-close 3b32dc9c "what happened"`

## The notes that led here

- `99e664b5` (public/docs/solid_notation.md:868): I'd love this to have captured output areas
  > Open this

The human asked for the "Open this" section of `public/docs/solid_notation.md` (the `offline.html?file=assets/solidsl_gallery/metadata.edn` iframe) to show captured output areas, so a reader sees what each of the three gallery boxes produces without running Field.

This needs a real capture run, not a prose edit: the three boxes of the `solidsl_gallery` starting point (and probably the three of `solidsl_inverse`) must be run against a live Field with Manifold present, and their outputs captured, either into the offline document so the iframe renders them or as per-box `diffstatic_runner` screenshots. Docs agents in this pass may not run the docs build.

Approach options:
1. Make the offline viewer show saved output panels for the gallery document (does `offline.html` support persisted outputs? check the offline viewer under `public/`), and re-save the gallery with its outputs.
2. Add one `capture="box" open-outputs="true"` runner capture per gallery box under the iframe, using the transcript's box code.

Questions for the human: iframe with outputs, or separate screenshots under it? Should `solidsl_inverse` get the same treatment?

Once done, the page needs the bullet list under the iframe to refer to what each capture shows.
