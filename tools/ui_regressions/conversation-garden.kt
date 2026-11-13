import afield.ai.conversation.*

// UI fixture only: no model request. The code card records a real property edit below.
val owner = Box.context.get()!!
AiBoxConversation.write(owner, "<<you>>\nExplore a living drawing.\n<<ai>>\nLet the lines remember their earlier positions.\n\nThe colour can respond to speed.\n\nKeep the simulation separate from its presentation.")
val garden = AiConversationGarden.initialize(owner)
val source = garden.nodes.single()
val paragraph = source.passages[1]
val (queued, branch) = AiConversationGarden.enqueue(owner, "Try a shorter memory #motion", GardenAnchor(source.id, paragraph.id, paragraph.revision), "code")
AiConversationGarden.update(owner, branch, "<<ai>>\nA short trail will make the motion legible.\n\nWe can tune the decay while it runs.")
val before = (owner.getPropertyRaw("gardenExample") as? String).orEmpty()
owner.setPropertyRaw("gardenExample", "val trail = 0.92")
AiConversationGarden.edit(owner, branch, owner, "gardenExample", before, "val trail = 0.92")
AiConversationGarden.status(owner, branch, "complete")
