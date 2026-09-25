# Linux/NVIDIA ray-tracing audit

This pass uses Field's existing renderer on an RTX 4090, NVIDIA 610.43.02, Vulkan
1.4.341 (Field requests 1.3). The device report distinguishes advertised from enabled
features. No driver or system-package changes are required.

## Paths exercised

| Path | Implementation | Checks |
| --- | --- | --- |
| Portable path tracer | `fabricated/PathTracingRenderer.kt`, `pathtracing/trace.comp` | Compute BVH, HDR/alpha, glass, white furnace, glTF maps, camera, temporal, fog |
| Hardware path tracer | Same renderer/integrator, `Backend.RAY_QUERY` | Same assertions through KHR ray queries; backend image agreement |
| Ray-tracing pipeline | `modules/RayTracingModule.kt`, `fabricated/RayTracingDocsHarness.kt` | Raygen-only image writes, triangle BLAS/TLAS, barycentrics, shader tiles, procedural sphere intersection, SBT selection, recursive reflections |
| Live ray pipeline | `LiveMirrorBallsScene` | Instance movement/restoration, animation, replacement, resolution and delayed resource retirement |
| Emissive geometry | `PathTraceEmitterGpuSmoke` | Independent enclosure reference, MIS composition, lamp bias/noise checks, equal-budget RMSE on both backends |
| Participating medium | `PathTraceMediumGpuSmoke` | Absorption, energy, quadrature, finite light connections, fog and shafts on both backends |
| Scene producers | Canonical path-tracing transcripts | Materials, lens, glTF, glass, procedural meshes and live integrator reload |
| Raster-primary direct lighting / RTXDI | `four/lighting`, `FabricateDirectLighting` | Separate [lighting results](lighting/results/README.md); independent of the path-tracer changes |

All Kotlin paths above are beneath `src/jvmMain/kotlin/afield/four/`; shader paths are beneath
`src/jvmMain/resources/afield/four/`. Ordinary startup still negotiates optional ray features.
No RTXDI, DXC, DLSS or new native dependency is needed for this audit.

## Measured results, 24 September 2026

The [full GPU audit](raytracing/results/full-gpu-audit.json) completed all eight stages with
zero `VUID-` messages in stdout/stderr. Every medium arm passed on both backends; the complete
numbers are in [medium.txt](raytracing/results/medium.txt). In particular:

| Measurement | Result on both backends |
| --- | --- |
| Absorption, 65,536 samples | Matches `exp(-sigma_t*d)` for thin/thick unbounded media and a bounded slab |
| White furnace, 16,384 samples | Six anisotropies within 0.0002 of analytic radiance 1 |
| Single scattering, 65,536 samples | Mean 0.52483 and 0.17512, matching independent CPU quadrature |
| Lamp in fog, 256 samples | Emitter connections reduce RMSE from 0.10327 to 0.02091 (4.94×) |
| Lamp reference, 65,536 samples | Difference 0.00603 RMS, below predicted reference standard error 0.00645 |
| Shafts in empty space, 1,536 samples | Spatial deviation 2.02372 for scattering, 0 for depth fog |

The [surface emitter report](raytracing/results/emitters.txt) includes independent reference,
MIS and equal-budget variance checks. The [shared-edge regression](raytracing/results/shared-edge-regression.json)
records the original temporal failure and the passing edge/temporal checks after the repair.
These are scene-specific measurements, not universal error bounds.
The retained emitter stdout labels its 4× expected noise reduction as a “budget ratio”; the actual
budget grows 16× (4,096 to 65,536 samples). That diagnostic label is corrected in the source;
the estimator and acceptance thresholds are unchanged.

Public-source compilation and 49 focused JVM tests passed: shader compilation, mesh import,
instrument behaviour and capture orientation. The Python asset checker passed 15 tests, and its
repository gate checked 1,283 asset references with no placeholders or exceptions. The toolchain
was GraalVM Java 25.0.2+10, Gradle 9.5.1 and LWJGL 3.4.3. The full repository certification gate
was not run; the private-source exclusion below still applies.

The [live-scene and timing pass](raytracing/results/live-and-timings.json) also finished without
Vulkan validation messages. Moving/restoring a sphere, 60 frames of animation, replacement under
the same name, stale-handle cleanup and a 384×256 replacement capture all passed.

