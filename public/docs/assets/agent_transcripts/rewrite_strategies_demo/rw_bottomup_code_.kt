fun f(v: Int) = v * 2
fun g(v: Int) = v + 100
rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }
strategy { bottomup("fg") }
val r = f(f(1))
println("bottomup: r = $r  -> inner f rewritten: f(g(1)) = f(101) = 202")
check(r == 202) { "bottomup expected 202, got $r" }
