# Fix four_simulation_chain's description: the dye knob is dyeDissipation

- **Handoff:** `059d6758-06a0-401b-b6d9-08b42f006f95` (project)
- **Filed from:** https://openendedgroup.com/field4/agent_transcripts/
- **Filed by:** human, 2026-10-05 14:53
- **Close with:** `python tools/docs_notes.py handoff-close 059d6758 "what happened"`

Found by the box-code revision pass.

`dev_docs/agents/transcripts/four_simulation_chain.yaml`, `description:`: it asks the reader to change `dissipation` on `fluid-dye`; the `sim_fluid` box's knob is `dyeDissipation`, now a named val at the top of the box. Update the description (and the `agent_transcripts.md` entry if it says the same).
