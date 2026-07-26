import afield.macros.*
fun sink(vararg xs: Int) = xs.sum()
rewrite { first: Expr, rest: Vararg ->
    from { log(first, rest) }
    to { sink(first, rest) }
}
println("log(1, 2, 3) = " + log(1, 2, 3))
