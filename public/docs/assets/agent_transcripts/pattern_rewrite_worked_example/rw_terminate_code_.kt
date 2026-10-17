import afield.runtime.macros.*

fun wrap(v: Int) = v
fun f(v: Int) = v + 1
rewrite { x: Expr ->
    from { f(x) }
    // wrap marks an expanded call, so the next rewrite stops here.
    where { !isCallTo(x, "wrap") }
    to { f(wrap(x)) }
}
println("f(41) = " + f(41))
