import afield.chatlog.*
import java.io.File

val shed = File("public/docs/assets/ai4_chat_archive/chats/garden-shed")
val tree = ChatArchive.tree(ChatArchive.chats(shed).single())
val page = ChatLogViews.read(tree, "t-3.b0", "t-3.b5", detail = "full")
display("turn 3", "<pre style='margin:8px'>" + page.text.replace("<", "&lt;") + "</pre>")
