fun p(v: Int) = v
fun A(v: Int) = v * 10
rule("p2a") { x: Expr -> from { p(x) }; to { A(x) } }
strategy { once("p2a") }
val r = p(1) + p(2)
println("once: r = $r  -> only the first p() rewritten: A(1) + p(2)")
check(r == 12) { "once expected 12, got $r" }
