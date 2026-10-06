import afield.chatlog.*
import java.io.File

val assets = File("public/docs/assets/ai4_chat_archive")
registerChatlogTools(File(assets, "chats"), lensDir = File(assets, "lenses"))
showChatLens("garden-shed", "storyline", height = 600)
