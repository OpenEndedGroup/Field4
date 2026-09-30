// Kotlin: what Field recorded for each line of "hello" when it last ran. Run "hello" first.
val hello = root.children.first { it.name == "hello" }
val code = hello.getPropertyRaw("code") as String
code.lines().forEachIndexed { i, _ ->
    afield.stdlib.LineNotes.note(hello, "code", code, i + 1)?.let { println("line ${i + 1}: ${it.caption}") }
}
