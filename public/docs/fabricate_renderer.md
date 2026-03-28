# Fabricate Renderer

`StandardRenderer` is supposed to feel like a basic out-of-the-box Three.js setup:
you should get a lit scene immediately, metalness should change the material response,
shadows should be visible, and ambient occlusion should add contact darkening rather than garbage.

This page is not just illustrative. Each example below is also a `FIELD_TEST` smoke test.

## Default Scene

This is the baseline fabricated scene: three colored torii, lit and presented with the default setup.

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
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-default-scene.png" source-image="/tmp/docs-fabricate-default-native.png" wait="15.0" capture="box" execution="launch" embed=true graphics-context=true name="fabricate-default-scene"}
:::static
import afield.four.fabricated.StandardRendererDocsHarness

val captureScene: suspend () -> Unit = {
    val image = StandardRendererDocsHarness.renderDefaultScene("/tmp/docs-fabricate-default-native.png")
    display("render", StandardRendererDocsHarness.htmlForImage(image, "Default StandardRenderer scene"))
    println("fabricate default scene ready")
}
start(captureScene)
```

## Metalness

Left is a dielectric torus, right is a metallic torus, using the same base color and roughness.

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
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-metalness.png" source-image="/tmp/docs-fabricate-metalness-native.png" wait="15.0" capture="box" execution="launch" embed=true graphics-context=true name="fabricate-metalness"}
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

Left disables shadowing. Right is the default shadowed scene.

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
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-shadows.png" source-image="/tmp/docs-fabricate-shadow-native.png" wait="30.0" capture="box" execution="launch" embed=true graphics-context=true name="fabricate-shadows"}
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

Left disables AO. Right is the default AO setup.

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
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-ao.png" source-image="/tmp/docs-fabricate-ao-native.png" wait="30.0" capture="box" execution="launch" embed=true graphics-context=true name="fabricate-ao"}
:::static
import afield.four.fabricated.StandardRendererDocsHarness

val captureScene: suspend () -> Unit = {
    val image = StandardRendererDocsHarness.renderAoComparison("/tmp/docs-fabricate-ao-native.png")
    display("render", StandardRendererDocsHarness.htmlForImage(image, "Left: AO off, right: default AO"))
    println("fabricate ao comparison ready")
}
start(captureScene)
```
