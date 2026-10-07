import afield.chatlog.*
import java.io.File

val harbour = File("public/docs/assets/ai4_chat_archive/chats/harbour")
val tree = ChatArchive.tree(ChatArchive.chats(harbour).single())
val page = ChatLogViews.read(tree, "t-23d59848-f38.b3", "t-23d59848-f38.b8", detail = "full")
display("night", "<pre style='margin:8px'>" + page.text.replace("<", "&lt;") + "</pre>")
