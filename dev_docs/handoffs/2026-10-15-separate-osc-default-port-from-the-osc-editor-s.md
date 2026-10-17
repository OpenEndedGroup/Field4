# Separate osc() default port from the OSC editor's

- **Handoff:** `bac4f58a-869e-4c45-b81b-c968ebc7c5c4` (project)
- **Filed from:** https://openendedgroup.com/field4/osc/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close bac4f58a "what happened"`

Rule 25 sharp edge on `public/docs/osc.md`, twice:

> `osc()` with no argument listens on 9000. So does Field's own OSC editor (below), when it is enabled, which is a reason to give your boxes a different port.

> The default port is 9000, the same as `osc()`'s default, so a box that listens on 9000 shares that port with the editor; give your boxes another one.

Fix in code: give `osc()` and the welcome-page OSC editor different default ports (or make `osc()` with no argument refuse a port the editor holds). Delete both sentences in the same change.
