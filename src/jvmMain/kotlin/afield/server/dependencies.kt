package afield.server

import afield.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * `dependency-graph`: the dependency layer's view of [PropertyDependencies] for the asking page's
 * document — every producer → consumer flow and every unanswered read — or, with `action = "clear"`,
 * forget the document's record first.
 */
@RunsAt(installHandlers)
fun dependencyGraphHandlers() {
    handlers["dependency-graph"] = mutableSetOf({ message, reply ->
        val replyName = message.replyTo ?: "dependency-graph"
        val info: SessionInformation? = thisSessionInfo.get()
        val graph = if (info == null) {
            DependencyGraph(0, 0, PropertyDependencies.enabled, 0, emptyList(), emptyList(), ok = false, error = "This page has no document session yet")
        } else {
            try {
                val request = message.payload?.let { Json.decodeFromJsonElement<DependencyGraphRequest>(it) } ?: DependencyGraphRequest()
                if (request.action == "clear") PropertyDependencies.storeFor(info.filename).clear()
                PropertyDependencies.graph(info)
            } catch (e: Exception) {
                DependencyGraph(0, 0, PropertyDependencies.enabled, 0, emptyList(), emptyList(), ok = false, error = e.message ?: e.toString())
            }
        }
        reply(Message(replyName).payload(graph))
        Handled.yes
    })
}
