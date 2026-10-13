import afield.runtime.macros.*
rewrite { x: Expr -> from { boom(x) }; to { -x } }
