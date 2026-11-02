# Add the missing captures to vision.md (2 sections)

- **Handoff:** `597eecde-84d7-49a6-aca6-40a404ea9c5d` (project)
- **Filed from:** https://openendedgroup.com/field4/vision/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 597eecde "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Streaming the rendered scene as video (`public/docs/vision.md:140`, high, needs-gpu-or-hardware)

- **Point made:** The full Vulkan scene is streamed to the headset as HEVC/H.264 video and is verified end to end on a cinema screen or a movable window, but the page never shows that screen.
- **Capture:** A visionOS headset recording (Reality Composer or device screen capture) of a Field render on the world-locked cinema screen, next to the same frame in Field's native window. It has to be committed as a hand-captured asset.

### One half-frame per eye (`public/docs/vision.md:156`, medium, needs-gpu-or-hardware)

- **Point made:** The encoder produces side-by-side stereo frames that a ShaderGraph material splits between the two eyes, but the reader never sees what a stereo frame looks like.
- **Capture:** Save one side-by-side frame from the Field sender's RTT/IOSurface before encoding (a screenshot-native or texture readback of the stereo target), showing the slightly offset left and right halves.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
