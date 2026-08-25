# Spike: Kotlin source → JavaScript for RemoteJS

**Status:** spike (branch `spike/kotlin-to-js-remotejs`). Proves the seam end-to-end; not yet a
user-facing language.

## Why

RemoteJS runs user-authored **JavaScript** inside a remote browser / visionOS "graphics head"
(A-Frame / Three.js / WebXR / RealityKit) over the WebSocket bus. The AR axis and the visionOS
graphics axis are both clients of that same bus. We already carry a large Kotlin-compiler
investment (the K2 REPL, `KotlinInterface2`). This spike asks whether we can offer the same thing
JetBrains' [`kotlin-compiler-server`](https://github.com/JetBrains/kotlin-compiler-server) does —
self-contained Kotlin source → JS, served from our own web server — so a RemoteJS box can be
written in Kotlin instead of raw JS.

The prize is more than a language swap:

- **Native operator overloading.** ASTA (`Asta.kt`) exists *only* because JS has no operator
  overloading — it rewrites `a + b` → `__PLUS__(a, b)` via a Nashorn ES6 parse so vector/matrix
  math works. Kotlin has `operator fun plus` natively, so a Kotlin box needs **no ASTA**.
- **Real types + completion** (we already have the K2 Analysis-API completion machinery) and
  **compile-time diagnostics at the Kotlin source line**, instead of round-tripping syntax errors
  through the browser (`preferAstaLocationForBrowserSyntaxError`).

## The two strategies (and the one that fits)

- **(A) Build-time, whole-module KMP compilation** — the `js {}` target that builds `afield.mjs`,
  and the deferred `src/jsVision/` fork. KMP's source-set model fights a clean second bundle; this
  is the path that has bitten us before. *Not* what RemoteJS wants.
- **(B) Runtime, per-snippet compilation via `K2JSCompiler`** — what `kotlin-compiler-server` does
  and what this spike implements. On demand: small Kotlin source string → JS string → existing
  socket transport. Sidesteps the KMP source-set problem entirely.

## What the spike proved

The JetBrains two-phase compile reproduces exactly with our bundled fork compiler:

```
phase 1:  source.kt   --( -Xir-produce-klib-file )-->  <module>.klib
phase 2:  <module>.klib --( -Xir-produce-js -Xinclude=… -module-kind plain )-->  <module>.js
```

- The bundled `libs/kotlin-compiler-embeddable-2.4.255-SNAPSHOT.jar` carries the **entire Kotlin/JS
  IR backend** (`K2JSCompiler`, `ir.backend.js.*`, klib tooling) — nothing new to download.
- The produced JS is a **`plain` module**: an IIFE that self-invokes `mainWrapper()`. It is
  directly eval-able — which is exactly what the head's `global.eval(code)` (`field.js`) needs.
- Kotlin `operator fun plus` compiled and ran (`V(1,2) + V(10,20)` → `11,22` under Node). ASTA-free.
- Compile errors come back as structured diagnostics (`path.kt:LINE:COL: error: …`).

### The one hard caveat — klib version lockstep

The fork compiler **refuses** to link the stock Maven `kotlin-stdlib-js-2.4.0.klib`:

> error: the Kotlin/JS standard library has an older version (2.4.0-release-281) than the compiler
> (2.4.255-SNAPSHOT). Such a configuration is not supported.

So the Kotlin/JS stdlib klib must be built **from the same fork**. We vendor it (via git-lfs):

```
libs/kotlin-stdlib-js-2.4.255-SNAPSHOT.klib   (sourced from ~/temp/kotlin/dist/kotlinc/lib/kotlin-stdlib-js.klib)
```

Any future compiler-fork bump must re-vendor this klib in lockstep. This is the maintenance cost of
the approach.

## What the spike built

- `src/jvmMain/kotlin/afield/remotejs/KotlinToJsCompiler.kt` — the driver. `compile(kotlinSource)`
  → `{ js, diagnostics, success }`.
