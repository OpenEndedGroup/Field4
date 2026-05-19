# Fabricate Renderer

`StandardRenderer` is the quick path into the fabricated renderer. A new scene should be lit already. Metalness should visibly change the material. Shadows should land where objects touch the ground. Ambient occlusion should add contact darkening, not mystery noise.

These examples are also `FIELD_TEST` smoke tests, so the page doubles as a small renderer checklist.

## Default Scene

The default scene is deliberately plain: three colored torii, the standard camera, and the stock lighting setup. If this image is wrong, the higher-level Fabricate examples are not worth trusting yet.

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

A compact “hero” scene: reflective torii over a checkerboard floor. This is a quick visual sanity check for specular highlights, floor contacts, and stable shading under denser geometry.

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
