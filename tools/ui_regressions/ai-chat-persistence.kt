// A saved conversation from before panel presentation was persisted: no model request or box autorun.
val owner = Box.context.get()!!
AiBoxConversation.write(owner, "<<you>>\nKeep this conversation with the document.\n<<ai>>\nSaved reply, ready to continue.")
owner.resyncAll()
