# Let a required graph constrain polyvector tracing

- **Handoff:** `05beb75a-5a6d-459d-a5e9-f799e9d22fea` (project)
- **Filed from:** https://openendedgroup.com/field4/geometry_chains/
- **Filed by:** human, 2026-10-20 11:09
- **Close with:** `python tools/docs_notes.py handoff-close 05beb75a "what happened"`

Sharp edge (rule 25) on public/docs/geometry_chains.md, section "What does not chain yet":

> The proposal behind these extensions also asks for one module to constrain another, for example a Morse–Smale graph fixing the connectivity that polyvector tracing must keep. The polyvector implementation has no input for a required graph, so this is not available. ...

Fix in code: give `Polyvector.vectorize` an optional required-graph input (e.g. a `GeometryNetwork` whose connectivity tracing must keep), so a Morse–Smale complex can constrain it. Delete the section in the same change (or replace it with a third chain example).
