// A code keyframe is a program. Two of them have no midpoint in general — half a program
// is not a program — so a keyframe blend steps them. Unless they differ only in numbers,
// which is the case worth finding, because that is the case a person authoring two looks
// for one patch keeps producing.
import afield.stdlib.CodeBlendMode
import afield.stdlib.CodeBlendOptions
import afield.stdlib.planStackSLCodeBlend

val warm = "uv 3.0 * fbm 0.15 + [1.0,0.55,0.2] *"
val cool = "uv 8.0 * fbm 0.45 + [0.2,0.5,1.0] *"

val plan = planStackSLCodeBlend(warm, cool, CodeBlendOptions(mode = CodeBlendMode.RUNTIME))
println(plan.describe())
println("blendable=" + plan.blendable + "  runtimeCapable=" + plan.runtimeCapable)
println()

// One program, for every t. Each moving literal became a push of both endpoints, a read of
// the blend push constant, and `mix` — which leaves one value on the stack, exactly as the
// literal it replaced did, so the surrounding stack arithmetic is untouched.
println("the one program a runtime blend runs:")
println(plan.runtimeSource)
println()

// And the refusal. One word different is a structural difference, and there is no honest
// program between the two, so the blend steps whatever was asked for.
val elsewhere = "uv 3.0 * noise 0.15 + [1.0,0.55,0.2] *"
val stepped = planStackSLCodeBlend(warm, elsewhere, CodeBlendOptions(mode = CodeBlendMode.RUNTIME))
println(stepped.describe())

display(plan)
