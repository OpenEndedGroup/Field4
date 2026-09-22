# `GpuProbe` — a compute shader on a bench

`src/jvmMain/kotlin/afield/four/util/GpuProbe.kt`

Fixture buffers, a dispatch chain, evidence that it ran, and numbers printed next to the bounds
they were compared against. Usable from `jvmMain` and from a box, with the same API.

---

## Why it exists — the census

The same forty lines are written out longhand every time anyone wants to know whether a compute
shader is right. Allocate buffers. Upload a `FloatArray`. Build a `ComputeModule` from a GLSL
string, hang a `ComputeMesh` off it with named slots bound, append a barrier, register it on the
renderer. Pump frames — some number of them, chosen by feel. Wait the compute fences. Read a buffer
back. Compare against a hand-tuned threshold. `println` a tag. Remember the `finally` that removes
the module.

**Six files in this repository do exactly that**, each independently:

| file | lines | removes its module afterwards? |
| --- | --- | --- |
| `examples/four/GpuSkinningSmoke.kt` | 685 | via `renderer.cleanup()` only |
| `examples/four/NeuralFieldParitySmoke.kt` | 391 | yes, by hand |
| `examples/four/ShaderFieldQuantileWarpSmokeTest.kt` | 367 | no |
| `examples/four/GpuNeighbourGridSmoke.kt` | 279 | `grid.removeFrom` + `cleanup` |
| `examples/four/ShaderFieldAutoControlSmokeTest.kt` | 234 | no |
| `examples/four/FieldStatsSmokeTest.kt` | 156 | no |

The four that do not remove their module get away with it only because they `exitProcess`
immediately afterwards.

And **34 of the 82 boxes** in one live workspace document
(`~/Documents/AFieldWorkspace/light_four`) are the same idiom, by name: `Temporary sensor
reprojection checks`, `Temporary light pressure checks`, `Temporary grain coherence probe`,
`Temporary dust lifecycle test`, `Motion_readback`, `Salvage_readback`, `enemy verification`,
`Refactor verification`, and so on. Every one carries `"_tombstone" true` and `"autorun" false` —
they are retired by hand rather than by a facility.

Two of those authors independently invented the same two workarounds, which is the signal that they
belong in a facility rather than in a box:

- **"did my module actually run?"** — `GpuSkinningSmoke` solves it with a `SENTINEL = -98765.0f`
  prefill plus a three-second wall-clock warm-up; the human's `Temporary dust lifecycle test`
  solves it with `// Let initial GPU zero fills complete before uploading the fixture.` and
  `repeat(4) { frame() }`;
- **cleanup** — a hand-written `finally { renderer.computeList.modules.remove(key) }` in every box.

### What already existed, and why it is not this

Checked by grepping for the *mechanism*, not the name:

- **`afield.four.util.FieldProbe`** (1098 lines) is the closest thing and is the other half of the
  problem. It is an in-shader *sample tap*: a GLSL include (`fieldProbe`) plus a ring buffer at
  descriptor binding 63 that a running shader writes samples into, read back asynchronously and
  drawn as a histogram. It instruments a shader that is **already running in a scene**, for
  exploratory looking. It allocates no fixtures, uploads nothing, assembles no module, pumps no
  frames, has no sentinel, no thresholded assertion and no teardown. `GpuProbe` builds the scene
  and grades the answer; the two compose, and a stage's GLSL may include `fieldProbe`.
- **`afield.four.util.FieldStats`** (790 lines) is a GPU reduction — a measurement primitive a
  probe would *use*.
- **`GpuNeighbourGrid` / `GpuRadixSort`** each have an `install` / `removeFrom` / `cleanup` triple,
  which is the closest existing idea to managed teardown, but per-component and hand-written.

---

## The shape

```kotlin
gpuProbe(renderer, "my-probe", pump = { window.pollEvents(); renderer.render() }) { probe ->
    val input  = probe.fixture("input", 1024)          // streamReads(), always
    val output = probe.fixture("output", 1024)
    probe.upload(input, myFloats)

    probe.stage("doubling", glsl)                      // one shader, one compile
        .dispatch(groups = 16) { bind("input") { input }; bind("output") { output } }

    probe.settle(output)                               // arm, pump until written, fence
    val got = probe.read(output)
    probe.atMost("worst |error|", worstError(got), 1e-5f, buffer = "output")
    probe.verdict("doubling")                          // returns the failure count
}                                                      // close() removes every module
```

