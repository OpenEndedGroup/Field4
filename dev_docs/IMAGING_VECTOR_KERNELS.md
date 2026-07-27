# The imaging vector-kernel layer

How `afield.stdlib`'s CPU imaging operators are made fast, and what to reach for when you add the
next one. Written 2026-07-27, after closing the largest gaps against single-threaded OpenCV 4.9.

The layer is `src/jvmMain/kotlin/afield/stdlib/imaging_simd.kt` — one `internal object ImgSimd` of
span kernels. It is deliberately **not public**: it is an implementation detail of
`imaging_filters.kt`, and keeping it out of the generated codebase reference means adding a kernel
does not churn public API.

## The one idea

Every operator here is a loop over pixels that reads neighbours. Written naively, each read goes
through `Img.get`, which costs a bounds check, a channel clamp and a `when (Border)`. That is
correct, readable, and unvectorizable — C2 cannot hoist a branch whose outcome depends on the loop
index.

The fix is never "make `get` faster". It is **split the loop into an interior where no read can go
out of bounds, and a border where it can**. The interior is then a straight span over contiguous
memory with no branches, and the border is a handful of rows/columns where nobody cares. Because
`Img` is interleaved (`data[(y*width + x)*channels + c]`), moving one pixel across is `+channels`
and one row down is `+width*channels`, so *both* axes are contiguous strides — which is why this
works for horizontal and vertical passes alike.

Every kernel in `ImgSimd` assumes the caller has already done that split. They do no bounds checking
on purpose.

## What is in the layer

| kernel | shape | used by |
|---|---|---|
| `stencil` | `dst[i] = Σ w[t] * src[i + off[t]]` | `convolve2D` (dense) |
| `mulSet` / `axpy` | `dst = w*src` / `dst += w*src` | separable passes, `pyrDown` |
| `splitByPhase` | even/odd pixel split of a row | `pyrDown`'s decimating pass |
| `addSub` / `addInto` / `subInto` | `acc ±= row` | `boxBlur`'s running sums |
| `scaleInto` | `dst = src * w` | `boxBlur`'s normalising store |
| `compareExchange` | lanewise min/max of two spans | (intended for a median sorting network) |

## Three lessons that cost real time to learn

### 1. A register accumulator needs unrolling to be worth anything

`stencil` keeps the running sum in vector registers across all taps, so `k` taps cost `k` reads and
**one** write. But it is a chain of `k` dependent FMAs, and on Apple silicon an FMA has ~4 cycles of
latency while issuing ~4 per cycle — so a single-accumulator chain runs at *latency*, roughly 16x off
peak. `stencil`'s main loop therefore keeps **four independent accumulators** (four output chunks in
flight). That one change took `convolve2D` of an 11x11 kernel from 2.4x OpenCV's `filter2D` to about
parity.

If you write a new multi-tap kernel, unroll it the same way. "Uses the Vector API" and "is fast" are
different properties.

### 2. The same register accumulator is *slower* for the separable passes — measured

The paper argument for using `stencil` in `horizontalPass`/`verticalPass` is identical, and it is
wrong. Measured 2026-07-27: Sobel 2.08x -> 2.63x of OpenCV, pyrDown 16.2x -> 18.2x, Gaussian sigma 2
(17 taps) 1.07x -> 1.11x. All worse. Reverted.

Why: the read-modify-write that `stencil` saves **never reaches memory**. A span there is one image
row — 7.7 KB at 1920x1 — so taps 1..k-1 hit L1, and L1 bandwidth is not the constraint. What the
simple one-tap `mulSet`/`axpy` loops give up in theory they win back in code C2 optimises perfectly:
one live vector variable, a counted loop, aggressive software pipelining. The stencil's four
loop-carried accumulators plus a runtime trip count is the worse trade until the tap loop is long
enough to amortise it — which for a dense 11x11 (121 taps) it is, and for a separable 3..17 it is not.

There is a comment saying this in `horizontalPass`. Please do not re-derive the paper argument and
re-apply it.

### 3. The big wins were algorithmic, not lanes

The two largest improvements came from not doing work, and from touching memory in the right order:

- **`pyrDown`, 16.2x -> ~4x.** It used to blur at full resolution and then throw three quarters away.
  Now the vertical pass runs only on even output rows, and the horizontal pass runs **polyphase**:
  output `x` reads source pixel `2x + s`, whose parity is fixed by the parity of `s`, so each tap
  only ever touches even-indexed or odd-indexed pixels. `splitByPhase` splits a row into its two
  halves once — into one buffer, even half then odd half, so a single base pointer addresses both —
  and every tap is unit-stride again. No gather, which matters because on NEON a gather is emulated.
  Both scratch buffers are one row, so they stay in L1 and the operator allocates nothing but its
  result.
- **`boxBlur`, 5.9x -> ~1.3x.** Already O(1) per pixel, so all of it was constant factor. The
  vertical pass was `for (x) for (y)` — a column walk, stride `width*channels` down an 8 MB array,
  a cache miss per sample, one serial dependency chain. Turned inside out: the accumulator is a whole
  **row** of partial sums, one lane per column, advanced together, so each output row is
  `acc += rowEntering - rowLeaving` then a scale. Sequential and vectorized, same operation count.

  The incremental form stays valid under every `Border`, including the repeated rows `CLAMP`
  produces, because it only ever adds what enters the window and subtracts what leaves it.
