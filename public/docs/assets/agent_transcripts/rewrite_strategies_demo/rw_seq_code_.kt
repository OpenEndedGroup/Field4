fun p(v: Int) = v
fun q(v: Int) = v
fun r0(v: Int) = v * 1000
rule("p2q") { x: Expr -> from { p(x) }; to { q(x) } }
rule("q2r") { x: Expr -> from { q(x) }; to { r0(x) } }
strategy { "p2q" then "q2r" }
val out = p(5)
println("then: out = $out  -> p -> q -> r0(5) = 5000")
check(out == 5000) { "then expected 5000, got $out" }
