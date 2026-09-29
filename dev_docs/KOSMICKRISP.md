# KosmicKrisp on macOS

KosmicKrisp is LunarG's Vulkan driver layered on Metal 4, part of Mesa. It needs Apple silicon and
macOS 26 or later. Unlike MoltenVK it is a conformant driver rather than a portability subset, and
LunarG calls it "the future of Vulkan on Apple". MoltenVK stays in the SDK for Intel Macs and older
macOS. The Vulkan 1.4-conformant build ships in the SDK released 2026-09-29, the same day as the XDC
talk "KosmicKrisp production ready!" (<https://indico.freedesktop.org/event/12/contributions/538/>).

## Choosing the driver

`Options.macVulkanDriver`: set `-Dafield.four.vulkanDriver=` or `AFIELD_VULKAN_DRIVER=` to `auto`,
`moltenvk` or `kosmickrisp`. **`auto`, the default, means KosmicKrisp** wherever it can run: Apple
silicon, macOS 26 or later, and an SDK that ships it (`Options.kosmicKrispCanRun`). Elsewhere it
means MoltenVK. It was flipped on 2026-09-29, after the sweep below. The IOSurface features still
need `moltenvk`, and refuse with that instruction (`Device.requireMetalObjects`).

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

From SDK 1.4.363.0 KosmicKrisp is part of `com.lunarg.vulkan.core`. Before that it was an
optional component, `com.lunarg.vulkan.kosmic`, and naming that component to the 1.4.363.0
installer fails the whole install ("Component(s) not found"). Install without sudo or
`/usr/local` changes:

```bash
vulkansdk-macOS-<v>.app/Contents/MacOS/vulkansdk-macOS-<v> --root ~/VulkanSDK/<v> --accept-licenses --default-answer --confirm-command install com.lunarg.vulkan.core
```

(add `com.lunarg.vulkan.kosmic` for SDKs 1.4.341–1.4.357).

Without any SDK, LWJGL loads its bundled `libMoltenVK.dylib` directly, with no loader. KosmicKrisp
is then unreachable, and Field says so when it was requested.

The loader also takes validation layers from `/usr/local/share/vulkan/explicit_layer.d` ahead of
its own SDK's, which on 2026-09-29 meant a 1.4.335 layer validating a 1.4.363 driver. Field now
sets `VK_ADD_LAYER_PATH` to the SDK's `explicit_layer.d` (unless you set a layer path). The loader
then drops each system duplicate with a warning; Field logs those at info and keeps them out of boxes.

`Window` and `Instance` call `Options.doVulkanSDK()` themselves. Before that, any entrypoint that
forgot to call it got LWJGL's bundled MoltenVK with no loader, no validation and no KosmicKrisp,
whatever `AFIELD_VULKAN_DRIVER` said. Seven GPU smoke tests were in that state.

## What differs (vulkaninfo, Apple M1 Max, macOS 26.5, SDK 1.4.363.0, 2026-09-29)

| | MoltenVK 1.4.2 | KosmicKrisp (sdk-1.4.363) |
|---|---|---|
| apiVersion | 1.4.357 | 1.4.363 |
| `maxPushConstantsSize` | 4096 | **256** |
| `maxBoundDescriptorSets` | 8 | 32 |
| per-stage storage buffers / sampled images | 31 / 256 | 1048576 / 1048576 |
| timestamp query pools | ≥ 4096, any size | **32 per process, ≤ 4096 queries each** (measured, `vkCreateQueryPool` → `VK_ERROR_OUT_OF_DEVICE_MEMORY`) |
| `fillModeNonSolid`, EDS3 `polygonMode` | yes | **no** |
| `geometryShader`, `wideLines`, `depthBounds` | no | no (depthBounds on M5+) |
| `tessellationShader`, `largePoints` (1–511), `vertexPipelineStoresAndAtomics` | yes | yes |
| `VK_EXT_metal_objects` | yes | **no** |
| `VK_EXT_external_memory_metal` | — | yes |
| `VK_KHR_unified_image_layouts` | no | yes (`unifiedImageLayouts` = true) |
| `VK_KHR_portability_subset` | yes | no (not a portability driver) |
| debug printf, GPU-assisted validation | no | yes (per the SDK release notes) |

Only MoltenVK has: `VK_EXT_metal_objects`, `VK_EXT_fragment_shader_interlock`,
`VK_KHR_fragment_shader_barycentric`, `VK_GOOGLE_display_timing`, `VK_KHR_incremental_present`,
`VK_KHR_portability_subset`, a few AMD/INTEL/NV shader extensions, PVRTC. Neither has ray tracing.

LunarG's XDC 2026 advice for KosmicKrisp: record command buffers with `ONE_TIME_SUBMIT`. It encodes
Metal commands as they are recorded instead of at `vkQueueSubmit`. Also avoid things that break a
render pass there: tessellation, indirect draw count, toggling Bresenham lines, and local reads
with MSAA or depth. Field's per-frame buffers now use `ONE_TIME_SUBMIT`: each is reset,
re-recorded and submitted once per frame. `AFIELD_FOUR_ONE_TIME_SUBMIT=false` turns it off for
measurement. Its performance effect has **not** been measured; the machine was too loaded.

## The sweep (2026-09-29, SDK 1.4.363.0, M1 Max, macOS 26.5)

Validation on. Complete output of every test and every server boot kept and counted; nothing was
tailed or filtered away before counting.

- **55 GPU smoke tests** (every catalog test tagged graphics/gpu/four/renderer/fabricate/shaders/
  rectsl): **55/55 pass on each driver, 0 validation errors, 0 VUIDs** on either. The only warnings
  are the loader's duplicate-layer notices (both drivers) and MoltenVK's "Metal does not support
  disabling primitive restart". All 34 Vulkan test JVMs and all 20 server boots ran on the
  requested driver with the SDK's 1.4.363 validation layer loaded.
- **10 `afield_four_graphics.md` lesson captures**: KosmicKrisp matches the committed MoltenVK
  figures — 9 byte-identical, 1 differing in one pixel by 1/255.

What the sweep found and fixed, in order:

1. `LightingGpuTimer` spent one `VkQueryPool` per timer; the optical-flow smoke holds ~25 and hit
   KosmicKrisp's 32-pool limit. Timers now take 128-query slices of shared 4096-query pools
   (`TimestampSlabs`), and a timer that cannot get one records nothing instead of throwing.
2. The fabricate textured G-buffer material had 288 bytes of push constants. It now uses a
   `mapFlags` bitmask and the 212–223 gap, and ends at 224 bytes.
3. `LoadedTexture.stubTexture` returned one texture per renderer whatever colour was asked for,
   and never uploaded it. Stubs were sampled in `UNDEFINED` layout on **both** drivers
   (110 × VUID-vkCmdDraw-None-09600 on MoltenVK). Now there is one stub per colour and format,
   uploaded before it is returned.
4. `Device` requested EDS3 `polygonMode` without probing for it (fatal on KosmicKrisp), and
   unconditionally requested core features that KosmicKrisp before SDK 1.4.357 lacked.

## Unified image layouts (2026-09-29)

Where the device offers `unifiedImageLayouts` (KosmicKrisp does, MoltenVK does not), Field enables
`VK_KHR_unified_image_layouts` and hands Vulkan `GENERAL` for every layout except `UNDEFINED`,
`PREINITIALIZED` and `PRESENT_SRC_KHR` (`afield.four.device.ImageLayouts`, `vkLayout()`). Off with
OpenXR, whose runtimes expect specific layouts on swapchain release, and with
`AFIELD_FOUR_UNIFIED_LAYOUTS=false`.

Field's logical layouts are untouched: `Image`'s per-command-buffer tracking, the comparisons that
decide whether a barrier is needed, and every barrier, which still carries the synchronisation.
Only the value crossing into Vulkan is mapped, at the 78 calls that take one — barriers
(`image_layout_transition` and the direct `VkImageMemoryBarrier`s), descriptor and attachment infos,
and copy/blit/clear commands. A mapping that is a pure function of the logical layout keeps both
ends of every transition in agreement, which a partial hand conversion would not.

Sweep, same 55 GPU smoke tests, complete output counted: **55/55 on the default (KosmicKrisp) path
with unified layouts on in all 37 Vulkan devices created, 0 validation errors, 0 VUIDs, 0 warnings.**
MoltenVK: 55/55, 0 errors, 0 VUIDs, mapping inactive. Performance effect not measured.

## Still to do

1. **IOSurface interop.** `Image.exportMetalIOSurfaceRef`, the IOSurface-backed RTT
   (`RenderToTexture`, `FieldRTTStreamer`, `field-encode`) and `MacExternalBrowserSurface` need
   `VK_EXT_metal_objects`. On KosmicKrisp they must move to `VK_EXT_external_memory_metal`, or stay
   MoltenVK-only. None of them is in the smoke catalog, so the sweep does not cover them.
2. **Wireframe.** No `fillModeNonSolid`: `fillMode = LINE` needs a fallback, or at least an error
   that names the missing feature.
3. **Unified layouts on NVIDIA.** Recent NVIDIA drivers offer the extension too, but it is on by
   default only on macOS. Linux/Windows opt in with `AFIELD_FOUR_UNIFIED_LAYOUTS=true` until a sweep
   there covers the NVENC/CUDA, dma-buf and D3D11 import paths.
4. **Mac-only gates to re-key on the driver.** Debug labels are off on every Mac (`VulkanNames`),
   but KosmicKrisp's `VK_EXT_debug_utils` now reaches Metal objects. Multiview stereo is disabled
   on every Mac (`SideBySideStereoTarget`).
5. **HDR/EDR** (`MacHdrActivator`) is unverified on KosmicKrisp.
6. **Performance**: MoltenVK against KosmicKrisp, and `ONE_TIME_SUBMIT` on against off, on a quiet
   machine.
