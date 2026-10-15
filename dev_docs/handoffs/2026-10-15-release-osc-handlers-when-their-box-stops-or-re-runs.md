# Release OSC handlers when their box stops or re-runs

- **Handoff:** `11ee3441-fea9-405c-a5d5-215299b806c5` (project)
- **Filed from:** https://openendedgroup.com/field4/osc/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 11ee3441 "what happened"`

Rule 25 sharp edge on `public/docs/osc.md`, section "Handlers outlive the box" and the fader example:

> Stopping the box stops the animation but not the handler, which goes on writing to the stopped run's `level` until the box runs again and registers its replacement.

> Handlers belong to the server, and the server belongs to the port, not to any box. Stopping a box, running it again, or closing its document leaves every handler it registered in place, still calling the code it was registered with. ... A pattern the new code does not mention is not replaced by anything, so `/old` goes on firing until `remove("/old")`.

Fix in code (`afield.io.osc.OscServer`): register handlers against the box that called `on`, and remove them when that box stops, re-runs or its document closes (the box-lifecycle release policy other resources use). Then the `docs.osc.lifecycle` figure and these paragraphs are rewritten/deleted in the same change.
