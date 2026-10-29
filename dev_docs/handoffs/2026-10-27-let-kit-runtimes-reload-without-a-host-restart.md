# Let kit runtimes reload without a host restart

- **Handoff:** `7cf5f53d-ac5c-4e38-bb2d-9773a30543d8` (project)
- **Filed from:** https://openendedgroup.com/field4/kits/
- **Filed by:** human, 2026-10-27 18:21
- **Close with:** `python tools/docs_notes.py handoff-close 7cf5f53d "what happened"`

`public/docs/kits.md` (Lifecycle and shared Project state) carries a limitation paragraph:

> Kit runtimes currently share one extension layer in a host. Changing or removing a runtime requires
> restart; `reloadPersonal()` refuses to replace a layer containing active kit runtimes. Shutdown
> allows four seconds for kit cleanup.

Rule 25: this exists to warn about a limitation. Fix in code (`src/jvmMain/kotlin/afield/document/workspace/KitRuntime.kt` and the personal-extension reload path): give each kit runtime its own reloadable layer, or have `reloadPersonal()` deactivate/cleanup kit runtimes and reload them rather than refuse. Delete the first two sentences of that paragraph in the same change (the shutdown budget sentence can stay if still true).
