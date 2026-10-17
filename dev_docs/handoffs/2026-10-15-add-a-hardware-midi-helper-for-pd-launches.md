# Add a hardware MIDI helper for Pd launches

- **Handoff:** `cf48634f-592c-4491-9d04-26fe24647a47` (project)
- **Filed from:** https://openendedgroup.com/field4/pure_data/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close cf48634f "what happened"`

Rule 25 sharp edge on `public/docs/pure_data.md` ("What is not here"):

> **A MIDI device.** ... Nothing connects those to a hardware port yet; that is a few lines of Kotlin per box.

Fix in code: provide a helper (e.g. `PdLaunch.connectMidi(device)`) that wires a hardware MIDI port to `noteOn`/`controlChange` and `onMidi`, so users do not write it per box. Replace the caveat with one sentence showing the helper in the same change.
