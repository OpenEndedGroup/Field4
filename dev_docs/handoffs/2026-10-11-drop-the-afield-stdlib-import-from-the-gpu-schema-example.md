# Drop the afield.stdlib import from the GPU schema example

- **Handoff:** `24c46269-22ff-4722-b7aa-cbfb4596dbbc` (project)
- **Filed from:** https://openendedgroup.com/field4/gpu_schema/
- **Filed by:** human, 2026-10-11 12:29
- **Close with:** `python tools/docs_notes.py handoff-close 24c46269 "what happened"`

The first fence on public/docs/gpu_schema.md ("A particle schema") opens with `import afield.math.linalg.*`, which DOCS_WRITING_RULES rule 7 forbids in box code (the box preamble already provides it). Remove the import from the fence (and any other fence on the page that relies on it), and confirm the example still compiles in a box beneath a renderer. The fences are plain `diffstatic`, so no capture needs regenerating, but check `tools/completion-corpus/rank-carets.tsv` for `gpu_schema` rows first.
