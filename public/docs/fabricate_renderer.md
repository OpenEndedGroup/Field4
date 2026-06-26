# Fabricate Renderer

`StandardRenderer` is the quick path into the fabricated renderer. A new scene should be lit already. Metalness should visibly change the material. Shadows should land where objects touch the ground. Ambient occlusion should add contact darkening, not mystery noise.

These examples are also `FIELD_TEST` smoke tests, so the page doubles as a small renderer checklist. The runnable cells use `StandardRendererDocsHarness` to make deterministic captures, but each section includes the part you would normally edit in a Field box: scene settings, mesh registration, transforms, and material pushes.

The common pattern is:

```diffstatic {data-lang=kotlin}
val bundle = StandardRenderer.build(renderer)
bundle.attachTo(renderer)

val mesh = bundle.registerTestTorus(name = "torus", slices = 64, stacks = 64, renderer = renderer)
mesh.setModel(Matrix4f().translate(0f, -0.18f, 0f))
mesh.gBuffer.mesh.push["baseColor"] = Vector4f(0.83f, 0.58f, 0.28f, 1f)
mesh.gBuffer.mesh.push["metallic"] = 0.18f
mesh.gBuffer.mesh.push["roughness"] = 0.36f

bundle.present(renderer = renderer)
```

## Default Scene

The default scene is deliberately plain: three colored torii, the standard camera, and the stock lighting setup. If this image is wrong, the higher-level Fabricate examples are not worth trusting yet.

The scene is intentionally just mesh registration, transforms, and material pushes:

```diffstatic {data-lang=kotlin}
val left = bundle.registerTestTorus(name = "left", slices = 48, stacks = 48, renderer = renderer)
left.setModel(Matrix4f().translate(-0.95f, 0f, 0f))
left.gBuffer.mesh.push["baseColor"] = Vector4f(0.17f, 0.98f, 0.24f, 1f)

val middle = bundle.registerTestTorus(name = "middle", slices = 48, stacks = 48, renderer = renderer)
middle.gBuffer.mesh.push["baseColor"] = Vector4f(0.98f, 0.92f, 0.22f, 1f)

val right = bundle.registerTestTorus(name = "right", slices = 48, stacks = 48, renderer = renderer)
right.setModel(Matrix4f().translate(0.95f, 0f, 0f))
right.gBuffer.mesh.push["baseColor"] = Vector4f(0.98f, 0.30f, 0.32f, 1f)
```

<!--
FIELD_TEST
id: docs.fabricate.default-scene
runner: backdoor-box
graphicsContext: true
tags: [docs, fabricate, renderer, graphics, default]
timeoutSec: 120
withPlaywright: true
execution: launch
waitSec: 15.0
expect:
  allowMissingOutput: true
  screenshot:
    kind: box
    padding: 16
  image:
    path: /tmp/docs-fabricate-default-native.png
    lumaComparisons:
      - darker: [0.02, 0.02, 0.12, 0.12]
        lighter: [0.28, 0.28, 0.44, 0.34]
        marginGt: 10
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-default-scene.png" source-image="/tmp/docs-fabricate-default-native.png" wait="15.0" timeout="120" capture="box" execution="launch" embed=true graphics-context=true name="fabricate-default-scene"}
:::static
import afield.four.fabricated.StandardRendererDocsHarness

val captureScene: suspend () -> Unit = {
    val image = StandardRendererDocsHarness.renderDefaultScene("/tmp/docs-fabricate-default-native.png")
    display("render", StandardRendererDocsHarness.htmlForImage(image, "Default StandardRenderer scene"))
    println("fabricate default scene ready")
}
start(captureScene)
```

## Shiny Torii on Checkerboard Planes

A compact "hero" scene: reflective torii over a checkerboard floor. This is a quick visual sanity check for specular highlights, floor contacts, and stable shading under denser geometry.

The checkerboard here is mesh-side: alternating tile geometry receives alternating material values. The harness packs the two parities into pulling meshes for fewer draw items; conceptually, the scene logic is this:

```diffstatic {data-lang=kotlin}
bundle.scene.camera.at.set(0f, 0.9f, -5.3f)
bundle.scene.camera.target.set(0f, -0.25f, 0f)
bundle.scene.lightDirection.set(Vector3f(-0.55f, -1.0f, -0.20f).normalize())
bundle.scene.lightIntensity = 1.55f
bundle.scene.shadowStrength = 1f
bundle.scene.aoStrength = 1f

