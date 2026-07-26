import afield.macros.*
rewrite { x: Expr -> from { neg(x) }; to { -x } }
println("neg(10) = " + neg(10))
