# Stop the docs runner expecting SVG from lines( inside three { }

- **Handoff:** `1abed9ee-1e1a-415b-bb57-2a28319cfa38` (project)
- **Filed from:** https://openendedgroup.com/field4/three_output/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close 1abed9ee "what happened"`

Sharp edge (rule 25) and agent note on three_output.md, aside "Agents: a bare `lines(` in a docs fence":

> The runner's output validator treats a bare `lines(` in the code as a call to `Box.lines(...)` ... `// FIELD_DOCS_EXPECT_SVG: false` in the fence is the escape hatch.

Fix in public/docs_hooks.py: do not expect SVG for `lines(` inside a `three { }` / `threeLines` block (or key the expectation off the produced output type), then remove the escape-hatch comment from the fence and delete the aside in the same change.
