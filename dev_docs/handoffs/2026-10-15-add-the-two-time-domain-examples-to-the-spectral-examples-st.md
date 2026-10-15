# Add the two time-domain examples to the spectral_examples starting point

- **Handoff:** `b8f9dd2e-b702-456b-831d-caee972c86e4` (project)
- **Filed from:** https://openendedgroup.com/field4/spectral_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close b8f9dd2e "what happened"`

Sharp edge (rule 25) on public/docs/spectral_examples.md, "Open the examples as a document":

> The two time-domain examples at the top of the page are not in that document; paste either fence into a box to run it.

Fix in data: add the "Filter chains as layered curves" and "Distribution-aware event flags" boxes to the `spectral_examples` starting point (dev_docs/agents/transcripts / demo build script), so the whole page opens as one document. Delete that sentence (and "The five frequency-domain examples, from One spectrum on," qualifier) in the same change.
