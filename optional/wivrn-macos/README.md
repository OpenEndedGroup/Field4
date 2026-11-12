# WiVRn for macOS on KosmicKrisp

`kosmickrisp-iosurface-copy-path.patch` lets the
[WiVRn for macOS](https://github.com/orchardxr/wivrn-macos) OpenXR runtime run in an app whose
Vulkan driver is KosmicKrisp. The released runtime shares its swapchain images with the app by
wrapping IOSurfaces (`VK_EXT_metal_objects`), which only MoltenVK has, so without the patch Field
must use MoltenVK in VR (`field_vr_ide.md`, "macOS through WiVRn").

With the patch, a driver without `VK_EXT_metal_objects` gets app-local swapchain images, and each
image's release copies it into the service's IOSurface through a buffer over the IOSurface's own
memory (`VK_EXT_external_memory_host`). On Apple silicon that memory is unified, so this is one GPU
copy per eye per frame with no CPU copy; the client's existing CPU wait before each commit orders
the copy before the service reads. MoltenVK, when the runtime creates the device, keeps the
original zero-copy path.

It patches the port's Monado tree, after the port's own `patches/monado` series, at the revisions
in `pin.json`. Only the runtime client (`libopenxr_wivrn.dylib`) changes: the Mach service name
is derived from the socket path, so a patched client connects to the released `WiVRn.app` server.

Build it, and use it, with the launcher:

```sh
uv run python tools/field_wivrn_macos.py --build-kk-runtime
uv run python tools/field_wivrn_macos.py --kosmickrisp -- /path/to/workspace -d Field-VR-IDE
```

`--build-kk-runtime` clones the pinned port into `~/.field4/wivrn-macos/`, applies its patch series
and this patch, builds only the `openxr_wivrn` target with the port's
`scripts/build-wivrn.sh --client-arm64`, and installs the library and a runtime manifest in
`.afield-local/wivrn-kk/`. It needs the port's Homebrew build dependencies (its `Brewfile`, minus
the dashboard's Qt and KDE packages), Xcode, and the LunarG Vulkan SDK in `~/VulkanSDK`.

Tested on-headset on 12 November 2026: M1 Max, macOS 26.5, Vulkan SDK 1.4.363, Quest 3, the VR
IDE at 88 frames per second with stream scale 0.5 and 76 at 1.0, with latency matching MoltenVK.

To change the patch, work in the port checkout that `--build-kk-runtime` leaves in
`~/.field4/wivrn-macos/src`. `scripts/patches.sh apply` stages the port's own series, so record
that tree before editing, then diff against it:

```sh
cd ~/.field4/wivrn-macos/src
scripts/patches.sh apply                # resets third_party/monado: commit or save edits first
base=$(git -C third_party/monado write-tree)
git -C third_party/monado apply /path/to/Field4/optional/wivrn-macos/kosmickrisp-iosurface-copy-path.patch
# ... edit third_party/monado ...
git -C third_party/monado add -A src
git -C third_party/monado diff --cached "$base" -- src \
  > /path/to/Field4/optional/wivrn-macos/kosmickrisp-iosurface-copy-path.patch
```
