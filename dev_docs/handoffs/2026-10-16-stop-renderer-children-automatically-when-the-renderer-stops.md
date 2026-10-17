# Stop renderer children automatically when the renderer stops

- **Handoff:** `ce738dca-a83d-46a4-9bea-6a1b00036a53` (project)
- **Filed from:** https://openendedgroup.com/field4/open_dlss_nr/
- **Filed by:** human, 2026-10-16 07:48
- **Close with:** `python tools/docs_notes.py handoff-close ce738dca "what happened"`

Refiled from the code batch on 2026-10-16. OpenDlssNr runs only on NVIDIA, and this session was on macOS, so neither the change nor the linux-nvidia recapture could be verified. Take it in a Linux/NVIDIA session.

Note from the attempt: Renderer has a `cleanup` Runnable list (Renderer.kt) but no obvious close-listener API. Find how a renderer box's stop tears the device down (search the renderer box lifecycle in afield/graphics/vulkan and the backdoor's dispose-graphics-context) and have OpenDlssNrStudy register its close there.


`open_dlss_nr.md` tells the reader twice to respect an ordering:

> Stop the child boxes before the renderer.

> **neural ink** is connected beneath it and owns the source target, NR pass and display. **record clip** is connected beneath neural ink and reads the same study. Stop the child boxes before stopping the renderer.

This is an ordering trap (rule 25). Fix in code: stopping the **renderer** box should stop (or release resources of) the boxes beneath it that hold `OpenDlssNrStudy` / `OpenDlssNrPass`, e.g. by registering their close with the renderer's lifecycle so the study is closed before the device goes away. Then delete both sentences from `public/docs/open_dlss_nr.md` in the same change.
