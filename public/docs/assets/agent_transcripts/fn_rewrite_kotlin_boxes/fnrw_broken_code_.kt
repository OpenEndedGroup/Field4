/*
Intentionally wrong: source hydration would compile in this running box's own engine.
The runtime refuses before replaying the body. Run this box to see that refusal;
copy the two-box example above, whose parent host is idle.
*/
val spec = [
    "#fnrewrite",
    "sin/*/saw => op=triangle",
    "sin/**/[tag=number][value>0.15] => *= @{1.0 + depth * 0.08}",
    "**/[role=amplitude] => *= @{if (parentOp == \"sin\") 0.75 else 1.0}",
].joinToString("\n")

println("one box: body entered")

with(Functions) {
    val base = (sin(sin(saw(0.18))) * 0.45) over (0.0..3.0)
    base.rewriteKotlin(spec)
}
