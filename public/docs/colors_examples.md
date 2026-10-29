# Colors to FLine examples

A colour can look good on its own and disappear beside its neighbours. These examples draw a
whole palette as geometry, so you can see how its colours work together.

Every constructor returns an sRGB-encoded `Vec4` with components in `[0,1]`. There are four color spaces to build from:

- **sRGB**: `rgb(r, g, b, a)` (alias `rgba`) takes components already sRGB-encoded, on 0..1 or 0..255. The scale is guessed per call: any of `r`, `g`, `b` above 1 means 0..255. `rgb255(...)` is the same thing always on 0..255, so nothing is guessed.
- **HSL**: `hsl(h, s, l, a)` (alias `hsla`) is the classic cylinder, treated as linear light and then encoded to sRGB, so it is brighter than CSS `hsl()` at the same numbers.
- **OKHSL**: `okhsl(h, s, l, a)` is hue, saturation and lightness built on Oklab, with `l` as *perceived* lightness and `s = 1` at the gamut edge for that hue.
- **OKHSV**: `okhsv(h, s, v, a)` is the HSV-shaped version of the same, where `v` dims a color toward black.

The three hue-based spaces read `h` as a fraction of a turn below 1 and as degrees from 1 up. Each one also has a ring generator that spaces `count` hues evenly: `hueSetHsl`, `hueSetOkhsl` and `hueSetOkhsv`, with the short aliases `hslSet`, `okhslSet` and `okhsvSet`. `paletteForBackground` builds an OKHSL ring and then corrects it for contrast. Oklab itself is used inside the file and has no public constructor.

## HSL vs OKHSL hue sets

Stepping evenly round the hue wheel is the obvious way to ask for ten distinguishable colors, and it mostly works. What drifts is perceived weight. One swatch shouts, the next one disappears, and ten colors that were meant to read as a family stop doing so.

```diffstatic_runner {data-lang=kotlin screenshot="assets/colors_examples/hsl_okhsl_sets.png" wait="1.0" capture="box"}
:::static
fun swatchRow(y0: Double, colors: List<Vec4>, h: Double): List<FLine> = colors.mapIndexed { i, c ->
    val x0 = 40.0 + i * 90.0
    fline {
        rect(x0, y0, 78.0, h)
        filled = true
        stroked = true
        color = c
        thickness = 1.2
    }
}

val hslSet = hueSetHsl(10, saturation = 0.78, lightness = 0.58)
val okhslSet = hueSetOkhsl(10, saturation = 0.78, lightness = 0.58)

lines(
    swatchRow(120.0, hslSet, 120.0) + swatchRow(300.0, okhslSet, 120.0),
    channel = "colors-hues",
    w = 1000.0,
    h = 560.0,
    fitToSize = true,
)
```

The `swatchRow(...)` helper removes everything except the palette itself: same count, same geometry, same spacing, same background, one row per hue system.

![Two rows of ten swatches: HSL hues on top, OKHSL hues below](assets/colors_examples/hsl_okhsl_sets.png)

In the HSL row on top, the third and sixth swatches (a yellow-green and a cyan) come forward off the black; the OKHSL row below holds one weight across all ten. `okhsl`'s `l` is perceived lightness rather than an HSL number, so no entry ends up conspicuously brighter than its neighbours the way an HSL yellow does.

## paletteForBackground on layered paths

A palette can look fine in isolation and fail against a real background. The colors have to read against the ground and against each other, and on a dark canvas the ones that fail are the dark ones.

```diffstatic_runner {data-lang=kotlin screenshot="assets/colors_examples/palette_background_paths.png" wait="1.0" capture="box" open-outputs="true"}
:::static
val bg = rgb(0.06, 0.08, 0.11, 1.0)
val palette = paletteForBackground(bg, count = 7, saturation = 0.78, lightness = 0.63, minContrast = 3.5)

fun ring(radius: Double, phase: Double, color: Vec4): FLine = fline {
    val n = 260
    breakNext()
    for (i in 0..n) {
        val t = i / n.toDouble() * PI * 2.0
        val x = 540.0 + cos(t + phase) * radius
        val y = 340.0 + sin(t + phase * 1.4) * radius * 0.55
        lineTo(x, y)
    }
    stroked = true
    filled = false
    this.color = color
    this.thickness = 3.0
}

val rings = palette.mapIndexed { i, c -> ring(80.0 + i * 38.0, i * 0.21, c) }

lines(rings, channel = "colors-rings", w = 1120.0, h = 740.0, fitToSize = true)
```

`paletteForBackground(...)` starts from the same evenly spaced okhsl ring as `hueSetOkhsl`, then for any entry whose WCAG contrast against the background falls short of `minContrast` it walks that entry's lightness until it clears the bar; a dark background is searched lighter first. Here it gets `minContrast = 3.5`, a little above the WCAG large-text threshold of 3, and all seven colors go on nested rings where a weak one would be easy to spot.

![Seven nested rings in paletteForBackground colors on a dark background](assets/colors_examples/palette_background_paths.png)

Every ring stays readable and none sinks into the background. If nothing reaches `minContrast` the best candidate found is returned rather than an exception, so the call always hands back the count you asked for and it is the picture that tells you whether it managed. For more on constructing the underlying paths, see [FLine examples](fline_examples.md); for functional signal stacks that can drive color or geometry, see [Functions examples](functions_examples.md).

*The public declarations used on this page are listed, with their KDoc, in the codebase reference: [`colors.kt`](reference/graphics.md#colors-kt). Start from [what's already in the box](reference/index.md).*
