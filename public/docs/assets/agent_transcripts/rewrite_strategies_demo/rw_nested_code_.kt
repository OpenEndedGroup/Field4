fun f(v: Int) = v * 2
fun g(v: Int) = v + 100
rule("fg") { x: Expr -> from { f(f(x)) }; to { g(x) } }
strategy { repeat(topdown("fg"), 2) }
val r = f(f(f(f(1))))
println("nested pattern: r = $r  -> each step eats TWO f's: g(g(1)) = g(101) = 201")
check(r == 201) { "nested pattern expected 201, got $r" }
r
