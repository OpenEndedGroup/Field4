import afield.ai.conversation.chatlog.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

// A fixture chat archive in a temp folder: one document with a two-turn chat (a round of tool calls,
// a tool picture), a two-block lens over it, and a one-block summary lens over that lens. Nothing is
// written into the test document itself.
val root = Files.createTempDirectory("chat-lens-fixture").toFile()
val doc = File(root, "fixture-house").apply { mkdirs() }
val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
val sha = MessageDigest.getInstance("SHA-256").digest(png.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
File(doc, "ai4_blobs").mkdirs()
File(doc, "ai4_blobs/$sha.png").writeBytes(java.util.Base64.getDecoder().decode(png))

fun q(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
val t1 = "<<you>>\nPlan a small house.\n<<ai>>\nStarting with the rooms.\n" +
    "<<progress>>\nWaiting for model\n<<progress>>\n_field_sketch_edit_e0b4aeb5c8da0381fe14367f finished (1200 ms)\nrevision 2\n" +
    "<<progress>>\nViewBoxOutputRequest finished (900 ms)\nOne image\n" +
    "<<ai>>\n_Output of box 'Plan' (main) — looking for: the rooms_\n\n![tool image](ai4img:$sha.png)\n" +
    "<<ai>>\nThe rooms are in.\n"
val t2 = "<<you>>\nAdd a roof.\n<<progress>>\nStartStudyBuildRequest finished (40 ms)\nstarted\n<<progress>>\nWaitStudyBuildRequest finished (3000 ms)\ndone\n<<ai>>\nThe roof is on.\n"
File(doc, "b1_ai4_session_.jsonl").writeText(listOf(
    """{"t":"header","v":1}""",
    """{"t":"turn","id":"t-a","parent":null,"ts":1000,"markdown":${q(t1)},"user":"Plan a small house."}""",
    """{"t":"turn","id":"t-b","parent":"t-a","ts":2000,"markdown":${q(t2)},"user":"Add a roof."}""",
    """{"t":"head","id":"t-b","renderedSha":"x"}""",
).joinToString("\n"))
File(doc, "metadata.edn").writeText("""{:version 2 :boxes [{"id" "b1" "properties" {"name" "Fixture chat" "ai4_session" #afield/file "b1_ai4_session_.jsonl"}}]}""")

val lenses = File(root, "lenses")
registerChatlogTools(root, lensDir = lenses)
// The first block cites from inside a round (t-a.b3, a tool call) to the reply after the picture, so its
// band must start at the round's line; the second turn is left uncited.
File(lenses, "fixture-house").mkdirs()
File(lenses, "fixture-house/fixture.md").writeText(
    "<<you>>\n<<source:t-a.b0>>\nPlan a small house.\n" +
    "<<ai>>\n<<source:t-a.b3..t-a.b6>>\nDrew the rooms and looked at them.\n![the plan](ai4img:$sha.png)\n")
File(lenses, "fixture-house/fixture.json").writeText("""{"chat":"fixture-house","name":"fixture","brief":"a fixture lens","model":"fixture-model","stats":{"compression":"2.0"}}""")
// A lens over the lens: one summary block condensing both of its blocks. The viewer shows the chain.
File(lenses, "fixture-house#fixture").mkdirs()
File(lenses, "fixture-house#fixture/overview.md").writeText(
    "<<summary>>\n<<source:lens:fixture.b0..lens:fixture.b1>>\nAsked for a plan; the rooms were drawn and checked.\n")
File(lenses, "fixture-house#fixture/overview.json").writeText("""{"chat":"fixture-house#fixture","name":"overview","brief":"one line","model":"fixture-model"}""")
showChatLens("fixture-house#fixture", "overview", height = 640)
println("chat lens fixture shown")
