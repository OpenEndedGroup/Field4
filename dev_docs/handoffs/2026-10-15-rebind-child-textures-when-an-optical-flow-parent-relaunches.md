# Rebind child textures when an optical-flow parent relaunches

- **Handoff:** `176ebd0e-8c5f-4f08-93e5-0075f6107761` (project)
- **Filed from:** https://openendedgroup.com/field4/optical_flow/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 176ebd0e "what happened"`

Rule 25 sharp edge on `public/docs/optical_flow.md` ("Keep the previous frame with ping-pong"):

> When relaunching the parent, relaunch the child too so its texture bindings follow the new allocation.

Fix in code: texture bindings resolved by name (`prop<RenderToTexture>(motion)`, `PingPongRenderToTexture` history) should re-resolve when the parent republishes a new allocation, or the parent relaunch should re-launch/rebind dependent children. Delete the sentence in the same change.
