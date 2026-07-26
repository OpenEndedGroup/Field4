import afield.macros.*
rewrite { x: Ident -> from { rev(x) }; to { unquote { "\"" + x.reversed() + "\"" } } }
println("rev(hello) = " + rev(hello))
