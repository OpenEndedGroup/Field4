# Fabricate Renderer

`StandardRenderer` is the quick path into the fabricated renderer. A new scene should be lit already. Metalness should visibly change the material. Shadows should land where objects touch the ground. Ambient occlusion should add contact darkening, not mystery noise.

These examples are also `FIELD_TEST` smoke tests, so the page doubles as a small renderer checklist. The docs runner uses `StandardRendererDocsHarness` behind the scenes to make deterministic captures; the code shown on this page is the part you would normally edit in a Field box: scene settings, mesh registration, transforms, and material pushes.

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

The current built-in frame is:

```diffstatic {data-lang=kotlin}
std-gbuffer
std-shadow
std-ao
std-lighting
std-transparency
std-motion-blur
std-aa
```

`bundle.present(...)` presents `bundle.postStack.output()`, so any effect you append after the stock stack becomes the final image automatically.

## Scene Graph and Transforms

Fabricate now has a small scene layer on top of `afield.four`: nodes, local transforms, world transforms, render intent, animation samples, and draw-list ordering. The scene graph is pure Kotlin; renderer modules consume it through bound model matrices.

```diffstatic {data-lang=kotlin}
val bundle = StandardRenderer.build(renderer)
bundle.attachTo(renderer)

val parent = bundle.sceneGraph.node("pivot") {
    transform.position.set(0f, -0.2f, 0f)
}

val glass = bundle.sceneGraph.mesh(
    name = "glass",
    parent = parent,
    material = FabricateMaterial(Vector4f(0.45f, 0.70f, 1.0f, 0.35f)).transparent(0.35f)
) {
    transform.position.set(0.4f, 0f, -0.45f)
    transform.scale.set(0.7f, 0.7f, 0.7f)
}

bundle.sceneGraph.updateWorldTransforms()
val worldMatrix = Matrix4f(glass.worldMatrix)
```

Draw lists are computed from the same tree. Opaque items are sorted front-to-back, transparent items back-to-front, and shadow casters are separated:

```diffstatic {data-lang=kotlin}
val lists = bundle.sceneDrawLists()
println(lists.opaque.map { it.node.name })
println(lists.transparent.map { it.node.name })
println(lists.shadowCasters.map { it.node.name })
```

For moving transparent objects, call:

```diffstatic {data-lang=kotlin}
bundle.sceneGraph.advance(1f / 60f)
bundle.sortTransparentMeshes()
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
execution: launch
waitSec: 15.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/docs-fabricate-default-native.png
    lumaComparisons:
      - darker: [0.02, 0.02, 0.12, 0.12]
        lighter: [0.28, 0.28, 0.44, 0.34]
        marginGt: 10
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-default-scene.png" source-image="/tmp/docs-fabricate-default-native.png" wait="15.0" timeout="120" execution="launch" embed=true graphics-context=true name="fabricate-default-scene" hidden=true fabricate-scene="default-scene"}
```

## Shiny Torii on Checkerboard Planes

A compact "hero" scene: reflective torii over a checkerboard floor. This is a quick visual sanity check for specular highlights, floor contacts, and stable shading under denser geometry.

The checkerboard here is mesh-side: alternating tile geometry receives alternating material values. The harness packs the two parities into pulling meshes for fewer draw items; conceptually, the scene logic is this:

```diffstatic {data-lang=kotlin}
bundle.scene.camera.at.set(0f, 0.78f, -3.75f)
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
execution: launch
waitSec: 20.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/docs-fabricate-shiny-checkerboard-native.png
    lumaComparisons:
      - darker: [0.02, 0.72, 0.12, 0.10]
        lighter: [0.50, 0.30, 0.16, 0.16]
        marginGt: 8
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-shiny-checkerboard.png" source-image="/tmp/docs-fabricate-shiny-checkerboard-native.png" wait="20.0" timeout="180" execution="launch" embed=true graphics-context=true name="fabricate-shiny-torii-checkerboard" hidden=true fabricate-scene="shiny-torii-checkerboard"}
```

## Checkerboard Camera Depth Study

This scene keeps geometry fixed and compares two camera framings over the same checkerboard setup to make near-versus-far depth layering obvious in a single capture.

Nothing changes between the two halves except `camera.at` and `camera.target`:

