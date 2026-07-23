# KotlinJS (Kotlin → remote head): status & roadmap

Companion to [`kotlin_to_js_spike.md`](kotlin_to_js_spike.md) (the compiler-pipeline spike). This
doc captures where the **RemoteAR/Kotlin-in-the-head** work stands and the detailed plan for what's
next. Branch: `spike/kotlin-to-js-remotejs`.

## Where we got to

A box marked `mainLanguage = "kotlinjs"` (or `// {{ remoteAR }}` / `// {{ visionmetal }}` on the
first line) is compiled Kotlin → JS in-process (`K2JSCompiler`, PLAIN_DCE, ~5 KB modules) and shipped
over the existing RemoteJS WebSocket transport to a browser/visionOS head. Working and smoke-guarded:

- **Compiler pipeline** — source→klib→JS, in-process (subprocess fallback), vendored fork stdlib
  klib. Implicit `fun main` wrapping. Kotlin syntax highlighting (`kotlinjs` Monaco language).
- **Completion** — K2 Analysis-API, scoped to the Kotlin stdlib + the selected head profile's
  declarations + the box's own code. Stage 2 fixed extension-function surfacing in the sidecar.
- **One binding model → two façades** — `KotlinJsBinding.kt` generates BOTH the compiler `external`
  declarations and the JVM-analysis-safe completion mirror from one description, so they can't drift.
  The two parity bugs (Vector/Euler `var` vs `val`; Mesh property-vs-ctor-param) are fixed by
  construction. Adding a name is one DSL call.
- **RemoteAR surface** (all grounded in the runtime `extensions/remote_ar/*`, generated for both
  façades, each validated by compiling a sample + live completion):
  - THREE core: Object3D, Scene, Camera, Vector2/3/4, Euler, Quaternion, Matrix4, Color, geometries,
    Material/MeshBasicMaterial, Mesh, PointLight; operator overloads on the vectors.
  - Field-native: `vec` (arity → Vector2/3/4), `clearAll`, transforms (`rotate`/`scale`/`translate`/
    `rotate3` → Matrix4), scene search (`findByName`/`findBySearchingFor`/`findBySearchingPathFor`),
    `getCurrentCamera` (→ typed `CameraPose`), `addTextureTo`.
  - Object creation: `addBox`/`addPlane` (→ `PlacedMesh`), `addImagePlane` (→ `ImagePlane`),
    `addVideoPlane` (→ `VideoPlane`), `addPointLight` (→ `PointLight`).
  - Stage + attach: `stage` and `SCENE.attach[name]` — `dynamic` for the compiler (permissive
    `stage.name = fline` etc.) but typed for completion (`stage.add|`/`stage.remove|`).
  - Interaction + XR: FLine `pointed`, Vector2 paths, cubicTo 3-vector/6-/9-number forms, and the
    pointer registries `mouseDown`/`mouseUp`/`mouseMove`/`dblClick` (`fline.mouseDown[name] = { e -> }`);
    `onXRHit`/`onXRSelect` (→ unsubscribe) and `xrHit()` (→ typed `XRHit`).
- **Vision Metal profile** — `// {{ visionmetal }}` compiles to the native head script + MSL shader.

### The two enabling tricks (worth remembering)

1. **Generate both façades from one model.** Compiler vs completion differ only mechanically
   (`external` keyword, member bodies, `definedExternally` vs real defaults, `: Base` vs `: Base()`,
   `dynamic`↔`Any?`, callback types). `field()` = ctor arg + mutable property in both.
2. **`dynamic` for the compiler, a typed shape for completion.** A `completionCls` type plus a
   `JsType("dynamic", "Foo")` lets a value stay maximally permissive at the JS target while giving
   completion real members. Used for `stage`, `SCENE.attach`, the `add*` result objects, the pointer
   registries, and XR hits. Result-object completion works too (`addBox("x").mesh` completes).

## Validation approach (keep doing this)

- **Ground every name in the runtime** before binding it — never bind a name the head doesn't
  install (a bound-but-absent name compiles then fails at runtime). File pointers live in the commit
  messages.
