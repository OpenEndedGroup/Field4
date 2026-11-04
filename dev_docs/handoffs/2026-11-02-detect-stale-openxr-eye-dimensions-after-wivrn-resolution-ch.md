# Detect stale OpenXR eye dimensions after WiVRn resolution change

- **Handoff:** `cf5b4201-01a0-476c-9de7-238a9af95a8e` (project)
- **Filed from:** https://openendedgroup.com/field4/stereo_rtt_composite/
- **Filed by:** human, 2026-11-02 10:30
- **Close with:** `python tools/docs_notes.py handoff-close cf5b4201 "what happened"`

Rule 25 sharp edge in public/docs/stereo_rtt_composite.md, section "Use a headset":

> After changing WiVRn's render resolution, start a fresh WiVRn and Field session. A resumed session
> can retain the old eye dimensions. Check the dimensions printed by Field rather than assuming the
> slider has changed the running texture.

Fix in code: detect that the OpenXR runtime's recommended eye image size differs from the eye RTT
size in use (box 2 / SideBySideStereoTarget) and either rebuild the targets or print a clear
warning naming the stale dimensions. Delete the paragraph in the same change.
