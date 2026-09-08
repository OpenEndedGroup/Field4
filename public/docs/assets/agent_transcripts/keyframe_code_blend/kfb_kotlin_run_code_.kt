// The same plan, rebuilt here — building one is pure, so it costs an alignment and
// nothing else. Then the blended expression is handed to a SourceBackedBlock whose box is
// kfb_kotlin, so it compiles and runs *there* while this box is the one evaluating.
import afield.stdlib.CodeBlendMode
import afield.stdlib.CodeBlendOptions
import afield.stdlib.SourceBackedBlock
import afield.stdlib.planKotlinCodeBlend

val plan = planKotlinCodeBlend(
    "2.0f * 3.0f + 1.0f",
    "8.0f * 3.0f + 5.0f",
    CodeBlendOptions(mode = CodeBlendMode.RUNTIME)
)
val block = SourceBackedBlock<Any?>("kfb_kotlin", plan.runtimeSource)
println(plan.runtimeSource)
println()

// One block, five values of t. The first invocation compiles the generated text through
// K2; the rest reuse the lambda, and only the registry slot changes.
listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { t ->
    plan.drive(t)
    val got = block() as Float
    val want = (2.0f + 6.0f * t.toFloat()) * 3.0f + (1.0f + 4.0f * t.toFloat())
    println("t=%.2f   block() = %8.4f   independently %8.4f".format(t, got, want))
}

display(plan)
