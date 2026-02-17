# Vulkan Resource State Tracking

## Overview

Field4 now includes a centralized **ResourceStateTracker** system to manage Vulkan image and buffer states. This system addresses validation issues caused by incorrect image layout assumptions and provides a single source of truth for resource states across the entire rendering pipeline.

## Problem Statement

Previously, image layout tracking was scattered across the codebase:
- `Image.kt`: Per-command-buffer tracking using `WeakHashMap` (best-effort only)
- `RenderToTexture.kt`: Manual `colorLayout` and `depthLayout` arrays
- `PingPongRenderToTexture.kt`: Additional `pingLayouts` array
- Various texture classes: Each with its own `recordImageTransition` methods

This led to:
1. **Validation errors** when layouts didn't match expectations
2. **Difficult debugging** with no centralized state visibility
3. **Code duplication** of transition logic
4. **Maintenance burden** keeping multiple trackers in sync

## Solution: ResourceStateTracker

The `ResourceStateTracker` class provides:
- **Centralized state storage** for all images and buffers
- **Thread-safe** access via `ConcurrentHashMap`
- **Automatic state updates** when transitions occur
- **Transition history** for debugging (last 10 transitions per image)
- **Validation methods** to verify expected states
- **Debug logging** when validation layers are enabled

## Architecture

```
Renderer
  └── ResourceStateTracker (single instance)
        ├── imageStates: Map<VkImage, ImageState>
        │     └── layout, accessMask, stageMask, history
        └── bufferStates: Map<VkBuffer, BufferState>
              └── accessMask, stageMask
```

Every `Renderer` has one `ResourceStateTracker` instance that tracks all resources used by that renderer.

## Usage

### Basic Image Transition

Old way (still works):
```kotlin
image_layout_transition(
    commandBuffer,
    image,
    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
    VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
    VK_ACCESS_2_SHADER_READ_BIT,
    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,  // Must know old layout
    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    range
)
```

New way with tracking:
```kotlin
image_layout_transition(
    commandBuffer,
    image,
    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
    VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
    VK_ACCESS_2_SHADER_READ_BIT,
    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    range,
    renderer.stateTracker  // Automatically updates tracker
)
```

### Automatic Old Layout Detection

When you don't know the old layout:
```kotlin
image_layout_transition_auto(
    commandBuffer,
    image,
    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
    VK_ACCESS_2_SHADER_READ_BIT,
    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    range,
    renderer.stateTracker  // Looks up current state automatically
)
```

### Initializing Resources

When creating images:
```kotlin
val image = Image(renderer.device, imageData)
renderer.stateTracker.initImageState(image.vkImage, VK_IMAGE_LAYOUT_UNDEFINED)
```

For swapchain and depth images, this is done automatically in `Renderer.createWindowContext()` and `Renderer.createDepthImages()`.

### Querying State

```kotlin
val currentLayout = renderer.stateTracker.getImageLayout(image.vkImage)
val accessMask = renderer.stateTracker.getImageAccess(image.vkImage)
val stageMask = renderer.stateTracker.getImageStage(image.vkImage)
```

### Validation

Verify an image is in the expected layout:
```kotlin
renderer.stateTracker.verifyImageLayout(
    image.vkImage,
    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    "before sampling in fragment shader"
)
// Throws if layout doesn't match, with transition history
```

### Debugging

Get diagnostics:
```kotlin
println(renderer.stateTracker.getDiagnostics())
// Output:
// ResourceStateTracker Diagnostics:
//   Images tracked: 12
//   Buffers tracked: 5
//   Image states:
//     0x12345678: SHADER_READ_ONLY_OPTIMAL
//     0x23456789: COLOR_ATTACHMENT_OPTIMAL
//     ...
```

Enable debug logging:
```kotlin
// Automatically enabled when Options.validate = true
val tracker = ResourceStateTracker(debug = true)
// Logs every transition:
// ResourceStateTracker: Image 0x12345678 layout UNDEFINED -> COLOR_ATTACHMENT_OPTIMAL
```

## Integration Points

### Core Classes Updated

1. **Renderer.kt**
   - Added `val stateTracker: ResourceStateTracker`
   - Initializes swapchain images
   - Initializes depth attachments
   - Reinitializes on resize

2. **Image.kt**
   - `image_layout_transition()` accepts optional `stateTracker`
   - New `image_layout_transition_auto()` for automatic detection
   - Cleanup unregisters from tracker

3. **RenderTarget.kt**
   - Uses tracker to infer swapchain layouts
   - All transitions pass tracker parameter

4. **RenderToTexture.kt**
   - Deprecated manual `colorLayout`/`depthLayout` arrays
   - Helper methods bridge tracker ↔ arrays
   - All transitions use tracker
   - Images initialized in tracker

5. **PingPongRenderToTexture.kt**
   - Deprecated `pingLayouts` array
   - Syncs with tracker in `beforeRender`/`afterRender`
   - Both ping-pong images tracked

## Backward Compatibility

