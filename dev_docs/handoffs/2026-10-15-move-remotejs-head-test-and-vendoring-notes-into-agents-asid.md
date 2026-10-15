# Move RemoteJS head test and vendoring notes into Agents asides

- **Handoff:** `19d94daa-72a1-4e91-bfdb-3405d2995591` (project)
- **Filed from:** https://openendedgroup.com/field4/ar_reference/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 19d94daa "what happened"`

`public/docs/ar_reference.md` sections "Testing the head" (regression command `tools/run_ui_regressions.py --id remote-ar-head`) and "Vendored runtime" (`python3 tools/vendor_remote_ar_runtime.py`, licences, refresh) are addressed to maintainers/agents, not users (rule 26). They belong in collapsed `??? aside "Agents: ..."` blocks, but each contains a code fence that would have to be re-indented, which this prose pass may not do. Move both into `Agents:` asides (or into `dev_docs/` with a link), keeping the user-facing `?selftest=1&debug=1` / `window.__fieldRemoteHead` part in the body. Also consider whether "Camera permission, native Android WebXR session entry, and Endrun/ARKit session entry still require their physical devices" is a test-coverage gap worth a device test.
