// StackSL, running on the CPU. No GPU, no window, no graphics context, no AWT.
//
// Until this backend existed, a StackSL program could only be RUN on a GPU — so every claim about
// what a word does was checked either by eye against a screenshot, or against a hand-written
// Kotlin twin of the emitted GLSL. This box is the third option: the real compiler's real output,
// interpreted.
//
// Note what is printed below the picture. That GLSL is not a description of what the CPU is about
// to do — it IS what the CPU is about to do, and it is byte for byte what a GPU would be handed.
// The CPU backend has no opinion about any StackSL word; it only knows GLSL.

import afield.stacksl.cpu.StackSLCpu

run {

    val program = """
        p 6 kaleido
        1.7 twist
        3.0 *
        fbm
        palette
        saturate
    """.trimIndent()

    // `StackSLCpu.compile` is the ordinary `stack.auto` compile: same options, same emitter.
    val glsl = StackSLCpu.compile(program).glsl
    println("The StackSL program:")
    println(program.prependIndent("    "))
    println()
    println("compiles to ${glsl.lines().count { it.isNotBlank() }} lines of GLSL, which is what BOTH backends read:")
    println(glsl.prependIndent("    "))

    // 256 is a comfortable size for a box. This is an interpreter, not a renderer: expect a
    // second or two here, and single-digit seconds for the docs' 512.
    val started = System.nanoTime()
    val raster = StackSLCpu.renderStill(program, dim = 256)
    val seconds = (System.nanoTime() - started) / 1e9
    println()
    println("rendered ${raster.width}x${raster.height} on the CPU in %.2fs".format(seconds))

    // `display(ByteArray)` recognises PNG bytes and shows the picture inline, so this needs no
    // image toolkit at all — `PngEncoder` is pure Kotlin.
    display(raster.toPngBytes())

    // Try it: change a number, or a word. `p 6 kaleido` -> `p 8 kaleido`, `fbm` -> `noise`,
    // `palette` -> `ryb-paint`. Anything the language accepts, this will render.
}
