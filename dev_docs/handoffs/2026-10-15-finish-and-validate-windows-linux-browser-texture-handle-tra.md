# Finish and validate Windows/Linux browser-texture handle transfer

- **Handoff:** `d2d7aa13-aa11-4e2a-a91d-a0c0bec76ad1` (project)
- **Filed from:** https://openendedgroup.com/field4/puppet_browser/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close d2d7aa13 "what happened"`

`public/docs/puppet_browser.md`, "Pixels without readback", carries this paragraph:

> The macOS producer-to-renderer path is the currently live-tested implementation. Windows and Linux retain their compile-reviewed receivers while their Electron handle-transfer bridges and native validation are completed.

This is a "still does not" paragraph (DOCS_WRITING_RULES rule 25). Fix: finish the Electron handle-transfer bridges for Windows (D3D11 NT handle) and Linux (dma-buf + DRM modifier) in `src/jvmMain/kotlin/afield/media/browser/{Windows,Linux}ExternalBrowserSurface.kt` and the Electron producer, validate them live, or, until then, have `FieldUiBrowserTexture` fail with a clear message on those platforms. Delete the two sentences from the page in the same change.
