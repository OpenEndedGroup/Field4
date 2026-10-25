# Rebind child textures when an optical-flow parent relaunches

- **Handoff:** `3ca69032-e630-403f-a811-efed6c089006` (project)
- **Filed from:** https://openendedgroup.com/field4/optical_flow/
- **Filed by:** human, 2026-10-16 08:52
- **Close with:** `python tools/docs_notes.py handoff-close 3ca69032 "what happened"`

Refiled from the code batch. Not started on 2026-10-16. Name-resolved texture bindings would have to observe a parent's republished allocation, which is a resolution-lifecycle design change.


Rule 25 sharp edge on `public/docs/optical_flow.md` ("Keep the previous frame with ping-pong"):

> When relaunching the parent, relaunch the child too so its texture bindings follow the new allocation.

Fix in code: texture bindings resolved by name (`prop<RenderToTexture>(motion)`, `PingPongRenderToTexture` history) should re-resolve when the parent republishes a new allocation, or the parent relaunch should re-launch/rebind dependent children. Delete the sentence in the same change.
