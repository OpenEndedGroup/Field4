import afield.macros.*
rewrite { x: Expr -> from { boom(x) }; to { -x } }
