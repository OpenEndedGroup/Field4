# Make osc(port) fail when another program holds the port

- **Handoff:** `7de3b9fd-fc62-4c09-9467-6089c3d1ef62` (project)
- **Filed from:** https://openendedgroup.com/field4/osc/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 7de3b9fd "what happened"`

Rule 25 sharp edge on `public/docs/osc.md`, the warning "`osc(port)` succeeds even when another program already has the port":

> JavaOSC opens its sockets with `SO_REUSEADDR`, so binding a port that TouchOSC Bridge, Max or a second Field already holds succeeds, and `isListening` says `true`. ... the Field box receives nothing, with no error anywhere. When a handler never fires, look for another listener first: `lsof -nP -iUDP:57301`

Fix in code (`afield.io.osc.OscServer` / `osc(port)`): bind without SO_REUSEADDR (or probe the port first) and raise a box error naming the port when it is already held. Delete the warning admonition in the same change.
