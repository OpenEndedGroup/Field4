# Vendored: MorphoHDL

Upstream: <https://github.com/paradigms-of-intelligence/morpho>
Commit: `3ff923f9fafcb5b97ad2ef393795c96710ed00ea` (2026-07-25)
License: Apache-2.0 — see `LICENSE`. Copyright 2026 Google LLC.
Not an officially supported Google product.

## What is here, and why only this

Field runs MorphoHDL by hosting morpho's **own** JavaScript on GraalJS and its **own**
WebAssembly on GraalWasm. Nothing in this directory is a port, and nothing has been
edited — the files are byte-identical to upstream. That is the point of the exercise:
the bridge is only honest if it is their compiler producing the netlist, not ours.

| file | role | modified? |
| --- | --- | --- |
| `js/parser.js` | MorphoHDL tokenizer + parser → SSA | no |
| `js/primitives.js` | `SPLIT` / `CAT` / `LSLICE` / `HSLICE` / `REPEAT`, LUT optimisation | no |
| `js/compiler.js` | SoA netlist compiler, width inference, cell expansion, DCE | no |
| `js/utils.js` | `SoA`, `MaxHeap`, `mulberry32`, `range`, `clamp` | no |
| `tiny_morpho.py` | Python reference implementation **and** the MorphoHDL standard library | no |
| `graphs_engine/main.wasm` | 3D Barnes–Hut force layout kernel (zig → wasm32-freestanding) | no |

`tiny_morpho.py` is here for two reasons. It carries the MorphoHDL standard library as
text between the `# MORPHO_BEGIN` / `# MORPHO_END` markers — upstream's JS parses the
same bytes the Python executes, so there is exactly one copy of the language's circuit
library. And it is the differential oracle: `MorphoBridgeTest` compiles a design
through GraalJS and pins its gate count against the numbers this file's own
implementation prints.

## Deliberately not vendored

`viewer.js`, `layout_renderer.js`, `swissgl.js`, `hex_layout.js`, `bloom.js`,
`figures.js`, `demo.js`, `article.js`, `presets.js` — roughly 2,900 lines of canvas /
WebGL rendering and DOM wiring. Field supplies all of that.

`grower.js` is also absent, and it is the one interesting omission. Its expansion
*schedule* (largest-first, chasing single-child chains) is 40 lines we do want, but its
constructor builds a `LayoutEngine`, so importing it drags in the 2D layout we are
replacing. `morpho-field-driver.mjs` reimplements that schedule with the layout
dependency removed. Because the original is not vendored, nothing compares the two
schedules step for step; what `MorphoBridgeTest` pins is the circuit that expansion
arrives at — its gate count against `tiny_morpho.py`, and the driver's two orders
(`largest`, morpho's default, and `bfs`) reaching the same gate count, cell count and
function.

## A note on the WASM

`main.wasm` has **no import section at all** — 11 KB, 68 exports, owns its own linear
memory — so GraalWasm instantiates it with no host shim.

It is genuinely 3D: `points[i*3]`, an octree with a θ criterion, a full 3×3 inertia
tensor for angular-momentum compensation, and SIMD `v128` inner loops. Upstream's
`force_layout.js` nonetheless pins `pos[p*3+2] = 0` and zeroes z-velocity every frame
(lines 259–264), so the shipped demo only ever uses it in the plane. Field drives it
without that constraint.
