# Proving What a Shader Draws

A StackSL program is a function of `fragUV`, run once per pixel. Field's CPU backend can also run it over a whole *rectangle* of pixels at once, with every number replaced by an interval. What comes back is a colour range that every pixel in the rectangle is guaranteed to fall inside. Nothing is sampled, so a one-pixel speck can't hide from it.

This runs the GLSL the compiler emits: the same interpreter that renders the [gallery](stacksl_examples.md) on the CPU and differentiates it for fitting, given intervals in place of floats. The bound covers exactly the `Float` values that renderer produces, not an idealised version of the program.

## Tiles that prove themselves

`StackSLIntervals.tilePicture(program)` renders a program and, beside it, splits the image into squares. A square whose colour range is narrower than the tolerance (two 8-bit levels, by default) is **settled**: proven flat without rendering a single pixel of it. A square that isn't settled is split into four, down to single pixels.

```diffstatic_runner {data-lang=kotlin screenshot="assets/stacksl_intervals/ring.png" wait="1.0" wait-output="true" capture="box" embed="true" open-outputs="true" w="1100" timeout="120" name="stacksl-intervals-ring"}
:::static
import afield.stacksl.cpu.StackSLIntervals

display("tiles", StackSLIntervals.tilePicture("p length 0.5 0.08 pulse white *"))
```

The soft ring (left) and its proof (right). Each square on the right is filled with the colour it was proven to have and outlined. The black inside and outside the ring settles in large squares; the squares shrink towards the ring's soft edge, where the colour changes from one pixel to the next. About 60% of the image is proven by a square bigger than one pixel.

A single pixel is always settled, because a pixel is one input, and one input gives exactly the value the renderer computes.

## How fast the bound tightens

The width of the bound depends on how much the program can change across the region, and on how well interval arithmetic can follow it. This table bounds a square of each size centred on the pixel `(64, 8)`, which sits on the soft ring's edge, and reports the widest channel's range:

```diffstatic_runner {data-lang=kotlin screenshot="assets/stacksl_intervals/widths.png" wait="1.0" wait-output="true" capture="box" embed="true" open-outputs="true" w="1100" timeout="180" name="stacksl-intervals-widths"}
:::static
import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLIntervals

val programs = listOf(
    "p length 0.5 0.08 pulse white *",
    "uv x 18 * sin 0.5 + 0.5 * palette saturate",
    "uv 6 * noise palette saturate",
    "uv 5 * fbm 0.45 threshold orange * saturate",
)
val sizes = listOf(64, 16, 4, 2, 1)
val table = Table(listOf("program") + sizes.map { "${it}×$it" },
    title = "widest channel of the bound, for a square centred on pixel (64, 8) of a 128×128 render")
for (program in programs) {
    val glsl = StackSLCpu.expandStackAuto(StackSLCpu.stillTemplate(), program)
    table.row(listOf(program) + sizes.map { n ->
        val x0 = 64 - n / 2
        val y0 = (8 - n / 2).coerceIn(0, 128 - n)
        val b = StackSLIntervals.enclosePixels(glsl, x0, y0, x0 + n - 1, y0 + n - 1, 128, 128)
        if (b.certified) "%.4f".format(b.width) else "refused"
    })
}
display("widths", table)
```

The ring and the stripes tighten steadily as the square shrinks. The two noise programs don't: their bound stays wide until the square is a single pixel. Value noise and fbm look up a pseudo-random hash at each lattice point, and the hash takes `fract` of a large product. Over any range wider than a point, that is anywhere in `[0, 1]`. So these programs are bounded, and the bound is correct, but it is only useful pixel by pixel. That is a limit of plain interval arithmetic over a hash: splitting the region further doesn't help until it stops being a region.

```diffstatic_runner {data-lang=kotlin screenshot="assets/stacksl_intervals/threshold.png" wait="1.0" wait-output="true" capture="box" embed="true" open-outputs="true" w="1100" timeout="180" name="stacksl-intervals-threshold"}
:::static
import afield.stacksl.cpu.StackSLIntervals

display("tiles", StackSLIntervals.tilePicture("uv 5 * fbm 0.45 threshold orange * saturate"))
```

Here the image is mostly flat orange and flat black, but because the threshold reads fbm, no square larger than a pixel can be proven. The quadtree goes all the way down everywhere.

## Branches

