import afield.macros.*
fun wrap(v: Int) = v
fun f(v: Int) = v + 1
rewrite { x: Expr ->
    from { f(x) }
    where { !isCallTo(x, "wrap") }
    to { f(wrap(x)) }
}
println("f(41) = " + f(41))
