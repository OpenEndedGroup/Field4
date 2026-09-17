// A `threshold` is a WALL. It lowers to a hard `step`, whose derivative is exactly zero everywhere
// except on a measure-zero set, so a fit standing at the seed reads no signal at all and does not
// move. This box shows the wall, and shows the object that replaces it.
//
// `AdSurrogate` does not smooth the shader. Every value it pushes is still the value the `Float`
// path computes; only the local partial changes. So the loss printed here is the TRUE loss at every
// temperature, and it is only the direction that is a surrogate.

import afield.stacksl.cpu.AdSurrogate
import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLImageLoss
import afield.stacksl.cpu.StackSLWires
import afield.util.PngEncoder

run {

    val seedProgram = "uv x 0.35 threshold"
    val targetProgram = "uv x 0.57 threshold"
    val template = StackSLCpu.stillTemplate()
    val dim = 32

    val wired = StackSLWires.lift(seedProgram)
    val wire = wired.literalWires.first { it.token == "0.35" }
    println("seed   : $seedProgram")
    println("target : $targetProgram      (the same program at a different edge)")
    println(wired.describe())
    println()

    val target = StackSLCpu.renderProgram(targetProgram, template, dim, dim)
    val loss = StackSLImageLoss(wired, template, dim, target)
    val seed = wired.initial

    // The wall itself. Not "small" — exactly zero, printed as a raw bit pattern so there is no
    // chance of reading 1e-18 as 0.
    val exact = loss.valueAndGradient(seed).gradient[wire.index]
    println("loss at the seed        : %.6e".format(loss.value(seed)))
    println("EXACT gradient of wire #${wire.index} ('${wire.token}') : $exact")
    println("  ... and is it exactly zero? " + (exact == 0.0))
    println()

    // The surrogate. `K_t(u) = sech^2(u / 2t) / 4t` is a mollified delta: the distributional
    // derivative of `step` is one delta at the edge, and this is that delta convolved with a bump
    // of width t. It integrates to `tanh(1/4t)` over a unit period, so a hot temperature carries
    // only part of the jump it stands for — which is the first half of the surrogate's bias.
    //
    // The honest oracle for a width-t derivative is the width-t CENTRAL DIFFERENCE of the true
    // loss, not its derivative: as h shrinks the truth goes to zero, which is the whole problem.
    println("temperature   unit-period mass   surrogate gradient   central difference at h=t   ratio")
    listOf(0.25, 0.1, 0.04, 0.016).forEach { t ->
        val surrogate = AdSurrogate.stepOnly(t)
        val g = loss.valueAndGradient(seed, surrogate).gradient[wire.index]
        val up = seed.copyOf().also { it[wire.index] += t }
        val down = seed.copyOf().also { it[wire.index] -= t }
        val fd = (loss.value(up) - loss.value(down)) / (2.0 * t)
        println(
            "   %.3f            %.4f          % .5e              % .5e         %s".format(
                t, surrogate.unitPeriodMass, g, fd,
                if (fd == 0.0) "(no oracle)" else "%.3f".format(g / fd)
            )
        )
    }
    println()
    println("The gradient is not an approximation of zero. It is the derivative of the loss AT A")
    println("SCALE - which is the only thing a descent through a jump could possibly use.")

    // And the two images, so the thing being recovered is visible rather than described. Left is
    // the target's edge at 0.57, right is the seed's at 0.35.
    val seedRaster = loss.render(seed)
    val scale = 5
    val panel = dim * scale
    val gutter = 8
    val width = 2 * panel + gutter
    // A NEUTRAL grey canvas, not the near-black the other demos use: these panels are thresholded
    // to pure black and white, and a dark gutter is invisible against them — the strip then reads
    // as one image with four bands rather than as two panels. Looked at, not assumed.
    val strip = IntArray(width * panel) { 0xFF7A7F87.toInt() }
    listOf(target, seedRaster).forEachIndexed { index, raster ->
        val argb = raster.toArgb()
        val x0 = index * (panel + gutter)
        for (y in 0 until panel) for (x in 0 until panel) {
            strip[y * width + x0 + x] = argb[(y / scale) * dim + (x / scale)]
        }
    }
    println()
    println("panels: TARGET (edge at 0.57) | SEED (edge at 0.35)")
    display(PngEncoder.encode(strip, width, panel))

    // Try it: change `0.35` to `0.55` and run again. The exact gradient stays exactly zero — a wall
    // does not get shorter as you approach it — while the loss falls and the surrogate's number
    // grows. That asymmetry is the whole reason the surrogate exists.
}
