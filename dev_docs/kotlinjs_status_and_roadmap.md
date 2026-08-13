# KotlinJS (Kotlin → remote head): status & roadmap

Companion to [`kotlin_to_js_spike.md`](kotlin_to_js_spike.md) (the compiler-pipeline spike). This
doc captures where the **RemoteAR/Kotlin-in-the-head** work stands and the detailed plan for what's
next. The work developed on `spike/kotlin-to-js-remotejs` is now landed on `main`.

## Where we got to

A box under a root marked `mainLanguage = "kotlinjs"` (or the legacy KotlinJS root flag) is compiled
Kotlin → JS in-process (`K2JSCompiler`, PLAIN_DCE, ~5 KB modules) and shipped over the existing
RemoteJS WebSocket transport. Its first non-blank line can then select the destination and binding
surface with `// {{ remoteAR }}` or `// {{ visionmetal }}`. Working and smoke-guarded:

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
  - Motion + assets + richer scene (roadmap items 1/2/3, below): FLine transform operators
    (`fline + rotate(0.5)`/`+ vec(…)`/`* s`/`/ s`, `byTransforming`); asset/animation loaders
    (`loadGLTF`/`loadPointCloudPLY`/`loadFBX`/`loadTexture`/`loadJSONScene`/`mapDirectory`) with
    typed-for-completion callback payloads whose `play()`/`stop()`/`apply(t)` drive the auto
    AnimationMixer; and a much richer THREE (geometries/materials/lights, `Group`/`Points`/`Line`/
    `Texture`, and fuller `Object3D` props/methods).
- **Vision Metal profile** — `// {{ visionmetal }}` compiles to the native head script. The box's
  three parts are explicit and separately reloadable: MSL in the `msl` property (its own Monaco
  language), geometry from the Kotlin/JS `geometry { … }` builder running in the headset, and uniform
  slot order from the marker. Wire version 2 carries only the parts that changed.

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

1. **FLine transform operators — DONE.** `fline + rotate(0.5)`, `fline + vec(1,0,0)`, `fline * 2.0`,
   `fline / 2.0`, and `-`, all type-check and run. The runtime FLine implements `__PLUS__`/`__MINUS__`/
   `__MULTIPLY__`/`__DIVIDE__` (Matrix4 + vector-promote3; `*`/`/` also accept a scalar) and a public
   `byTransforming` (`fline.js:492–578`). Those raw members are bound compiler-only (new
   `TypeBuilder.compilerFn`, filtered out of the completion mirror); the public `operator fun
   FLine.plus/minus/times/div` extensions (Matrix4 / Vector3 / Vector2 / Double as grounded) delegate to
   them, and `byTransforming((x,y,z)->Array<Double>)` is exposed. Because a kotlinjs `external` member
   maps to its verbatim JS name, `line + rotate(0.5)` emits `line.__PLUS__(rotate(0.5))` at runtime.
2. **Assets & animation — DONE (loaders + mixer; shader helpers deferred).** `loadGLTF`,
   `loadPointCloudPLY`, `loadFBX`, `loadTexture`, `loadJSONScene`, `mapDirectory` are bound as installed
   globals (each verified in the `__superglobal.*` export list). Loaders are async/callback-based
   (`files.js`); the callback payload is `dynamic` for the compiler but a typed shape for completion
   (`GltfAsset`/`FbxAsset`/`PlyAsset`) so its members complete inside the lambda. The AnimationMixer is
   module-local and auto-created + auto-`update()`d every XR frame (`webxr.js:76#new THREE.AnimationMixer`); user code drives it
   through the payload's `play()`/`stop()`/`apply(t)` (which call `mixer.clipAction(clip)…`,
   `files.js:396+`). `loadFBX` THROWS unless the head vendors a FBXLoader — bound + documented; prefer
   glTF. Array-valued payload fields are kept `Any?` (a `dynamic` receiver emits member names verbatim,
   and a JS array's length is `.length`, not Kotlin's `.size`). The shader helpers (`newShaderForBox`)
   remain deferred (box-scoped, reload-callback machinery).
3. **Richer THREE props — DONE.** Object3D `visible`/`up`/`quaternion`/`castShadow`/`receiveShadow`/
   `renderOrder`/`userData`/`parent` + `lookAt`/`applyMatrix4`/`applyQuaternion`/`traverse`/
   `getObjectByName`/`updateMatrix(World)`/`rotate{X,Y,Z}`/`translate{X,Y,Z}`/`clone`; materials
   MeshStandard/Lambert/Phong/Normal/Points/LineBasic (color/map/wireframe/emissive/roughness/metalness/
   specular/shininess/size…) atop a richer base `Material` (side/depthTest/depthWrite/needsUpdate);
   geometries Plane/Cylinder/Cone/Torus/TorusKnot/Circle/Ring; lights Directional/Ambient/Hemisphere/
   Spot; scene-graph `Group`, renderable `Points`/`Line`, and `Texture`/`TextureLoader`. Grounded against
   the vendored super-three r0.184.0 (every name confirmed in its re-export block; all reach user code via
   `__superglobal.THREE`). Verified: `remotejs.kotlinjs-remotear-surface` (broad sample compiled through
   the real K2->JS compiler), `remotejs.kotlinjs-binding-parity`, and `remotejs.kotlinjs-head-profiles`
   (compiled Kotlin->JS **executed under node against a mock THREE/head** — proves the emitted JS calls
   the bound names at runtime, incl. `FLine.__PLUS__`/`__MULTIPLY__`, `TorusGeometry`/`MeshStandardMaterial`
   /`Group`/`DirectionalLight`, `lookAt`/`rotateY`, `loadTexture`/`mapDirectory`, and a `loadGLTF` callback
   whose `play()`/`apply()` fire). Demo: `dev_docs/agents/transcripts/remotear_scene_motion.yaml`
   (replayed through `examples.demo-transcript.exec`).