From a box, the pump stays with the box because `frame()` is only available inside `frames { }`:

```kotlin
val probe = GpuProbe(renderer, "my-probe")
val out = probe.fixture("out", 256)
probe.stage("check", glsl).dispatch(groups = 4) { bind("out") { out } }
frames {
    try {
        probe.arm(out)
        while (probe.stillWaiting()) frame()
        probe.verdict("check") { println(it) }     // NB the print sink -- see below
    } finally { probe.close() }
}
```

### A chain is the unit, not a dispatch

A `Stage` is one GLSL source and one shader compile with as many `dispatch` calls behind it as you
like, each with its own bindings and each followed by a barrier. Ordering is structural:
`ComputeTarget.modules` is a `LinkedHashMap` executed in iteration order, and a `ComputeModule`'s
`meshes` likewise, so stages run in creation order and dispatches in insertion order. Twenty-four
Jacobi iterations are therefore **one** stage and twenty-four dispatches, not twenty-four compiles.

---

## The four traps it closes by construction

1. **A buffer the host reads back must ask for it.** `fixture` always applies `streamReads()`
   (`HOST_VISIBLE | HOST_CACHED`). A plain `hosted()` buffer lands in write-combined memory where
   host reads are pathological; a `zeroInit()` one is `DEVICE_LOCAL`. Not offered as an option,
   because no probe wants it wrong.
2. **A frame count is not a wait.** A `ComputeModule`'s shader compiles asynchronously and a module
   whose shader is not ready **dispatches as a silent no-op** — the "not valid" branch in
   `ComputeModulePointer.render` has its `println` commented out. So `repeat(4) { frame() }` and
   "three seconds" are both hopes. `arm` prefills the sentinel and `stillWaiting` is a *condition*
   with a deadline, and the deadline's message names the fixture, the count and the first unwritten
   index.
3. **A slot name is not checked.** `Structure.bind(name) { … }` accepts *any* string; only
   `bindPushConstant` reports a miss, and none of the six hand-rolled probes use it. A typo binds
   nothing and the shader reads a null device pointer. `Bindings.bind` validates against the
   shader's introspected push constants and lists the slots it does have.
4. **Teardown.** `close()` removes every module the probe registered, and `gpuProbe { }` is
   `use`-shaped so it happens even when the block throws.

### Two things deliberately *not* done

- **No host-read barrier.** `afield.four.modules.hostReadBarrier` exists (compute write → host
  read) and is used by none of the six probes, which looked like a latent bug. It is not:
  `VulkanBuffer.mapAndRead` already calls `vmaInvalidateAllocation` over the range before mapping,
  and `landed()` waits the window's compute fences, which is the execution dependency. A probe
  reading mid-frame *would* need the barrier, and this is not the facility for that.
- **No headless path.** `afield.four` has no headless mode: a compute-only dispatch still needs a
  `Window` for the device to exist, and an awake display. Building one is a larger, different job.

### The print sink, and where lines land

`verdict(title, print = ::println)` takes its output sink because box code's `println` is rewritten
by the K2 REPL into `_printlnHere(line, …)`, which posts to *that box's* output pane, while a
`println` inside the library is an ordinary one and goes to the server's stdout. A probe driven
from a box and using the default would print its table where nobody looking at the box can see it.
Passing `{ println(it) }` from box code fixes it, because the rewrite happens at the call site.
`verdictLines(title)` returns the table as `List<String>` for anywhere else.

---

## Verification, and the numbers

### `four.gpu-probe.pressure-chain` — a probe over a chain

`src/jvmMain/kotlin/afield/examples/four/GpuPressureProjectionProbe.kt`

```
tools/run_field_tests.py --id four.gpu-probe.pressure-chain
```

Divergence → 16 Jacobi pressure sweeps → project, on a 64×64 MAC grid with solid walls: three
stages, 18 dispatches, 4,096 cells. MAC (staggered) and not collocated on purpose — `u` on the left
x-face and `v` on the bottom y-face, forward-difference divergence and backward-difference
gradient, so the two are exact adjoints and `div · grad` is precisely the 5-point Laplacian the
Jacobi stage inverts. The collocated alternative has a checkerboard null space the pressure solve
cannot see, and a probe built on it would report a small divergence for a field carrying a large
invisible one.