GPU timings below use the same 6,626-triangle, three-sphere `PathTraceDemo` scene, unit environment,
five bounces, 32 warm-up frames and 256 measured dispatches per row. Each cell is p50 / p95 in ms.
The JSON also contains min/max. These measure the compute module on this desktop session;
they exclude initial acceleration-structure construction, image readback and presentation and are
not a promised application frame rate. No denoiser was enabled.

| Resolution | Samples per frame | Compute BVH | Ray query |
| --- | ---: | ---: | ---: |
| 640×360 | 1 | 6.253 / 6.620 | 0.344 / 0.368 |
| 640×360 | 4 | 18.029 / 20.957 | 1.584 / 1.637 |
| 1280×720 | 1 | 14.094 / 14.508 | 1.444 / 1.499 |
| 1280×720 | 4 | 19.342 / 19.881 | 4.979 / 5.159 |

The [optional-feature-disabled run](raytracing/results/fallback.json) passed shared edges and the
full base/glass/glTF smoke through `COMPUTE_BVH`, with acceleration structures, ray queries and
RT pipelines disabled. Its pipeline stage is explicitly `SKIPPED`. The pure scene/BVH/importance
and shader-layout contracts (`PathTraceTestMain`) and transactional glTF import contracts
(`PathTraceGltfTestMain`) also passed.

Six existing procedural transcripts converged through `RAY_QUERY`: skinning → Manifold,
RectSL space-time → solids, polytope lineage, polytope shells, physics cloth → Manifold and
3D cellular automata → Manifold. Their [run manifest](raytracing/results/procedural-scenes.json)
and [capture contact sheet](raytracing/results/procedural-scenes.png) preserve the results.
Full-resolution native captures remain in `build/raytracing-audit/transcripts/`.
The `path_tracing_material_shader_check` transcript also passed includes, reflected push constants,
an intentional failed compile preserving the last pipeline, a changed push-constant ABI and
successful-reload accumulation reset.
The instrument transcript exercised `.h`/`.h2`, convergence logging and unchanged/failed/successful
integrator reloads; a live check confirmed the restored shader and medium/emitter tuning fields.
The [live-coding manifest](raytracing/results/live-coding.json) records both invocations.

## Reproduce

Run from a graphical terminal in the checkout. Select a fresh agent port; the child JVM inherits
the server's display and Vulkan environment. On a multi-GPU Linux machine this selects NVIDIA:

```bash
export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/nvidia_icd.json
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9730 --timeout 3900 \
  --command 'run-jvm-main afield.four.fabricated.RayTracingGpuAudit --arg=--all --arg=--require-ray-query --arg=--require-pipeline --timeout 3800'
```

`--all` runs the core, pipeline, emitter, medium and benchmark stages. Without it, the shorter
core/pipeline audit is the `four.raytracing.gpu-audit` catalog test. Select stages with repeatable
`--arg=--stage=seams`, `base`, `camera`, `temporal`, `fog`, `pipeline`, `emitters`, `medium`, or
`benchmark`. `--arg=--output=build/my-audit` keeps independent runs separate.

The default output is `build/raytracing-audit/gpu/`. `report.json` is rewritten at stage start
and completion, including failure stack traces and elapsed **wall** seconds. Captures and medium
statistics are in backend-specific subdirectories. The process fails on an assertion; the catalog
also rejects Vulkan VUIDs in either output stream. Never infer completion from Vulkan startup.

Without `--require-pipeline`, an unavailable pipeline stage is explicitly `SKIPPED`; compute checks
still run. Test optional-feature disablement with `--arg=--fallback --arg=--stage=base`, using a
separate output directory. The `--require-*` switches prevent a hardware acceptance run from
quietly turning into fallback coverage.

This checkout had unrelated compile errors in its personal source tree, which is excluded from
the public build and is not something a reader of this document can open.
For this session the server was built with public sources and the private rebuild excluded:

```bash
mkdir -p build/raytracing-audit
AFIELD_SKIP_WORKSPACE_PERSIST=1 PORT=9730 ./gradlew runJvm -x compilePersonalKotlinJvm -x personalJar \
  --args='--workspace /home/marc/Field4/build/raytracing-audit'
# In another terminal, add --attach-existing --port 9730 to the backdoor command above.
```

