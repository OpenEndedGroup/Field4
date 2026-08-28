# The PlayCanvas `.compressed.ply` splat format

Reading a quantized Gaussian-splat PLY — the output of
[splat-transform](https://github.com/playcanvas/splat-transform), which SuperSplat and the
PlayCanvas engine also read — as if it were the float-per-property PLY a 3DGS trainer emits.

Reader: [`CompressedSplatPly.kt`](../src/jvmMain/kotlin/afield/four/modules/CompressedSplatPly.kt).
Normative source: splat-transform's `src/lib/writers/compressed-chunk.ts` (the packing) and
`src/lib/readers/decompress-ply.ts` (the unpacking). Both are short; read them before changing
anything here.

## Nothing to call

`gaussianSplats(path)`, `RenderTarget.addGaussianSplats(path)` and `readPlyAsFloatTable(path)`
already take one. Detection is by schema, not by filename — a compressed file called `.ply`
decompresses, and a plain file called `.compressed.ply` reads as plain — and what comes back is a
`PlyFloatTable` with the uncompressed column names and conventions:

    x  y  z   f_dc_0..2   opacity   rot_0..3   scale_0..2

Log scales, logit opacity, SH-DC colour, `rot_0` the real part. Everything downstream —
`GaussianSplatRenderer.pack`, the depth-key compute pass, the sort — is unchanged and unaware.

The higher spherical-harmonic bands are **off by default**: up to 45 more floats per splat, three
times the size of the table itself, and nothing in the splat renderer reads them yet. Ask with
`readPlyAsFloatTable(path, includeSphericalHarmonics = true)`.

To read a compressed file's raw quantization tables rather than its splats, ask for the element by
name: `readPlyAsFloatTable(path, elementName = "chunk")`.

## What is in a file

An ordinary `binary_little_endian` PLY. Two or three elements, in this order.

**`chunk`** — `ceil(N / 256)` rows of 18 floats (or 12 in files from older writers). Splats are
Morton-sorted and cut into groups of 256; each row is that group's own min and max for position,
log-scale and colour:

    min_x min_y min_z  max_x max_y max_z
    min_scale_x .. min_scale_z  max_scale_x .. max_scale_z
    min_r min_g min_b  max_r max_g max_b        # the optional six

**`vertex`** — N rows of four `uint32`: `packed_position`, `packed_rotation`, `packed_scale`,
`packed_color`. Every value is normalised into its *chunk's* range, which is why 11 bits of
position is enough — 256 neighbours share a tight box.

**`sh`** — optional, N rows of 9, 24 or 45 `uchar` named `f_rest_0..`, channel-major, one band per
9 coefficients. A byte per coefficient over the fixed window `(-4, 4)`.

## The bit layouts

| word | layout | inverse |
|---|---|---|
| position, scale | `x:11 << 21 \| y:10 << 11 \| z:11`, each a unorm | `lerp(chunk_min, chunk_max, unorm)` |
| colour | `r:8 << 24 \| g:8 << 16 \| b:8 << 8 \| a:8` | `f_dc = (lerp(min_c, max_c, unorm) - 0.5) / SH_C0` |
| rotation | `largest:2 << 30`, then the other three components at 10 bits each, descending | below |
| sh byte | `trunc((v/8 + 0.5) * 256)` clamped to 0..255 | `((b + 0.5)/256 - 0.5) * 8`, with 0 and 255 as exactly ∓4 |

**Rotation.** The encoder normalises the quaternion, finds the largest-magnitude component,
negates the whole quaternion if that component was negative (which names the same orientation),
writes that component's *index* in the top two bits, and stores the remaining three in ascending
index order scaled so that ±1/√2 fills the range. Decoding rebuilds the dropped component from
the unit-length constraint and puts it back at the recorded index. Get the index handling wrong
and the scene is not obviously broken — it is subtly, per-splat wrong, which is why
`PlyLoaderUnitTest` Test 9 pins two rotation words with the largest component at different indices
using literal hex rather than anything produced by an encoder.

**Opacity, and the one deliberate difference from the reference.** Opacity is stored as an 8-bit
*sigmoid*, so bytes 0 and 255 invert to a logit of ∓infinity, which is what splat-transform's own
uncompress writes into the file. Field clamps to ±30 instead. `sigmoid(30f)` is 1 and
`sigmoid(-30f)` is 9.4e-14, so both still round-trip to the byte they came from — but a *non-finite*
logit hits `GaussianSplatRenderer.decodeOpacity`'s finite guard, lands on `sigmoid(0) = 0.5`, and
renders a solid splat half-transparent. On `frame_000012.compressed.ply` (883k splats) 10,385 splats
— 1.2% — have a saturated opacity byte, so this is not hypothetical.

## Streaming: the fused path

Reading a file and then re-packing it walks the data three times and materializes a 14-float table
nobody keeps. For playback — fifteen files a frame at 12–24 fps — use the fused path instead:

    val cloud = GaussianSplatRenderer.streaming(capacity = 15 * 30_000,
                    config = Config(sortMode = SortMode.None))
    // per source frame:
    val sources = files.mapIndexed { i, f ->
        SplatSource(CompressedSplatPly.readRaw(f), circle[i])   // per-file transform
    }
    cloud.reupload(sources, sortAlong = Vector3f(0f, 0f, 1f))

`CompressedSplatPly.readRaw` returns the quantized payload without decoding it;
`GaussianSplatRenderer.packInto` takes N of those and writes the 16-float GPU layout **directly into
the mapped storage buffer**, so there is no `FloatArray` to copy afterwards. One destination, one
draw, and — if you sort — one order that is correct across sources rather than fifteen
locally-correct orders that interleave wrongly.

Two round trips disappear, because the compressed format already stores what the shader wants.
Colour is linear 0..1 in the file, so `f_dc = (c - 0.5) / SH_C0` followed by `0.5 + SH_C0 * f_dc`
cancels; opacity is stored post-sigmoid, so the logit and the sigmoid cancel too — which means the
±30 clamp above never happens on this path and a saturated opacity byte comes out exact. The fused
path is therefore both faster than `pack` *and* slightly more accurate.

**Per-file transforms are baked at decode**, and that is nearly free because the per-file linear map
folds into a congruence the decode already performs: the covariance is `A·S²·Aᵀ` with `A` the splat's
own rotation, so a file transform `L` just makes it `(L·A)`. One 3×3 product per splat, +8% measured.
The alternative — a source index in one of the three unused floats plus a matrix palette in its own
buffer — is only worth it if the *arrangement* must move faster than the data refreshes. Sorting does
not force the choice: `dot(L·p + t, d)` is `dot(p, Lᵀ·d) + dot(t, d)`, so an untransformed position
sorts in world order against a per-source direction and offset at no per-splat cost, which is what
`packInto` does.

### Measured, on a real 15-file frame

309,555 splats over 15 files, 4.9 MB, M4-class laptop, 10 cores. A 24 fps frame is 41.7 ms.

| | ms | % of frame |
|---|---|---|
| fused decode, no transform, no sort | 20.9 | 50% |
| fused decode + transforms baked | 22.4 | 54% |
| + CPU back-to-front sort (`Arrays.sort`) | 37.9 | 91% |
| + CPU sort, parallel (`Arrays.parallelSort`) | 27.2 | 65% |
| + re-reading the files from disk | ~35 | ~84% |
| 4-thread decode, unsorted | 6.1 | 15% |
| the old decompress-then-`pack` path | 59.4 | 142% |

The sort of 310k longs is 16.7 ms sequential and 2.8 ms parallel; the scattered read that emitting in
sorted order implies costs only 1–2 ms, because the raw payload is 5 MB and stays in cache. So
`parallelSort` is the default in `packInto`, and **CPU sorting once per source frame is comfortably
affordable** — which is the right trade when a frame is on screen for ~10 headset frames, since a
depth-map capture's sort order stays valid for any view near the source camera.

`pack` over a `PlyFloatTable` is still there and is now ~4× faster than it was: it used to rebuild
`"scale_$index"` and a `listOf` of alternatives *per splat* and hash each one, which cost more than
all its arithmetic. The column indices are resolved once now.

### Rewriting the buffer every frame

`streaming()` sets `perFrameSplatData`, which gives the splat buffer one slot per swapchain image.
`FrameBufferGroup` then does the right thing without any further machinery: a write lands in the
current image's slot and is recorded as last-written, and `getGPUAddress` resolves to the
**last written** slot, not the current one. A 12 fps source into a 90 Hz display is therefore safe —
the frames that skip a reupload read the most recent upload.

Measured on a 3-image swapchain, writing once and then watching ten frames:

    frame 0: WROTE at currentImage=2, lastWritten=2
    frame 1: currentImage=0  get()=slot0  getLastWritten()=slot2  same=false
    frame 2: currentImage=1  get()=slot1  getLastWritten()=slot2  same=false
    frame 3: currentImage=2  get()=slot2  getLastWritten()=slot2  same=true

**The trap is the binding, not the buffer.** `push.bind("splatData") { splatData.result.get() }` pins
the address to `renderer.currentImage` and reads an unwritten slot two frames in three; binding the
*group* — `push.bind("splatData") { splatData.result }` — gets last-written semantics. With
`perFrame = false` the group holds one buffer and the two are indistinguishable, which is why the
one-shot path never noticed.

### SortMode.None

Builds no depth-key compute pass, no radix sort, no bucket sorter and no per-frame pre-target. Use it
when the splats are already back-to-front (pass `sortAlong` to `reupload`) or when the blend does not
care — `Blend.Additive`, `Screen`, `Multiply` and `Max` all report `orderIndependent`. `sortedIndices`
is still allocated and filled with the identity once, so one vertex shader serves both paths and the
GPU reads it as a coalesced pass-through.

## Playback: sequence and transport

[`SplatSequence`](../src/jvmMain/kotlin/afield/four/fabricated/SplatSequence.kt) is a directory of
frames read in **any order**, because scrubbing jumps around; frames are held in a bounded
most-recently-used cache so a shot longer than memory still works. `peakWindowSplats(window)` gives
the capacity a streaming cloud needs — the true peak over any `window` consecutive frames, which on a
124-frame capture came out 379,588 against 391,218 for the cruder "the 15 largest anywhere".

`SplatTransport` is the play head: `playing`, `speed` (negative runs backwards), `loop`, `seek`,
`scrubBy`, and `normalized` for a slider.

The property worth having a class for is that **`advance()` reports whether the resolved frame
actually changed**. At 12 fps into a 90 Hz display that is false six display frames in seven, and a
scrub that stays inside one frame is false too — and every false is fifteen files not decoded to
write identical bytes. Driven live from a fragment:

    PROBE after seek(60) frame = 60
    PROBE after scrubBy(-1) frame = 59
    PROBE after normalized=0.25 frame = 31 of 124
    PROBE ten 0.05-frame scrubs: 9 reported no change (no decode)

There is also a stall guard: a ten-second gap — a breakpoint, a shader recompile, a GC pause —
advances the head by `maxCatchUpSeconds` rather than by ten seconds of frames.

It is clock-driven and fractional, so `position` accumulates rather than being derived from an
origin. That drifts by a few times 1e-15 over a second, which is irrelevant to playback but means an
assertion pinned to landing exactly on a frame boundary is asserting the floating-point accumulation.
`SplatTransportTest` asserts the *rate* and the quiet-frame proportion instead. Twelve tests, no GPU
needed — the transport is the one part of this path that is pure arithmetic.

## Verification

`PlyLoaderUnitTest` Tests 9–12, in the `four.ply-loader.smoke` FIELD_TEST:

```bash
python3 tools/run_field_tests.py --id four.ply-loader.smoke
```

Test 9 decodes hand-authored bit patterns against values computed by hand. Test 10 round-trips 600
splats across three chunks (the last partial) through
[`CompressedSplatPlyFixture`](../src/jvmMain/kotlin/afield/four/modules/CompressedSplatPlyFixture.kt),
which is a transcription of splat-transform's encoder so that the reader is checked against the
format rather than against a second copy of its own beliefs. Test 11 covers the 12-property chunk
variant, Test 12 that detection is by schema and that every wrong turn is loud.

Beyond the tests, the reader was compared against splat-transform v3.3.3 itself — `splat-transform
in.compressed.ply out.ply`, then both column sets diffed — over all 882,952 splats of a real capture:
12.35M values, max relative error 4.1e-7, which is single-precision accumulation against JavaScript's
doubles and not a difference in logic. The `sh` path was checked the same way, against a compressed
file the reference encoder produced with three bands: all 13,500 coefficients bit-identical.

## On the CPU: seeing and editing a cloud without a GPU

Everything above is about getting splats onto a graphics card as fast as possible. The trouble with
that as the *only* path is that it left the pipeline unfalsifiable in the way that matters: the tests
above check numbers, and every assertion in them would still pass if the shader drew the cloud
mirrored, inside out, or with red and blue swapped. Seeing a splat required a GPU and a window.

[`SplatRaster`](../src/jvmMain/kotlin/afield/four/modules/SplatRaster.kt) rasterizes a cloud on the
CPU into PNG bytes. It is a line-by-line transcription of `splatVertexShader` and
`splatFragmentShader` — the view-space covariance congruence, the projective Jacobian, the 0.3 screen
dilation, the eigen-decomposition into an oriented 3σ quad, the `minScreenRadius`/`maxScreenRadius`
clamps, the `colorGamma` decode, premultiplied Over in linear, sRGB on the way out — so the same
`Config` numbers mean the same things on both sides.

It consumes **`packInto`'s own 16-float output** rather than decoding the payload again. That was the
one real design decision here, and it went against the obvious: a second decoder would turn "the CPU
and the GPU are looking at the same cloud" into a claim about two decoders staying in step, which is
the kind of claim that rots unobserved. There is still exactly one bit-level decoder in the tree.

What is left to doubt is whether the rasterizer reads each of the sixteen slots for the purpose the
shader reads it for — a swapped covariance off-diagonal produces a *plausible* cloud, and no float
comparison against `packInto` can see it. So `SplatRasterTest` perturbs one slot at a time and
requires the image to move for the thirteen the shader reads and to be byte-identical for the three it
does not. It reports 13 load-bearing (0–6, 8–13) and 3 inert (7, 14, 15).

Against the table path in pixels: **100.000% of 25,600 pixels bit-identical, worst channel delta 0**.
The floors are 99.99% and one code value, and they are set by a negative control rather than by taste
— the same test moves one splat of 1200 by 0.01 and requires the comparison to notice. That control
failed on its first run against the original 99.9% floor, because one moved splat leaves 99.938%
identical at delta 1, which the old floors admitted.

### The Y convention, which is answerable

`Renderer.setViewportAndScissor` sets a **negative-height** Vulkan viewport
(`.y(rect.y + rect.h).height(-rect.h)`), so NDC `+y` is at the **top** and the mapping is
`row = (1 - ndc.y) · 0.5 · height`. That is the GL convention, not raw Vulkan's. Output goes through
`PngEncoder.encode`, the `IntArray` entry point, which has no `flipY` parameter to get wrong. Four
corner-marked splats pin it in the test regardless: a derivation from a viewport call is evidence, a
corner-marked image is proof. A single splat's rendered centroid lands at column 80.657, row 54.921
against 80.656 and 54.922 derived on paper from the perspective matrix.

### Contact sheets

[`SplatContactSheet`](../src/jvmMain/kotlin/afield/four/modules/SplatContactSheet.kt) tiles N frames
of a `SplatSequence` into one image, frame numbers drawn from a 3×5 bitmap rather than
`java.awt.Font`. `SplatSequence` and `SplatTransport` scrub beautifully, but scrubbing is a *serial*
instrument and the questions a fresh capture raises are parallel: which frames are empty, where does
the subject leave frame, are 40–60 the same moment. `Sheet.emptyFrames` names the dead ones, because a
sheet with three dead frames and twenty-one good ones looks healthy in aggregate.

24 tiles of the real 126-frame circle-05 capture — 522,171 splats — take about nine seconds including
JVM startup. `SheetCamera.autoFrame` fits the box in the view's own axes rather than its bounding
sphere; on this data the sphere throws away more than a factor of two of linear magnification, because
a depth-map capture of one video frame measures 1.77 × 0.92 × 0.58 and so has a bounding sphere of
radius 1.04 where the view needs 0.46 vertically.

### Editing: `SplatCloud`

[`SplatCloud`](../src/jvmMain/kotlin/afield/four/modules/SplatCloudEdit.kt) is a fourth
representation and it needed justifying. `PlyFloatTable` is the file's conventions — log scales, logit
opacity, SH-DC colour — right for a reader and hostile to a person, since the number that halves a
splat's width is not `0.5` but a subtraction of `ln 2`. `RawSplats` is deliberately undecoded. The
16-float layout has the covariance already baked into six numbers from which scale and rotation cannot
be cleanly recovered. `SplatCloud` is the one you can edit: **world-unit scales, 0..1 opacity, linear
0..1 colour, unit quaternions**. It adds no fourth *decoder* — `of` converts conventions on the table
the existing reader already produced.

```kotlin
val cloud = SplatCloud.read("frame_000060.compressed.ply")
println(cloud.stats())
cloud.filtered(minAlpha = 0.15f).cropped(lo, hi).decimated(0.5f)
     .transformed(m).write(File("trimmed.compressed.ply"))
```

`toRawSplats()` re-quantizes without touching the disk, so an edited cloud goes straight into
`packInto` — and therefore into `SplatRaster` or a live `Cloud.reupload`. It is the same encoder
`write` uses, so a preview is not a different picture from the save; the test requires bit-identical
payloads between the two.

`transformed` is the subtle one. `packInto` folds a matrix into the covariance congruence
`(L·A)·S²·(L·A)ᵀ` and never names a rotation; `transformed` *must* produce a rotation and three
scales, because that is all the format holds, so it multiplies, Gram-Schmidts and converts back to a
quaternion. A sign error in that recovery is invisible to any float comparison inside either path, so
`SplatCloudEditTest` rasterizes both and compares pixels: **0.120 code values per channel against an
untransformed control of 63.774**. Shear cannot be stored, so it is reported rather than absorbed —
`shearLoss` is 2.5e-7 after a rotation and 0.599 after a shear of 0.6.

`stats()` is what settles arguments rendering can only start. The first real CPU render of circle-05
came out very dark and nearly monochrome, which is also what a broken capture looks like. The data
settles it: mean colour (0.1569, 0.1572, 0.1571), mean saturation 0.0013 — genuinely greyscale,
genuinely dim, and the render was right. It also turned up something nothing else had said:
**opacity is 0.996 at p1 and 0.996 at p99**. Every splat is fully opaque, because a depth map gives
one opaque splat per pixel. So opacity filtering buys nothing on this kind of capture, and
compositing *order* is everything — a better argument for `sortAlong` than any in its own KDoc.

## Writing

`CompressedSplatPlyWriter` writes a valid `.compressed.ply` — the inverse of the reader, and readable
by this tree, SuperSplat, the PlayCanvas engine and splat-transform. It exists because `SplatCloud`
gave Field a reason to write one: an edit has to be able to leave.

`CompressedSplatPlyFixture` stays a fixture. It checks the *reader* against the reference encoder and
is deliberately optionless. `SplatCloudEditTest` cross-checks the two on the same input and requires
**byte-identical** payloads — 11,200 vertex bytes and 216 chunk-range bytes over 700 splats — which is
a better arrangement than either being the other's only witness.

**Morton order is load-bearing, and measuring that caught a bug.** Position, scale and colour are
quantized per chunk of 256 against that chunk's own min and max, so a splat's error is set by how
tightly its 255 neighbours-in-file-order bound it. The first sort key packed a 63-bit Morton code and
a 24-bit index into one `Long` by shifting the code right 3 and left 24 — silently discarding the top
24 bits, which are exactly the *coarse* subdivision that decides which chunk a splat lands in. It
sorted correctly on sub-cell detail and randomly on anything larger, so it looked like a working
Morton sort. The grid is 13 bits per axis now (`3·13 + 24 = 63`, with a `require`). The bug was put
back afterwards to confirm the diagnosis rather than argue it: with the overflow, mean position error
was 2.244e-4 against 2.254e-4 unsorted — a 1.004× improvement, which is none. With 13 bits it is
1.02e-4 against 2.25e-4, or 2.2× tighter.

The test asserts the **mean**, not the worst, and that is not a softening: chunks hold exactly 256
splats while Morton octants hold whatever they hold, so some chunk always straddles a boundary and
inherits nearly the full span on one axis. The worst error is a statistic about that one unluckiest
chunk; the mean is a statistic about the file.

Round trip on 1500 splats: worst position error 6.8e-4, colour 1.2e-3, alpha 1.9e-3, scale ratio
5.3e-4. Save and reload moves a rendered picture by 0.000 code values per channel.

SOG is still what splat-transform recommends over compressed PLY, and is still not here.

## Not done

**The other splat containers.** splat-transform reads `.splat`, `.ksplat`, `.spz`, `.sog` and its LOD
formats too. None of them are here.

**Spherical harmonics survive reading and nothing else.** `SplatRaster` shades from the DC term as the
shader does, so `f_rest_*` is as unused there as on the GPU. More sharply: `SplatCloud` does not carry
the bands at all, so an edit-and-write round trip **drops them** — a cloud with three SH bands comes
back with none, silently. `readCompressedSplatPly(includeSphericalHarmonics = true)` still reads them;
nothing downstream keeps them.

**Parallelism.** `SplatRaster` is single-threaded. Alpha compositing is order-dependent, so the split
has to be by screen tile rather than by splat, which is a real change rather than a `parallelStream`.
A 764×430 preview of 20k splats is about 15.5M fragments and takes a fraction of a second, so nothing
has needed it yet.

**View-dependent colour.** The `f_rest_*` bands can be read but nothing evaluates them —
`GaussianSplatRenderer` shades from `f_dc` alone, so a scene's specular highlights are baked at
whatever direction the DC term implies.