Measured on an M1 Max, 2026-09-22, three other Field JVMs live on the same GPU. **Bit-identical
across two runs**, which is what a chain with no atomics and no race over a fixed fixture should
be, and is itself evidence there is no race:

```
--- chain: 3 stages, 18 dispatches over 4096 cells (64x64), 16 Jacobi sweeps per frame
  cold start: landed after 1 pumped frames (three shader compiles)
  divergence pass vs closed form: worst |error| 1.192e-07 at cell 96 of 4096; field carries max |div| 1.7649
  projected divergence against Jacobi sweeps (population: 4096 cells each):
     1 frames =   16 sweeps : max |div| after 0.41805   reduction    4.2x
     2 frames =   32 sweeps : max |div| after 0.22320   reduction    7.9x
     4 frames =   64 sweeps : max |div| after 0.08392   reduction   21.0x
     8 frames =  128 sweeps : max |div| after 0.01336   reduction  132.1x
    16 frames =  256 sweeps : max |div| after 0.00078   reduction 2250.8x
  Poisson residual max |Lap p - div| = 0.00079 against max |div| 1.7649 (0.0%)
--- gpu pressure projection chain: 16 claims over 3 stage(s), 18 dispatch(es), landed after 1 pumped frame(s) ---
    divergence pass vs closed form              1.1921e-07 <= 1.0000e-06       [divergence index 96]
    divergence pass: non-finite                          0 == 0                [divergence]
    divergence pass: surviving sentinels                 0 == 0                [divergence]
    input field max |div|                         1.764873 >= 1.500000         [velocity]
    sweep is monotone (non-monotone steps)               0 == 0
    reduction at 256 sweeps                    2250.828369 >= 1,500            [velocityOut]
    extra sweeps helped (16 -> 256)             533.156189 >= 100
    projection changed the field                  1.434136 >= 1                [velocityOut]
    projected field: non-finite                          0 == 0                [velocityOut]
    projected field: surviving sentinels                 0 == 0                [velocityOut]
    projected field (spread)                      1.517729 >= 1.200000         [velocityOut]
    Poisson residual / max |div|                4.4509e-04 <= 0.005000         [pressureA]
    pressure: non-finite                                 0 == 0                [pressureA]
    pressure: surviving sentinels                        0 == 0                [pressureA]
    pressure (spread)                            11.913630 >= 8                [pressureA]
    frames to land once warm                             1 <= 2
  16 of 16 claims hold
```

**Method.** The "before" divergence comes from the GPU's own divergence buffer; the "after" is
recomputed **on the CPU** from the read-back projected field, so the GPU does not grade its own
homework. The GPU divergence pass is itself first graded against the same stencil written in Kotlin
over the uploaded fixture, at every one of 4,096 cells — not a sample, because an off-by-one at a
boundary is exactly what a sample misses. Pressure is re-zeroed before each sweep sample, so
`frames × 16` is the whole iteration count and the five points are comparable.

**How the floors were set.** The first run held 15 of 16 and the one that failed was a floor I had
*guessed* rather than measured — `input field max |div| >= 2` against a measured 1.7649. The table
named both numbers, so the fix took one reading. Every floor is now within a small factor of its
measurement, and each has the measured value written into the comment beside it. The earlier draft's
`reduction >= 20` against a measured 2250 was not a test. Note the Poisson floor in particular: 5e-3
sits an order of magnitude *above* the measured 4.45e-4 and an order of magnitude *below* the
~4.5e-2 that 64 sweeps gives, so the claim can tell the sweep lengths apart instead of accepting
any of them.

One floor is deliberately loose and says so in the source: `extra sweeps helped >= 100` against a
measured 533, because the first sample's population is `max(1, framesToLand)` and a contended GPU
can make that 2 frames, which honestly shrinks the ratio to about 286. The sweep table prints the
frame count for every point, so the population is never hidden.

### `four.gpu-probe.self-check` — the negative control

`src/jvmMain/kotlin/afield/examples/four/GpuProbeSelfCheck.kt`

```
tools/run_field_tests.py --id four.gpu-probe.self-check
```

A sentinel that is never triggered is indistinguishable from a sentinel that *cannot* trigger. The
chain probe holding sixteen claims says nothing about whether it would have failed. So this puts
each guarantee deliberately wrong and requires the harness to notice. All five fire, observed:

