import afield.solidsl.*

// ---- change the twist and re-run: the shader changes with it ----------------------
val twist = 140.0       // Degrees over the column's height.
val blend = 0.18
// -----------------------------------------------------------------------------------

// This box needs no Manifold, no GPU and no window. Lowering to GLSL is a tree walk, so
// it is the cheapest possible check that the emitter still tracks the language.
val column = Profile.star(6, 0.85, 0.42)
    .extruded(2.4, twistDegrees = twist)
    .at(0.0, 0.0, -1.2)
val plate = cuboid(3.6, 3.6, 0.5).at(0.0, 0.0, -1.35)
val scene = smoothUnion(column, plate, k = blend) - cylinder(4.0, 0.28, segments = 64)

val emitted = scene.toGlsl()

println("---- the expression, as a distance function ----")
println("function         ${emitted.functionName}")
println("statements       ${emitted.statementCount}")
println("step scale       ${emitted.stepScale}")
if (emitted.notes.isEmpty()) {
    println("notes            (none — a true distance field, so sphere tracing is exact)")
} else {
    println("notes:")
    emitted.notes.forEach { println("  - $it") }
}
println()

// The emitted code is scalar single-assignment on purpose: no vec3 locals, no swizzles,
// no loops. Any driver re-vectorises it in the first pass, and the small grammar is what
// lets the test suite parse the shader BACK and run it against the Kotlin field.
println("---- the first 20 lines of ${emitted.glsl.lines().size} ----")
println(emitted.glsl.lines().take(20).joinToString("\n"))
println("    ...")
println()

// ---- the simplifier, which only exists because the shape is a value ---------------
val wasteful = differenceOf(
    listOf(scene) + (0 until 40).map {
        cylinder(3.0, 0.12, segments = 32).at(-8.0 + it * 0.42, 0.0, 0.0)
    },
)
println("---- 40 extra bores, most of them nowhere near the part ----")
println(wasteful.simplificationReport())
println("Disjointness is decided from symbolic bounds. Nothing was sampled and nothing built.")
println()

// The rule that is genuinely strange, and worth trying in the box above too: a blend of
// radius zero IS a union, and a union has an exact CSG form where a smooth blend never
// will. So simplifying can move an expression off the level-set fallback onto the exact
// path — a rewrite that changes which BACKEND can take it.
val noBlend = smoothUnion(column, plate, k = 0.0)
println("---- the same scene with k = 0.0 ----")
println(noBlend.simplificationReport())
// Read those two lines together, because apart they look like a contradiction. The report
// ends `path level set -> exact`: that is the path of the SIMPLIFIED tree. The original
// tree still has a SmoothUnion node in it and still refuses. The rewrite is what removes
// the refusal, so you have to ask the simplified expression to see it gone.
println("  before simplifying: ${noBlend.exactRefusal()?.why ?: "(none)"}")
println("  after simplifying:  ${noBlend.simplified().exactRefusal()?.why ?: "(none — it lowers exactly)"}")
