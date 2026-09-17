// Why `AdSurrogate.all(...)` is not the default, and why the brief that asked for this work had
// the question upside down.
//
// `fbm`'s lattice is `floor(p)`, and it was supposed to be the wall blocking a noise fit. It is the
// opposite. `_hash21` is `fract(p * vec2(123.34, 456.21))`, `p += dot(p, p + 45.32)`,
// `fract(p.x * p.y)` — all differentiable, none of it bit-twiddling, and utterly chaotic. Inside a
// value-noise cell the four corner hashes ARE constants, so zero is the TRUE derivative there, and
// `floor` is the thing keeping that chaos out of the answer.
//
// So this box runs the experiment the premise implies, and watches it fail on purpose.

import afield.stacksl.cpu.AdSurrogate
import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLImageLoss
import afield.stacksl.cpu.StackSLWires
import kotlin.math.abs
import kotlin.math.max

run {

    val program = """
        uv 3 * fbm
        0.5 0.5 0.5 1 vec4
        0.5 0.5 0.5 1 vec4
        1.0 1.0 1.0 1 vec4
        0.0 0.33 0.67 1 vec4
        palette
    """.trimIndent()

    val template = StackSLCpu.stillTemplate()
    val dim = 32

    val wired = StackSLWires.lift(program)
    val wire = wired.literalWires.first { it.token == "3" }
    println("the frequency wire: #${wire.index} '${wire.token}' at ${wire.line}:${wire.column}")

    // A fixed target, so the loss is a real objective. Any picture will do here; the question is
    // about the GRADIENT, and the oracle is a central difference of the same loss.
    val target = StackSLCpu.renderProgram("uv 5 * fbm 0.2 0.5 0.7 1 vec4 palette", template, dim, dim)
    val loss = StackSLImageLoss(wired, template, dim, target)
    val values = wired.initial

    val h = 1e-3 * max(1.0, abs(values[wire.index]))
    val up = values.copyOf().also { it[wire.index] += h }
    val down = values.copyOf().also { it[wire.index] -= h }
    val fd = (loss.value(up) - loss.value(down)) / (2.0 * h)
    val exact = loss.valueAndGradient(values).gradient[wire.index]

    println()
    println("the EXACT gradient, with floor() carrying its honest zero:")
    println("  exact              % .6e".format(exact))
    println("  central difference % .6e   (h = %.1e)".format(fd, h))
    println("  relative disagreement %.3e   <- this wire ALREADY works".format(abs(exact - fd) / max(abs(fd), 1e-12)))
    println()
    println("now switch the STAIRCASE family on, which is what a 'smoothed floor' means here:")
    println("  temperature   staircase gradient    relative disagreement")
    var worst = 0.0
    var signsSeen = 0
    listOf(0.25, 0.1, 0.04, 0.016).forEach { t ->
        val g = loss.valueAndGradient(values, AdSurrogate.staircaseOnly(t)).gradient[wire.index]
        val relative = abs(g - fd) / max(abs(fd), 1e-12)
        if (relative > worst) worst = relative
        if (g > 0.0) signsSeen = signsSeen or 1
        if (g < 0.0) signsSeen = signsSeen or 2
        println("     %.3f        % .6e         %.3e".format(t, g, relative))
    }
    println()
    // Read off the measurement rather than remembered: the exact size depends on the target and
    // the resolution, and a sentence with a number baked into it goes stale the first time either
    // one moves. Five to seven orders of magnitude at this target; the jvmTest fence, on a
    // different target, measures six to eight.
    println(
        "That is %.0f orders of magnitude, and the sign %s.".format(
            kotlin.math.log10(worst),
            if (signsSeen == 3) "FLIPS across the sweep" else "at least stays put"
        )
    )
    println("It gets WORSE as the schedule cools, because a narrower kernel has a taller peak and")
    println("the hash multiplies it up. A soft floor does not help this wire - it is exactly what")
    println("the wire was being protected from.")
    println()
    println("The rule: STEP for a predicate, STAIRCASE for a quantiser, and never STAIRCASE on a")
    println("program containing noise, fbm, noise3, fbm3, worley3 or voronoi3.")

    // And the picture the wire draws, so the thing being argued about is on screen.
    display(StackSLCpu.renderStill(program, dim = 220).toPngBytes())

    // Try it: change `AdSurrogate.staircaseOnly(t)` to `AdSurrogate.stepOnly(t)`. The numbers go
    // straight back to the exact gradient, because this program contains no `step` at all — which
    // is the argument for a per-family surrogate rather than one switch.
}
