import afield.ai.conversation.chatlog.*
import java.io.File

val assets = File("public/docs/assets/ai4_chat_archive")
registerChatlogTools(File(assets, "chats"), lensDir = File(assets, "lenses"))
showChatLens("harbour", "storyline", height = 600)
