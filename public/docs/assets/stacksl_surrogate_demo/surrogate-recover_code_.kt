// The counterfactual, run twice: the same fit, the same seed, the same target, the same sixty Adam
// steps, with the surrogate OFF and then ON.
//
// This is PARAMETER RECOVERY, not fitting. The target is rendered by the same program at a known
// edge, so "did it work" has an arithmetic answer and not an aesthetic one. Fitting a picture no
// StackSL program generated is a different claim and is not made here.
//
// With the surrogate off the fitted value is bit-identical to the seed — an exact gradient of zero
// is a zero step in ADAM too. That is what a wall looks like from inside an optimiser.

import afield.stacksl.cpu.AdAnnealing
import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLFit
import afield.util.PngEncoder
import kotlin.math.abs

run {

    val seedProgram = "uv x 0.35 threshold"
    val targetProgram = "uv x 0.57 threshold"
    val truth = 0.57
    val template = StackSLCpu.stillTemplate()
    val dim = 32
    val iterations = 60

    val target = StackSLCpu.renderProgram(targetProgram, template, dim, dim)

    val started = System.nanoTime()
    val off = StackSLFit.fit(
        seedProgram, target, template, dim = dim, iterations = iterations, learningRate = 0.05
    )
    // Geometric from a quarter of a unit cell down to a percent of one, stepped once per loss
    // evaluation. STEP only: the staircase family would also soften a noise lattice, where zero is
    // the CORRECT derivative — see the third box for what that costs.
    val on = StackSLFit.fit(
        seedProgram, target, template, dim = dim, iterations = iterations, learningRate = 0.05,
        annealing = AdAnnealing.stepOnly(iterations)
    )
    val seconds = (System.nanoTime() - started) / 1e9

    val wire = on.fittedIndices.first()
    val seedValue = on.seedWires[wire]
    val offValue = off.fittedWires[wire]
    val onValue = on.fittedWires[wire]
    val offError = abs(offValue - truth) / truth
    val onError = abs(onValue - truth) / truth

    println("recovering the edge of '$seedProgram', truth $truth")
    println("$iterations Adam steps at lr=0.05, ${dim}x$dim, both arms, %.1fs total".format(seconds))
    println()
    println("             fitted edge   relative error   loss")
    println("  seed         %.5f       %.4f          %.4e".format(seedValue, abs(seedValue - truth) / truth, off.seedLoss))
    println("  surrogate off %.5f      %.4f          %.4e".format(offValue, offError, off.fittedLoss))
    println("  surrogate on  %.5f      %.4f          %.4e".format(onValue, onError, on.fittedLoss))
    println()
    println("  did the OFF arm move at all? " + (offValue != seedValue))
    println("  recovery improvement: %.1fx".format(if (onError == 0.0) Double.POSITIVE_INFINITY else offError / onError))
    println("  annealing: ${on.annealing}, stepped ${on.annealing?.used} times")
    println()
    println("the re-emitted program, which is a program:")
    println("  " + on.fittedProgram.lines().joinToString("\n  "))

    // Four panels, so the number above is checkable by eye: the target's edge, the seed's edge,
    // where the exact fit left it (nowhere), and where the surrogate fit put it.
    val seedRaster = on.loss.render(on.seedWires)
    val offRaster = off.loss.render(off.fittedWires)
    val onRaster = on.loss.render(on.fittedWires)
    val scale = 4
    val panel = dim * scale
    val gutter = 8
    val width = 4 * panel + 3 * gutter
    // A NEUTRAL grey canvas: these panels are thresholded to pure black and white, so a dark gutter
    // is invisible against them and the four panels read as one banded image.
    val strip = IntArray(width * panel) { 0xFF7A7F87.toInt() }
    listOf(target, seedRaster, offRaster, onRaster).forEachIndexed { index, raster ->
        val argb = raster.toArgb()
        val x0 = index * (panel + gutter)
        for (y in 0 until panel) for (x in 0 until panel) {
            strip[y * width + x0 + x] = argb[(y / scale) * dim + (x / scale)]
        }
    }
    println()
    println("panels: TARGET | SEED | surrogate OFF (identical to the seed) | surrogate ON")
    display(PngEncoder.encode(strip, width, panel))

    // Try it: replace `threshold` with `6.0 posterize palette` and set the truth to 0.52 — a
    // quantiser rather than a predicate, and it needs the STAIRCASE family instead — add
    // `import afield.stacksl.cpu.AdSurrogateFamily` and pass:
    //
    //     annealing = AdAnnealing(0.25, 0.01, iterations, setOf(AdSurrogateFamily.STAIRCASE))
    //
    // Continuous parameters like this edge recover to about 2% relative. An INTEGER-valued one — a
    // level count, a bit depth — recovers to about 20%, because the loss is itself a staircase in
    // that parameter and a bump of width t cannot see across its shelves.
}
