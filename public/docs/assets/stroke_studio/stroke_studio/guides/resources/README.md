# Ambient resources

Connect a new box beneath **Stroke studio**. Field publishes these typed Kotlin properties along the graph. The textures are writable by their producer. Sample them from a separate pass and run that pass after the producer. **Read the layers** is a runnable GPU readback example.

| Property | Type and contents |
| --- | --- |
| `renderer`, `window` | Inherited renderer and desktop window |
| `strokeResolution` | `StrokeResolution`, analysis and paint dimensions |
| `strokeFrame` | `AtomicReference<StrokeFrame?>`, latest camera eye and FOV tangent, scene time, frame delta and dimensions |
| `strokeClear` | `AtomicBoolean`. Set to true to clear the stroke pool on the next frame. |
| `strokeSettings` | `AtomicReference<StrokeSettings>`, shared typed settings |
| `strokeGBuffer` | Analysis-size target. Attachments 0 lit RGBA16F, 1 current world RGBA32F, 2 previous world RGBA32F |
| `strokeLitColor`, `strokeWorld`, `strokePreviousWorld` | `HasImageView` aliases for those attachments. World xyz is in scene units and w carries the surface ID |
| `strokeVelocity` | RGBA16F. XY projected velocity in analysis px/s, z confidence, w speed |
| `strokePaint` | Full-resolution RGBA16F attachments. 0 signed paint RGB with accumulated alpha, 1 drawing/tracking/interrupted-release coverage and eraser coverage |
| `strokePaintColor`, `strokePaintPhases` | `HasImageView` aliases for those paint attachments |
| `strokeEdgeInput` | Analysis-size RGBA16F. 0 mixed RGB for analysis, 1 resolved raw paint and accumulated alpha |
| `strokeEdgeColor`, `strokeFeedback` | `HasImageView` aliases for the analysis-sized inputs |
| `strokeBlur`, `strokeTensor`, `strokeSoftTensor` | Gaussian prefilter, RGB Sobel tensor, smoothed tensor. Tensor RGB stores dot(gx,gx), dot(gy,gy), dot(gx,gy) |
| `strokeDirection` | RGBA16F. XY is strength-weighted double-angle tangent, z strength, w contrast |
| `strokeSummary` | `Map<String, RenderToTexture>` keyed by `stroke-summary-<plane>-<depth>`. Each target is RGBA32F |
| `strokeSummaryValue`, `strokeSummaryExtent`, `strokeSummaryMoment` | Summary targets at the current authored probe depth |
| `strokeMediumPaint`, `strokeBroadPaint` | Gaussian-resolved signed RGBA16F sums, half and quarter paint resolution |
| `strokeComposite` | Full-resolution RGBA8 sRGB display result |
| `strokeCapacity`, `strokeSlots` | Maximum slot count and GPU `FrameBufferGroup`. The 224-byte std430 layout is in `strokes.glsl` |
| `strokeCalibration` | GPU Field Stats result used by birth, four floats for low, high, gate and sample count |

Read or update settings from Kotlin:

```kotlin
val dimensions = strokeResolution
val frameNow = strokeFrame.get()
strokeSettings.updateAndGet {
    it.copy(velocityMix = 0.85f, varianceTarget = 0.7f, varianceThickness = 0.45f)
}
println("Analysis ${dimensions.analysisWidth}×${dimensions.analysisHeight} at ${frameNow?.time}")
```

To use an inherited view under a shader sampler called `sourceLayer`, pass it through the normal Field texture resolver:

```kotlin
val debug = moduleHere {
    textureResolver = { texture ->
        if (texture.name == "sourceLayer") fromViews(listOf(strokePaintColor), texture.name) else null
    }
}
```

The `moduleHere` example assumes the diagnostic box has vertex and fragment code already, with a sampler named `sourceLayer`. For a simple CPU export, `strokeComposite.readPixelsAsync()` returns packed row-major RGBA8 bytes from the actual GPU image. Use `strokePaint.readPixelsAsync(0)` to export signed RGBA16F instead.

## Frame order and ownership

1. Draw lit and paired world positions. Project both positions through the current camera for the velocity field.
2. Update active world-space knots and their lifecycle. Clear on request.
3. Compact active slots in the task shader and draw exact-length meshes into signed paint.
4. Resolve paint onto the analysis grid. Mix it with the lit scene, construct the edge tensor and build the pooled quadtree. The Field Stats sketch samples the field every twelfth frame.
5. Integrate births using those textures and the residual of target minus existing paint. Newly born marks draw on the next frame.
6. Blur the paint, apply the light-to-paint composite and present.

The quadtree resource API originates in the existing [summary-pyramid starting place](https://github.com/OpenEndedGroup/Field4/blob/main/public/docs/assets/agent_transcripts/four_summary_pyramid/sp_summary_code_.kt). Its shaders are supplied by `FabricatePassLibrary.summaryPyramid`. The studio adapts their sampler names and targets into this frame graph.