This does not modify private sources; an existing private JAR can still be loaded. The GPU checks
run in fresh child JVMs through `run-jvm-main`, with Field's required JVM flags.

## Repairs

The temporal test exposed four missed pixels exactly along a quad's shared diagonal on NVIDIA's
software-BVH path. Independent Möller–Trumbore barycentric tests could reject both triangles.
The fallback now uses ray-aligned, consistently evaluated edge functions with GLSL `precise` to
prevent contraction from breaking agreement at a shared edge. It needs no `shaderFloat64` feature.
The [PBRT triangle-intersection discussion](https://pbr-book.org/4ed/Shapes/Triangle_Meshes#RayTriangleIntersection)
describes the underlying change of coordinates. This is not a claim of arbitrary-scale numerical
robustness. A new GPU regression requires an unbroken emissive quad across both diagonals,
from both sides and at several camera translations, on both backends. The original temporal
weight assertions pass unchanged.

The mirror scene now retains immutable TLAS/instance input for earlier frames and retires it
through Field's cleanup queue. Static floor and sphere BLASes are reused. Camera UBOs are per frame.
Cleanup detaches only the scene's own pointers; closing a stale instance cannot remove its
replacement. Deterministic capture helpers also retire their pipelines and SBTs explicitly.
Animated transforms rebuild the tiny TLAS; this is not a refit/deforming-mesh implementation.

`PathTraceDemo.install(..., backend=...)` permits an explicit comparison in a live box while
preserving the original overload. The path-tracer `.h2` snapshot now includes the medium and
emitter-sampling controls which were absent from its custom adapter.

## Capture provenance and publication

The four `public/docs/assets/rt-*.png` availability cards have been replaced by actual Field
captures. Their placeholder exceptions have been removed; the detector still rejects a regression.
The former four box tests permitted missing output and execution errors. The GPU audit replaces
that coverage with actual readback assertions and transformed-sphere comparisons.

Regenerate the two public pages through the real docs runner:

```bash
FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9730 \
  FIELD_DOCS_RUNNER_GRADLE_ARGS='-x compilePersonalKotlinJvm -x personalJar' \
  uv run --project . --group docs python tools/build_zensical_docs.py \
  --page ray_tracing_examples --page path_tracing --skip-build
```

The docs runner owns and restarts its server for environment-checked captures. On a checkout
with the private-source error above, the explicit `FIELD_DOCS_RUNNER_GRADLE_ARGS` exclusions
are necessary even when a manually launched server is already healthy. Omit that setting on a
clean checkout. It is parsed into individual Gradle arguments without a shell; it never changes
the default build or silently suppresses errors.

The final forced recapture regenerated all ten capture fences on the two pages in 126.4 seconds,
with zero failures. The four ray-pipeline PNGs are byte-identical to the independently checked
GPU audit captures. The [capture manifest](raytracing/results/captures.json) records dimensions,
SHA-256 and producer for all fourteen refreshed public figures, including the four emitter/medium
images. The docs-runner launch change and related environment/failure/asset tests passed 83 Python
tests. All six procedural captures and the six public path-tracing captures were visually reviewed.
The HTML-only full docs build passed with “No issues found”; an HTML check confirmed four
ray-pipeline figures and ten path-tracing figures are actually embedded. The asset gate now parses
braces inside quoted capture captions correctly, and the existing Datalog capture is embedded
instead of remaining an orphaned asset.

Emitter and medium figures are ordinary Markdown images, not capture fences: their source is the
corresponding successful audit's `emitters/ray_query/` and `medium/ray_query/` directories. Inspect
the renders and their reports before copying them into `public/docs/assets/path_tracing/`.
A docs HTML-only build can reuse these committed captures on hardware without ray tracing.

## Limits

Mono only. OpenXR/stereo and Vulkan-to-Metal ray-tracing support are not certified by a Linux run.
The path tracer still snapshots geometry/material tables; procedural topology edits recreate it.
Hardware query support does not imply pipeline or DLSS support. Benchmark GPU timestamps exclude
construction, readback and presentation; stage wall seconds include compilation and test work and
must not be presented as frame times. See the separate DLSS feasibility note for reconstruction.
