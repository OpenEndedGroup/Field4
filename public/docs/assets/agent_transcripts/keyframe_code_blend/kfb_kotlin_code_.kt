// A Kotlin keyframe is a `sourceBlock { }`: a callable that remembers its own source. Two
// of them blend the same way, and for the same reason — the interesting pair is the one
// that differs only in numbers. The transport differs: a shader reads a push constant,
// and a generated Kotlin expression reads a registry slot addressed by an integer written
// into its text, because generated text cannot close over a value.
import afield.stdlib.CodeBlendMode
import afield.stdlib.CodeBlendOptions
import afield.stdlib.KeyframeScope
import afield.stdlib.sourceBlock

val quiet = sourceBlock { 2.0f * 3.0f + 1.0f }
val loud = sourceBlock { 8.0f * 3.0f + 5.0f }
println("left  source: " + quiet.source)
println("right source: " + loud.source)
println()

var amount: Any? = quiet
val scope = KeyframeScope()
scope.target("amount", get = { amount }, set = { v -> amount = v })
scope.codeBlend = CodeBlendOptions(mode = CodeBlendMode.RUNTIME)

val a = scope.Key(mutableMapOf<String, Any?>("amount" to quiet))
val b = scope.Key(mutableMapOf<String, Any?>("amount" to loud))

val plan = scope.codeBlendPlans(a, b).getValue("amount")
println(plan.describe())
println("the one expression a runtime blend evaluates:")
println(plan.runtimeSource)
println()
println("the middle literal is unchanged between the two, so it is left alone.")

// The invocation is in kfb_kotlin_run, not here. A SourceBackedBlock hydrates by
// compiling and evaluating its source in its own box, and asking for that from inside
// this box's own running code re-enters: the nested evaluation replays this snippet,
// which hydrates again. Invoke one from a frame loop, a control callback, or another box.
display(plan)
