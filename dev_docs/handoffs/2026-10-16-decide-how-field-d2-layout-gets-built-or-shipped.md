# Decide how field-d2-layout gets built or shipped

- **Handoff:** `99e14fea-f4f4-4361-ab01-7dd28de9184e` (project)
- **Filed from:** https://openendedgroup.com/field4/petri_nets/
- **Filed by:** human, 2026-10-16 05:42
- **Close with:** `python tools/docs_notes.py handoff-close 99e14fea "what happened"`

Refiled from the code batch (original: "Build or ship the field-d2-layout helper so PetriLayout.TALA works out of the box").

Done on 2026-10-16: the merged half (7d34daa3). D2Layout's availability probe is keyed on the helper's mtime, so a helper built or rebuilt under a running Field is used on the next draw. D2LayoutProbeTest covers it. The cheatsheet's `clearPetriLayoutCache()` row was deleted, and petri_nets.md now says so.

Not done: building or shipping the helper. It is Go (`native/field-d2-layout`), and this machine has no Go toolchain. A Gradle task that needs `go` would fail wherever Go is absent, which is not "out of the box".

Decision for Marc:
- (a) a buildDocsNativePrerequisites-style task that runs `go build` only when Go is present;
- (b) fetch a pinned Go toolchain into the native download cache (checksummed, like Ceres/TetGen);
- (c) ship prebuilt per-platform binaries;
- (d) keep it optional. Under (d) the petri_nets.md paragraph stays as it is.
