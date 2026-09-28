# KosmicKrisp on macOS

KosmicKrisp is LunarG's Vulkan driver layered on Metal 4, part of Mesa. It needs Apple silicon and
macOS 26 or later. Unlike MoltenVK it is a conformant driver rather than a portability subset, and
LunarG calls it "the future of Vulkan on Apple". MoltenVK stays in the SDK for Intel Macs and older
macOS. The Vulkan 1.4-conformant build ships in the SDK released 2026-09-29, the same day as the XDC
talk "KosmicKrisp production ready!" (<https://indico.freedesktop.org/event/12/contributions/538/>).

## Choosing the driver

`Options.macVulkanDriver`: set `-Dafield.four.vulkanDriver=` or `AFIELD_VULKAN_DRIVER=` to `auto`,
`moltenvk` or `kosmickrisp`. **`auto` means MoltenVK** until the sweep below comes back clean;
flipping it is one line in `Options.resolvedMacVulkanDriver`.

`Options.doVulkanSDK()`:

- takes the loader from `VULKAN_SDK`, or else from the newest `~/VulkanSDK/<version>/macOS` (or from
  `AFIELD_VULKAN_LIBRARY`). The loader path used to be hard-coded to 1.4.335.1;
- `setenv`s `VK_DRIVER_FILES` to that SDK's manifest for the chosen driver, unless `VK_DRIVER_FILES`
  or `VK_ICD_FILENAMES` is already set.

The pin matters because the SDK loader does not search its own `share/vulkan/icd.d`. It finds
`/usr/local/share/vulkan/icd.d` (last system-global SDK install) and `~/.local/share/vulkan/icd.d`
(a hand-built Mesa KosmicKrisp from Nov 2025 on Marc's machine). Unpinned, that enumerated three
devices — MoltenVK, KosmicKrisp, KosmicKrisp — and Field took the first. It got MoltenVK because of
the loader's ordering, not because anyone chose it.

`PhysicalDevice` now records `driverId`/`driverName`/`driverInfo` (`isMoltenVK`, `isKosmicKrisp`),
prefers the requested driver when several are enumerated, and prints the one it picked.

The KosmicKrisp component is optional in the SDK installer (`com.lunarg.vulkan.kosmic`). Install
without sudo or `/usr/local` changes:

```bash
vulkansdk-macOS-<v>.app/Contents/MacOS/vulkansdk-macOS-<v> --root ~/VulkanSDK/<v> --accept-licenses --default-answer --confirm-command install com.lunarg.vulkan.core com.lunarg.vulkan.kosmic
```

Without any SDK, LWJGL loads its bundled `libMoltenVK.dylib` directly, with no loader. KosmicKrisp
is then unreachable, and Field says so when it was requested.

## What differs (vulkaninfo, Apple M1 Max, macOS 26.5, SDK 1.4.357.1, 2026-09-27)

| | MoltenVK 1.4.2 | KosmicKrisp (sdk-1.4.357.1) |
|---|---|---|
| apiVersion | 1.4.357 | 1.4.359 |
| `maxPushConstantsSize` | 4096 | **256** (was 128 in 1.4.335) |
| `maxBoundDescriptorSets` | 8 | 32 |
| per-stage storage buffers / sampled images | 31 / 256 | 1048576 / 1048576 |
| `fillModeNonSolid` | yes | **no** (wireframe `fillMode` pipelines will fail) |
| EDS3 `polygonMode` | yes | **no** (Device now probes EDS3; it used to request it blindly → `vkCreateDevice` failure) |
| `geometryShader`, `wideLines` | no | no |
| `tessellationShader` | yes | yes (new in 1.4.357) |
| `largePoints` / `pointSizeRange` | yes | yes, 1–511 (was 1–1 in 1.4.335) |
| `vertexPipelineStoresAndAtomics` | yes | yes (was **no** in 1.4.335 → Device now degrades with a warning) |
| `VK_EXT_metal_objects` | yes | **no** |
| `VK_EXT_external_memory_metal` | — | yes |
| `VK_KHR_portability_subset` | yes | no (not a portability driver) |
| present_id/present_wait (1 & 2), maintenance5–10, robustness2, calibrated timestamps | yes | yes (new in 1.4.357) |

Extensions only MoltenVK has: `VK_EXT_metal_objects`, `VK_EXT_fragment_shader_interlock`,
`VK_KHR_fragment_shader_barycentric`, `VK_KHR_deferred_host_operations`, `VK_GOOGLE_display_timing`,
`VK_KHR_incremental_present`, `VK_KHR_portability_subset`, a few AMD/INTEL/NV shader extensions and
PVRTC. Neither driver offers ray tracing.

## Known work before KosmicKrisp can be the default

1. **IOSurface interop.** `Image.exportMetalIOSurfaceRef`, the IOSurface-backed RTT
   (`RenderToTexture`, `FieldRTTStreamer`, `field-encode`) and `MacExternalBrowserSurface` are all
   built on `VK_EXT_metal_objects`. On KosmicKrisp they have to be ported to
   `VK_EXT_external_memory_metal`: we create the IOSurface-backed `MTLTexture` ourselves and import
   it. `MacExternalBrowserSurface` already `require`s `supportsMetalObjects`. The RTT export path
   has not been checked for a clean failure.
2. **Push constants: 256 bytes, against MoltenVK's 4096.** `GpuSkinning` documents 228 bytes, and
   anything larger that has only ever run on MoltenVK will fail at pipeline creation. Sizes come
   from shader reflection, so only running the canonical lessons will tell.
3. **Wireframe.** No `fillModeNonSolid`: `fillMode = LINE` needs a fallback, or at least an error
   that names the missing feature.
4. **MoltenVK-specific workarounds** (`VulkanBuffer` null-buffer realloc, `Module` pushed-handle
   deref, `RenderToTexture` broad barriers, `SideBySideStereoTarget` multiview) are keyed on
   `OS == MACOS`. Where the bug is MoltenVK's, re-key them on `PhysicalDevice.isMoltenVK`.
5. **HDR/EDR.** `MacHdrActivator` reaches the `CAMetalLayer` behind the GLFW surface. It is
   unverified on KosmicKrisp.

## Not yet done

- None of the above has been run in a live Field on either driver. It compiles, and the tables come
  from `vulkaninfo`.
- The sweep: `AFIELD_VULKAN_DRIVER=kosmickrisp` over the `afield.four` smoke catalog and the
  canonical graphics lessons (`public/docs/assets/canonical_graphics/LESSONS.md`), then the same
  under `moltenvk` for timings on the same machine.
- Re-profile on the 2026-09-29 SDK.