1. **Half the grid dispatched.**
   ```
   probe 'selfcheck-under': the GPU never finished writing, after 323 pumped frames.
     fixture 'out': 8192 of 16384 slots still hold the sentinel, from index 8192
   ```
   The count and the index are asserted, not merely printed: `8192 of 16384` is what distinguishes
   a wrong dispatch size from a wrong binding.
2. **A slot named `targt`.** Caught at bind time, before any dispatch:
   `the compute shader has no push constant named 'targt'. It has target, side`.
3. **A shader containing `this is not glsl at all`.** Two unconditional verdict failures carrying
   shaderc's own message, rather than a log line.
4. **A `FrameBufferGroup` allocated by a different probe, armed.** Refused — a silently skipped
   witness is a sentinel that cannot fire.
5. **Stage A writes 5; stage B, created after it, doubles what A wrote.** One frame later, **0 of
   4,096** cells are anything but exactly 10.0 and **0** hold the doubled sentinel. One claim, two
   properties: stages run in creation order, and the barrier between two modules holds. Its wrong
   answer is unmistakable — `-197530` is the sentinel doubled — which is why the shader multiplies
   rather than copies.

And after five probes, all closed, `renderer.computeList.modules` is back to the 0 it started with.

---

## The loadable demo

`dev_docs/agents/artifacts/gpu_probe_bench/probe_bench/` — four boxes, and its `README.md` carries
the output of each. Built by `dev_docs/agents/artifacts/gpu_probe_bench/build_probe_bench.yaml`.

```
./field.sh dev_docs/agents/artifacts/gpu_probe_bench/probe_bench --read-only
```

All four boxes were launched one at a time against a real GPU. The invocation, for the record:

```
python3 tools/field_backdoor.py --port <yours> --json \
  --workspace dev_docs/agents/artifacts/gpu_probe_bench --document probe_bench \
  --command "launch probe_renderer --property code" \
  --command "sleep 5" \
  --command "launch probe_hello  --timeout 120" \
  --command "wait-output probe_hello --contains 'claims hold' --timeout 90" \
  --command "output probe_hello" \
  --command "launch probe_chain   --timeout 240" \
  --command "wait-output probe_chain --contains 'claims hold' --timeout 200" \
  --command "output probe_chain" \
  --command "launch probe_caught  --timeout 120" \
  --command "wait-output probe_caught --contains 'still hold the sentinel' --timeout 90" \
  --command "output probe_caught"
```

---

## Traps paid for, in the order they cost time

- **`run` does not attach a `frames { }`; `launch` does.** A box whose last expression is a
  `frames { }` evaluated with the backdoor's `run` returns immediately and the body is never hooked
  to the runloop, so the box prints nothing — which looks exactly like a box that failed silently.
  Half an hour.
- **A library `println` is invisible from a box.** Box `println` is rewritten to `_printlnHere`;
  library `println` goes to the server's stdout. This is why `verdict` takes a print sink. If you
  are hunting for a box's output in `.field-backdoor/logs/*.managed-server.log`, look for the
  library half; the box half is in the `output` events.
- **`#version` must be the first token.** A raw GLSL string that opens with a newline before
  `#version` reports a wall of errors about everything *else*, which masks the real one. Every GLSL
  literal here starts on its opening line.
- **`stillWaiting` is a spin.** Each call fence-waits and scans every armed float, and the negative
  control pumped 323 frames in three seconds doing it. Correct, because it is a condition rather
  than a duration, but it is not free: do not arm a 100 MB buffer with a 60-second deadline.
- **Only the *last* row of a sweep is a weak claim.** The row that matters is the *difference*
  between rows. A chain missing a barrier reports the same number on every row, which passes both a
  reduction floor and a monotonicity check.

## Known gaps

- `GpuNeighbourGridSmoke` and `GpuSkinningSmoke` are **not** ported to the facility yet, so the
  duplication is reduced by argument rather than by deletion. The neighbour-grid port is the
  smaller of the two and the obvious next step.
- Nothing in the repository verifies the demo document's four boxes — see its `README.md` for why
  and what the fix is.
- `GpuProbe` is new public API in `afield.four.util`, so the generated codebase reference has
  drifted. Regenerating it is a post-merge step and was deliberately not done here.
