# Fabricate renderer architecture

Fabricate is the higher-level scene, render-planning, and pass-graph layer over
`afield.four`. Public usage lives in `public/docs/fabricate_renderer.md`; exact
API behavior belongs in the KDoc of the files below.

## Code map

- `FabricateScene.kt`: Vulkan-independent nodes, transforms, animation samples,
  materials, renderables, lights, and glTF scene intent.
- `FabricateRenderPlan.kt`: deterministic opaque, alpha-mask, transparent, and
  shadow queues plus the compact material table.
- `standardRenderer.kt`: `StandardRenderer.Bundle`, render targets, module
  registration, scene-plan application, light upload, presentation, and generic
  fullscreen post effects.
- `FabricateFrameGraph.kt` and `FabricatePostStack.kt`: ordered target/pass
  structures and synchronization into `RenderTarget.preTargets`.
- `FabricatePostEffects.kt`: tone mapping and the four-pass bloom chain.
- `FabricateShaderBoxes.kt`: exposes each built-in `ModulePointer` through the
  ordinary `Box.attachShader(...)` path.
- `FabricateGltfBridge.kt` and the glTF registration helpers in
  `FabricateScene.kt`: separate scene intent from GPU mesh registration.

## Invariants worth keeping here

The CPU planner must not allocate Vulkan resources or mutate render targets.
`FabricateScenePlanner` orders opaque, mask, and shadow items by material
`sortOrder` then node name; transparent items use `sortOrder`, descending camera
distance, then node name. Invisible ancestors suppress their descendants.

`Bundle.applyScenePlan()` is intentionally a narrow bridge: it reorders existing
module mesh maps using the string names stored in `renderable.userData`. It does
not yet upload the material table or create draw-indirect buffers.

The standard frame graph names are constants in `FabricateFrameGraph`. The graph
contains g-buffer, shadow, AO, lighting, transparency, then the current post
stack. `addShaderEffect(...)` samples the previous output as `sourceTex`.
Tone mapping replaces the named `tonemap` pass and bloom temporarily removes and
then restores it so bloom operates on HDR input. After changing the post stack,
call `bundle.attachTo(renderer)` again.

`syncLights()` is explicit. It selects the first shadow-casting directional
light (or otherwise the first directional light) as the key and uploads every
remaining light, bounded by `maxLights`, to `lightsBuffer`. With no scene lights, it retains the
historical default directional-light state.

Shader boxes are one box per module. `shaderModules()` de-duplicates modules by
identity and includes deferred passes, transparency, stock and user post passes,
and registered textured g-buffer variants. Reloading goes through
`Box.attachShader`/`reloadShader`, preserving the module's non-source parameters.

## Verification

```bash
./gradlew compileKotlinJvm
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command 'run-jvm-main afield.four.fabricated.FabricateSceneTestMain'
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command 'run-jvm-main afield.four.fabricated.FabricateRenderPlanTestMain'
```

Rendered behavior is exercised by the `FIELD_TEST` examples in
`public/docs/fabricate_renderer.md`; use the docs runner on a dedicated port when
the render path or captures change.
