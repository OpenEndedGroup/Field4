// THE WRONG WAY, kept runnable so you can watch it refuse. `rewriteKotlin` takes its box
// from `Box.context`, i.e. THIS box, so the first `@{...}` asks this engine to evaluate
// while it is already mid-evaluation and the engine re-runs this body.
//
// Unguarded that was 172 nested entries and 285 snippet compiles, ending in
//   Unable to instantiate class ...__code__81: java.lang.StackOverflowError
// with the same 115-character snippet taking 1.58s, 2.14s, 2.69s as the chain grew.
//
// The FnRewriteSelfBoxReentry guard now refuses the second hydration, which breaks the
// recursion: this body runs exactly TWICE (watch for two "one box:" lines), the second run
// dies at the first @{...}, and the rewrite then completes with the right answer. Look in
// the log, channel `error,fn-rewrite`, for the refusal and the three ways out.
//
// Twice is still once too many -- anything this body does before the rewrite happens twice,
// which is why the box above is the form to copy. dev_docs/FN_REWRITE_SELF_BOX_REENTRY.md.
import afield.stdlib.Functions
import afield.stdlib.rewriteKotlin

val spec = listOf(
    "#fnrewrite",
    "sin/*/saw => op=triangle",
    "sin/**/[tag=number][value>0.15] => *= @{1.0 + depth * 0.08}",
    "**/[role=amplitude] => *= @{if (parentOp == \"sin\") 0.75 else 1.0}"
).joinToString("\n")

println("one box: body entered")

with(Functions) {
    val base = (sin(sin(saw(0.18))) * 0.45) over (0.0..3.0)
    val changed = base.rewriteKotlin(spec)
    println("one box: f(1.0) = " + changed.eval(1.0) + " (correct, but the body ran twice)")
}
