# Nightly worklog — 2026-07-24 / 1 — RemoteAR motion + assets + richer scene (kind A)

Branch `nightly/2026-07-24/1-remotear-animation-assets`, worktree
`/Users/marc/field4-worktrees/2026-07-24-1-remotear-animation-assets`, based on d8f9eccb.

## Plan (refined against the real code)

Radically extend the RemoteAR Kotlin-in-the-head surface across roadmap items 1/2/3, additively via the
`jsBinding` DSL in `RemoteArKotlinJs.kt` (one model → compiler `external` + completion mirror).

Grounding done (every name verified against the runtime the head actually installs):

- **Item 1 — FLine transform operators.** `fline.js:492–578` implements `__PLUS__`/`__MINUS__`/
  `__MULTIPLY__`/`__DIVIDE__` (each: vector-promote3 OR Matrix4; times/div also accept a number) and a
  public `byTransforming(t)`. Plan: bind compiler-only member funs `__PLUS__`/`__MINUS__`/`__MULTIPLY__`/
  `__DIVIDE__` (dynamic arg) kept OUT of completion, plus public `operator fun FLine.plus/minus/times/div`
  extensions (Matrix4 / Vector3 / Vector2 / Double as grounded) delegating to them; and public
  `byTransforming` (typed `(Double,Double,Double)->Array<Double>` callback). Requires a small additive DSL
  feature: compiler-only member funs (`compilerFn`).

- **Item 2 — assets & animation.** `files.js` installs `loadGLTF(url,name,cb)` (~347), `loadPointCloudPLY`
  (~272), `loadFBX` (129, THROWS unless a FBXLoader is vendored — bind + document), `loadTexture(url)` (780,
  → THREE.Texture), `mapDirectory(onDisk,servedAs)` (9), `loadJSONScene(url,name)` (239). All confirmed in
  the `__superglobal.*` export list. The AnimationMixer is module-local (auto-created + auto-`update()`d per
  XR frame in `webxr.js:76`); user code drives it via the loader callback payload `loaded.play()/stop()/
  apply(t)` (which call `mixer.clipAction(clip)…`). Plan: bind loaders as installed globals whose callback
  is `(loaded: dynamic)->Unit` for the compiler but `(loaded: GltfAsset|FbxAsset|PlyAsset)->Unit` for
  completion (dynamic/typed split on the callback function type) so members complete inside the lambda.

- **Item 3 — richer THREE.** Vendored super-three r0.184.0 re-exports (verified in the export block) every
  name planned: geometries Plane/Cylinder/Cone/Torus/TorusKnot/Circle/Ring; materials MeshStandard/Lambert/
  Phong/Normal/Points/LineBasic; lights Directional/Ambient/Spot/Hemisphere; Group, Points, Line, Texture,
  TextureLoader. Object3D richer props/methods (visible/up/quaternion/castShadow/receiveShadow/renderOrder/
  userData; lookAt/applyMatrix4/traverse/getObjectByName/updateMatrix…) are all standard Object3D.

Verification: extend all three smokes (`remotear-surface` compile-through-real-compiler, `binding-parity`,
`head-profiles` node-exec against a mock head) to cover FLine transforms + a loader callback + a mixer call
(`loaded.play()`) + richer props. Ship a replayable demo transcript and replay it through
`examples.demo-transcript.exec`.

## Execution log — what landed

Commits (on `nightly/2026-07-24/1-remotear-animation-assets`, atop d8f9eccb):

1. `feat(kotlinjs): compiler-only member funs in the jsBinding DSL` — `KotlinJsBinding.kt`:
   `TypeBuilder.compilerFn` + a `JsFun.compilerOnly` flag; the renderer filters compiler-only members
   out of the completion mirror. This is the one small enabling DSL addition (everything else is data).
2. `feat(kotlinjs): RemoteAR motion + assets + richer scene surface` — `RemoteArKotlinJs.kt`, all
   additive `jsBinding` calls (items 1/2/3 — see the roadmap doc for the full grounded list).
3. `test(kotlinjs): cover the new RemoteAR surface in the smokes` — `KotlinJsRemoteArSurfaceSmokeTest.kt`
   (require-checks for the new names in both façades + a broad compile-through-real-compiler sample) and
   `KotlinJsHeadProfilesSmokeTest.kt` (extended RemoteAR node-exec source + mock head + probe).
4. `docs(kotlinjs): replayable RemoteAR scene+motion demo transcript` —
   `dev_docs/agents/transcripts/remotear_scene_motion.yaml` + wired into `DemoTranscriptExecSmokeTest`'s
   `KOTLINJS` set.
