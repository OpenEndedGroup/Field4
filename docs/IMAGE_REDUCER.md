# ImageReducer Utility

## Overview

`ImageReducer` is a utility class for efficiently computing statistical reductions (min, average, max) of images using Vulkan compute shaders. It processes images that implement the `HasImageView` interface and returns per-channel statistics.

## Features

- **Two-pass reduction algorithm**: Optimized for parallel GPU execution
- **Automatic workgroup sizing**: Calculates optimal workgroup counts based on image dimensions
- **Asynchronous readback**: Results can be read after GPU execution completes
- **RGBA support**: Computes min, avg, and max for all four channels
- **Clean API**: Simple interface for integration into rendering pipelines

## Architecture

The reduction is performed in two compute shader passes:

### First Pass (Local Reduction)
- Each workgroup processes a tile of the image (16x16 pixels by default)
- Uses shared memory for efficient within-workgroup reduction
- Outputs min/avg/max per workgroup to intermediate buffer

### Second Pass (Global Reduction)
- Single workgroup reduces all intermediate results
- Computes final min/avg/max across entire image
- Results are written to a small host-visible buffer

## Usage

### Quick Start - One-Shot Execution

The simplest way to use ImageReducer is with the `reduceNow()` method:

```kotlin
import afield.four.Renderer
import afield.four.util.ImageReducer
import afield.four.texture.HasImageView

// Assuming you have a renderer and an image that implements HasImageView
val imageView: HasImageView = ... // your image
val renderer: Renderer = ... // your renderer

// Create the reducer and execute once
val reducer = ImageReducer(renderer, imageView)
val results = reducer.reduceNow()

println("Min: ${results.min.contentToString()}")
println("Avg: ${results.avg.contentToString()}")
println("Max: ${results.max.contentToString()}")

reducer.cleanup()
```

### Per-Frame Execution - Install into RenderTarget

For continuous per-frame statistics, install the reducer into a RenderTarget:

```kotlin
import afield.four.targets.RenderTarget

val reducer = ImageReducer(renderer, imageView)
val renderTarget = RenderTarget(renderer)

// Install into the render target
reducer.installInto(renderTarget, "imageStats")

// In your render loop:
renderer.render()  // Reduction happens automatically
val results = reducer.readResults()  // Read results after frame
```

### Advanced - Manual Command Buffer Control

For fine-grained control over execution timing:

```kotlin
import afield.four.device.CommandBuffer
import afield.four.device.Fence

val reducer = ImageReducer(renderer, imageView)

// Execute reduction in a command buffer
val cmd = CommandBuffer(renderer.commandPool, true, true)
cmd.beginRecording()
reducer.reduce(cmd)
cmd.endRecording()

// Submit and wait
val fence = Fence(renderer.device, true)
fence.reset()
renderer.graphQueue.submit(stackPointers(cmd.vkCommandBuffer), null, null, null, fence)
fence.fenceWait()

// Read results
val results = reducer.readResults()

// Cleanup
fence.cleanup()
cmd.cleanup()
reducer.cleanup()
```

### Integration with Render Loop

```kotlin
class MyRenderer {
    val reducer = ImageReducer(renderer, myImageView)
    
    fun render() {
        // ... your rendering code ...
        
        // Perform reduction every N frames
        if (frameCount % 60 == 0) {
            reducer.reduce(commandBuffer)
            
            // On next frame (after fence), read results
            if (frameCount % 60 == 1) {
                val stats = reducer.readResults()
                updateImageStatistics(stats)
            }
        }
    }
}
```

## Performance Characteristics

### Workgroup Configuration
- Default local workgroup size: 16x16 (256 threads)
- Second pass workgroup size: 256 threads
- Both values are optimized for modern GPUs

### Memory Usage
- Intermediate buffer: `ceil(width/16) * ceil(height/16) * 12 * 4` bytes
- Result buffer: `12 * 4 = 48` bytes (fixed)
- For 1920x1080 image: ~216 KB intermediate + 48 B result

### Performance
- Typical reduction time: <1ms for 1920x1080 images on modern GPUs
- Scales well with image size due to parallel nature
- Memory barriers ensure correctness without significant overhead

## API Reference

### ImageReducer

```kotlin
class ImageReducer(
    val renderer: Renderer,
    private val imageView: HasImageView
)
```

#### Methods

##### `reduceNow(): ReductionResult`
**Convenience method for one-shot execution.**
- Creates command buffer, executes reduction, waits for completion
- Returns results synchronously
- Handles all synchronization automatically
- Use when you need results immediately

##### `installInto(target: RenderTarget, name: String = "imageReducer")`
**Install into a RenderTarget for per-frame execution.**
- Adds reduction to the render target's `others` map
- Executes automatically every frame
- Results can be read asynchronously with `readResults()`
- Use for continuous monitoring

##### `reduce(commandBuffer: CommandBuffer)`
Executes the reduction compute shaders on the GPU.
- **Must be called within command buffer recording**
- Automatically handles memory barriers between passes
- Safe to call multiple times if image dimensions haven't changed

##### `readResults(): ReductionResult`
Reads back the computed statistics from GPU memory.
- **Should be called after GPU execution completes** (e.g., after fence wait)
- Returns a `ReductionResult` containing min, avg, and max values

##### `cleanup()`
Releases GPU resources (buffers and compute modules).
- Should be called when reducer is no longer needed
- After cleanup, the reducer cannot be used again

### ReductionResult

```kotlin
data class ReductionResult(
    val min: FloatArray,  // RGBA minimum values [r, g, b, a]
    val avg: FloatArray,  // RGBA average values [r, g, b, a]
    val max: FloatArray   // RGBA maximum values [r, g, b, a]
)
```

## Implementation Details

### Shader Stages

The implementation uses two GLSL compute shaders:

1. **First Pass Shader** (`ImageReducer_FirstPass`)
   - Input: Storage image (rgba8 format)
   - Output: Intermediate buffer with per-workgroup results
   - Uses shared memory for efficient local reduction

2. **Second Pass Shader** (`ImageReducer_SecondPass`)
   - Input: Intermediate buffer from first pass
   - Output: Final result buffer with min/avg/max
   - Single workgroup reduces all intermediate results

### Synchronization

The class automatically inserts appropriate memory barriers:
- **Between passes**: Compute-to-compute barrier on intermediate buffer
- **Before readback**: Compute-to-host barrier on result buffer

### Image Format Support

Currently optimized for `rgba8` format images. Other formats may work but are untested. The shaders use `imageLoad` which should work with most color formats.

## Error Handling

The class will throw exceptions if:
- ImageView doesn't have an underlying Image
- Buffers fail to allocate
- Compute shader compilation fails
- Results are read before GPU execution completes

## Thread Safety

- Not thread-safe
- Should only be used from the render thread
- Multiple ImageReducer instances can exist for different images

## Future Improvements

Potential enhancements:
- Support for other image formats (R32F, RGBA32F, etc.)
- Configurable workgroup sizes
- Optional histogram computation
- Partial image reduction (region of interest)
- Mipmap level selection

## See Also

- `ComputeModule` - Base class for compute shader execution
- `HasImageView` - Interface for images in the rendering system
- `VulkanBuffer` - GPU buffer management
