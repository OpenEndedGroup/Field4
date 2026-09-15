# RectSL → GLSL: how the lowering is measured

Displaced from `public/docs/rectsl_cheatsheet.md`'s `### Fidelity` on 2026-09-16. The cheatsheet is
the page a reader arrives at wanting the words of the language; it keeps what you plan with (the
tolerance to expect, and which tracks are approximated) and this note keeps how any of it was
established.

`RectSLGlsl.emitSdf` lowers a `SceneProgram` to a self-contained GLSL function. Three separate
checks stand behind it, at three different granularities.

## Geometry, against the CPU sampler

`RectSLGlslSdfTest.kt:115` compares the emitted field against `program.sample(t)` to **`1e-9`**
across the whole gallery. Both sides are `double` here — the emitted GLSL is evaluated as
arithmetic, not on a GPU — so this is a check on the *emitter's algebra*, not on float precision.

`RectSLGlslSdfTest.kt:30`, `every gallery program emits GLSL that shaderc accepts`, compiles every
gallery scene through shaderc as part of the build. A shader that will not compile fails the build
rather than the pipeline. The test's own comment at `:96` calls this "the honest half of the check":
shaderc proves the text is a shader, and only the pixel differential below proves it is the right
one.

## Pixels, against a probe shader

`RectSLGalleryHarness.measureSdfAgreement` renders a probe shader that reports `scene(p, t)` as a
24-bit fixed-point number instead of drawing it, reads the framebuffer back, and compares it against
`program.sample(t)`. The probe also reports `p.x` and `p.y` on channels of their own, so the
pixel→wall mapping is recovered from the shader's own answers rather than assumed.

`measureGallerySdfAgreement` then repeats it across a spread of the real gallery, one scene per
distinct feature signature — groups, named easings, and each distinct rect count get covered rather
than one hand-picked shape. `maxScenes` defaults to 8 and `times` to `0.0, 0.41, 1.7`.

**The guard is `maxAbsError < 1e-5`** (`RectSLGalleryHarness.kt:381`). A recent run observed a max of
`2.47e-06` wall units with a mean under `1e-06`, over roughly 51,000 points. Those two figures are
`lprintln`ed, not asserted: they are an observation of one run on one machine, and the committed
guarantee is the 1e-5 floor. The cheatsheet says "about `1e-6` of a wall unit" and cites the floor,
which is the claim that has a guard behind it.

The residual is float-versus-double and nothing else: the sampler works in `double` and a shader in
`float`, so expect about `1e-6` of a wall unit rather than the ulp.

## Which tracks are baked, and what each costs

`emitSdf` returns one `RectSLSdfBake` per sampled track, carrying the classification, the rate, and
the measurement it made of itself. The sentence in `sdf.notes` is that report read aloud. Classes,
and what `RectSLSdfBakeTest` holds each to:

| class | what it is | measured |
|---|---|---|
| `STEPPED` | a rhythm: piecewise constant, so only its edge positions matter. Each edge is found by bisection rather than snapped to the sample grid, landing within a microsecond — the resolution `PatTrack` works at anyway, since it quantises its query to a millionth of a cycle | the five stepped bakes in the gallery disagree with their own sampler at no instant the emitter probes; the two focused cases in `RectSLSdfBakeTest` agree across a grid 40× finer than the bake |
| `SAMPLED` | a signal (`sine`, `tri`, `perlin`): no plateaus, so no exactness to claim. Both a hold chain and a ramp through the samples are built and the closer one emitted | for `sine` over a 0-to-1 range the ramp wins at `0.000024` against the staircase's `0.0086`; for `saw` and `isaw` the staircase wins, because a straight line would smear the once-a-cycle wrap across a whole interval. The gallery's seven sampled tracks are worst-case **0.31%** of what the track does at all; the ramps among them are under 0.005% |
| `ALIASED` | `rand`: hashes the reduced rational instant it is handed, so it has no value *between* samples | bake it eight times finer and the deviation does not fall (0.96 → 0.97 of a 0-to-1 range). One gallery track is in this state, `3 rand 0.2 quantize` in the musicality-surface example |

`"1 ~ 3"` steps at `0.666667s` rather than at two thirds, and the emitted step has to land on the
same side of that, or every frame instant sitting on the exact rational shows the next value early.

Why the pattern queries are the only thing that cannot be lowered in principle: a `PatPattern` is a
`(PatArc) -> List<PatEvent>` lambda, so the emitter cannot tell a `"bd*4"` from a `sine` from the
outside, let alone find a closed form for it. Rational arithmetic over a mini-notation tree is an
interpreter, not a formula.

## `->degrade` lowers exactly, and is held to a stronger standard

Despite being a 64-bit integer hash, `->degrade`'s dropout gate is not approximated. A 64-bit value
is a `uvec2` lane pair, `umulExtended` gives the exact 32×32→64 product the multiplies need, and the
final `k / 2^53 < probability` test is an integer comparison in disguise: the threshold folds to an
exact 53-bit integer on the CPU and is compared with an unsigned 64-bit `<`, with no float anywhere
in the decision. `ordinal` and the salt are known at emit time, so two of the three hash inputs fold
to a single literal.

6720 `(ordinal, cycle, probability, salt)` points agree **bit for bit** with the CPU track, and the
pixel differential then covers a `->degrade` scene end to end — a wrong hash is not a small numeric
error but a *different set of rectangles*, so it shows up at the scale of the wall rather than at
1e-6.

The only residual difference is which cycle `t` belongs to, since `floor(t · cps)` runs in `float`
here and `double` there: possible only within about an ulp of a cycle boundary, where the gate is
discontinuous anyway.

## Open

- The observed `2.47e-06` has no guard at its own magnitude: the floor is `1e-5`, so the pixel
  agreement can degrade fourfold without any test noticing. Dropping the floor to `5e-6` would cost
  nothing against today's numbers and would make the published sentence self-guarding.
- The gallery's per-class deviation figures (0.31%, 0.005%, 0.96 → 0.97) are all `lprintln`ed by
  `RectSLSdfBakeTest`'s `report`, not asserted. `ALIASED` is the one class where that is arguably
  right — the point of the classification is that no rate improves it.