- `src/jvmMain/kotlin/afield/remotejs/RemoteJS.kt` :: `compileAndSendKotlin(box, kotlinSource)` —
  compiles, reports diagnostics on the box, ships the JS via `executeRemoteJS(doASTA = false)`.
- `src/jvmMain/kotlin/afield/examples/KotlinToJsSpikeTest.kt` — smoke test
  (`remotejs.kotlin-to-js-spike`), runs in-process from a Field JVM; node-executes the JS if node
  is present.

### Deliberate spike shortcut: subprocess, not in-process

The driver now defaults to **IN_PROCESS** (`K2JSCompiler().exec(...)`), with the subprocess as an
automatic fallback (`-Dafield.kotlinjs.mode=subprocess` forces it). The feared clash with the K2
REPL's live IntelliJ `CoreApplicationEnvironment` did **not** materialize — the in-process compile
runs fine alongside the REPL (proven by `remotejs.kotlin-to-js-benchmark`, which asserts
`mode == IN_PROCESS`). The subprocess remains as a safety net (`java -cp <app classpath>
K2JSCompiler …`, reusing the embeddable compiler already on our runtime classpath).

## Latency & output size (measured, warm, in-process, `remotejs.kotlin-to-js-benchmark`)

A "medium" program (data classes, `operator` overloads, collections, `kotlin.math`, sequences):

| profile         | median | output          | notes |
|-----------------|--------|-----------------|-------|
| PLAIN_DCE       | ~660ms | 56 KB           | self-contained, eval-able, DCE'd |
| ESM_SHARED      | ~860ms | 4.7 KB box mod  | per-module; re-emits stdlib each compile (slower) |
| PLAIN_DCE + IC  | ~370ms | **620 KB**      | fast, but ships full stdlib every edit |
| **ESM_SHARED + IC** | **~343ms** | **4.7 KB box mod** | **fastest AND smallest** (cold populate ~1.05s) |

Key findings:

- **DCE is not the latency cost** — it's actually cheaper to emit (less code). The ~660ms floor is
  the two-phase pipeline; per-module ES is *slower* uncached because it re-emits the full stdlib.
- **Single-pass is impossible**: K2 rejects producing a klib and JS in one invocation — the
  source→klib→JS round-trip is mandatory, which is why the compiler in this tree is written as two
  phases and not one (`KotlinToJsCompiler.kt#-Xir-produce-js, -Xinclude`).
- **The latency lever is incremental compilation** (`-Xcache-directory` + `-Xir-build-cache`): the
  stdlib IC cache is built once and reused, ~halving warm latency (660→~350ms).
- **DCE and IC are mutually exclusive.** DCE is whole-program (can't cache); IC is per-module (ships
  everything). So the only way to get *both* fast compiles and a tiny wire payload is **ESM_SHARED +
  IC**: ship the stdlib to the head once, and each edit is a fast IC compile emitting a ~5 KB module
  that `import`s it. This vindicates the "per-module + shared stdlib" instinct — via IC, not DCE.

## Open blocker: the shared full-surface `kotlin-kotlin-stdlib.mjs`

