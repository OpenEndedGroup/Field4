# Report NR unavailable when device predates the native build

- **Handoff:** `a6da8bc3-4ddf-47cb-a8d2-e5e792263d68` (project)
- **Filed from:** https://openendedgroup.com/field4/open_dlss_nr/
- **Filed by:** human, 2026-10-20 11:10
- **Close with:** `python tools/docs_notes.py handoff-close a6da8bc3 "what happened"`

Rule 25 sharp edge in `public/docs/open_dlss_nr.md` ("Prepare the network"):

> Build before starting the renderer; an already-created Vulkan device cannot gain these features.

Ordering trap: preparing OpenDLSS-NR after the renderer is running silently leaves NR unavailable until a restart. Fix in code: have `OpenDlssNrStudy`/`OpenDlssNrPass` availability detect "library now present but device was created without the NR features" and report it as such (or offer a renderer restart), so the user is told exactly what to do. Delete the paragraph sentence in the same change.

Done 2026-10-29: OpenDlssNrNative retries a missing-library load once the library appears, and OpenDlssNrSupport.availability reports "NR adapter was built after this renderer's Vulkan device was created ...; restart the renderer to enable NR" instead of the stale build instruction (OpenDlssNrSupportTest; OpenDlssNrLiveEditSmoke --built-after-device verifies the message and that a renderer created afterwards in the same JVM is NR-ready). Doc sentence deleted.
