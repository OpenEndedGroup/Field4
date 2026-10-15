# Orient the ImGui VR panel so it is not mirrored from -Z

- **Handoff:** `85479cc4-b98c-48e1-8cbe-023e42a415b0` (project)
- **Filed from:** https://openendedgroup.com/field4/imgui_vr_panel/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 85479cc4 "what happened"`

Rule 25 sharp edge on `public/docs/imgui_vr_panel.md` ("If you build your own"):

> !!! note "The camera has to look down +Z"
>     With that orientation world-right maps to screen-right and the ImGui text reads correctly; look the other way and the whole panel comes out mirrored left-to-right. The demo sets this up for you.

Fix in code: make the ImGui panel quad (the `ImGuiPanel` helper) orient its UVs/winding from the camera so text reads correctly from either side, or flip the panel face toward the viewer. Delete the note (and the "If you build your own" heading if empty) in the same change.