5. (this commit) roadmap doc + worklog.

### Files changed (5 code/doc + 1 transcript + this worklog)
- `src/jvmMain/kotlin/afield/graphics/KotlinJsBinding.kt` (DSL: compiler-only member funs)
- `src/jvmMain/kotlin/afield/graphics/RemoteArKotlinJs.kt` (the surface — bulk of the work)
- `src/jvmMain/kotlin/afield/examples/KotlinJsRemoteArSurfaceSmokeTest.kt`
- `src/jvmMain/kotlin/afield/examples/KotlinJsHeadProfilesSmokeTest.kt`
- `src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt`
- `dev_docs/agents/transcripts/remotear_scene_motion.yaml` (NEW demo)
- `dev_docs/kotlinjs_status_and_roadmap.md` (items 1/2/3 → DONE)

## Verification (what I ran + observed — all on FRESH ports to avoid the cross-worktree false-green)

`python3 tools/run_field_tests.py --port 9412 …` (build off this worktree), then a re-run on `--port 9413`
after the honesty edit below:

- `remotejs.kotlinjs-binding-parity` — PASS.
- `remotejs.kotlinjs-remotear-surface` — PASS. Observed: `expanded surface compiled: 19326 chars Kotlin
  -> 10032 chars JS` — the broad sample (FLine transforms, loaders + callbacks, richer geometries/
  materials/lights/Object3D) type-checks against the generated `external` decls. (AA sidecar not
  reachable → live-completion assertions skipped, as designed.)
- `remotejs.kotlinjs-head-profiles` — PASS, and node WAS on PATH so it EXECUTED: stdout showed
  `remote-ar-profile-ok`, i.e. the compiled Kotlin→JS actually called `FLine.__PLUS__`/`__MULTIPLY__`,
  `TorusGeometry`/`MeshStandardMaterial`/`Group`/`DirectionalLight`, `lookAt`/`rotateY`,
  `loadTexture`/`mapDirectory`, and a `loadGLTF` callback firing `gltf.play`/`gltf.apply` — verified
  against the mock head + probe. This is the strongest headless proof.
- `examples.demo-transcript.exec` — PASS. Observed: `[kotlinjs] agent_remotear_scene_motion: PASS
  (remoteAR, 17592 chars Kotlin -> 7409 chars JS)` then `DemoTranscriptExecSmokeTest ok` — the
  hand-written demo box body compiled Kotlin→JS through the REAL `KotlinToJsCompiler` with no error
  diagnostics (the fake-demo class of bug is closed).

### Honesty note found + fixed mid-run
First run printed `gltf meshes=undefined`: the loader callback receiver is `dynamic` for the compiler, so
member names are emitted verbatim — a JS array's length is `.length`, and Kotlin's `Array<>.size` would
NOT be translated. Typing payload array fields as `Array<>` for completion would therefore suggest a
`.size` that resolves to `undefined` at runtime. Fixed: array-valued payload fields (`meshes`/
`animations`/`objects`) are `Any?` in the completion shape (documented in-code), and demo/tests no longer
model `.size`. Re-verified on port 9413 — all green.

## Replay this to see it
`python3 tools/run_field_tests.py --port <free> --id examples.demo-transcript.exec` compiles the demo box
Kotlin→JS through the real engine. The artifact:
`dev_docs/agents/transcripts/remotear_scene_motion.yaml` (a `// {{ remoteAR }}` box exercising an FLine
`+ rotate()/ + vec / * scale` transform, a `loadGLTF` callback whose `play()` drives the AnimationMixer,
and a lit torus-knot `Group` with richer materials/lights/Object3D props).

## Risks / unknowns
- Bindings are grounded against the runtime JS + super-three r0.184.0 export list, but the loaders'
  actual asset fetch + the AnimationMixer's visual playback need a REAL browser/visionOS head + a served
  asset — not headlessly verifiable (same class of gap as Tone audio playback). The node-exec proves the
  emitted JS calls the right names; it does not prove the vendored GLTFLoader parses a real `.glb`.
- `loadFBX` throws unless a FBXLoader is vendored (documented + bound for discoverability only).

## Recommendation: MERGE
Large, coherent, purely-additive surface expansion landing roadmap items 1/2/3. Compiles through the real
K2→JS compiler, stays parity-clean, executes under node against a mock head, and ships a real-engine-replayed
demo. The one runtime-faithfulness subtlety (`.size`) was caught by the node-exec and fixed. No changes to
routing, the shared completion sidecar, or any non-kotlinjs path, so blast radius is contained.
