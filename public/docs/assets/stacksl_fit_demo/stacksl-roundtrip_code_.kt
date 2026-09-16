// The step that turns a number into a capability: the fit's output is StackSL SOURCE.
//
// A fitted wire vector is a `DoubleArray` and nobody can run a `DoubleArray`. So the fitted values
// are written back into the program at the token positions the compiler recorded, and the result
// is an ordinary StackSL program — which this box then compiles from scratch through the ORDINARY
// non-wired path and renders at 256. Paste the text it prints into a `stackSL` box and a GPU will
// draw it.
//
// The number to watch is the last one: the maximum per-channel difference between the re-emitted
// program's render and the fitted wire vector's render. If the literal rewriting were wrong — a
// column off by one, a token mismatched — it would not be small, it would be a different picture.

import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLFit
import afield.util.PngEncoder
import kotlin.math.abs

run {

    val seedProgram = """
        uv 3.0 * fbm
        0.5 0.5 0.5 1 vec4
        0.5 0.5 0.5 1 vec4
        1.0 1.0 1.0 1 vec4
        0.0 0.33 0.67 1 vec4
        palette
    """.trimIndent()

    val targetProgram = """
        uv 3.0 * fbm
        0.62 0.41 0.35 1 vec4
        0.31 0.45 0.52 1 vec4
        1.0 1.0 1.0 1 vec4
        0.12 0.55 0.88 1 vec4
        palette
    """.trimIndent()

    val template = StackSLCpu.stillTemplate()

    // 48 square and 40 steps, because this box is about the round trip and not about the fit — the
    // box next door does the careful version at 64 with 80 steps.
    val dim = 48
    val target = StackSLCpu.renderProgram(targetProgram, template, dim, dim)
    val result = StackSLFit.fit(seedProgram, target, template, dim = dim, iterations = 40, learningRate = 0.06)
    println("fitted at ${dim}x$dim: loss %.4e -> %.4e".format(result.seedLoss, result.fittedLoss))

    // Same wire vector, two routes to an image.
    val viaWires = result.loss.render(result.fittedWires)
    val viaSource = StackSLCpu.renderProgram(result.fittedProgram, template, dim, dim)

    var worst = 0.0
    for (i in 0 until dim * dim) for (ch in 0 until 3) {
        val d = abs(viaWires.rgba[i * 4 + ch] - viaSource.rgba[i * 4 + ch]).toDouble()
        if (d > worst) worst = d
    }

    println()
    println("paste this into a stackSL box and a GPU will draw it:")
    println(result.fittedProgram.prependIndent("    "))
    println()

    // Render the re-emitted program big, through the ordinary compiler, with no wires involved.
    val started = System.nanoTime()
    val big = StackSLCpu.renderStill(result.fittedProgram, dim = 256)
    println("re-emitted program rendered at 256x256 in %.1fs".format((System.nanoTime() - started) / 1e9))
    println("round trip, max per-channel difference over ${dim * dim * 3} samples: %.3e".format(worst))
    display(big.toPngBytes())

    // A side-by-side of the two routes at fit resolution, 4x, so the claim above is visible and
    // not only printed.
    val scale = 4
    val panel = dim * scale
    val gutter = 6
    val width = 2 * panel + gutter
    val strip = IntArray(width * panel) { 0xFF14161A.toInt() }
    listOf(viaWires, viaSource).forEachIndexed { index, raster ->
        val argb = raster.toArgb()
        val x0 = index * (panel + gutter)
        for (y in 0 until panel) for (x in 0 until panel) {
            strip[y * width + x0 + x] = argb[(y / scale) * dim + (x / scale)]
        }
    }
    println("panels: rendered from the fitted WIRES | rendered from the re-emitted SOURCE")
    display(PngEncoder.encode(strip, width, panel))

    // The one lossy step in the trip is the printing: `%.8f` is eight significant decimals and
    // binary32 needs nine to round-trip in general, so a fitted value can land one ulp away in the
    // source. In the committed measurement it did not — the round trip is exactly zero — but the
    // guard in `StackSLFitFigureTest` allows 1e-4 for that reason rather than demanding zero.
}
