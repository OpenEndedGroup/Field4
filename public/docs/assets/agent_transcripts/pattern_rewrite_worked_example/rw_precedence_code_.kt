import afield.macros.*
rewrite { x: Expr -> from { twice(x) }; to { x * 2 } }
println("twice(3 + 4) = " + twice(3 + 4))
