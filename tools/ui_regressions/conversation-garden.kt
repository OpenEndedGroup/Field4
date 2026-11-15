import afield.ai.conversation.*
import afield.ai.agent.*

// UI fixture only: no model request. The code card records a real property edit below.
val owner = Box.context.get()!!
AiBoxConversation.write(owner, "<<you>>\nExplore a living drawing.\n<<ai>>\nLet the lines remember their earlier positions.\n\nThe colour can respond to speed.\n\nKeep the simulation separate from its presentation.")
val garden = AiConversationGarden.initialize(owner)
val source = garden.nodes.single()
val paragraph = source.passages[1]
val (queued, branch) = AiConversationGarden.enqueue(owner, "Try a shorter memory #motion", GardenAnchor(source.id, paragraph.id, paragraph.revision), "code")
val graph = FieldGraphTools.forTurn(owner.inside, origin = "garden-ui-fixture")
graph.onTextEdit = { box, property, before, after -> AiConversationGarden.edit(owner, branch, box, property, before, after) }
val began = System.nanoTime()
val result = graph.setProperty(SetBoxPropertyRequest(owner.id, "gardenExample", "val trail = 0.92"))
check(result.success)
val millis = (System.nanoTime() - began) / 1_000_000
AiConversationGarden.update(owner, branch, "<<progress>>\nStarting AI conversation\n<<progress>>\nSetBoxPropertyRequest finished ($millis ms)\n<<progress>>\nWaiting for model\n<<progress>>\nCompleted\n<<ai>>\nA short trail will make the motion legible.\n\nWe can tune the decay while it runs.")
AiConversationGarden.status(owner, branch, "complete")
