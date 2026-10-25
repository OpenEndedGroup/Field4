// Box K's run left a line note on each top-level declaration, with the value it ended up holding,
// and none on a statement that declares nothing.
val k = thisBox.named("KotlinLineNotes")
val src = k.properties["code"] as String
fun note(line: Int) = afield.ui.output.LineNotes.note(k, "code", src, line)?.caption
check(note(1)?.endsWith("x = 42") == true) { "line 1: ${note(1)}" }
check(note(2)?.endsWith("""words = ["a", "b", "c", "d", … (6)]""") == true) { "line 2: ${note(2)}" }
check(note(3)?.endsWith("""label = "hello"""") == true) { "line 3: ${note(3)}" }
check(note(4) == null) { "println declares nothing, yet line 4 has ${note(4)}" }
check(note(6)?.endsWith("y = 84") == true) { "line 6: ${note(6)}" }
display("main", "<div id=\"kotlin-line-notes-ok\">kotlin line notes ok</div>")
