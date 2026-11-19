import afield.lang.stacksl.cpu.*

// Edit this and rerun: the residual is re-derived from the program each time.
val program = "uv 4.0 * fbm palette saturate"

val source = StackSLCpu.expandStackAuto(StackSLCpu.stillTemplate(), program)
val started = System.nanoTime()
val residual = GlslPartialEvaluator.specialize(source)
val specializeMs = (System.nanoTime() - started) / 1e6

// The same picture twice: interpreted, then the residual's register program.
val interpreted = StackSLCpu.renderFragment(source, 160, 160)
val specialized = residual.render(160, 160)
val identical = interpreted.rgba.indices.all { interpreted.rgba[it].toRawBits() == specialized.rgba[it].toRawBits() }

display(specialized.toPngBytes())
println(
    "${source.lines().size} lines of GLSL -> ${residual.operationCount} residual operations in %.1f ms"
        .format(specializeMs),
)
println("bit-identical to the interpreter: $identical")
println(residual.operationHistogram().take(6).joinToString(", ") { (op, n) -> "$op $n" })
markdown("residual", "```glsl\n" + residual.toGlsl().lines().take(40).joinToString("\n") + "\n...\n```")
