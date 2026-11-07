package afield.app.server

import afield.ai.memory.ChatHistory
import afield.ai.memory.Dreaming
import afield.ai.memory.UserMemory
import afield.ai.models.createIdeBridge
import afield.app.protocol.*
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.installHandlers
import java.io.File
import kotlin.concurrent.thread

/**
 * The server's side of AI4's memory dreaming ([Dreaming]): the `ai4-memory-dream` message, which the palette's
 * **Consolidate AI4 Memory** sends and the backdoor can (`raw-send ai4-memory-dream '{}' --reply ai4-memory-dream`),
 * and the idle watcher, started with the handlers.
 */
@RunsAt(installHandlers)
fun ai4MemoryHandlers() {
    handlers["ai4-memory-dream"] = mutableSetOf({ message, reply ->
        val replyName = message.replyTo ?: "ai4-memory-dream"
        val session = thisSession.get()
        val info = thisSessionInfo.get()
        val memory = UserMemory.forPerson()
        if (memory == null || Dreaming.mode() == "off") {
            val why = if (memory == null) "AI4 memory is off (AFIELD_AI4_MEMORY)." else "Dreaming is off (AFIELD_AI4_DREAM)."
            reply(Message(replyName).payload(Ai4DreamReply(false, why)))
        } else {
            val history = info?.let { ChatHistory.forDocument(File(it.filename)) } ?: ChatHistory.forWorkspace(File(home))
            _send(session, Message("notify").payload(Notify("Consolidating AI4 memory against the chats in ${history.workspace.name}…", "AI4")))
            // A pass is a model turn of up to a few minutes: never on the websocket's coroutine.
            thread(name = "ai4-dream-manual", isDaemon = true) {
                val r = Dreaming.run(memory, history, createIdeBridge(), "manual")
                _send(session, Message("notify").payload(Notify(r.message, "AI4")))
                reply(Message(replyName).payload(Ai4DreamReply(r.ok, r.message, r.turnsRead, r.created, r.updated, r.deleted, r.rounds, r.log)))
            }
        }
        Handled.yes
    })
    Dreaming.startIdleWatcher({ File(home) }, { runCatching { currentFieldPreferences().quietMode }.getOrDefault(false) }, { createIdeBridge() })
}
