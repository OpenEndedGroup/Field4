import afield.runtime.macros.*

rewrite { x: Ident ->
    from { rev(x) }
    to { unquote { "\"" + x.reversed() + "\"" } }
}
val answer = rev(hello)
println("rev(hello) = " + answer)
display(
    "main",
    "<div style='font:16px monospace;padding:8px'>rev(hello) = <b>" +
        answer +
        "</b>, a string built at compile time from the identifier's text</div>",
)
