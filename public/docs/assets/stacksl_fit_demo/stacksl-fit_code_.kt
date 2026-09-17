// Gradient descent on a shader's own literals.
//
// The target is the SAME program with twelve different palette numbers, so this fit has a known
// answer and "did it work" is a question you can settle by looking: panels are target, seed,
// fitted, and the residual amplified eight times.
//
// What makes this exact rather than approximate: StackSL is a per-pixel field. `main()` runs once
// per pixel with `fragUV` as its only spatial input, so d(pixel)/d(literal) is the ordinary chain
// rule. There is no rasteriser in the pipeline, no coverage approximation and therefore no bias to
// characterise — which is exactly why the same idea was correctly abandoned for RectSL, where a
// pixel depends on a parameter through coverage.
//
// The gradient flows through the emitted GLSL itself, interpreted node by node on
// `afield.stdlib`'s reverse-mode AD tape, one tape per pixel. No second evaluator.

import afield.stacksl.cpu.CpuRaster
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
    val dim = 64

    // 64 square is sized from the measured cost, not from optimism: this interpreter does about
    // 1.3e4 pixel-shader invocations per second per core and 4.4e4 across ten, so one forward
    // image pass is a tenth of a second and eighty gradient steps are a handful.
    val target = StackSLCpu.renderProgram(targetProgram, template, dim, dim)

    val started = System.nanoTime()
    val result = StackSLFit.fit(
        seedProgram, target, template,
        dim = dim, iterations = 80, learningRate = 0.05
    )
    val seconds = (System.nanoTime() - started) / 1e9

    println("${result.wired.wires.size} wires, ${result.fittedIndices.size} fitted")
    println(
        "loss %.4e -> %.4e (%.0fx) in 80 Adam steps, ${dim}x$dim, %.1fs"
            .format(result.seedLoss, result.fittedLoss, result.lossRatio, seconds)
    )
    println()
    println("what moved:")
    result.report().forEach { println("    $it") }

    // The four alpha wires do not move, and that is correct: the `w` component of a `palette`
    // operand never reaches RGB, so its gradient is exactly zero. A fit cannot invent a reason to
    // change a parameter the image does not depend on.

    val seed = result.loss.render(result.seedWires)
    val fitted = result.loss.render(result.fittedWires)
    val residual = CpuRaster(
        dim, dim,
        FloatArray(fitted.rgba.size) { i ->
            if (i % 4 == 3) 1f else (abs(fitted.rgba[i] - target.rgba[i]) * 8f).coerceIn(0f, 1f)
        }
    )

    var sum = 0.0
    var count = 0
    for (i in 0 until dim * dim) for (ch in 0 until 3) {
        sum += abs(fitted.rgba[i * 4 + ch] - target.rgba[i * 4 + ch]).toDouble()
        count++
    }
    println()
    println("fitted vs target: %.5f mean absolute error per RGB sample over $count samples".format(sum / count))
    println("panels: target | seed | fitted | residual x8")

    // Stitch the four panels into one strip at 3x, nearest-neighbour, with a gutter.
    val scale = 3
    val panel = dim * scale
    val gutter = 6
    val width = 4 * panel + 3 * gutter
    val strip = IntArray(width * panel) { 0xFF14161A.toInt() }
    listOf(target, seed, fitted, residual).forEachIndexed { index, raster ->
        val argb = raster.toArgb()
        val x0 = index * (panel + gutter)
        for (y in 0 until panel) for (x in 0 until panel) {
            strip[y * width + x0 + x] = argb[(y / scale) * dim + (x / scale)]
        }
    }
    display(PngEncoder.encode(strip, width, panel))

    println()
    println("the fitted program, which is a program:")
    println(result.fittedProgram.prependIndent("    "))

    // The residual is the panel that decides this, not the loss number. A sum-of-squares objective
    // rewards colour and luminance statistics, so a fit can drive the loss a long way down while
    // producing an image that does not resemble the target at all. Here the residual is nearly
    // black, which is what "it found the picture" looks like.
}
