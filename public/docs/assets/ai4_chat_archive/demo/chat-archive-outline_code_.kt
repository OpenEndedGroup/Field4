import afield.chatlog.*
import java.io.File

val shed = File("public/docs/assets/ai4_chat_archive/chats/garden-shed")
val tree = ChatArchive.tree(ChatArchive.chats(shed).single())
display("outline", "<pre style='margin:8px'>" + ChatLogViews.outline(tree).replace("<", "&lt;") + "</pre>")
