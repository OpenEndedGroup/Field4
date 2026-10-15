# Compare pattern colours by identity, not printed name, in Petri loop detection

- **Handoff:** `f383b890-2191-4f7e-8740-4bdc9d77405e` (project)
- **Filed from:** https://openendedgroup.com/field4/petri_nets/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close f383b890 "what happened"`

petri_nets.md, "Limits", says: "Loop detection compares colours by name. Two different patterns both print as `PatPattern(...)`, so to the loop detector swapping one section for another looks like nothing happening."

Sharp edge (rule 25). Fix in afield.simulation.petri: key colours by structural identity/equality (as stateSpace() already does exactly), or give PatPattern a distinguishing toString. Delete the bullet in the same change.