The system is **fully backward compatible**:
- Old code without tracker parameter still works
- Manual layout arrays remain but are deprecated
- Transition happens gradually as code is updated

## Best Practices

### DO:
✅ Pass `renderer.stateTracker` to all image transitions  
✅ Initialize images in tracker immediately after creation  
✅ Use `verifyImageLayout()` before critical operations  
✅ Use debug mode during development  
✅ Check diagnostics when debugging validation errors  

### DON'T:
❌ Manually modify tracker state (except via init/update methods)  
❌ Assume layout without checking tracker  
❌ Skip tracker parameter in new code  
❌ Forget to initialize newly created images  

## Migration Guide

### Updating Existing Code

1. **Find manual layout tracking**:
   ```kotlin
   // Old
   var myLayout = VK_IMAGE_LAYOUT_UNDEFINED
   ```

2. **Replace with tracker queries**:
   ```kotlin
   // New
   val myLayout = renderer.stateTracker.getImageLayout(myImage.vkImage)
   ```

3. **Update transitions**:
   ```kotlin
   // Add stateTracker parameter
   image_layout_transition(..., renderer.stateTracker)
   ```

4. **Initialize new images**:
   ```kotlin
   val image = Image(...)
   renderer.stateTracker.initImageState(image)
   ```

### New Code

Always use the tracker from the start:
```kotlin
// Create
val image = Image(renderer.device, imageData)
renderer.stateTracker.initImageState(image)

// Transition
image_layout_transition(..., renderer.stateTracker)

// Query when needed
val layout = renderer.stateTracker.getImageLayout(image.vkImage)

// Cleanup (automatic)
image.cleanup()  // Unregisters from tracker
```

## Performance Considerations

- **Overhead**: Minimal - `ConcurrentHashMap` lookups are O(1)
- **Memory**: ~100 bytes per tracked image (state + history)
- **Thread Safety**: Lock-free reads, synchronized writes
- **Debug Mode**: Adds println overhead - use only in development

## Future Improvements

Potential enhancements:
1. **Buffer state tracking refinement** (currently basic)
2. **Automatic barrier generation** from state tracker
3. **State visualization tools** for debugging
4. **Per-command-buffer state snapshots** for parallel recording
5. **Validation layer integration** for automatic checks

## Troubleshooting

### Validation Error: Wrong Layout

1. Enable debug logging:
   ```kotlin
   val tracker = ResourceStateTracker(debug = true)
   ```

2. Check transition history:
   ```kotlin
   renderer.stateTracker.imageStates[imageHandle]?.history?.forEach {
       println("${layoutName(it.oldLayout)} -> ${layoutName(it.newLayout)}")
   }
   ```

3. Add verification:
   ```kotlin
   renderer.stateTracker.verifyImageLayout(
       image,
       expectedLayout,
       "descriptive context"
   )
   ```

### Image Not Tracked

If `getImageLayout()` returns `UNDEFINED` unexpectedly:
1. Check if image was initialized: `initImageState()`
2. Verify transitions pass `stateTracker` parameter
3. Check if image was recreated (needs re-init)

### State Mismatch

If tracker state doesn't match actual Vulkan state:
1. Ensure ALL transitions go through tracker-aware functions
2. Check for external modifications (e.g., direct Vulkan calls)
3. Verify no parallel command buffers modify same image without tracking

## Examples

### Complete Render-to-Texture Example

```kotlin
// Setup
val rtt = RenderToTexture(renderer) {
    width = 1024
    height = 1024
    colorSampled = true
}
// Images automatically initialized in tracker

// Render
rtt.render(commandBuffer, imageIndex)
// Transitions automatically tracked

// Use result
val layout = renderer.stateTracker.getImageLayout(rtt.colorImageHandles[0])
// Query current state as needed
```

### Custom Texture Loading

```kotlin
// Create image
val image = Image(renderer.device, imageData)
renderer.stateTracker.initImageState(image, VK_IMAGE_LAYOUT_UNDEFINED)

// Transition to transfer dst
image_layout_transition(
    cmd,
    image,
    VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
    VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
    0L,
    VK_ACCESS_2_TRANSFER_WRITE_BIT,
    VK_IMAGE_LAYOUT_UNDEFINED,
    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
    range,
    renderer.stateTracker
)

// Copy data...

// Transition to shader read
image_layout_transition(
    cmd,
    image,
    VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
    VK_ACCESS_2_TRANSFER_WRITE_BIT,
    VK_ACCESS_2_SHADER_READ_BIT,
    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    range,
    renderer.stateTracker
)

// Verify before use
renderer.stateTracker.verifyImageLayout(
    image,
    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    "texture sampling"
)
```

## Summary

The `ResourceStateTracker` provides a robust, thread-safe solution to Vulkan resource state management. By centralizing state tracking, it:
- Eliminates scattered tracking code
- Prevents validation errors from incorrect assumptions
- Provides powerful debugging capabilities
- Maintains backward compatibility
- Scales efficiently with resource count

Use it in all new code and gradually migrate existing code for a more reliable rendering pipeline.
