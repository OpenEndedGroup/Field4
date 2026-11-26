import afield.runtime.macros.*

rewrite { x: Ident ->
    from { rev(x) }
    to { unquote { "\"" + x.reversed() + "\"" } }
}
val answer = rev(hello)
println("rev(hello) = " + answer)
markdown("main", "`rev(hello)` = **$answer**, a string built at compile time from the identifier's text.")
