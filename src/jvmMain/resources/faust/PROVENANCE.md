# libfaust, as WebAssembly

These four files are the [Faust](https://faust.grame.fr) compiler itself, taken verbatim from the
npm package `@grame/faustwasm` **0.16.6** (which bundles **libfaust 2.86.2**):

| File | Bytes | What it is |
|---|---|---|
| `libfaust-wasm.wasm` | 3,102,755 | the Faust compiler, compiled to WebAssembly |
| `libfaust-wasm.data` | 2,263,644 | the Faust standard library (`stdfaust.lib` and friends), as an Emscripten preload package |
| `libfaust-wasm.js` | 177,063 | Emscripten's loader glue for the above |
| `COPYING.txt` | — | the licence, copied from the package root |

Nothing here is modified. `FaustEngine` loads them at runtime and never writes to them, which is
both the LGPL-3.0 arrangement these are offered under (§4: the library stays replaceable — drop in
a newer `@grame/faustwasm` build and Field picks it up) and the reason the files are checked in
rather than generated.

## Why vendored rather than installed

This is the entire cross-platform install story, and it is the reason this approach was chosen over
shelling out to a native `faust`.

WebAssembly is platform-independent. These bytes are the same on macOS, Linux and Windows, on x86_64
and aarch64, and they run inside the GraalVM WebAssembly runtime Field already depends on for
`dough.wasm`. So a Faust box requires:

* no `faust` on `PATH`,
* no LLVM (a stock `libfaust` links against it, and is ~100MB per platform),
* no C++ toolchain, no `faust2*` scripts,
* no Homebrew / apt / vcpkg step, and no per-platform binary in `libs/`,
* nothing for the user to install at all.

Contrast `SonicPi.kt`, which can only work if Sonic Pi happens to be installed locally, and whose
"owned mode" exists to work around that. A Faust box works on a fresh clone.

The cost of that choice is 5.3MB in the repository and a slow compiler: GraalWasm runs libfaust
roughly 20× slower than V8 does (~600ms per compile once warm, versus ~30ms in Node), and the first
compile of a session pays 13–22s of Truffle warm-up. `FaustEngine` answers that with a background
boot warm-up and a source-hash cache rather than with a native toolchain. See `FaustEngine`'s KDoc
for the measurements.

## Updating

Bump the version, re-copy the four files, and re-run the smoke test:

```bash
npm pack @grame/faustwasm@<version>
```

`FaustEngine.libfaustVersion()` reports what actually loaded, and the smoke test asserts it matches
the version recorded here — so an update that changes the compiler cannot pass unnoticed.
