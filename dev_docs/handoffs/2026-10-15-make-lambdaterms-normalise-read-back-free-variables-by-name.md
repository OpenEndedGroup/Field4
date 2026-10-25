# Make LambdaTerms.normalise read back free variables by name

- **Handoff:** `493f066f-0f24-4a8f-9fe7-f2019bb5b44c` (project)
- **Filed from:** https://openendedgroup.com/field4/interaction_nets_examples/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 493f066f "what happened"`

interaction_nets_examples.md says: "`normalise` expects a closed term: a free variable that gets duplicated reads back as an agent id rather than as its name."

Sharp edge (rule 25). Fix in afield/data/graph/interactionnets.kt: carry the free variable name through fan duplication in compile/readback (or reject open terms with an error). Delete that sentence in the same change.
