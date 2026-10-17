# Support [scale:log] on Faust sliders

- **Handoff:** `5c294a8a-cc07-4dfc-8cc6-a6b6b4e61107` (project)
- **Filed from:** https://openendedgroup.com/field4/faust/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 5c294a8a "what happened"`

Rule 25 sharp edge on `public/docs/faust.md` ("The controls are the program's own"):

> `[scale:log]` keeps its range but the slider moves linearly, because Field's slider has no log setting. The scale is available as `FaustControl.scale`.

Fix in code: give Field's control-group slider a log (and exp) scale, and have the Faust control mapping honour `[scale:log]`/`[scale:exp]`. Delete this bullet from faust.md in the same change (and adjust "Three of those differ from Faust" to two).
