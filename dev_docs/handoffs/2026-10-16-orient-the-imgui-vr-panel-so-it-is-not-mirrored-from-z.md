# Orient the ImGui VR panel so it is not mirrored from -Z

- **Handoff:** `b28f735e-7301-4e5b-b66a-803cde6b2d7a` (project)
- **Filed from:** https://openendedgroup.com/field4/imgui_vr_panel/
- **Filed by:** human, 2026-10-16 09:06
- **Close with:** `python tools/docs_notes.py handoff-close b28f735e "what happened"`

Refiled from the code batch on 2026-10-16, not started: orienting the panel independently of the camera direction needs a VR headset (or at least the panel demo's capture) to verify, and this session had neither.


Rule 25 sharp edge on `public/docs/imgui_vr_panel.md` ("If you build your own"):

> !!! note "The camera has to look down +Z"
>     With that orientation world-right maps to screen-right and the ImGui text reads correctly; look the other way and the whole panel comes out mirrored left-to-right. The demo sets this up for you.

Fix in code: make the ImGui panel quad (the `ImGuiPanel` helper) orient its UVs/winding from the camera so text reads correctly from either side, or flip the panel face toward the viewer. Delete the note (and the "If you build your own" heading if empty) in the same change.