4. **Completion polish** —
   - **Stage 2b: DONE.** JVM-only stdlib members that have no Kotlin/JS equivalent are filtered out of
     kotlinjs-scoped AA completion. An explicit `jsTarget` flag threads client → sidecar request →
     `AaCompleter` (default `false`, so regular Kotlin completion — and the `completion_diff.py` corpus
     gate, whose spike path never sets it — are byte-for-byte unaffected). The filter (`isJvmOnlyForJsTarget`
     in `AaCompletion.kt`) is a hybrid: principled where possible (any `java.`/`javax.` type in a
     member's signature → drop, catching `stream`/`parallelStream`/`spliterator`/`chars`/`codePoints`)
     plus a small well-commented curated set (`JVM_ONLY_STDLIB_MEMBERS`: `toArray`/`getChars`/
     `parallel`/`sequential`) for the residue that returns Kotlin types, gated on a `kotlin.*` receiver
     so a user's own `fun stream()` is never hidden. The AA standalone session does not expose a clean
     "JVM-platform-only" symbol flag, so a purely principled rule isn't sufficient (e.g. `toArray`
     returns `kotlin.Array`). Grounded empirically against a stdlib-only completion on
     List/Collection/MutableList/String. Verified both directions in `remotejs.kotlinjs-completion`
     (kotlinjs → `stream`/`parallelStream`/`spliterator`/`toArray` + `chars`/`codePoints`/`getChars`
     ABSENT; normal Kotlin → still PRESENT) and the corpus gate stays `VERDICT: PASS`. Honest residue:
     the Java-21 `SequencedCollection` members (`getFirst`/`getLast`/`addFirst`/… on List) still surface
     — left in deliberately pending a data-driven JS-klib diff, since Kotlin 2.x may provide them in JS.
   - Exact overload/signature ranking. `splitPreamble` now preserves parameters and completion
     de-duplication keeps distinct parameter lists; signature-help presentation and ranking still
     need a dedicated pass.
5. **Latency / size** — ESM_SHARED fast path (~343 ms warm, ~5 KB box module) once the union-stdlib
   blocker is solved (see the spike doc); and moving the compile fully in-process with incremental
   caching for the live-edit loop.
6. **Tone & broad A-Frame** — as SEPARATE optional profiles / surface modules, not dumped into the
   RemoteAR core. Each is its own `jsBinding { … }` selected by a marker (`// {{ tone }}` etc.).
   - **Tone: DONE.** `// {{ tone }}` selects the Tone.js computer-music head (`ToneKotlinJs.kt`, its
     own `jsBinding` → compiler + completion façades). Bound slice: the synth family
     (`Synth`/`PolySynth`/`MonoSynth`/`AM`/`FM`/`Duo`/`Membrane`/`Metal`/`Pluck`/`NoiseSynth`), sources
     (`Oscillator`/`Player`/`Noise`), core effects (`Gain`/`Filter`/`Reverb`/`FeedbackDelay`/
     `PingPongDelay`/`Distortion`/`Chorus`/`Volume`), the `Param` value type, `Transport` scheduling
     (`scheduleRepeat`/`scheduleOnce`, `bpm`/`position`/`state`), `Loop`/`Sequence`/`Part`, and the
     top-level helpers (`now`/`start`/`getTransport`/`getDestination`/`dbToGain`/`mtof`/…). Every name
     is grounded against the vendored bundle (`vendor/tone/15.1.22/Tone.js`, whose 184 exports were
     enumerated; `amount` is the one plausible name NOT present, so `Distortion` uses dynamic options +
     the real `distortion` prop). The RemoteAR head installs `Tone` as a global (`index.html` loads it;
     `index_vr` redirects there); the head now reports a `tone` capability in `initialize` + `snapshot`
     + a `tone-loaded` self-test, and the `tone` profile routes `// {{ tone }}` only to a head that has
     it. Verified: `remotejs.kotlinjs-tone-surface` (compile a broad sample through the real compiler),
     `remotejs.kotlinjs-head-profiles` (compiled Kotlin→JS **executed** under node against a mock `Tone`
     — proves the emitted JS calls the bound names at runtime), and the `tone_synth_sequence` demo
     transcript (compiled Kotlin→JS by `examples.demo-transcript.exec`). Audio *playback* needs a real
     browser head + user gesture and is not headlessly verifiable. Broad A-Frame remains.
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
| Vision Metal channel + `msl` property + reload commands | `src/jvmMain/kotlin/afield/graphics/VisionMetal.kt` |
| MSL Monaco language | `src/commonMain/resources/msl.js`, `src/jvmMain/kotlin/afield/graphics/MslEditorLanguage.kt` |
| Completion client / sidecar | `src/jvmMain/kotlin/afield/AaCompletionClient.kt`, `tools/code-use-semantic-sidecar/…/AaCompletion.kt` |
| Runtime the surface mirrors | `src/commonMain/resources/extensions/remote_ar/{ar,field}/*.js` |
| Smokes | `src/jvmMain/kotlin/afield/examples/KotlinJs*SmokeTest.kt`, `KotlinToJsSpikeTest.kt` |

## Standing constraints

- **klib version lockstep** — the vendored `libs/kotlin-stdlib-js-<v>.klib` must match the compiler
  fork; re-vendor on any bump.
- **Shared-sidecar changes** touch regular Kotlin completion too — run the completion corpus gate
  (`tools/completion_diff.py --run --gate`) before merging sidecar edits.
