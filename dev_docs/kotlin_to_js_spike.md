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

The driver spawns `java -cp <this app's classpath> org.jetbrains.kotlin.cli.js.K2JSCompiler …`
rather than calling `K2JSCompiler().exec(...)` in-process. Reason: the K2 REPL already holds a live
IntelliJ `CoreApplicationEnvironment` singleton in this JVM, and `K2JSCompiler` builds its own —
they clash. The subprocess isolates the JS compile and reuses the embeddable compiler already on
our runtime classpath.

Cost: a cold `java` launch + full two-phase compile per run (~seconds). Fine for a spike; too slow
for a live editing loop.

## Path to production (follow-ups, roughly in order)

1. **In-process compile.** Call `K2JSCompiler` in-process with an isolated `Disposable`/application
   environment (or a dedicated classloader) so it coexists with the REPL. Removes the per-run JVM
   launch.
2. **Incremental.** Wire `JsIrCompilerWithIC` + persistent caches so box edits recompile in ~ms,
   matching the JVM REPL's feel. (There is no JS *REPL*; whole-module recompile per box is the unit.)
3. **Typed runtime façade.** The real bulk of the work: Kotlin `external` declarations / a DSL over
   the head's runtime (THREE, A-Frame, Tone, Field's `field/*.js`). Without this, Kotlin buys
   nothing over raw JS. This is scoped, incremental work — not a research risk.
4. **Language registration.** Register a `mainLanguage = "kotlinjs"` runtime (mirror
   `installRemoteJSMainLanguage` / `applyRuntimeToggle`) so a box can be flagged Kotlin-for-the-head
   and route through `compileAndSendKotlin` from `RemoteJSExectution`.
5. **Fork-version guard.** A build/CI check that `libs/kotlin-stdlib-js-<v>.klib` matches the
   compiler fork version, so a compiler bump can't silently break JS compiles.

## Repro

```
python3 tools/run_field_tests.py --id remotejs.kotlin-to-js-spike
```