for (row in 0 until 10) {
    for (col in 0 until 10) {
        val tile = bundle.registerCube(name = "tile-$row-$col", size = 1f, renderer = renderer)
        tile.setModel(
            Matrix4f()
                .translate((col - 4.5f) * 0.52f, -1.0f, (row - 4.5f) * 0.52f)
                .scale(0.26f, 0.02f, 0.26f)
        )
        val light = (row + col) % 2 == 0
        tile.gBuffer.mesh.push["baseColor"] =
            if (light) Vector4f(0.57f, 0.57f, 0.61f, 1f) else Vector4f(0.22f, 0.22f, 0.24f, 1f)
        tile.gBuffer.mesh.push["roughness"] = 0.9f
    }
}

val torus = bundle.registerTestTorus(name = "torus", slices = 72, stacks = 72, renderer = renderer)
torus.setModel(Matrix4f().translate(0f, -0.16f, 0f).rotateX(0.24f).rotateY(-0.25f))
torus.gBuffer.mesh.push["baseColor"] = Vector4f(0.28f, 0.86f, 0.96f, 1f)
torus.gBuffer.mesh.push["metallic"] = 1.0f
torus.gBuffer.mesh.push["roughness"] = 0.07f
```

<!--
FIELD_TEST
id: docs.fabricate.shiny-torii-checkerboard
runner: backdoor-box
graphicsContext: true
tags: [docs, fabricate, renderer, graphics, checkerboard, torus]
timeoutSec: 180
withPlaywright: true
execution: launch
waitSec: 20.0
expect:
  allowMissingOutput: true
  screenshot:
    kind: box
    padding: 16
  image:
    path: /tmp/docs-fabricate-shiny-checkerboard-native.png
    lumaComparisons:
      - darker: [0.02, 0.72, 0.12, 0.10]
        lighter: [0.50, 0.30, 0.16, 0.16]
        marginGt: 8
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-shiny-checkerboard.png" source-image="/tmp/docs-fabricate-shiny-checkerboard-native.png" wait="20.0" timeout="180" capture="box" execution="launch" embed=true graphics-context=true name="fabricate-shiny-torii-checkerboard"}
:::static
import afield.four.fabricated.StandardRendererDocsHarness

val captureScene: suspend () -> Unit = {
    val image = StandardRendererDocsHarness.renderShinyToriCheckerboard("/tmp/docs-fabricate-shiny-checkerboard-native.png")
    display("render", StandardRendererDocsHarness.htmlForImage(image, "Shiny torii over checkerboard planes"))
    println("fabricate shiny checkerboard scene ready")
}
start(captureScene)
```

## Checkerboard Camera Depth Study

This scene keeps geometry fixed and compares two camera framings over the same checkerboard setup to make near-versus-far depth layering obvious in a single capture.

Nothing changes between the two halves except `camera.at` and `camera.target`:

```diffstatic {data-lang=kotlin}
bundle.scene.camera.at.set(-1.8f, 0.8f, -2.2f)
bundle.scene.camera.target.set(-0.95f, -0.2f, 0.95f)
// capture the near framing

bundle.scene.camera.at.set(1.8f, 0.9f, -4.8f)
bundle.scene.camera.target.set(0.95f, -0.15f, -1.35f)
// capture the far framing
```

<!--
FIELD_TEST
id: docs.fabricate.camera-depth-study
runner: backdoor-box
graphicsContext: true
tags: [docs, fabricate, renderer, graphics, camera, depth]
timeoutSec: 180
withPlaywright: true
execution: launch
waitSec: 25.0
expect:
  allowMissingOutput: true
  screenshot:
    kind: box
    padding: 16
  image:
    path: /tmp/docs-fabricate-camera-depth-study-native.png
    regionsDifferent:
      - a: [0.20, 0.28, 0.24, 0.40]
        b: [0.70, 0.28, 0.24, 0.40]
        meanAbsDiffGt: 8
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-camera-depth-study.png" source-image="/tmp/docs-fabricate-camera-depth-study-native.png" wait="25.0" timeout="180" capture="box" execution="launch" embed=true graphics-context=true name="fabricate-camera-depth-study"}
:::static
import afield.four.fabricated.StandardRendererDocsHarness

