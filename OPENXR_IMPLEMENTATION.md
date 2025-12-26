# OpenXR Implementation for Field4

This document describes the OpenXR rendering support added to Field4.

## Overview

OpenXR rendering support has been implemented alongside the existing OpenVR support. The implementation follows the same patterns as OpenVRRenderTarget to ensure consistency and ease of use.

## Files Modified/Added

### Added Files
- `src/jvmMain/kotlin/afield/four/targets/OpenXRTargets.kt` - Complete OpenXR render target implementation

### Modified Files
- `build.gradle.kts` - Added OpenXR dependencies
- `src/jvmMain/kotlin/afield/four/device/Instance.kt` - Added OpenXR extension helpers

## Dependencies

OpenXR dependencies have been added to `build.gradle.kts`:
```kotlin
implementation("org.lwjgl:lwjgl-openxr:$lwjglVersion")
if (!lwjglNatives.contains("macos")) implementation("org.lwjgl:lwjgl-openxr::${lwjglNatives}")
```

Platform support matches OpenVR (Windows and Linux, with macOS native library excluded).

## Key Components

### OpenXRRenderTarget
The main render target class that manages the OpenXR rendering lifecycle:
- Creates OpenXR instance, system, and session
- Sets up Vulkan graphics binding
- Manages per-eye swapchains
- Handles frame synchronization (xrWaitFrame/xrBeginFrame/xrEndFrame)
- Tracks HMD and view poses
- Provides view/projection matrices via `leftMVP()` and `rightMVP()`

### OpenXRSwapchainWrapper
Internal class that wraps OpenXR swapchain images:
- Enumerates swapchain images provided by the OpenXR runtime
- Creates ImageView wrappers for each swapchain image
- Manages image acquisition and release
- Handles image layout transitions
- Provides rendering entry points via `beginRendering()` and `endRendering()`

### Extension Helpers (Instance.kt)
- `isOpenXRAvailable()` - Checks if OpenXR runtime is available
- `getOpenXRRequiredInstanceExts()` - Returns required Vulkan instance extensions
- `getOpenXRRequiredDeviceExts()` - Returns required Vulkan device extensions

## Usage Example

```kotlin
// Create OpenXR render target
val xrTarget = OpenXRRenderTarget(renderer) {
    format = VK_FORMAT_B8G8R8A8_SRGB
    clearColor = vec(0.1, 0.1, 0.1, 1.0)
}

// Add modules to render
xrTarget += "myScene" to myModule

// Access view/projection matrices for shader uniforms
val (leftView, leftProj) = xrTarget.leftMVP(zNear = 0.01f, zFar = 1000f)
val (rightView, rightProj) = xrTarget.rightMVP(zNear = 0.01f, zFar = 1000f)
```

## Features

### Frame Loop Integration
OpenXRRenderTarget integrates with Field4's render loop via hooks:
- `renderer.renderStart["__openxr__"]` - Calls `xrWaitFrame()` and `xrBeginFrame()`
- `renderer.renderEnd["__openxr__"]` - Calls `xrEndFrame()` with composition layers

### Session State Management
Automatically handles OpenXR session state transitions:
- `XR_SESSION_STATE_READY` - Begins the session
- `XR_SESSION_STATE_STOPPING` - Ends the session
- `XR_SESSION_STATE_EXITING` / `XR_SESSION_STATE_LOSS_PENDING` - Stops rendering

### View Tracking
- Uses `xrLocateViews()` to get current HMD pose and eye positions
- Updates view and projection matrices each frame
- Supports configurable near/far clipping planes

### Recommended Resolution
Queries the OpenXR runtime for recommended render target size via `xrEnumerateViewConfigurationViews()`.

## Architecture Decisions

### OpenXR vs OpenVR
OpenXR is created as a separate render target rather than replacing OpenVR:
- Both can coexist in the codebase
- OpenVR code remains completely untouched
- Users can choose which VR backend to use

### Extension Discovery
OpenXR Vulkan extensions are queried within `OpenXRRenderTarget` rather than at `Instance` creation:
- OpenXR instance is needed to query extensions (chicken-egg problem)
- Extensions are printed to stderr for debugging
- Device extensions are checked during session creation

### Swapchain Image Management
OpenXR manages VkImage lifecycle, but Field4 creates VkImageView:
- OpenXR runtime provides VkImage handles via `xrEnumerateSwapchainImages()`
- Field4 creates `ImageView` objects wrapping these handles
- Standard Field4 rendering pipeline works with wrapped images

## Compatibility

### OpenVR Compatibility
- All OpenVR code remains intact
- OpenVR dependencies unchanged
- No breaking changes to existing APIs

### Platform Support
- Windows: Full support
- Linux: Full support  
- macOS: Core library only (no native bindings, consistent with OpenVR)

## Requirements

- OpenXR runtime installed and running (e.g., SteamVR with OpenXR support, Oculus runtime, etc.)
- OpenXR-compatible HMD
- Vulkan-capable GPU
- Java 24 toolchain for building

## Limitations

- Currently targets stereo rendering only (PRIMARY_STEREO view configuration)
- Uses LOCAL reference space (standing/seated experience)
- No controller input support yet (can be added similar to OpenVR's ControllerButtonEvents)
- No hand tracking support yet

## Future Enhancements

Potential areas for expansion:
1. Controller input handling (actions and poses)
2. Hand tracking support
3. Additional reference space types (STAGE, VIEW)
4. Quad/cylinder layer support for UI
5. Depth layer submission for better reprojection
6. Foveated rendering support (if available)

## Testing

Testing requires:
1. OpenXR runtime to be installed and functional
2. Compatible VR HMD connected
3. Java 24 toolchain configured
4. Build with: `./gradlew build`
5. Run application that creates an `OpenXRRenderTarget`

## References

- [OpenXR Specification](https://www.khronos.org/registry/OpenXR/)
- [LWJGL OpenXR Bindings](https://www.lwjgl.org/guide#openxr)
- [OpenXR Vulkan Graphics Binding](https://registry.khronos.org/OpenXR/specs/1.0/html/xrspec.html#XR_KHR_vulkan_enable)
