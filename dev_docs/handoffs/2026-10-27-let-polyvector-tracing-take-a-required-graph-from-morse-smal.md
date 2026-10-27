# Let polyvector tracing take a required graph from Morse-Smale

- **Handoff:** `6f854af2-6dc4-4017-a3b0-bd9a08dc0d10` (project)
- **Filed from:** https://openendedgroup.com/field4/geometry_chains/
- **Filed by:** human, 2026-10-27 18:21
- **Close with:** `python tools/docs_notes.py handoff-close 6f854af2 "what happened"`

`public/docs/geometry_chains.md` ends with a section, "What does not chain yet", that exists only to say a capability is missing (rule 25):

> The proposal behind these extensions also asks for one module to constrain another, for example a
> Morse–Smale graph fixing the connectivity that polyvector tracing must keep. The polyvector
> implementation has no input for a required graph, so this is not available. Using a complex's
> ridges as polyvector evidence works, but it treats the ridges as pixels, and the connectivity is not
> carried over.

Fix in code: give polyvector vectorization an input for a required graph (a `GeometryNetwork`, e.g. from a Morse–Smale complex) whose connectivity the tracing must keep, and add a third chain example to the page showing it. Delete the "What does not chain yet" section in the same change. If Marc decides this constraint is not going to be built, delete the section anyway (the page should describe what chains, not what does not).
