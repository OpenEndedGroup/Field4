import afield.ai.core.*
import afield.*

val treeOwner = Box.context.get()!!
fun addStateTurn(text: String) {
    val before = AiBoxConversation.read(treeOwner)
    val after = "$before\n<<you>>\n$text\n<<ai>>\nSaved this alternative."
    AiBoxConversation.write(treeOwner, after)
    AiSessionTree.recordTurn(treeOwner, before, after, AiSessionTree.TurnMeta(kind="general", property="code"))
}
addStateTurn("Establish the initial design")
addStateTurn("Explore a wider layout")
AiSessionTree.rewind(treeOwner, 1, "Explore a wider layout")
addStateTurn("Keep the compact layout")
treeOwner.stateTree("design", "Design states", {
    StateTreeSnapshot(listOf(StateTreeNode("design-a", title="Initial design"),
        StateTreeNode("design-b", listOf("design-a"), "Move the east edge")), "design-b", "Saved design states")
}, { id -> "Saved design $id\n" + if(id == "design-a") "Width: 6000 mm" else "Width: 7200 mm" })
display("ai-chat", AiBoxConversation.read(treeOwner))
