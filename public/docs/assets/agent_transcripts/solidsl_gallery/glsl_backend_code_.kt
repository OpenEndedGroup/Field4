import afield.solidsl.*

// ---- a shape that is worth ray-marching ----------------------------------------------
// A twisted star column standing in a bored plate, blended where they meet. Change the
// twist and re-run: the printed shader changes with it, because the shader is emitted
// from the expression rather than written beside it.
val twist = 140.0
val blend = 0.18

val column = Profile.star(6, 0.85, 0.42).extruded(2.4, twistDegrees = twist).at(0.0, 0.0, -1.2)
val plate = cuboid(3.6, 3.6, 0.5).at(0.0, 0.0, -1.35)
val scene = smoothUnion(column, plate, k = blend) - cylinder(4.0, 0.28, segments = 64)
// ---------------------------------------------------------------------------------------

val emitted = scene.toGlsl()

println("$emitted")
println()
println("A `(Vec3) -> Float` runs on the JVM and nowhere else. An expression tree walks")
println("into whatever language you like, and this is the third place this one goes:")
println()
println(emitted.glsl.lines().take(26).joinToString("\n"))
println("    ...  (${emitted.glsl.lines().size} lines in total)")
println()

// The emitted code is scalar single-assignment on purpose — no vec3 locals, no swizzles,
// no loops. Any driver re-vectorises it in the first pass, and what it buys is a grammar
// small enough that `SolidSLGlslTest` can parse the shader BACK and run it against the
// Kotlin field. 29 shapes, 58,000 sampled points, worst disagreement 6.3e-15. That is a
// measurement of the backend's fidelity, not a hope that two tree walks agree.
println("step scale ${emitted.stepScale} — ${if (emitted.notes.isEmpty()) "a true distance field, so sphere tracing is exact" else "the field over-estimates distance somewhere"}")
emitted.notes.forEach { println("  - $it") }
println()

// The simplifier, which only exists because the shape is a value.
println("---- what the simplifier can do ----")
val wasteful = differenceOf(
    listOf(scene) + (0 until 40).map { cylinder(3.0, 0.12, segments = 32).at(-8.0 + it * 0.42, 0.0, 0.0) },
)
println("40 extra bores, most of them nowhere near the part:")
println("  ${wasteful.simplificationReport()}")
println("  Disjointness is decided from symbolic bounds — nothing sampled, nothing built.")
println()

// And the rule that is genuinely strange: a blend of radius zero IS a union, and a union
// has an exact CSG form where a smooth blend never will. So simplifying can move an
// expression off the level-set fallback and onto the exact path.
val noBlend = smoothUnion(column, plate, k = 0.0)
println("a blend with k = 0:")
println("  ${noBlend.simplificationReport()}")
println()

println("---- the whole raymarch fragment, ready to paste ----")
println(scene.toRaymarchFragment())
