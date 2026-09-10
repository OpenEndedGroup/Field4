# Progressive path tracing

`PathTracingRenderer` is a GPU path tracer for `afield.four`. It runs on ordinary
Vulkan compute devices, including MoltenVK on macOS. When Field has enabled
`VK_KHR_ray_query`, `AUTO` selects hardware triangle traversal. Both backends use
the same lighting, material evaluation and accumulation code.

```diffstatic_runner {data-lang=kotlin screenshot="assets/path_tracing/materials.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="materials study converged" embed=true hidden=true timeout="150" name="path-tracing-materials" alt="Plastic, polished gold and rough metal spheres lit by a Venice sunset environment, rendered by the compute path tracer" macro-file="../../dev_docs/agents/transcripts/path_tracing_materials.yaml" capture-box="path_trace_materials"}
// Built and launched from dev_docs/agents/transcripts/path_tracing_materials.yaml.
```

Environment: Venice Sunset by Greg Zaal / Poly Haven (CC0), from the existing
[Fabricate demo assets](assets/fabricate_demo/ATTRIBUTION.md).

## Start with the material study

Create a child box beneath an existing renderer box, then run the code below.
It traces three spheres over a floor using the bundled Venice sunset OpenEXR
map. Rerunning the box automatically replaces its previous tracer; no cleanup
coroutine is needed. The environment path is relative to the Field checkout.
See the [graphics guide](afield_four_graphics.md) for setting up renderer boxes.
The two opaque material studies opt into `indirectClamp = 10f`; the glass study leaves
clamping disabled so its transmitted lighting and caustics are preserved.

```diffstatic {data-lang=kotlin}
import afield.four.fabricated.*
import afield.four.modules.fromContext

val renderer: Renderer = fromContext()
val tracer = PathTraceDemo.install(renderer,
    "public/docs/assets/fabricate_demo/venice_sunset_1k.exr")
tracer.samplesPerFrame = 1
tracer.maxBounces = 5
tracer.indirectClamp = 10f // optional: reduces indirect fireflies, with some bias
tracer.blitToScreen() // separate pass sampling tracer.output
```

The checkout also includes a complete renderer-and-scene example in
`dev_docs/agents/transcripts/path_tracing_materials.yaml`, which can be run as an
OperationTranscript.

## Supply your own scene

Build geometry in world coordinates. Colours are linear RGB. For opaque materials,
metallic controls the dielectric/metal mixture, and roughness controls isotropic GGX.
Opaque roughness zero uses a small finite roughness floor. Glass has a true specular
reflection/refraction mode at zero roughness.

```diffstatic {data-lang=kotlin}
import afield.four.fabricated.*
import afield.four.util.Camera
import afield.stdlib.linalg.Vector3f

val scene = PathTraceScene()
val paint = scene.material(PathTraceMaterial.plastic(0.7f, 0.08f, 0.02f))
val gold = scene.material(PathTraceMaterial.metal(0.95f, 0.7f, 0.3f, 0.15f))
scene.sphere(Vector3f(-1f, 1f, 0f), 1f, paint)
scene.sphere(Vector3f(1f, 1f, 0f), 1f, gold)
// scene.mesh(positions, triangleIndices, paint, optionalVertexNormals)
// scene.triangle(a, b, c, paint)
// scene.quad(a, b, c, d, paint)

val env = PathTraceEnvironment.fromFile("/absolute/path/studio.exr")
val camera = Camera(renderer).also {
    it.at.set(0f, 2f, 7f)
    it.target.set(0f, 1f, 0f)
}
val tracer = PathTracingRenderer(renderer, scene, env,
    width = 640, height = 360, camera = camera).install()
```

`PathTraceEnvironment` accepts OpenEXR, Radiance HDR, STB-supported ordinary image
files, or a linear RGBA `FloatArray`. LDR files are converted to linear floats by
STB. Maps are equirectangular: north at the top; longitude is `atan2(z, x)`.
`environmentRotation` rotates the map around Y in radians, and
`environmentIntensity` scales its radiance. Construction copies scene and map data.
Recreate the tracer after geometry, CPU material or map changes. Shader material
functions can instead be edited and reloaded in place, as below.

