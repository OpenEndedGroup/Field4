# Make Options.profiling switchable without a renderer restart

- **Handoff:** `8f1b1485-4950-4140-966e-4f24380826ad` (project)
- **Filed from:** https://openendedgroup.com/field4/flow_grain/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 8f1b1485 "what happened"`

Rule 25 sharp edge on `public/docs/flow_grain.md`, "Profiling this graph":

> To get GPU queries too, set `Options.profiling = true` in the renderer box and relaunch the numbered graph. The setting is read at startup, so switching it on always costs you a restart, and which pipeline statistics you get back varies by device.

Fix: make GPU timestamp/pipeline-statistic queries switchable on a live renderer (allocate query pools lazily on first enable), and report which statistics the device supports in the renderer profiler rather than leaving it to prose. Delete the restart caveat in the same change.
