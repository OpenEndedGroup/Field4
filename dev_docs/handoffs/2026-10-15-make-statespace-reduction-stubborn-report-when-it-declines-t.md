# Make stateSpace(reduction = STUBBORN) report when it declines to reduce a colour-dependent net

- **Handoff:** `03b699a0-2eca-4ba5-af81-8a4fe5ca0f0f` (project)
- **Filed from:** https://openendedgroup.com/field4/petri_nets/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 03b699a0 "what happened"`

petri_nets.md, "Searching larger nets", says a guard or `enabledIf` makes `stateSpace(reduction = STUBBORN)` return "an unreduced (and therefore still `exact`) graph, with no complaint and with `graph.reduction == PetriReduction.NONE` saying so."

Silent downgrade is a sharp edge (rule 25). Fix in afield.simulation.petri: add a caveat/warning on the returned graph (like coverability().caveats) and print it in report(), or reduce only the arc-analysable part. Delete or shorten the bullet in the same change.
