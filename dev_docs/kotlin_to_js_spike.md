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
  source→klib→JS round-trip is mandatory.
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

Not yet done for kotlinjs boxes: completion (returns empty — the K2 Analysis-API completion is
JVM-classpath-based; wiring it to the JS surface is a follow-up), and the ESM_SHARED fast path.

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
4. **Typed runtime façades.** Started with the deliberately small visionOS Live Metal surface in
   `graphics/VisionMetalKotlinJs.kt`. Its one declaration source wraps the head's real `onFrame`,
   `uniform`, and `print` globals as a typed `VisionMetalFrame`, checked uniform arities, and `log`.
   It is appended after user source so compile diagnostics retain user line numbers, and it is also
   suitable as completion preamble input. `visionmetal.kotlinjs-externals` compiles a real snippet
   and links the generated module against a protocol-faithful host shim. Automatic surface
   selection and delivery of that compiled module to the native Live Metal JavaScriptCore context
   are the next integration step; the existing `visionMetal(..., script = ...)` path remains the
   live native transport today. THREE/FLine, A-Frame, Tone, and broader browser declarations remain
   scoped follow-ups rather than one giant declaration dump.
5. **Language registration.** ✅ DONE — `mainLanguage = "kotlinjs"` (`KotlinJs.kt`), live-tested.
6. **Fork-version guard.** A build/CI check that `libs/kotlin-stdlib-js-<v>.klib` matches the
   compiler fork version, so a compiler bump can't silently break JS compiles.

## Repro

```
python3 tools/run_field_tests.py --id remotejs.kotlin-to-js-spike        # correctness
python3 tools/run_field_tests.py --id remotejs.kotlin-to-js-benchmark    # latency/size table
```
