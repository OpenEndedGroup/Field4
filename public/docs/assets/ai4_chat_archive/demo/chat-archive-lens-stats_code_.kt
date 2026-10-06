import afield.chatlog.*
import java.io.File

val assets = File("public/docs/assets/ai4_chat_archive")
val tree = ChatArchive.tree(ChatArchive.chats(File(assets, "chats/garden-shed")).single())
val lens = File(assets, "lenses/garden-shed/storyline.md").readText()
val problems = ChatLogViews.lensProblems(tree, lens).ifEmpty { listOf("none") }
val stats = ChatLogViews.lensStats(tree, lens).entries.joinToString("\n") { (k, v) -> "$k: $v" }
display("lens", "<pre style='margin:8px'>problems: ${problems.joinToString("; ")}\n$stats</pre>")
