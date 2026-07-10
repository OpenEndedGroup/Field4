# Fabricate Renderer Architecture

Fabricate is the higher-level scene and frame harness for `afield.four`. It should make common graphics sketches shorter without hiding the renderer underneath. The public style is: build a `StandardRenderer.Bundle`, register geometry into that bundle, keep transforms/material intent in `bundle.sceneGraph`, and use raw `afield.four` modules only where a sketch needs custom shader or buffer behavior.

## User-Facing Objects

- `StandardRenderer.build(renderer)` creates the g-buffer, shadow, AO, lighting, transparency, motion-blur, and FXAA targets.
- `StandardRenderer.Bundle` owns those targets, their modules, scene defaults, `sceneGraph`, and `postStack`.
- `bundle.attachTo(renderer)` attaches the bundle's ordered frame graph to the window render target.
- `bundle.present(renderer)` blits `bundle.postStack.output()` to the window.
- `bundle.presentInternalBuffers(...)` is the debugging surface for g-buffer, motion, AO, and shadow attachments.

The bundle is deliberately not magic. Callers can still read and mutate the underlying `RenderToTexture`, `ModulePointer`, `Mesh`, push constants, texture resolvers, and `RenderTarget.preTargets`.

## Scene Style

`FabricateScene` is pure Kotlin state:

- `FabricateNode` stores parent/children, local `FabricateTransform`, current and previous world matrices, visibility, optional animation samples, and optional source metadata.
- `FabricateRenderable` marks a node as drawable and carries material, shadow-casting intent, bounds, and user data.
- `FabricateMaterial` carries base color, metalness, roughness, alpha mode, cutoff, double-sided state, AO/shadow receive flags, and sort order.

Use the scene graph for intent and composition. Use bundle registration functions for GPU resources. For example, a glTF import creates scene nodes first, then `registerGltf(...)` writes pulling buffers and binds mesh model matrices back to those nodes.

Alpha style:

- `FabricateAlphaMode.OPAQUE`: g-buffer, lighting, and shadow-caster queues.
- `FabricateAlphaMode.MASK`: alpha-test style draw intent; planned separately from opaque but still allowed to cast shadows.
- `FabricateAlphaMode.BLEND`: transparent pass after lighting; not a default shadow caster.

## Render Planning

`FabricateScenePlanner` is the CPU-side planning layer added for inspectable render queues. It takes a scene or root node and returns:

- `opaque`: stable front-to-back queue.
- `alphaMask`: stable front-to-back alpha-mask queue.
- `transparent`: back-to-front queue using camera distance.
- `shadowCasters`: non-transparent drawables that cast shadows.
- `materials`: compact `FabricateMaterialRecord` table, with draw items addressing materials by id.

This planner should stay renderer-agnostic. It is allowed to understand Fabricate scene/material concepts; it should not allocate Vulkan objects or mutate render targets. That makes it suitable for JVM smoke tests, docs harnesses, and future GPU material-buffer work.

The public docs harness is expected to use this path for screenshots: build `FabricateNode`/`FabricateMaterial` intent, bind GPU meshes to node world matrices, run `FabricateScenePlanner`, then commit the planned mesh order into the g-buffer, shadow, and transparency modules before capture. Do not let Fabricate screenshots regress to direct mesh push constants only; the images should prove the scene/planning layer is drawing pixels.

`bundle.applyScenePlan()` is the narrow bridge from pure planning to current renderer modules. It returns the `FabricateRenderPlan` and reorders the existing g-buffer, shadow, and transparent mesh maps from `renderable.userData` names. Keep this bridge simple until material buffers or draw-indirect style submission exists.

## Frame Graph and Post Stack

`FabricateFrameGraph` is the ordered pass contract for off-screen work. The standard pass names are:

```text
std-gbuffer
std-shadow
std-ao
std-lighting
std-transparency
std-motion-blur
std-aa
```

