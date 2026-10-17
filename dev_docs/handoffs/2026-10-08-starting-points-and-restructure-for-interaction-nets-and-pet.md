# Starting points and restructure for interaction nets and Petri nets pages

- **Handoff:** `bdefc21c-6d91-406f-b4d9-b64f5e06ccb8` (project)
- **Filed from:** https://openendedgroup.com/field4/petri_nets/
- **Filed by:** human, 2026-10-08 21:12
- **Close with:** `python tools/docs_notes.py handoff-close bdefc21c "what happened"`

Found by the docs language sweep of 2026-10-08 (night 37).

- `interaction_nets_examples.md` and `petri_nets.md` point at workspace documents instead of starting points: `dev_docs/agents/demos/interaction_nets_lambda` (removed from the prose tonight; a starting point should replace it) and `petri_state_space` "in the transcript gallery".
- `petri_nets.md` is ~750 lines and puts the state-space internals (stubborn-set closure, Karp–Miller caveats, measurement tables) in the middle of a page whose opening is about music. Proposal: keep the music half plus a short "Will it deadlock?" section, and move "Every interleaving, exactly" onward to an internals section at the bottom or its own page. Its measured tables (philosopher counts, timings) are planted numbers that should be derived or cut.
- `petri_nets.md` header lists source files as bare names (`PetriNets.kt` ...); they should link to source.
- `sorting_networks_examples.md`: no starting point for the annealing examples.