A comparison between two overlapping intervals is neither true nor false. When an `if` or a `?:` meets one, the interpreter runs **both** arms from the same starting state and keeps, for every variable, the range covering what either arm wrote. When the comparison is decided, only one arm runs, exactly as it would for a single pixel.

```diffstatic_runner {data-lang=kotlin screenshot="assets/stacksl_intervals/branches.png" wait="1.0" wait-output="true" capture="box" embed="true" open-outputs="true" w="1100" timeout="120" name="stacksl-intervals-branches"}
:::static
import afield.stacksl.cpu.StackSLIntervals

val shader = """
    #version 450
    layout(location = 0) in vec2 fragUV;
    layout(location = 0) out vec4 outColor;
    void main() {
        float v = 0.25;
        if (fragUV.x < 0.5) v = 0.75;
        outColor = vec4(v, v, v, 1.0);
    }
""".trimIndent()

val early = """
    #version 450
    layout(location = 0) in vec2 fragUV;
    layout(location = 0) out vec4 outColor;
    float f(float x) { if (x < 0.5) return 1.0; x = x * 2.0; return x; }
    void main() { outColor = vec4(f(fragUV.x)); }
""".trimIndent()

display("branches", Table(listOf("shader", "pixels", "red channel"), title = "an undecided if runs both arms")
    .row("if (x < 0.5) v = 0.75", "left third", StackSLIntervals.enclosePixels(shader, 0, 0, 20, 63, 64, 64).let { "${it.lo[0]}..${it.hi[0]}" })
    .row("if (x < 0.5) v = 0.75", "right third", StackSLIntervals.enclosePixels(shader, 43, 0, 63, 63, 64, 64).let { "${it.lo[0]}..${it.hi[0]}" })
    .row("if (x < 0.5) v = 0.75", "a row across the middle", StackSLIntervals.enclosePixels(shader, 0, 0, 63, 0, 64, 64).let { "${it.lo[0]}..${it.hi[0]}" })
    .row("return from one arm only", "a row across the middle", StackSLIntervals.enclosePixels(early, 0, 0, 63, 0, 64, 64).refusal))
```

Two arms that leave by different routes, one returning and one carrying on, can't be merged into one continuation, so that run is refused, and the refusal says why. A loop whose condition is undecided is refused the same way, and so is an array index that isn't a single value. StackSL's emitted loops count octaves to a fixed number, so they never hit this.

## What is refused

Builtins with no interval rule yet are refused by name rather than guessed: the texture functions, `cross`, `reflect`, `refract`, `faceforward`, `tan`, the inverse trigonometric functions, the hyperbolic functions other than `tanh`, the matrix builtins and the bit operators. None of the 29 gallery programs that don't sample a texture uses one, and all of them can be bounded.

!!! note "What exactly is guaranteed"
    For every pixel centre inside the rectangle, each channel the `Float` reference path renders lies inside the bound, or is NaN and the bound says NaN is possible, or the pixel was discarded and the bound says it may be. Each operation is bounded in `Double` and its endpoints rounded outward to `Float`. This is sound for `+ − × ÷` and `sqrt` because rounding to nearest is monotone. The library transcendentals are widened by a few `Double` ulps before rounding. Composite builtins (`mod`, `fract`, `mix`, `smoothstep`, `dot`, `length`) are bounded one operation at a time, in the order the reference path computes them.

??? aside "Where this lives"
    The interval values are `GlslIntervalScalars` in `src/jvmMain/kotlin/afield/stacksl/cpu/GlslInterval.kt`, the third value type for the GLSL interpreter after the `Float` reference and the reverse-mode one in `GlslAd.kt`. The three-valued conditions are in `GlslMachine` (`GlslInterpreter.kt`): `GlslScalars.truth` answers `UNKNOWN` only for the interval values, so the other two are unchanged, and `StackSLCpuFloatPathManifestTest` and `StackSLGradientTest` still pin their bits. `StackSLIntervals.kt` holds `enclosePixels`, `certifiedTiles` and `tilePicture`. `StackSLIntervalTest` renders the whole non-texture gallery and checks some 400,000 pixels against the bounds of the rectangles they sit in, with no violations. The Fn version of the same idea, and the design note for both, is [Proving a Function's Range](fn_intervals.md) and `dev_docs/FN_INTERVAL.md`.