- **`bilateral`, 6.3x -> below 1.0x** (we are now faster than OpenCV's). Its whole cost was that the
  range weight depends on the sample, so the literal definition calls `exp` once per tap per pixel —
  169 per pixel at the benchmark's settings. Now a 4096-entry table indexed by **squared** intensity
  distance (no `abs`, no `sqrt`), read with linear interpolation, worth ~2e-6 of weight error.

## Adding an operator: the checklist

1. **Write the naive version first and keep it**, `internal`, as `fooReference`. `convolveSeparableReference`,
   `convolve2DReference` and `pyrDownReference` all exist for this reason and cost nothing at runtime.
2. **Split interior from border.** Compute the interior bounds from the *full* kernel width, even if
   you drop zero taps.
3. **Add a differential test to `ImagingSimdTest`** comparing fast against reference. Sweep all four
   `Border`s, 1/3/4 channels, and odd/degenerate shapes — odd widths especially, because that is
   where a polyphase split or a row-block remainder goes wrong. The existing tests are the model;
   they are why the pyrDown and boxBlur rewrites landed without a correctness regression.
4. **Add a tile to `ImagingGallery`**, ideally carrying its own invariant check. The gallery is what
   a human looks at; an operator with no picture is an operator whose rewrite nobody can eyeball.
5. **Measure with `:tools:imaging-bench`**, and measure the *ratio* to OpenCV, not the absolute ms.

## Measuring, honestly

```
# steer: one operator, one fork, fast
./gradlew :tools:imaging-bench:jmh -Pjmh.includes='.*(oursPyrDown|opencvPyrDown)$' \
    -Pjmh.fork=1 -Pjmh.iterations=5
# publish: the committed configuration
./gradlew :tools:imaging-bench:jmh
python3 tools/imaging_bench_doc.py --write
```

`jmh.includes` is **one** regex, not a list — the Gradle plugin comma-joins its `includes` list into
a single argument, so two patterns silently match nothing. Use alternation.

Two rules the numbers depend on:

- **OpenCV is pinned to one thread** (`setNumThreads(1)`) because our code is single-threaded. Do not
  "win" by multithreading the compared arm. If you add a parallel path, add it as a separately named
  arm and label it.
- **Absolute ms are only meaningful on a quiet machine.** This is a laptop; a 2026-07-27 run under
  memory-bandwidth contention inflated *both* arms 2.5-2.7x. The load-robust metric is the
  ours ÷ OpenCV ratio measured inside the same JMH run, and even that assumes load is stationary
  across the run — so carry an arm whose quiet value you already know as a control, and check it.

## Where the remaining gaps are

As of 2026-07-27, worst first, with what the lever actually is in each case — none of them is "add
more lanes":

- **`median`, ~3.6x.** A per-pixel `FloatArray.sort()` (dual-pivot quicksort) of 25 floats, plus 25
  `Img.get` calls. `compareExchange` exists to replace it with a branchless **sorting network**, each
  SIMD lane carrying a different pixel — for a fixed window every tap is a constant element offset, so
  there is no gather and no data-dependent control flow. Derive the comparator list from
  `sorting_networks.kt` (a teaching data structure, not a hot kernel — do not call it per pixel);
  a 25-element median needs a partial network, not a full sort. This is the largest remaining win and
  the one the layer was already prepared for.
- **`resizeDown`, ~4.9x.** `resizeArea` recomputes `index(xx, yy, c)` per sample and has
  data-dependent inner trip counts, so it cannot vectorize. The general fix that does not special-case
  a ratio: for each output row, vertically accumulate the source rows into a one-row scratch with
  `addInto` (vectorized, any ratio), then reduce horizontally out of that L1-resident row. Watch the
  accumulation type — the current code sums in `Double`, and a large downscale sums thousands of
  samples, so a naive move to `Float` scratch can drift.
- **`Sobel`/`Scharr`, ~2.0-2.2x.** Memory-bound, not compute-bound: each is two `convolveSeparable`
  calls, so **four** full-image passes and **four** 8 MB allocations per call at 1920x1080 — around
  33 MB of allocate-and-zero per operation, and the JVM must zero every one. The lever is a scratch
  buffer pool for the intermediate (the `tmp` array in `convolveSeparable` is fully overwritten, so
  its zero-fill is pure waste), not anything to do with lanes.
- **`luma`, ~1.8x.** Already a tight scalar loop with no `Img.get`. It moves 32 MB (24 in, 8 out) in
  ~2 ms — about 16 GB/s, where OpenCV's `cvtColor` gets ~29 GB/s. The difference is that OpenCV
  vectorizes the RGB deinterleave (NEON `ld3`); the Vector API has no 3-way deinterleaving load, and
  emulating it with an index-map gather is slower than the scalar loop. Reachable only via a
  multi-vector `rearrange`, and worth measuring before believing. The free part first: hoist
  `RGB_TO_LUMA[0..2]` into locals, since C2 cannot prove the weight array does not alias the output.

`pyrDown`'s residual ~4x is **not** a bug and should not be "fixed" by narrowing the kernel: ours is
`gaussianKernel1D(sigma)`, 9 taps at the default, where OpenCV's `pyrDown` is a fixed 5-tap
`[1,4,6,4,1]/16`. We do close to twice the arithmetic because we apply a better, parameterised filter.
