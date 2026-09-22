# A GPU compute-probe bench — a document you can open

Four boxes. The first opens a window (there is no headless mode, so a compute-only dispatch still
needs one). The second is the whole `GpuProbe` idiom on one screen. The third is the pass chain a
GPU fluid actually runs — divergence, twelve Jacobi pressure sweeps, project — graded against a
closed-form oracle. The fourth dispatches half a grid **on purpose**, so you can watch the sentinel
fire and read what it says.

## Open it

```
./field.sh dev_docs/agents/artifacts/gpu_probe_bench/probe_bench --read-only
```

or, with a Field already running,

```
open 'http://localhost:<port>/offline.html?file=dev_docs/agents/artifacts/gpu_probe_bench/probe_bench/metadata.edn'
```

**Launch box 1 first**, then 2, 3 and 4 in any order. Before box 1 has run there is no `Renderer`
anywhere and the others fail with a context error rather than a GPU one.

**Launch, not run.** A box whose last expression is a `frames { }` needs *launch* — the action that
starts a strand. `run` evaluates the fragment and returns; the `frames` body is never attached to
the runloop, so the box goes quiet and prints nothing at all. That cost half an hour the night this
was written, because a box that prints nothing looks exactly like a box that failed silently.

## What each box says, measured

Every number below was read out of the box's own output pane on an M1 Max, 2026-09-22, with three
other Field JVMs live on the same GPU.

### Box 2 — the whole idiom

```
landed after 1 pumped frame(s)
--- hello: 4 claims over 1 stage(s), 1 dispatch(es), landed after 1 pumped frame(s) ---
    cell 0 value                           5 == 5                [out index 0]
    cell 63 id tag                        63 == 63               [out index 253]
    out: non-finite                        0 == 0                [out]
    out: surviving sentinels               0 == 0                [out]
  4 of 4 claims hold
```

Read the source for what is *not* there: no `defaultBuffer` whose access pattern you have to
remember, no `computeMemoryBarrier` task appended by hand, no frame count guessed by feel, and no
`finally { renderer.computeList.modules.remove(key) }`. Those are the four things every hand-rolled
probe in the tree writes out, and one of them — the access pattern — is a trap rather than a chore:
a host read out of write-combined memory is not wrong so much as pathological.

### Box 3 — the chain

```
chain: 3 stages, 14 dispatches, landed after 1 frame(s)
  frames  sweeps   max |div| after   reduction
       1      12           0.25849       9.9x
       2      24           0.06738      37.8x
       4      48           0.00707     360.2x
       8      96           0.00099    2584.5x
--- chain: 6 claims over 3 stage(s), 14 dispatch(es), landed after 1 pumped frame(s) ---
    divergence pass vs closed form            1.1921e-07 <= 1.0000e-06       [divergence index 37]
    input field max |div|                       2.546231 >= 2.200000         [velocity]
    reduction, best sample                   2584.468018 >= 1,500            [velocityOut]
    extra sweeps helped                       262.375397 >= 150
    projected field: non-finite                        0 == 0                [velocityOut]
    projected field: surviving sentinels               0 == 0                [velocityOut]
  6 of 6 claims hold
```

Three things to notice.

**Three stages, fourteen dispatches.** Twelve of those dispatches are the *same* shader with
different bindings, from one `stage` call, so there is one compile and not twelve. Each dispatch
carries its own barrier, which is why sweep *k* reads what sweep *k−1* wrote rather than what it
wrote a frame ago.

**The sweep is the evidence, not the last row.** A chain that had lost a barrier between Jacobi
iterations would read the same stale pressure every time and report an *identical* number on all
four rows — which would pass a reduction floor and a monotonicity check. `extra sweeps helped`
(262x between the ends of the sweep) is the claim that catches it.

**Every floor sits just under its measurement.** `reduction >= 1500` against a measured 2584. An
earlier draft of this box had `>= 30`, which is not a test; the floor is the part a reader should
be suspicious of, so the table prints both numbers and you can see for yourself how much room
there was.

### Box 4 — half a grid, so the sentinel fires

```
caught, as it should be:
  probe 'caught': the GPU never finished writing, after 464 pumped frames.
    fixture 'out': 128 of 256 slots still hold the sentinel, from index 128
    Causes, in the order they actually happen: a slot bound to the wrong buffer; too few
    workgroups to cover the output; an early return in main() that skips part of the range;
    a shader that did not compile.
```

This is the box that says whether any of the rest is load-bearing. The dispatch writes 32 of 64
cells. A probe that waited `repeat(4) { frame() }` and then read would have found 5.0 in the half
that ran and whatever was there before in the half that did not — and "unchanged" is
indistinguishable from "the dispatch never happened". `128 of 256, from index 128` is not
decoration: the index is the diagnosis. Index 0 means a binding; exactly half means a dispatch
size; an isolated index means a race.

Edit `bind("count") { 32 }` to `64` and re-launch; it goes green.

The frame count is the one number here that moves between runs — 464, 479 and 480 observed —
because `stillWaiting` spins on a condition for the four seconds it is given, and how many frames
fit in four seconds depends on what else is using the GPU. Everything else on this page is
reproducible.

## Provenance, honestly

- **All four boxes were launched, one at a time, by the agent that wrote them**, against a real GPU
  on 2026-09-22, and every number above is copied from the box output that came back. The exact
  invocation is in `dev_docs/GPU_PROBE.md`.
- **Nothing in the repository verifies these four boxes.** Registering a demo under
  `dev_docs/agents/demos/` requires an entry in
  `src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt`, which was off limits to the
  night's agents, so this lives under `dev_docs/agents/artifacts/` instead. That means no gate
  compiles this box Kotlin and no gate replays it: if `GpuProbe`'s API moves, these four boxes go
  stale silently. Promoting this directory to `dev_docs/agents/demos/` plus two registry lines is
  the fix, and it is recorded as a merge condition in the night's worklog.
- The **library** these boxes call is verified two ways that do sit in the FIELD_TEST catalog:
  `tools/run_field_tests.py --id four.gpu-probe.pressure-chain` (the same chain at 64x64, sixteen
  claims) and `--id four.gpu-probe.self-check` (five deliberate faults, each of which must be
  caught).
- `../build_probe_bench.yaml` is the operation transcript that built this document. Re-running it
  rebuilds it.