val captureScene: suspend () -> Unit = {
    val image = StandardRendererDocsHarness.renderCheckerboardCameraDepthStudy("/tmp/docs-fabricate-camera-depth-study-native.png")
    display("render", StandardRendererDocsHarness.htmlForImage(image, "Left: near framing, right: far framing"))
    println("fabricate camera depth study ready")
}
start(captureScene)
```

## Shader-Generated Checkerboard

This variant keeps floor geometry to a single scaled cube and generates the checker tiles procedurally in the g-buffer fragment shader. It demonstrates a production-ready path where shader-side tiling replaces mesh-side tile splits.
Use shader-generated tiles when you want lower mesh/buffer overhead; use mesh-split tiles when you need per-tile transforms or per-tile material control.
The checker pattern is evaluated in world space, so model scaling directly affects tile density.

Limitations:
- No per-tile transforms or per-tile material edits (all tiles share one mesh/material state)
- World-space scale dependency for checker density
- Shader-only pattern can alias at grazing angles compared with textured/filtered approaches

The mesh is one scaled cube. The checker controls are pushed into the g-buffer material:

```diffstatic {data-lang=kotlin}
val floor = bundle.registerCube(name = "shader-floor", size = 1f, renderer = renderer)
floor.setModel(
    Matrix4f()
        .translate(0f, -1.0f, 0f)
        .scale(3.2f, 0.02f, 3.2f)
)
floor.gBuffer.mesh.push["baseColor"] = Vector4f(0.58f, 0.58f, 0.62f, 1f)
floor.gBuffer.mesh.push["roughness"] = 0.9f
floor.gBuffer.mesh.push["checkerScale"] = 3.0f
floor.gBuffer.mesh.push["checkerColor"] = Vector4f(0.20f, 0.20f, 0.22f, 1f)
floor.gBuffer.mesh.push["checkerMix"] = 1f
```

<!--
FIELD_TEST
id: docs.fabricate.shader-generated-checkerboard
runner: backdoor-box
graphicsContext: true
tags: [docs, fabricate, renderer, graphics, checkerboard, shader]
timeoutSec: 180
withPlaywright: true
execution: launch
waitSec: 20.0
expect:
  allowMissingOutput: true
  screenshot:
    kind: box
    padding: 16
  image:
    path: /tmp/docs-fabricate-shader-checkerboard-native.png
    lumaComparisons:
      - darker: [0.08, 0.70, 0.10, 0.10]
        lighter: [0.20, 0.70, 0.10, 0.10]
        marginGt: 6
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-shader-generated-checkerboard.png" source-image="/tmp/docs-fabricate-shader-checkerboard-native.png" wait="20.0" timeout="180" capture="box" execution="launch" embed=true graphics-context=true name="fabricate-shader-generated-checkerboard"}
:::static
import afield.four.fabricated.StandardRendererDocsHarness

val captureScene: suspend () -> Unit = {
    val image = StandardRendererDocsHarness.renderShaderGeneratedCheckerboard("/tmp/docs-fabricate-shader-checkerboard-native.png")
    display("render", StandardRendererDocsHarness.htmlForImage(image, "Checkerboard generated in shader on one floor mesh"))
    println("fabricate shader checkerboard scene ready")
}
start(captureScene)
```

## Metalness

The two torii use the same base color and roughness. The left one is dielectric; the right one is metallic. Keeping the geometry fixed makes the material response visible.

`baseColor` and `roughness` stay fixed; only `metallic` changes:

```diffstatic {data-lang=kotlin}
left.gBuffer.mesh.push["baseColor"] = Vector4f(0.78f, 0.80f, 0.84f, 1f)
left.gBuffer.mesh.push["metallic"] = 0.0f
left.gBuffer.mesh.push["roughness"] = 0.16f

right.gBuffer.mesh.push["baseColor"] = Vector4f(0.78f, 0.80f, 0.84f, 1f)
right.gBuffer.mesh.push["metallic"] = 1.0f
right.gBuffer.mesh.push["roughness"] = 0.16f
```