Use `bundle.frameGraph()` when a caller needs to inspect, replace, insert, or remove passes before syncing them into a `RenderTarget`. Use `bundle.attachTo(renderer)` for the normal path.

`FabricatePostStack` starts at `transparentComposite`. The stock stack appends motion blur and FXAA. `bundle.addShaderEffect(...)` creates a fullscreen shader pass that samples the previous stack output as `sourceTex` and becomes the next `postStack.output()`.

`bundle.setToneMapping(mode, exposure)` and `bundle.addBloom(...)` (in `FabricatePostEffects.kt`) are convenience post effects built on `addShaderEffect`. Tone mapping is always kept last; bloom (bright-pass → separable blur → additive composite) is inserted before it so it accumulates on HDR values. Both mutate the post stack, so callers must re-attach the frame graph afterward.

## Lights

Lighting used to be a single hard-coded directional light in `SceneDefaults`. `FabricateScene.lights` now holds a Three.js-style list of `FabricateLight` (directional/point/spot). `bundle.syncLights()` is the narrow bridge to the GPU: it picks the first shadow-casting directional light as the key light (updating `SceneDefaults` so the shadow map and the built-in `LightData` uniform stay authoritative for shadows), then packs every other light into a `buffer_reference` SSBO (`bundle.lightsBuffer`, capacity `maxLights`) that the deferred lighting fragment loops over. An empty list leaves the historical single default directional light untouched, so existing scenes render unchanged. `bundle.showEnvironmentBackground()` reconstructs a per-pixel view ray (`invViewProj`) in the lighting pass and samples the equirect environment for background pixels.

Keep `syncLights()` an explicit call (like `sortTransparentMeshes()`); do not make lighting silently re-upload every frame. GLSL that Kotlin cannot check is validated GPU-free by `FabricateShaderCompileTest` (shaderc → SPIR-V); add new Fabricate shaders there.

## Editable Shader Boxes

`FabricateShaderBoxes.kt` binds the renderer's built-in shaders to boxes so they edit/hot-reload like user shaders. `bundle.shaderModules()` enumerates the built-in `ModulePointer`s (deferred passes + post-stack effects + textured g-buffer variants, de-duplicated by identity); `bundle.bindShadersToBoxes()` spawns one box per module via `box(id)` and calls `Box.attachShader(module)` (from `afield.stdlib.shaders`). This reuses the existing shader-box machinery: `attachShader` installs the `alt r` / `alt shift enter` actions, copies the module's source into the box's `vertex`/`fragment` properties, and `reloadShader` rebuilds by copying the module's existing `Parameters` — so `textureResolver`, attachment formats, and push seeding survive every edit; only GLSL changes.

This works because Fabricate's shader source is plain GLSL with no `#include`, `${...}` templates, or push-copy tags, so the box preprocessing path is effectively identity (the same source `FabricateShaderCompileTest` validates). Keep it that way: if a built-in shader gains include/template syntax, verify the box path still round-trips it. One box owns one module (single `_shader` slot), so multi-pass binding is one box per pass, not one box for the renderer.

## Extension Rules

- Keep pure planning code separate from Vulkan allocation.
- Add bundle helpers only when they save real repeated setup, not as wrappers around every low-level call.
- Prefer named pass constants over string literals.
- Keep `FabricateMaterial` as the user's material source of truth; derive queue/material records from it.
- Keep glTF import two-stage: scene intent first, GPU mesh registration second.
- Preserve deterministic ordering in tests: sort by material `sortOrder`, distance where needed, then node name.

## Verification

Useful checks while changing Fabricate:

```bash
./gradlew compileKotlinJvm
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command 'run-jvm-main afield.four.fabricated.FabricateSceneTestMain'
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command 'run-jvm-main afield.four.fabricated.FabricateRenderPlanTestMain'
FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

For rendered behavior, use the `fabricate_renderer.md` FIELD_TEST examples or the docs runner in `incremental` or `always` mode on a dedicated port.