```diffstatic {data-lang=kotlin}
bundle.scene.camera.at.set(-1.55f, 0.72f, -1.75f)
bundle.scene.camera.target.set(-0.95f, -0.2f, 0.95f)
// capture the near framing

bundle.scene.camera.at.set(1.45f, 0.78f, -3.35f)
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
execution: launch
waitSec: 25.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/docs-fabricate-camera-depth-study-native.png
    regionsDifferent:
      - a: [0.20, 0.28, 0.24, 0.40]
        b: [0.70, 0.28, 0.24, 0.40]
        meanAbsDiffGt: 8
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-camera-depth-study.png" source-image="/tmp/docs-fabricate-camera-depth-study-native.png" wait="25.0" timeout="180" execution="launch" embed=true graphics-context=true name="fabricate-camera-depth-study" hidden=true fabricate-scene="camera-depth-study"}
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
execution: launch
waitSec: 20.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/docs-fabricate-shader-checkerboard-native.png
    lumaComparisons:
      - darker: [0.08, 0.70, 0.10, 0.10]
        lighter: [0.20, 0.70, 0.10, 0.10]
        marginGt: 6
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-shader-generated-checkerboard.png" source-image="/tmp/docs-fabricate-shader-checkerboard-native.png" wait="20.0" timeout="180" execution="launch" embed=true graphics-context=true name="fabricate-shader-generated-checkerboard" hidden=true fabricate-scene="shader-generated-checkerboard"}
```

## Blended Transparency

Transparent meshes render after the deferred lighting pass. Fabricate copies the lit opaque scene into `transparentComposite`, then draws transparent pulling meshes with alpha blending while sampling opaque g-buffer depth to discard hidden fragments.

The scene graph supplies the ordering; the transparency pass supplies the blending:

```diffstatic {data-lang=kotlin}
val node = bundle.sceneGraph.mesh(
    name = "near-glass",
    material = FabricateMaterial(Vector4f(1.0f, 0.78f, 0.38f, 0.38f)).transparent(0.38f)
) {
    transform.set(Matrix4f().translate(0.42f, -0.28f, -0.38f).scale(0.66f))
    renderable?.userData = "near-glass"
}

val glass = bundle.registerTransparentPullingMesh(
    name = "near-glass",
    maxVertices = 32 * 32 * 6,
    renderer = renderer
)

StandardShapes.sphere(
    positions = glass.position,
    normals = glass.normal,
    radius = 1f,
    slices = 32,
    stacks = 32,
    config = StandardShapes.WriteConfig(append = false)
).also { glass.setVertexCount(it.writtenVertices) }

glass.bindModel { Matrix4f(node.worldMatrix) }
glass.applyFabricateMaterial(node.renderable!!.material)

bundle.sceneGraph.updateWorldTransforms()
bundle.sortTransparentMeshes()
bundle.present(renderer = renderer)
```

<!--
FIELD_TEST
id: docs.fabricate.blended-transparency
runner: backdoor-box
graphicsContext: true
tags: [docs, fabricate, renderer, graphics, transparency]
timeoutSec: 180
execution: launch
waitSec: 25.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/docs-fabricate-blended-transparency-native.png
    regionsDifferent:
      - a: [0.26, 0.28, 0.22, 0.32]
        b: [0.58, 0.28, 0.22, 0.32]
        meanAbsDiffGt: 5
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-blended-transparency.png" source-image="/tmp/docs-fabricate-blended-transparency-native.png" wait="25.0" timeout="180" execution="launch" embed=true graphics-context=true name="fabricate-blended-transparency" hidden=true fabricate-scene="blended-transparency"}
```

## Post Stack

The post stack is Fabricate's small EffectComposer equivalent. It starts with the transparent composite and, by default, runs motion blur then FXAA:

```diffstatic {data-lang=kotlin}
println(bundle.postStack.names()) // [motion-blur, aa]
println(bundle.postStack.output()) // the texture bundle.present(...) will use
```

Append a fullscreen shader effect with `addShaderEffect`. The fragment shader receives the previous stack output as `sourceTex`; the new pass becomes the stack output.

```diffstatic {data-lang=kotlin}
bundle.addShaderEffect("vignette-warmth", """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(set = 0, binding = 0) uniform sampler2D sourceTex;
    layout(location = 0) out vec4 outColor;

    void main() {
        vec4 c = texture(sourceTex, vUv);
        vec2 p = vUv * 2.0 - 1.0;
        float vignette = 1.0 - smoothstep(0.18, 1.05, dot(p, p));
        vec3 warm = c.rgb * vec3(1.18, 1.04, 0.84);
        vec3 lifted = pow(max(warm, vec3(0.0)), vec3(0.88));
        vec3 graded = mix(c.rgb, lifted, 0.72) * mix(0.38, 1.22, vignette);
        outColor = vec4(graded, c.a);
    }
""".trimIndent(), renderer = renderer)

