# How the imaging benchmark is conducted

The published table lives in `public/docs/imaging_examples.md`, between the
`BEGIN imaging-bench table` / `END imaging-bench table` markers, and is written there by
`tools/imaging_bench_doc.py` from `tools/imaging-bench/results-1920x1080.txt`. This note is the
conduct of the measurement: what is being compared to what, what the run checks, and the conditions
the absolute milliseconds depend on. It was moved off the page on 2026-09-18, where forty lines of
it stood between a reader and the API.

Run the benchmark with:

```bash
./gradlew :tools:imaging-bench:jmh
```

## The "before" column

"Before" is the original scalar implementation, still in the source as
`convolveSeparableReference`, so the claim can be re-measured at any time. Only the
separable-convolution family has a preserved original; a dash in that column means the operation
has no scalar reference to compare against, not that its speedup is unknown.

Where there is a before, all three gains land within a few percent of each other, and they should:
the win came from dropping a bounds-checked accessor call out of every multiply-add, and that
saving does not depend on which kernel is running.

## Threading

OpenCV is pinned to one thread, because the Kotlin code is single-threaded. Letting OpenCV use
every core does not help at this image size either, so the pin costs the comparison nothing.

## Which rows are like-for-like, and the tolerances

Three rows are the same operation with the same parameters on both sides, and the benchmark **fails
the run** if the outputs stop agreeing:

| row | tolerance |
|---|---|
| Gaussian blur | 2.4e-7 |
| Box blur | 7.8e-6 |
| Laplacian | exact |

The remaining rows are matched in work but not in definition. Those differences are stated on the
page itself, because a reader choosing between the two implementations acts on them: BT.709 versus
BT.601 luma, Sobel/Scharr scaled to true units-per-pixel, a configurable `pyrDown` sigma against
OpenCV's fixed 5-tap kernel, and different internal algorithms for `median` and `bilateral`. The
Kotlin API also returns a fresh `Img` per call, so part of its cost is an allocation OpenCV is not
paying.

## Reading the intervals

These are laptop numbers. A wide interval on a short benchmark is scheduler noise rather than a
real difference, and two arms whose intervals overlap are a tie. `bilateral` is the one arm whose
interval is wide, which is why the page reads its row as "about 1.2x" rather than 1.19x.

`dev_docs/IMAGING_VECTOR_KERNELS.md` describes the vectorized span-kernel layer the "now" column
measures, the rules the absolute milliseconds depend on, and what contention did to a run that
ignored them.
