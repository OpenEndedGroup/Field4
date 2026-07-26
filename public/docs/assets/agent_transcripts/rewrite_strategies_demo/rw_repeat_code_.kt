fun f(v: Int) = v * 2
fun g(v: Int) = v + 100
rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }
strategy { repeat(topdown("fg"), 2) }
val r = f(f(f(1)))
println("repeat(topdown, 2): r = $r  -> two outer steps, inner f(1) left: g(g(f(1))) = 202")
check(r == 202) { "repeat bounded expected 202, got $r" }