bundle.present(renderer = renderer)
```

<!--
FIELD_TEST
id: docs.fabricate.post-stack-vignette
runner: backdoor-box
graphicsContext: true
tags: [docs, fabricate, renderer, graphics, post, effects]
timeoutSec: 180
execution: launch
waitSec: 25.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/docs-fabricate-post-stack-native.png
    lumaComparisons:
      - darker: [0.02, 0.02, 0.10, 0.10]
        lighter: [0.42, 0.28, 0.18, 0.18]
        marginGt: 6
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-post-stack-vignette.png" source-image="/tmp/docs-fabricate-post-stack-native.png" wait="25.0" timeout="180" execution="launch" embed=true graphics-context=true name="fabricate-post-stack-vignette" hidden=true fabricate-scene="post-stack-vignette"}
```

## GLTF Loading and Animation

The GLTF path uses the existing Assimp-backed loader, then imports mesh instances into the Fabricate scene graph. Opaque meshes go to the g-buffer/shadow pair. Alpha materials go to the blended transparency pass.

```diffstatic {data-lang=kotlin}
val loaded = bundle.registerGltf(
    path = "/path/to/model.glb",
    namePrefix = "robot",
    renderer = renderer
)

// The imported nodes carry bind-pose transforms and any sampled TRS animation.
bundle.sceneGraph.updateWorldTransforms(time = 0f)

// Advance sampled node animation and keep transparent draw order current.
loaded.animate(time = 1.25f)
bundle.sortTransparentMeshes()

println("opaque meshes: ${loaded.opaqueMeshes.size}")
println("transparent meshes: ${loaded.transparentMeshes.size}")
```

You can still override material classification on import:

```diffstatic {data-lang=kotlin}
val model = GltfSupport.loadGltfBasic("/path/to/model.glb")
bundle.sceneGraph.importGltf(model, namePrefix = "ghost") { mesh ->
    FabricateMaterial.fromGltf(mesh.material).transparent(0.42f)
}
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
execution: launch
waitSec: 15.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/docs-fabricate-metalness-native.png
    regionsDifferent:
      - a: [0.16, 0.22, 0.24, 0.46]
        b: [0.60, 0.22, 0.24, 0.46]
        meanAbsDiffGt: 10
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-metalness.png" source-image="/tmp/docs-fabricate-metalness-native.png" wait="15.0" timeout="120" execution="launch" embed=true graphics-context=true name="fabricate-metalness" hidden=true fabricate-scene="metalness"}
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
execution: launch
waitSec: 30.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/docs-fabricate-shadow-native.png
    lumaComparisons:
      - darker: [0.74, 0.48, 0.12, 0.12]
        lighter: [0.75, 0.63, 0.12, 0.10]
        marginGt: 18
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-shadows.png" source-image="/tmp/docs-fabricate-shadow-native.png" wait="30.0" timeout="180" execution="launch" embed=true graphics-context=true name="fabricate-shadows" hidden=true fabricate-scene="shadows"}
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
execution: launch
waitSec: 30.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/docs-fabricate-ao-native.png
    lumaComparisons:
      - darker: [0.66, 0.53, 0.08, 0.08]
        lighter: [0.16, 0.53, 0.08, 0.08]
        marginGt: 6
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/fabricate-ao.png" source-image="/tmp/docs-fabricate-ao-native.png" wait="30.0" timeout="180" execution="launch" embed=true graphics-context=true name="fabricate-ao" hidden=true fabricate-scene="ambient-occlusion"}
```