<!--
FIELD_TEST
id: docs.fabricate.metalness
runner: backdoor-box
graphicsContext: true
tags: [docs, fabricate, renderer, graphics, metalness]
timeoutSec: 120
withPlaywright: true
execution: launch
waitSec: 15.0
expect:
  allowMissingOutput: true
  screenshot:
    kind: box
    padding: 16
  image:
    path: /tmp/docs-fabricate-metalness-native.png
    regionsDifferent:
      - a: [0.16, 0.22, 0.24, 0.46]
        b: [0.60, 0.22, 0.24, 0.46]
        meanAbsDiffGt: 10
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-metalness.png" source-image="/tmp/docs-fabricate-metalness-native.png" wait="15.0" timeout="120" capture="box" execution="launch" embed=true graphics-context=true name="fabricate-metalness"}
:::static
import afield.four.fabricated.StandardRendererDocsHarness

val captureScene: suspend () -> Unit = {
    val image = StandardRendererDocsHarness.renderMetalnessComparison("/tmp/docs-fabricate-metalness-native.png")
    display("render", StandardRendererDocsHarness.htmlForImage(image, "Left: metallic = 0.0, right: metallic = 1.0"))
    println("fabricate metalness comparison ready")
}
start(captureScene)
```

## Shadows

The left side disables shadowing. The right side uses the default shadowed scene. Look for the contact shadow under the object, not for a dramatic lighting change.

The comparison is a single scene captured twice:

```diffstatic {data-lang=kotlin}
bundle.scene.aoStrength = 0f
bundle.scene.ambientColor.set(0.015f, 0.015f, 0.018f)
bundle.scene.lightIntensity = 1.35f

bundle.scene.shadowStrength = 0f
// capture the unshadowed scene

bundle.scene.shadowStrength = 1f
// capture the shadowed scene
```

<!--
FIELD_TEST
id: docs.fabricate.shadows
runner: backdoor-box
graphicsContext: true
tags: [docs, fabricate, renderer, graphics, shadows]
timeoutSec: 180
withPlaywright: true
execution: launch
waitSec: 30.0
expect:
  allowMissingOutput: true
  screenshot:
    kind: box
    padding: 16
  image:
    path: /tmp/docs-fabricate-shadow-native.png
    lumaComparisons:
      - darker: [0.74, 0.48, 0.12, 0.12]
        lighter: [0.75, 0.63, 0.12, 0.10]
        marginGt: 18
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-shadows.png" source-image="/tmp/docs-fabricate-shadow-native.png" wait="30.0" timeout="180" capture="box" execution="launch" embed=true graphics-context=true name="fabricate-shadows"}
:::static
import afield.four.fabricated.StandardRendererDocsHarness

val captureScene: suspend () -> Unit = {
    val image = StandardRendererDocsHarness.renderShadowComparison("/tmp/docs-fabricate-shadow-native.png")
    display("render", StandardRendererDocsHarness.htmlForImage(image, "Left: shadows off, right: default shadows"))
    println("fabricate shadow comparison ready")
}
start(captureScene)
```

## Ambient Occlusion

The left side disables ambient occlusion. The right side uses the default AO setup. The useful difference is the small darkening where nearby surfaces crowd each other.

AO is also just a scene parameter in this harness:

```diffstatic {data-lang=kotlin}
val originalAoStrength = bundle.scene.aoStrength

bundle.scene.aoStrength = 0f
// capture the AO-off scene

bundle.scene.aoStrength = originalAoStrength
// capture the AO-on scene
```

<!--
FIELD_TEST
id: docs.fabricate.ambient-occlusion
runner: backdoor-box
graphicsContext: true
tags: [docs, fabricate, renderer, graphics, ao]
timeoutSec: 180
withPlaywright: true
execution: launch
waitSec: 30.0
expect:
  allowMissingOutput: true
  screenshot:
    kind: box
    padding: 16
  image:
    path: /tmp/docs-fabricate-ao-native.png
    lumaComparisons:
      - darker: [0.66, 0.53, 0.08, 0.08]
        lighter: [0.16, 0.53, 0.08, 0.08]
        marginGt: 6
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-ao.png" source-image="/tmp/docs-fabricate-ao-native.png" wait="30.0" timeout="180" capture="box" execution="launch" embed=true graphics-context=true name="fabricate-ao"}
:::static
import afield.four.fabricated.StandardRendererDocsHarness

val captureScene: suspend () -> Unit = {
    val image = StandardRendererDocsHarness.renderAoComparison("/tmp/docs-fabricate-ao-native.png")
    display("render", StandardRendererDocsHarness.htmlForImage(image, "Left: AO off, right: default AO"))
    println("fabricate ao comparison ready")
}
start(captureScene)
```
