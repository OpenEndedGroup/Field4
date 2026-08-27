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

## Not done

**Writing.** `CompressedSplatPlyFixture` can emit a file and is a faithful transcription, but it is
test scaffolding: no Morton sort, no streaming, no options, and it materializes everything. If Field
ever needs to *write* splats, write a real encoder — and prefer SOG, which is what splat-transform
now recommends over compressed PLY.

**The other splat containers.** splat-transform reads `.splat`, `.ksplat`, `.spz`, `.sog` and its LOD
formats too. None of them are here.

**View-dependent colour.** The `f_rest_*` bands can be read but nothing evaluates them —
`GaussianSplatRenderer` shades from `f_dc` alone, so a scene's specular highlights are baked at
whatever direction the DC term implies.
