# Nightly worklog — 2026-07-24 / kind C — "Kotlin in the head" worked example

Branch: `nightly/2026-07-24/3-kotlin-in-the-head-worked-example` (based on d8f9eccb)

## Mission
Surface the KotlinJS "code-in-the-head" runtime to the PUBLIC docs site as a worked
example (`public/docs/kotlin_in_the_head.md`), in the house style of the other public
worked-example pages. Explain `mainLanguage = "kotlinjs"`, the profile markers
(`// {{ remoteAR }}` / `// {{ visionmetal }}` / `// {{ tone }}`), and a concrete
data-class + operator-overload worked example. HONESTY: the WebXR/audio payoff is not
headlessly capturable — the reproducible evidence is the REAL JS the compiler emits.

## Plan
1. [x] Read existing public worked examples (fline_examples, rectsl_examples), the public
   `kotlin_overview.md`, and the dev roadmap `dev_docs/kotlinjs_status_and_roadmap.md`.
2. [x] Read + grep-confirm the binding model:
   - `RemoteLifecycleKotlinJs.kt` (shared: onStart/onFrame/onStop/keepAlive/HeadFrame)
   - `RemoteArKotlinJs.kt` (THREE + vec/scene/stage/operators/log/addBox/...)
   - `ToneKotlinJs.kt` (synths/effects/Transport/scheduleRepeat/...)
   - `VisionMetalKotlinJs.kt` (uniform/log + uniforms= marker option)
   - `KotlinJsHeadProfiles.kt` (marker parsing + prepareSource)
   - `KotlinToJsCompiler.kt` (K2→JS pipeline, implicit-main wrapping, PLAIN_DCE)
   Every code sample's API is drawn verbatim from these + the smoke tests
   (`KotlinToJsSpikeTest.kt`, `KotlinJsHeadProfilesSmokeTest.kt`).
3. [ ] Add a tiny additive evidence entrypoint that drives the REAL `KotlinToJsCompiler`
   on the worked-example source and dumps the emitted JS (a FIELD_TEST jvm-main). Keep
   it + its output in this worktree. Writes only to build/ (no committed test writes).
4. [ ] Run it via `python3 tools/run_field_tests.py --id <id> --port <free>` and read FULL
   output. Embed a faithful excerpt of the REAL emitted JS in the doc.
5. [ ] Write `public/docs/kotlin_in_the_head.md`; cross-link `kotlin_overview.md`.
6. [ ] Wire into `public/zensical.yml` nav.
7. [ ] Finalize worklog + stop any JVM I started.

## Notes / grounded surface
(see the four binding files — all names below are grep-confirmed)
- shared: onStart{}, onStop{}, onFrame{ frame -> frame.time/.deltaTime/.frameNumber/.predictedDisplayTime }, animate{}, keepAlive()
- remoteAR: THREE.Vector2/3/4 (+,-,* operators via clone().add/sub/multiplyScalar), THREE.Mesh,
  BoxGeometry, SphereGeometry, MeshBasicMaterial, Color, PointLight; scene/camera/SCENE/CAMERA/stage,
  vec(...), clearAll(), log(...), addBox(name,size), addPointLight(name), FLine.moveTo/lineTo/cubicTo
- tone: Tone.PolySynth/Synth/Reverb(decay)/getTransport(); transport.bpm.value; scheduleRepeat(cb,"4n");
  synth.triggerAttackRelease("C4","8n",time); connect/toDestination/dispose; Tone.start(); Tone.mtof(midi)
- visionmetal: `// {{ visionmetal uniforms=phase,amount,tint }}`, uniform(name, x[,y,z,w]), log(...)

## Files changed
- `public/docs/kotlin_in_the_head.md` (NEW) — the deliverable. Worked-example page.
- `public/zensical.yml` — nav entry "Kotlin in the Head" after "Kotlin Overview".
- `src/jvmMain/kotlin/afield/examples/KotlinInTheHeadEvidence.kt` (NEW) — additive jvm-main
  FIELD_TEST `docs.kotlin-in-the-head.emitted-js` that regenerates the emitted-JS evidence.

## Verification (HONEST)
- **REAL compiler output (reproducible):** ran
  `python3 tools/run_field_tests.py --refresh-catalog --id docs.kotlin-in-the-head.emitted-js
  --port 9611` → **PASS** (9247 ms). The test drives the real `KotlinToJsCompiler` (IN_PROCESS,
  PLAIN_DCE) through the real `remoteAR`/`tone` `prepareSource` path on the doc's exact sources:
  vectors 258 chars Kotlin → 4912 chars JS; ring + bells also compiled clean. The emitted-JS block
  quoted in the page (`function main$V`, `protoOf(main$V).plus_oi8stp_k$` / `times_qz1dds_k$`,
  `toString`, the `mainWrapper()` self-invoke) is a byte-faithful excerpt of that output, with only
  the stdlib runtime prelude elided (marked). Teaching point verified live: DCE dropped the unused
  `equals`/`hashCode`/`copy`/`componentN` — the doc says so honestly rather than claiming them.
  Note: my first run FAILED because I asserted `class V` (the plain module emits `function main$V`);
  fixed the assertion to match actual output — a good honesty check that the evidence is real.
- **API grep-confirmation:** every name in every code sample is grounded in the binding model —
  RemoteLifecycleKotlinJs (onStart/onFrame/onStop/keepAlive/HeadFrame), RemoteArKotlinJs (THREE.*,
  vec/addBox/clearAll/scene/stage/log, +/-/* operators), ToneKotlinJs (PolySynth/Reverb/getTransport/
  bpm/scheduleRepeat/triggerAttackRelease/connect/toDestination/dispose/Tone.start), VisionMetalKotlinJs
  (uniform, `uniforms=` marker option). Confirmed by direct grep of the four `*.kt` surfaces.
- **NOT headlessly verified (stated plainly in the page):** the actual head payoff — WebXR pixels
  moving, Tone audio playing — needs a real browser / Vision Pro + (for audio) a user gesture. No
  screenshots were faked. The page marks every such spot ("open this in a WebXR head to see it move").
- **Docs render NOT run:** I did not run the full Zensical docs build (heavy, ~30+ min, unrelated-flake
  risk). Verified instead: `public/zensical.yml` parses and its nav includes `kotlin_in_the_head.md`;
  the page references NO image/mp4/svg assets, so `tools/check_doc_assets.py` has nothing to fail on.
  I therefore do NOT claim an observed site render.

## Risks / unknowns
- Kotlin/JS mangled method names (`plus_oi8stp_k$`) are compiler-version-specific; if the vendored
  fork bumps, the quoted excerpt drifts. Mitigated: the entrypoint regenerates it and asserts the
  structural pieces (`function main$V`, `.plus_`, `.times_`) rather than the exact hash.
- The page describes the head payoff it cannot show; that gap is inherent to kind C and is disclosed.

## Recommendation: MERGE
The deliverable exists, is nav-wired, every API is grep-confirmed, and the sole load-bearing evidence
(emitted JS) is real, reproducible compiler output guarded by a committed additive smoke. Optional
follow-up (had more time): a `capture=ui`-style live-head recording once a WebXR/Vision head is
scriptable, and folding more of the Tone/THREE surface into the page.
</content>
