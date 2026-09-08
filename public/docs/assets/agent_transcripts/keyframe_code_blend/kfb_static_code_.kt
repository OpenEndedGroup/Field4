// Two StackSL token forms carry their numbers inside a grammar that wants literals: a
// modifier chain (`0.35s3` — an amount modulated by a sine at frequency 3) and a random
// range (`0.2_0.5`). No mix expression can be written at either position, so a runtime
// blend cannot reach them and says so rather than pretending.
import afield.stdlib.CodeBlendMode
import afield.stdlib.CodeBlendOptions
import afield.stdlib.planStackSLCodeBlend

val quiet = "uv 0.35s3 * fbm 0.2_0.5 +"
val loud = "uv 0.80s3 * fbm 0.6_0.9 +"

val plan = planStackSLCodeBlend(quiet, loud, CodeBlendOptions(mode = CodeBlendMode.RUNTIME))
println(plan.describe())
println("asked for RUNTIME, will run " + plan.effectiveMode)
println("because " + plan.downgradeReason)
println()

// A different program per t, and therefore a compile per t: fine for a scrub, not for a
// frame loop. Note the modifier's frequency: unchanged between the two, so it comes back
// as `3` rather than `3.0`. A site is atomic — one moving component rewrites the whole
// token — and keeping an untouched number's spelling is what stops that showing.
listOf(0.0, 0.5, 1.0).forEach { t ->
    println("t=" + t + "   " + plan.bake(t))
}

display(plan)