- **Each surface addition is validated** by compiling a sample against the generated compiler
  declarations (`remotejs.kotlinjs-remotear-surface`) and, where a sidecar is up, by asserting live
  completion. Parity invariants are guarded by `remotejs.kotlinjs-binding-parity`.

## What's next (detailed plan, rough priority order)

1. **FLine transform operators** — `fline + rotate(0.5)`, `fline + vec(1,0,0)`, and `-`. The runtime
   FLine implements `__PLUS__`/`__MINUS__` (Matrix4 + vector promotion) and a public `byTransforming`.
   Bind `operator fun FLine.plus(THREE.Matrix4)`/`plus(THREE.Vector3)` delegating to a
   compiler-only `__PLUS__` external member (kept out of completion). Grounded: `fline.js:458–527`.
2. **Assets & animation** — `loadGLTF`, `loadPointCloudPLY`, `loadTexture`, `loadFBX`, the animation
   mixer, and shader helpers. Loaders are async/callback-based (`files.js:129+`); type the callback
   payloads with the dynamic/typed-completion pattern. Confirm each loader is actually installed.
3. **Richer THREE props** — Object3D `visible`/`up`/`lookAt`/`quaternion`/`applyMatrix4`, more
   materials (MeshStandard/Lambert/Phong) and geometries (Plane/Cylinder/Torus), Texture. Purely
   additive, one `field()`/`fn()` each.
4. **Completion polish** —
   - Stage 2b: filter JVM-only members (`parallelStream`/`spliterator`/`toArray`) that don't exist in
     a JS head (kotlinjs-scoped, so regular Kotlin completion is unaffected).
   - Param-preserving preamble stubs in the sidecar so overloaded call-result completion and
     signature help are exact (today `splitPreamble` drops params and collapses overloads).
5. **Latency / size** — ESM_SHARED fast path (~343 ms warm, ~5 KB box module) once the union-stdlib
   blocker is solved (see the spike doc); and moving the compile fully in-process with incremental
   caching for the live-edit loop.
6. **Tone & broad A-Frame** — as SEPARATE optional profiles / surface modules, not dumped into the
   RemoteAR core. Each is its own `jsBinding { … }` selected by a marker (`// {{ tone }}` etc.).
7. **Generate the Vision Metal façade from the binding model too** — fold `VisionMetalKotlinJs` into
   the same `jsBinding` DSL so both profiles share the generator (and gain parity guarantees).

## Key files

| Area | File |
|---|---|
| Compiler driver | `src/jvmMain/kotlin/afield/remotejs/KotlinToJsCompiler.kt` |
| Head runtime / language | `src/jvmMain/kotlin/afield/remotejs/KotlinJs.kt` |
| Head profiles + transport | `src/jvmMain/kotlin/afield/remotejs/KotlinJsHeadProfiles.kt`, `RemoteJS.kt` |
| Binding model + renderer | `src/jvmMain/kotlin/afield/graphics/KotlinJsBinding.kt` |
| RemoteAR surface | `src/jvmMain/kotlin/afield/graphics/RemoteArKotlinJs.kt` |
| Vision Metal surface | `src/jvmMain/kotlin/afield/graphics/VisionMetalKotlinJs.kt` |
| Completion client / sidecar | `src/jvmMain/kotlin/afield/AaCompletionClient.kt`, `tools/code-use-semantic-sidecar/…/AaCompletion.kt` |
| Runtime the surface mirrors | `src/commonMain/resources/extensions/remote_ar/{ar,field}/*.js` |
| Smokes | `src/jvmMain/kotlin/afield/examples/KotlinJs*SmokeTest.kt`, `KotlinToJsSpikeTest.kt` |

## Standing constraints

- **klib version lockstep** — the vendored `libs/kotlin-stdlib-js-<v>.klib` must match the compiler
  fork; re-vendor on any bump.
- **Shared-sidecar changes** touch regular Kotlin completion too — run the completion corpus gate
  (`tools/completion_diff.py --run --gate`) before merging sidecar edits.
