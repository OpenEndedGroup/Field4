# Make a still ShaderTrinket canvas still publish frames

- **Handoff:** `d0bcd8e6-c914-4fee-92a4-74f67ce00577` (project)
- **Filed from:** https://openendedgroup.com/field4/webrtc/
- **Filed by:** human, 2026-10-27 18:20
- **Close with:** `python tools/docs_notes.py handoff-close d0bcd8e6 "what happened"`

`public/docs/webrtc.md` carries this rule-25 note:

> !!! note "A still shader publishes nothing"
>     A browser canvas only produces video frames when it changes, so a ShaderTrinket whose shader
>     does not use `iTime` (its default does not) publishes an empty stream, and Field shows the
>     waiting line. Field's own views redraw every frame and are not affected.

Fix in code: have the ShaderTrinket publisher force frames (e.g. `track.requestFrame()` on a timer, or redraw at a minimum rate while publishing), and/or make Field's `webrtcSource` show the last/first frame of a connected-but-idle stream instead of the waiting line. Delete the note from `webrtc.md` in the same change.