## Camera, jitter and depth of field

The tracer uses an ordinary Field `Camera`: `at`, `target`, `up` and `p.fov` control
the view. You can share it with rasterized geometry, animate it, or attach the usual
keyboard controls. The tracer uses perspective projection and its own output aspect
ratio; `SuperProjection` orthographic blending and principal-point offsets are not used.

```diffstatic {data-lang=kotlin}
import afield.four.util.keyCamera

val camera = tracer.camera
camera.at.set(0f, 2.6f, 8f)
camera.target.set(0f, 0.65f, 0f)
camera.p.fov = 0.62 // vertical field of view, in radians
var cameraKeys by keyCamera(renderer = renderer, camera = camera)

tracer.pixelJitter = 1f
tracer.apertureRadius = 0.25f
tracer.focusDistance = null // follow the target
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/path_tracing/camera.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="camera study converged" embed=true hidden=true timeout="180" name="path-tracing-camera" alt="A sharp gold sphere at the focus plane between a blurred near red sphere and distant blue sphere, over a checkerboard" macro-file="../../dev_docs/agents/transcripts/path_tracing_camera.yaml" capture-box="path_trace_camera"}
// Camera, antialiasing and depth of field in a live Field scene.
```

| Control | Default | Meaning |
| --- | --- | --- |
| `pixelJitter` | `1f` | Samples the full pixel footprint for antialiasing. `0f` samples its centre; values between zero and one narrow the footprint. |
| `apertureRadius` | `0f` | Circular lens radius in world units. Zero disables depth of field; larger values increase defocus blur. |
| `focusDistance` | `null` | Tracks the camera target. Set a positive distance in world units to hold focus independently of the target. |

```diffstatic {data-lang=kotlin}
tracer.focusDistance = 5f // focus plane five world units along camera.forward
tracer.apertureRadius = 0.08f // a smaller opening gives subtler blur
// tracer.apertureRadius = 0f // pinhole view again
```

