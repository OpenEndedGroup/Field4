fun p(v: Int) = v
fun q(v: Int) = v
fun A(v: Int) = v * 10
fun B(v: Int) = v * 100
rule("p2a") { x: Expr -> from { p(x) }; to { A(x) } }
rule("q2b") { x: Expr -> from { q(x) }; to { B(x) } }
strategy { "p2a" }
val r = p(1) + q(2)
println("subset: r = $r  -> only p2a fires: A(1) + q(2); q2b left alone")
check(r == 12) { "subset expected 12, got $r" }
