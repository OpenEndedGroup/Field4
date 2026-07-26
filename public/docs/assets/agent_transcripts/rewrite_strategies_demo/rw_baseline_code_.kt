fun p(v: Int) = v
fun A(v: Int) = v * 10
rule("p2a") { x: Expr -> from { p(x) }; to { A(x) } }
val r = p(1) + p(2)
println("baseline (global fixpoint): r = $r  -> both p() rewritten to A(1)+A(2)")
check(r == 30) { "baseline expected 30, got $r" }
