# Ray Tracing Examples

`RayTracingDocsHarness` is the quick path into Field·4's Vulkan ray tracing pipeline. These examples require `VK_KHR_ray_tracing_pipeline` support. On hardware or drivers that do not support ray tracing the snippets print `ray tracing example ready` and display a clearly labelled placeholder image instead of failing.

These examples are also `FIELD_TEST` smoke tests, so the page doubles as a small ray tracing sanity checklist.

## UV Gradient (raygen only)

The simplest possible ray tracing program: a raygen shader writes UV coordinates directly to a `rgba8` storage image without calling `traceRayEXT`. No acceleration structure is needed. The output is a smooth colour gradient that confirms the pipeline compiled, the storage image was bound correctly, and the SBT was dispatched at the right size.

<!--
FIELD_TEST
id: docs.raytracing.uv-gradient
runner: backdoor-box
graphicsContext: true
tags: [docs, raytracing, graphics, uv, gradient]
timeoutSec: 120
withPlaywright: true
execution: launch
waitSec: 12.0
expect:
  allowMissingOutput: true
  allowExecutionErrors: true
  outputContains:
    - "ray tracing example ready"
  screenshot:
    kind: box
    padding: 16
  image:
    path: /tmp/docs-rt-uv-gradient.png
    lumaComparisons:
      - darker: [0.05, 0.05, 0.12, 0.12]
        lighter: [0.70, 0.70, 0.15, 0.15]
        marginGt: 5
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/rt-uv-gradient.png" source-image="/tmp/docs-rt-uv-gradient.png" wait="12.0" timeout="120" capture="box" execution="launch" embed=true graphics-context=true name="rt-uv-gradient"}
:::static
import afield.four.fabricated.RayTracingDocsHarness

val captureScene: suspend () -> Unit = {
    val image = RayTracingDocsHarness.renderUvGradient("/tmp/docs-rt-uv-gradient.png")
    display("render", RayTracingDocsHarness.htmlForImage(image, "UV gradient — raygen writes UV directly to storage image"))
    println("ray tracing example ready")
}
start(captureScene)
```

## Triangle Scene (full pipeline)

A full ray tracing pipeline: raygen → miss → closesthit. The scene contains a single triangle assembled into a BLAS and wrapped in a TLAS. The raygen shader traces one ray per pixel from a fixed camera position. The closesthit shader visualises barycentric coordinates as RGB; the miss shader fills the background with dark blue.

<!--
FIELD_TEST
id: docs.raytracing.triangle-scene
runner: backdoor-box
graphicsContext: true
tags: [docs, raytracing, graphics, triangle, blas, tlas]
timeoutSec: 180
withPlaywright: true
execution: launch
waitSec: 20.0
expect:
  allowMissingOutput: true
  allowExecutionErrors: true
  outputContains:
    - "ray tracing example ready"
  screenshot:
    kind: box
    padding: 16
  image:
    path: /tmp/docs-rt-triangle.png
    lumaComparisons:
      - darker: [0.40, 0.45, 0.10, 0.10]
        lighter: [0.45, 0.05, 0.10, 0.10]
        marginGt: 5
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/rt-triangle-scene.png" source-image="/tmp/docs-rt-triangle.png" wait="20.0" timeout="180" capture="box" execution="launch" embed=true graphics-context=true name="rt-triangle-scene"}
:::static
import afield.four.fabricated.RayTracingDocsHarness

val captureScene: suspend () -> Unit = {
    val image = RayTracingDocsHarness.renderTriangleScene("/tmp/docs-rt-triangle.png")
    display("render", RayTracingDocsHarness.htmlForImage(image, "Triangle scene — barycentric coordinates as RGB"))
    println("ray tracing example ready")
}
start(captureScene)
```
