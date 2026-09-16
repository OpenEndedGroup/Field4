// Every number you typed in a StackSL program is a free parameter. Here they are.
//
// `StackSLCompileOptions.externalizeConstants` lifts each numeric literal out of a program into a
// wire, and records where it came from: the token, the line, the column. `StackSLWires.lift` turns
// that into something the CPU backend can actually run — the emitted array becomes scalar globals,
// so a new parameter vector costs a `setGlobal` rather than a recompile.
//
// Read the table below against the program above it. Wire #0 is the `3.0` you can see; wires
// #1..#16 are the four `vec4` operands of `palette`. Nothing here invented syntax for a parameter.

import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLWires

run {

    val program = """
        uv 3.0 * fbm
        0.5 0.5 0.5 1 vec4
        0.5 0.5 0.5 1 vec4
        1.0 1.0 1.0 1 vec4
        0.0 0.33 0.67 1 vec4
        palette
    """.trimIndent()

    println("The StackSL program:")
    println(program.prependIndent("    "))
    println()

    val wired = StackSLWires.lift(program)
    println("${wired.wires.size} wires, ${wired.literalWires.size} of them literal-backed:")
    println("    idx  token   at      initial  note")
    wired.wires.forEach { wire ->
        println(
            "    #%-3d %-7s %-7s %-8s %s".format(
                wire.index,
                wire.token ?: "-",
                if (wire.line != null) "${wire.line}:${wire.column}" else "-",
                wire.initial,
                wire.note
            )
        )
    }

    // The distinction that matters for writing a fitted value BACK into the source: a wire noted
    // "number" stands for a literal a human typed, so it has a token to replace. A wire noted
    // "optional default ..." is a default the emitter supplied for a word that was given no
    // operand — there is nothing in the source to rewrite. This program has 17 of the first kind
    // and 0 of the second, which is why it is the one the fit uses.
    println()
    println("The lowered GLSL starts with the wires as ordinary globals:")
    println(wired.glsl.lines().take(4).joinToString("\n").prependIndent("    "))
    println("    ... then ${wired.glsl.lines().size - 4} more lines, with no array anywhere:")
    println("    " + (if (wired.glsl.contains("${wired.prefix}_wires")) "FAILED" else "ok"))

    // And it renders, on the CPU, with no GPU and no window.
    val started = System.nanoTime()
    val raster = StackSLCpu.renderStill(program, dim = 220)
    println()
    println("rendered ${raster.width}x${raster.height} in %.2fs".format((System.nanoTime() - started) / 1e9))
    display(raster.toPngBytes())

    // Try it: change the `3.0`, or any of the twelve palette numbers, and run again. Then look at
    // the table — the token and the column move with your edit, because they are read off the
    // compiler's own record of where it found each number.
}