Focus is a plane perpendicular to the viewing direction, so off-axis objects at the
same depth remain sharp. This uses a [thin-lens camera model](https://www.pbr-book.org/4ed/Cameras_and_Film/Projective_Camera_Models):
each path starts at a sampled point on the circular lens and aims at that plane.
Aperture changes blur without changing exposure. Lens samples use their own two
Sobol dimensions, independently of pixel and lighting samples; depth of field still
converges when `pixelJitter` is zero.

Pose, FOV, jitter, aperture and focus changes reset accumulation before the next
sample. With automatic focus, moving the target nearer or farther also resets it,
even if the viewing direction is unchanged. If sampling is paused, the new view
appears when you resume. Scaling a scene calls for scaling its aperture and explicit
focus distance too. This models a circular aperture; it does not simulate shutter
motion blur, diffraction or a multi-element photographic lens.

## Loading glTF and GLB models

```diffstatic_runner {data-lang=kotlin screenshot="assets/path_tracing/gltf.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="glTF study converged" embed=true hidden=true timeout="240" name="path-tracing-gltf" alt="Textured Toy Car imported from GLB and path traced on a grey floor with hidden sunset environment lighting" macro-file="../../dev_docs/agents/transcripts/path_tracing_gltf.yaml" capture-box="path_trace_gltf"}
// Imported and rendered by dev_docs/agents/transcripts/path_tracing_gltf.yaml.
```

Load a static glTF 2.0 scene before creating the tracer:

```diffstatic {data-lang=kotlin}
import afield.four.fabricated.*

val scene = PathTraceScene.fromGltf("/absolute/path/model.glb")
val tracer = pathTracingHere(scene, env, camera = camera)
tracer.indirectClamp = 10f
tracer.blitToScreen()
```

Both `.gltf` with external buffers/images and `.glb` with embedded images work.
The import copies the default scene's node hierarchy into world-space triangles,
including repeated mesh instances, mirrored transforms and inverse-transpose
normals. It needs no live renderer. Geometry is a static snapshot; recreate the
tracer after importing another model or changing its geometry.

To add a model alongside other geometry, use `scene.importGltf`. Its result
provides world-space bounds, a name-to-material-ID map and import warnings:

```diffstatic {data-lang=kotlin}
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f

val scene = PathTraceScene()
val model = scene.importGltf("/absolute/path/model.gltf",
    transform = Matrix4f().translate(0f, 1f, 0f).scale(2f))
val center = Vector3f(model.boundsMin).add(model.boundsMax).mul(0.5f)
val radius = model.boundsMin.distance(model.boundsMax) * 0.5f
camera.target.set(center)
camera.at.set(center).add(radius * 2.3f, radius * 1.4f, radius * 2.7f)
```

Model units are preserved. Choose a scale and `rayEpsilon` appropriate to the
model: the default epsilon is 0.0001 world units. The bundled Toy Car example
scales the small source model by 20 and fits the camera to its imported bounds.

| Imported feature | Path-tracer behavior |
| --- | --- |
| Base colour, metallic and roughness | Factors multiply their textures; metallic uses blue and roughness uses green. |
| Base-colour and emissive images | sRGB RGB is decoded to linear light; alpha remains linear. |
| Vertex colours | Multiply base colour and coverage. |
| Normal maps | Tangent frames are reconstructed from each triangle's transformed UVs; normal scale is applied. |
| Texture coordinates and samplers | Per-texture UV channels, baked `KHR_texture_transform`, repeat/clamp/mirrored repeat, nearest or bilinear magnification. |
| `OPAQUE`, `MASK`, `BLEND` | Opaque coverage, cutoff-tested holes, or stochastic alpha coverage, including shadow rays. Alpha blend does not refract light. |
| Emission | Emissive factors, textures and `KHR_materials_emissive_strength`. |
| Transmission, IOR and volume | Mapped approximately to the tracer's glass models; see the limits below. |

You can amend materials during import, and then edit them through the same
`shadeMaterial` hook as a hand-built scene:

```diffstatic {data-lang=kotlin}
val model = scene.importGltf("/absolute/path/model.glb",
    material = { name, imported ->
        if (name == "Glass") imported.copy(
            transmission = true, metallic = 0f, thinWalled = false,
            roughness = 0.08f, ior = 1.5f)
        else imported
    })
// model.materialIds["Glass"] selects that material in surface.materialId.
```

Textures and vertex colour are applied before `shadeMaterial`; `surface.uv`
contains the base-colour texture's UV, and `surface.color` contains interpolated
vertex colour. Material reload and named replacement work as for other scenes.

This first importer supports static meshes. Skins and morph targets are rejected;
node animations are reported and the default pose is imported. Clearcoat, sheen,
specular extensions, texture compression and unlit shading are not evaluated.
Unsupported **required** extensions fail the import; unsupported optional
extensions produce warnings. Toy Car uses optional clearcoat and sheen, so this
example shows their base-material fallback. Its model and attribution are in the
[Fabricate demo assets](assets/fabricate_demo/ATTRIBUTION.md).

There are no texture mipmaps or anisotropic filtering yet, and reconstructed
normal-map tangents may differ from an asset's authored tangent frames. Baked
ambient-occlusion maps are not applied. Partial transmission is approximated as
full dielectric transmission, and transmission textures are not evaluated.
Smooth zero-volume materials become thin panes; rough transmission uses solid
glass and needs closed geometry. Volume absorption uses actual mesh thickness
and world-space attenuation distance; nested media remain unsupported.

Colour, texture-channel and coverage conventions follow the
[glTF 2.0 specification](https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html).

## Scene ownership and render-to-texture

`install()` registers under `name = "path-tracer"` by default. Installing another
tracer with that name detaches and retires the old one, including its presentation
pass. An old handle's `close()` cannot remove its replacement. Use distinct names
for independent tracers. A setup box can finish immediately; the scene keeps
rendering until replacement or explicit `tracer.close()`.

The renderer schedules compute, then resolves into `tracer.output` before the
screen passes. The output is an ordinary `RenderToTexture` with linear,
premultiplied HDR RGBA. Sample it in your own Field module, post-process it, or
call `tracer.blitToScreen()` for aspect-preserving exposure/Reinhard presentation.
`tracer.output.blitToScreen()` is also available when Field's ordinary blit/color
grade is desired; it does not apply the tracer's Reinhard curve. When registering
that helper directly, give it a stable name so subsequent helper calls replace it.

To render into an existing RTT, pass `target = rtt` and matching `width` and
`height` to the constructor or `pathTracingHere`. It needs one floating-point color
attachment and must belong to the same renderer. The tracer schedules this RTT;
do not schedule it a second time. `close()` removes the tracer's resolve module
without disposing a caller-owned RTT.

## Environment lighting without a visible sky

```diffstatic_runner {data-lang=kotlin screenshot="assets/path_tracing/hidden_environment.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="hidden environment study converged" embed=true hidden=true timeout="150" name="path-tracing-hidden-environment" alt="The sunset environment illuminates and reflects in three PBR spheres while camera-visible sky pixels are black" macro-file="../../dev_docs/agents/transcripts/path_tracing_hidden_environment.yaml" capture-box="path_trace_hidden_environment"}
// Built and launched from dev_docs/agents/transcripts/path_tracing_hidden_environment.yaml.
```

```diffstatic {data-lang=kotlin}
tracer.showEnvironment = false
// Default background is opaque black. Choose any linear RGBA color:
tracer.background = Vector4f(0.02f, 0.02f, 0.02f, 1f)
// Or make empty pixels transparent for compositing:
tracer.background = Vector4f(0f, 0f, 0f, 0f)
tracer.blitToScreen(blend = true)
```

Only camera rays that miss their first surface see this background. The environment
still illuminates objects and remains visible in reflections and through refracting
surfaces. Changing either background control resets accumulation. This is a useful
art-direction/compositing control; it does not change the environment's radiance.

## Material shaders in a Field box

Use `pathTracingHere` instead of the plain constructor to put material evaluation
in the current box's editable **compute** property:

```diffstatic {data-lang=kotlin}
import afield.four.fabricated.*

val tracer = pathTracingHere(scene, env, camera = camera, name = "study")
tracer.blitToScreen()
```

The first run seeds this shader, preserving any existing or linked compute source:

```diffstatic {data-lang=glsl}
{{ pathTracing.preamble }}

Material shadeMaterial(Material material, PathTraceSurface surface) {
    return material;
}

{{ pathTracing.integrator }}
```

The templates supply types, buffer declarations and the integrator. The middle is
ordinary Field shader source: edit it, link the compute property, include shared
box properties with `{% include "paint.compute" %}` (box name plus property), and use the normal **Reload Compute
Shader** / **Update Compute Shader** actions. Failed compilation keeps the last
working pipeline. Successful shader replacement resets accumulation automatically.

`surface.materialId` is the integer returned by `scene.material(...)`. The other
fields are world-space `position`, oriented `normal` and `geometricNormal`,
`outgoing`, and triangle `barycentric` coordinates. For example, select material
zero and give it a procedural stripe:

```diffstatic {data-lang=glsl}
{{ pathTracing.preamble }}
layout(push_constant) uniform Paint { float stripeScale; } paint;

Material shadeMaterial(Material material, PathTraceSurface surface) {
    if (surface.materialId == 0) {
        float stripe = step(0.5, fract(surface.position.x * paint.stripeScale));
        material.base.rgb = mix(vec3(0.08), vec3(0.8, 0.12, 0.03), stripe);
    }
    return material;
}
{{ pathTracing.integrator }}
```

The usual reflection generates `ComputeMesh.stripeScale`; use it on
`tracer.materialMesh`, or use `tracer.materialMesh.push["stripeScale"] = 4f`.
After changing parameter values or a sampled texture, call `tracer.reset()` to
start a new accumulation. For continuously animated material parameters, reset
once per frame. Reload/update actions themselves reset automatically.

`material.maps` holds internal texture offsets; `coverage` holds alpha, cutoff,
coverage mode and sidedness; `textureParams.x` is normal scale.
`material.base` is RGB plus metallic; `emission` is emitted RGB plus roughness;
`optical` is transmission flag, IOR, thin-sheet flag, reserved; `absorption` is
absorption coefficient RGB for solids or direct tint RGB for thin sheets. Preserve
the same ranges and restrictions as the CPU material factories. This hook changes
PBR parameters; the integrator continues to own BSDF sampling and its matching PDFs.

All materials share one compiled compute pipeline. Individual material functions
can live in included box properties and be selected by `materialId`. Internal
buffer pointers live in a separate UBO, leaving the push-constant block available
to your shader. Set 0 bindings 0–3 are reserved for Frame, environment, optional
ray-query acceleration structure, and SceneBuffers; use binding 4 onward or
another descriptor set for additional uniforms/textures. Imported textures use a
separate buffer pointer in SceneBuffers, so no descriptor-indexing extension is needed.

## Clear, tinted, thin and frosted glass

```diffstatic_runner {data-lang=kotlin screenshot="assets/path_tracing/glass.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="glass study converged" embed=true hidden=true timeout="180" name="path-tracing-glass" alt="Clear, green absorbing and frosted glass spheres beside a thin blue pane, over a checkerboard and striped background" macro-file="../../dev_docs/agents/transcripts/path_tracing_glass.yaml" capture-box="path_trace_glass"}
// Built and launched from dev_docs/agents/transcripts/path_tracing_glass.yaml.
```

From left to right: clear glass, green absorbing glass, frosted glass and a thin
blue pane. Launch `dev_docs/agents/transcripts/path_tracing_glass.yaml`, or use
`PathTraceGlassDemo.install(renderer, environmentPath)` followed by
`tracer.blitToScreen()` as above. It uses ten bounces so rays can enter and leave the solids
and still illuminate another surface.

```diffstatic {data-lang=kotlin}
val clear = scene.material(PathTraceMaterial.glass(ior = 1.5f))
val green = scene.material(PathTraceMaterial.glass(
    attenuationColor = Vector3f(0.45f, 0.9f, 0.65f),
    attenuationDistance = 1f))
val frosted = scene.material(PathTraceMaterial.glass(roughness = 0.32f))
val pane = scene.material(PathTraceMaterial.thinGlass(
    ior = 1.5f, tint = Vector3f(0.65f, 0.85f, 1f)))
```

Solid glass uses exact dielectric Fresnel reflection, Snell refraction and total
internal reflection. `attenuationColor` is the linear RGB fraction remaining after
`attenuationDistance` world units inside the solid: a red value of 0.5 becomes 0.25
after twice that distance. Components must be greater than zero and at most one;
one means no absorption. IOR must be at least one. These values are copied into
immutable material scalars when the factory is called.

Solid meshes must be closed and consistently wound outward. The built-in spheres
already satisfy this. The integrator tracks one homogeneous absorbing medium;
separate solids in air are supported. A camera inside one solid is inferred when
its first hit is that solid’s back face. Nested or
intersecting dielectric boundaries, such as water inside a glass vessel, need a
medium stack and are not supported yet.

`thinGlass` represents an unresolved parallel pane. It transmits straight through,
combines the two interfaces' Fresnel reflections, and multiplies transmitted light
by `tint` once per crossing. Both faces work, and the pane does not change the
current medium. This mode is smooth only; use an actual closed slab for frosted
window glass or thickness-dependent absorption.

For solid glass, roughness above 0.001 enables single-scattering GGX reflection and
transmission with matching direction PDFs and environment MIS. Very rough glass
can lose energy through the microfacet model's omitted multiple scattering. Ideal
glass paths use delta-event MIS handling, and roulette accounts for refraction's
radiance scaling. Each bounce still follows one stochastic ray, on either backend.

Glass requires more bounces and often more samples than opaque paint. Shadows and
caustics come from traced paths; shadow rays do not shortcut solids with alpha
blending. Small bright caustics therefore remain noisy, particularly under a sharp
sun in an HDR map. This is optical transmission; it does not add texture alpha
cutouts or transparent output-background compositing.

## Accumulation and interaction

Each frame traces `samplesPerFrame` new paths per pixel. A 100-dimensional scrambled Sobol sequence gives
each pixel a low-discrepancy temporal sequence, with distinct Joe–Kuo direction
numbers for every sampling decision and a separate scramble per pixel/dimension. This is **not a spatial blue-noise mask**.
The GPU accumulates a linear HDR mean. Exposure and Reinhard tone mapping are
applied only in the display pass.

- Start with 640 × 360, one sample per frame, five bounces. Reduce resolution first
  when camera movement is too slow; this implementation does not denoise or reproject.
- Move the supplied Field `Camera` with your usual controls. Pose and FOV changes
  automatically clear the history before the next dispatch, as do the jitter and lens controls.
- Lighting, bounce count and ray epsilon changes reset history too. `reset()` is an
  explicit reset; `paused` stops sampling while leaving the current image visible.
- `samples` reports the number of samples recorded for the current view. `exposure`
  changes presentation without restarting convergence.
- Size is fixed for each tracer. The fullscreen pass preserves its aspect ratio with black
  letterboxing when needed. Recreate the tracer to change resolution.
- Use `Backend.COMPUTE_BVH` to force the portable path. `Backend.RAY_QUERY` requires
  enabled device support and fails clearly when it is unavailable.

The integrator combines cosine-weighted diffuse and GGX reflection, environment
next-event estimation, power-heuristic multiple importance sampling, and Russian
roulette after the first few bounces. The environment distribution weights linear
luminance by texel solid angle, accounting for the bilinear texture footprint across
texel boundaries. A separately sampled 5% uniform-sphere component preserves
support even for very dim texels. GGX reflection and rough transmission sample
visible microfacet normals with matching PDFs.
The software backend traverses a CPU-built, median-split, stackless triangle BVH.

## Reducing bright specks

Small bright sources in an HDR map can produce occasional bright pixels, often
called *fireflies*. They can take many samples to fade, especially in reflections
and caustics. More samples reduce ordinary noise; they do not guarantee a clean
preview at an interactive sample budget.

For stubborn specks, an optional filter limits the indirect luminance of each path
sample before accumulation:

```diffstatic {data-lang=kotlin}
tracer.indirectClamp = 10f // linear HDR luminance; 0f disables (the default)
```

The clamp scales RGB together to preserve hue. It leaves the camera background,
visible emission, first reflections and first-surface environment lighting untouched.
It limits the summed contribution from subsequent surface interactions, including
light transmitted through multiple glass interfaces. **This is a biased filter:**
lower limits suppress specks more strongly but can dim indirect lighting and caustics.
The threshold is in scene units, before exposure; changing it resets accumulation.
The sampling improvements work with the clamp disabled.

Start at `10f` for the bundled sunset scene. Lower the limit if specks are still
obtrusive; raise it if reflected or transmitted light becomes too dim. Set it to
`0f` when preserving the unclamped lighting matters more than fast convergence.
Use `tracer.samples` to compare views at the same sample count, and adjust
`tracer.exposure` separately for display brightness.

## Current limits

This is a basic static-scene renderer. It does not yet include scattering volumes,
nested dielectric media, deformation/refitting, area-light
importance sampling, motion blur, denoising, reprojection or an OpenXR stereo pass.
Emissive triangles contribute when hit; small emitters can therefore converge slowly.
The intended interactive resolution is a workload choice, not a frame-rate guarantee.

## Verification and export

`PathTraceTestMain` checks scene/BVH packing, environment distributions, and actual
shader compilation/reflection for both backends. `PathTraceGpuSmoke.run(renderer)`
checks GPU readback against known HDR colours, material placement, image orientation,
workgroup edge pixels, accumulation resets, and white-furnace energy/convergence.
glTF checks cover embedded/external texture equivalence, UV orientation, transforms,
colour spaces, material channels, normal maps and alpha coverage.
Glass checks cover slab transmission, Beer–Lambert thickness, both faces of a thin
pane, a camera inside glass, total internal reflection, and convergence at three
frosted roughnesses. Clamp checks cover indirect luminance, colour preservation,
accumulation reset, and preservation of direct HDR lighting. It also compares backends when a
ray-query device is available. Run the suspend smoke from a launched renderer child:

```diffstatic {data-lang=kotlin}
val renderer: Renderer = fromContext()
val verify: suspend () -> Unit = { PathTraceGpuSmoke.run(renderer) }
start(verify)
```

`tracer.readLinear()` returns the actual GPU RGBA accumulation buffer as floats;
alpha holds primary visibility coverage. RGB is premultiplied by alpha; `samples`
reports the sample count. Glass hits have coverage one, including their traced
refraction; this alpha is not a transmission matte. Call it outside frame recording on Field's render thread.
It waits for GPU readback and is intended for export and checks, not the frame loop.
Create/install/close on that same thread, as with other `afield.four` resources.
`close()` detaches the passes immediately and defers destruction until in-flight
frames have retired.

The reflection model follows the standard
[GGX microfacet formulation](https://www.pbr-book.org/4ed/Reflection_Models/Roughness_Using_Microfacet_Theory).
The glass model follows the
[dielectric BSDF formulation](https://pbr-book.org/4ed/Reflection_Models/Dielectric_BSDF).
The optional traversal uses the
[Khronos ray-query interface](https://github.com/KhronosGroup/GLSL/blob/main/extensions/ext/GLSL_EXT_ray_query.txt).

## Noise comparison

A compute-backend check on the M1 Max rendered the same sunset material study at
320 × 180 and 1,024 samples per pixel. In the foreground floor patch (x 10–309,
y 125–174), a strong speck means red radiance more than 0.1 above the median of
its eight neighbouring pixels:

| Setting | Strong specks | Largest red excess | Mean red radiance |
| --- | ---: | ---: | ---: |
| Previous sampler | 20 | 1.288 | 0.27255 |
| Filtered environment + visible-normal GGX, unclamped | 9 | 1.970 | 0.27236 |
| Same sampler, `indirectClamp = 10f` | 0 | 0.044 | 0.27206 |

Sampling changes alone do not remove rare caustic outliers; the largest one in
this particular unclamped run was worse. The optional clamp addresses that tail.
At 16,384 samples, the patch's mean red radiance changed from 0.27244 unclamped to
0.27206 with the clamp (about −0.14%). This is a scene-specific measurement, not a
bound on clamp bias in other scenes.

The unit-environment frosted slab at roughness 0.6 and 2,048 samples had spatial
variance 0.0000187 with visible-normal sampling, versus 0.000250 previously, while
its mean remained about 0.815. Glass and white-furnace checks run with clamping
disabled, or with exclusively direct lighting, to test the actual sampler.

## Measured baseline

Before the transmission extension, on an Apple M1 Max using MoltenVK, the bundled
opaque three-sphere scene (6,626 triangles)
at 640 × 360, one sample per frame and five bounces measured **4.28 ms median / 6.26 ms
p95** for the GPU tracing module. These are GPU timestamp measurements after 30 warmup
samples, across 1,666 module samples; they exclude display, presentation and unrelated
application work. They describe this scene and machine, not every workload.

The GPU white-furnace check measured mean radiance 0.9985 under unit white illumination;
central-patch variance fell from 0.00625 to 0.00000224 as accumulation increased from
about 8 to 1,024 samples. Hardware ray-query execution was unavailable on this Mac;
both shader variants compiled, and the GPU smoke compares their outputs automatically
when a ray-query device is available.

The glass study (7,222 triangles), measured on the same Apple M1 Max at 640 × 360,
one sample per frame and ten bounces, took **10.12 ms median / 10.89 ms p95** for
GPU tracing across 2,189 samples after 30 warmup samples. This excludes presentation
and other application work. The captured scene includes clear, absorbing, frosted
and thin glass, with traced caustics that remain visibly noisy under the sunset sun.

GPU slab checks measured white-environment radiance 1.00005 for clear glass. Red
transmission through an index-matched absorbing slab was 0.50007 at unit thickness
and 0.25012 at double thickness. Thin-pane measurements agreed from either face.
At roughness 0.35, the frosted slab's mean was 0.97649 and spatial variance fell
from 0.00640 at about eight samples to 0.0000177 at 2,048 samples. At roughness 0.6,
the mean fell to 0.81528: the single-scattering approximation loses appreciable
energy at high roughness. Total internal reflection produced no leaked radiance.
