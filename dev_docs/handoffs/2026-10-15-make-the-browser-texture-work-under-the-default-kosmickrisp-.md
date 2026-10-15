# Make the browser texture work under the default KosmicKrisp driver

- **Handoff:** `14324f09-d235-42f8-9739-4d96e3e2c0ac` (project)
- **Filed from:** https://openendedgroup.com/field4/puppet_browser/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 14324f09 "what happened"`

`public/docs/puppet_browser.md` says: "On macOS the transferred object is an IOSurface sent through a Mach port and imported by MoltenVK."

AGENTS.md says the default Vulkan driver on Apple silicon with macOS 26+ is now KosmicKrisp, and that `AFIELD_VULKAN_DRIVER=moltenvk` is still needed for the IOSurface features. So on a default modern Mac, the native browser texture likely needs a driver override the page never mentions. That is a sharp edge (rule 25). Fix it in code: support IOSurface import under KosmicKrisp in `MacExternalBrowserSurface.kt` / `ExternalBrowserVulkanImports.kt`, or select MoltenVK automatically when a browser surface is requested, or fail with a clear message naming the override. Then check that the page sentence is still accurate, and do not add a warning to the page.