ESM_SHARED box modules do `import { … } from './kotlin-kotlin-stdlib.mjs'`, and the box↔stdlib
import mechanism is proven to run in Node **when the box's own paired stdlib is present**. But for
"ship once" the shared stdlib must export the stdlib's *complete* public surface — and Kotlin/JS
per-module output **prunes stdlib exports to the current compilation's usage** (verified: `fun
main(){}` → 0-export stdlib; Field's own build → 369–502 aliases scoped to afield). No prebuilt
full-surface stdlib `.mjs` exists in the fork, and compiling the stdlib klib as its own main module
(`-Xinclude=<stdlib.klib>`) throws in the JS backend. `KotlinToJsCompiler.emitSharedStdlibMjs()`
therefore currently throws rather than emit a silently-incomplete stdlib.

Candidate resolutions:
1. Run the stdlib's own JS build once in `~/temp/kotlin` (`-Xstdlib-compilation`) to produce the
   canonical full-surface `kotlin-kotlin-stdlib.mjs`; vendor + serve it statically.
2. Per-session "growing" stdlib: on an import-resolution miss, rebuild a stdlib superset.
3. Fall back to PLAIN_DCE (self-contained) for boxes whose imports miss the shared stdlib.

## Live in Field (PLAIN_DCE) — working

A `mainLanguage = "kotlinjs"` runtime is wired and **tested live against a running Field + a
connected head**:

- `KotlinJs.kt` — `KotlinJsExecution : BoxExecution` + `installKotlinJsMainLanguage` (mirrors the
  RemoteJS registration). A box flagged `kotlinjs` (or legacy `isKotlinJsRoot`) has its children
  compiled Kotlin→JS (in-process, PLAIN_DCE) and shipped over the RemoteJS WebSocket transport.
  Compiles run on a background pool so the runloop never blocks.
- End-to-end result (real Field server + MCP/backdoor authoring + a protocol-faithful head):
  - valid box → head received a 4.7 KB module (`noSandbox=true`) → ran → `field-live-kotlinjs: 11,22`
    (native `operator fun plus`, no ASTA);
  - broken box → `Initializer type mismatch: expected 'Int', actual 'String'` surfaced on the box,
    and **nothing** was sent to the head.

### Editor ergonomics

- **Kotlin syntax highlighting** — `kotlinjs.js` registers a distinct Monaco language `kotlinjs`
  that reuses Monaco's official Kotlin grammar, so head boxes highlight as Kotlin without attaching
  the JVM-Kotlin providers/diagnostics that would flag `external` etc. (`code_language = "kotlinjs"`).
- **Implicit `fun main`** — `KotlinToJsCompiler.ensureImplicitMain`: a box with no top-level
  `fun main(` gets its file-scope-only lines (`import`/`package`/`@file:`/`external`) hoisted and the
  rest wrapped in a synthesized `main` (author data classes / `operator fun`s / statements become
  locals). An explicit `fun main` disables wrapping.

### Completion (Stages 1 + 2 — done)

KotlinJS boxes get K2 Analysis-API completion **scoped to the Kotlin stdlib** (no JVM classpath /
afield leak), plus the selected head profile's declaration model, plus the box's own declarations
(Stage 1). **Stage 2** fixed the sidecar so Kotlin **extension functions**
surface on prefixed dot-completion (`xs.m` → `map`/`mapNotNull`, `s.up` → `uppercase`) — two fixes in
`AaCompletion.completeAt`: (a) compute extensions even with no selector (right after `.`), and (b) a
receiver-conformance fallback (exact then erased-classId `isSubtypeOf`) because the IDE
applicability checker returns nothing for binary-index extensions in a standalone AA session. Gated
on a non-empty prefix so bare `.` stays fast. Benefits regular Kotlin completion too — run the
completion corpus gate before merging that shared-sidecar change.

**Stage 3** adds a separate file-scope `declarations` field to the completion request. A head keeps
its real Kotlin/JS externals and a small JVM-analysis-safe mirror together; the latter is parsed as
file declarations rather than flattened into unrelated locals. The AA sidecar now enumerates
source classifiers and nested type-scope classifiers, which makes namespace-shaped APIs work:
`THREE.Ve` offers `Vector2`/`Vector3`, constructed vectors and `FLine` expose members, and
`onFrame { frame -> frame.pred… }` infers `VisionMetalFrame.predictedDisplayTime`. Public profile
functions/properties are also reduced to local stubs so bare-name discovery and named parameters
continue to work.

Remaining: **Stage 2b** — filter JVM-only members (`parallelStream`/`spliterator`/`toArray`) that
don't exist in a JS head; and the ESM_SHARED fast path.

## Head profiles

The first non-blank source line can select the compiler surface and destination:

```kotlin
// {{ remoteAR }}
```

or:

```kotlin
// {{ visionmetal uniforms=phase,amount,tint }}
```

`KotlinJsHeadProfiles` resolves the marker once and owns the declaration preamble, connected-head
count, transport filter, and stop behavior. Fragment and symbol execution inherit the marker from
the box's full `code` property. Unknown explicit markers fail locally; unmarked source retains the
legacy behavior of broadcasting self-declared Kotlin/JS to all RemoteJS clients.

`remoteAR` injects the curated THREE/FLine declarations in `RemoteArKotlinJs`, and sends only to
RemoteJS clients whose initialize payload advertises both A-Frame and THREE.

`visionmetal` injects `VisionMetalKotlinJs` (lifecycle + `uniform` + the `geometry { … }` mesh
builder), compiles the box code to the native script, takes MSL from the box's **`msl`** property, and
publishes on the replayable `__visionmetal__` channel. `box.fragment` is still read as a fallback so
the original proof-of-concept box keeps rendering, with a notification saying so — it named a GLSL
fragment stage and could not describe a vertex function, which is why `msl` exists.

The channel is **wire version 2**: shader, script, uniforms and geometry are independently revisioned
parts, and a message carries only what changed. That is what makes the reload commands in
`VisionMetal.kt` meaningful — `updateShader` recompiles the pipeline while the JavaScriptCore context,
the vertex buffers and the frame clock stay resident. Geometry defaults to
`VisionMetalGeometryOrigin.SCRIPT`: the box's Kotlin/JS owns the buffers, uploading them through a
`__fieldSetGeometry(Float32Array, Int32Array)` bridge. Uniform slots use explicit marker order when
supplied; otherwise first-literal-`uniform("name", …)` inference applies *and notifies*, because the
head turns slot order into `#define u_NAME frame.user[i]` and silent inference can disagree with what
the shader source expects.

## Path to production (follow-ups, roughly in order)

1. **Shared full-surface stdlib** (the blocker above) — prerequisite for ESM_SHARED shipping.
   NOTE: not a buildable file — Kotlin/JS emits the stdlib JS usage-scoped per compilation, so
   independent boxes need a *union / accumulating* stdlib (regenerate on first-use of a new symbol),
   or the stock stdlib's own `-Xstdlib-compilation` build. The Build Tools API (2.4.20+) is the
   clean in-process/incremental replacement for the hand-driven `K2JSCompiler().exec` driver, but it
   does not change this module-model constraint.
2. **Head-side ESM loading.** The head runs `global.eval(code)`; ESM `import` can't be eval'd. Load
   the box module via dynamic `import()` of a blob URL (or `<script type=module>`), with the import
   specifier rewritten to the absolute URL where the head serves the shared stdlib.
3. **Per-session IC cache wiring.** `compileAndSendKotlin` passes a stable per-session `cacheDir`
   (and a stable per-box `moduleName`) so edits hit the warm ~350ms path.
4. **Typed runtime façades.** ✅ PROFILE SPINE DONE — `// {{ remoteAR }}` and
   `// {{ visionmetal }}` select declarations, completion context, transport, and stop semantics.
   The Live Metal façade covers its complete deliberately-small JS host. The initial RemoteAR
   façade covers scene objects, vectors, basic meshes, FLine construction, stage, and the frame
   loop; extending THREE/A-Frame/Tone remains incremental rather than one giant declaration dump.
5. **Language registration.** ✅ DONE — `mainLanguage = "kotlinjs"` (`KotlinJs.kt`), live-tested.
6. **Fork-version guard.** A build/CI check that `libs/kotlin-stdlib-js-<v>.klib` matches the
   compiler fork version, so a compiler bump can't silently break JS compiles.

## Repro

```
python3 tools/run_field_tests.py --id remotejs.kotlin-to-js-spike        # correctness
python3 tools/run_field_tests.py --id remotejs.kotlin-to-js-benchmark    # latency/size table
```
