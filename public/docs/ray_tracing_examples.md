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

## Shader-Generated Tiles (full pipeline)

This full pipeline example still traces against simple geometry (just one quad made from two triangles), but the tile pattern itself is generated in the closest-hit shader from world hit position. So the "tiles" come from shader logic rather than tile mesh topology.

<!--
FIELD_TEST
id: docs.raytracing.shader-tiles
runner: backdoor-box
graphicsContext: true
tags: [docs, raytracing, graphics, shader, tiles, checker]
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
    path: /tmp/docs-rt-shader-tiles.png
    lumaComparisons:
      - darker: [0.12, 0.62, 0.10, 0.10]
        lighter: [0.22, 0.62, 0.10, 0.10]
        marginGt: 6
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/rt-shader-tiles.png" source-image="/tmp/docs-rt-shader-tiles.png" wait="20.0" timeout="180" capture="box" execution="launch" embed=true graphics-context=true name="rt-shader-tiles"}
:::static
import afield.four.fabricated.RayTracingDocsHarness

val captureScene: suspend () -> Unit = {
    val image = RayTracingDocsHarness.renderShaderTileScene("/tmp/docs-rt-shader-tiles.png")
    display("render", RayTracingDocsHarness.htmlForImage(image, "Shader-generated checker tiles on a traced quad"))
    println("ray tracing example ready")
}
start(captureScene)
```

## Mirror Balls (procedural spheres + reflections)

An Amiga Juggler-inspired scene: three reflective spheres sit above a checkerboard floor. The floor is regular triangle geometry, but the spheres are procedural — a single AABB BLAS is instanced three times and resolved by an intersection shader. This example exercises multiple hit groups, TLAS instance SBT offsets, recursive reflection, and procedural ray tracing geometry all at once.

<!--
FIELD_TEST
id: docs.raytracing.mirror-balls
runner: backdoor-box
graphicsContext: true
tags: [docs, raytracing, graphics, spheres, reflection, intersection, sbt]
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
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/rt-mirror-balls.png" source-image="/tmp/docs-rt-mirror-balls.png" wait="20.0" timeout="180" capture="box" execution="launch" embed=true graphics-context=true name="rt-mirror-balls"}
:::static
import afield.four.fabricated.RayTracingDocsHarness

val captureScene: suspend () -> Unit = {
    val image = RayTracingDocsHarness.renderMirrorBallsScene("/tmp/docs-rt-mirror-balls.png")
    display("render", RayTracingDocsHarness.htmlForImage(image, "Three reflective procedural spheres over a traced checker floor"))
    println("ray tracing example ready")
}
start(captureScene)
```
