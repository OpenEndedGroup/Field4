fun pick(v: Int) = v
fun X(v: Int) = v + 1
fun Y(v: Int) = v + 2
rule("toX") { x: Expr -> from { pick(x) }; to { X(x) } }
rule("toY") { x: Expr -> from { pick(x) }; to { Y(x) } }
strategy { choice("toY", "toX") }
val r = pick(10)
println("choice: r = $r  -> prefers toY though toX is declared first: Y(10) = 12")
check(r == 12) { "choice expected 12, got $r" }
