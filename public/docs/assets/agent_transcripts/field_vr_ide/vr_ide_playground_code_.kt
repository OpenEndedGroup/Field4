// Select this box, open its editor, change the colour or message, then Run box (Alt+Up).
val colour = "#237bc2"
display("hello", """
    <div style="width:320px;padding:24px;background:$colour;color:white;font:24px system-ui">
    Hello from inside Field VR!
    </div>
""".trimIndent())
displayOpen("hello")
println("Code executed in the same Field document")
