fun f(v: Int) = v * 2
fun g(v: Int) = v + 100
rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }
strategy { topdown("fg") }
val r = f(f(1))
println("topdown: r = $r  -> outer f rewritten: g(f(1)) = g(2) = 102")
check(r == 102) { "topdown expected 102, got $r" }
