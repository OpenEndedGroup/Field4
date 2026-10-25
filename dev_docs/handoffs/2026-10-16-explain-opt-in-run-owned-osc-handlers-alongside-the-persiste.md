# Explain opt-in run-owned OSC handlers alongside the persistent default

- **Handoff:** `c1b480b7-2609-4d33-83d7-716c77793088` (project)
- **Filed from:** https://openendedgroup.com/field4/osc/
- **Filed by:** human, 2026-10-16 19:31
- **Close with:** `python tools/docs_notes.py handoff-close c1b480b7 "what happened"`

The live fader on public/docs/osc.md now uses on(..., runOwned = true). Explain opt-in stop/rerun ownership beside that example; qualify the later 'Handlers outlive a stop' section as the default persistent registration behavior. Other examples intentionally retain that default. Real checks prove stop removes the opt-in handler while the port's shared server remains listening.
