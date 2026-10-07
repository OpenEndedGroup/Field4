import afield.chatlog.*
import java.io.File

val harbour = File("public/docs/assets/ai4_chat_archive/chats/harbour")
val tree = ChatArchive.tree(ChatArchive.chats(harbour).single())
display("outline", "<pre style='margin:8px'>" + ChatLogViews.outline(tree).replace("<", "&lt;") + "</pre>")
