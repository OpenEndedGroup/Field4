# Stop renderer children automatically when the renderer stops

- **Handoff:** `794aaaa3-9af3-4192-bb79-7c36fa9fe4a2` (project)
- **Filed from:** https://openendedgroup.com/field4/open_dlss_nr/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 794aaaa3 "what happened"`

`open_dlss_nr.md` tells the reader twice to respect an ordering:

> Stop the child boxes before the renderer.

> **neural ink** is connected beneath it and owns the source target, NR pass and display. **record clip** is connected beneath neural ink and reads the same study. Stop the child boxes before stopping the renderer.

This is an ordering trap (rule 25). Fix in code: stopping the **renderer** box should stop (or release resources of) the boxes beneath it that hold `OpenDlssNrStudy` / `OpenDlssNrPass`, e.g. by registering their close with the renderer's lifecycle so the study is closed before the device goes away. Then delete both sentences from `public/docs/open_dlss_nr.md` in the same change.

## Completed on Linux/NVIDIA, 2026-10-17

Both OpenDlssNrStudy and standalone OpenDlssNrPass retire before renderer device cleanup. Native teardown smoke passed, including replacement-run ownership.

Evidence and reproduction: [Linux/NVIDIA verification](../lighting/ELSEWHERE_20261017.md). The originating annotation store is absent on this host; this tracked record carries the outcome for closing there after landing.
