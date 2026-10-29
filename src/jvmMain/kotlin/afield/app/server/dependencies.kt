package afield.app.server

import afield.ai.agent.*
import afield.ai.conversation.*
import afield.ai.tools.*
import afield.animation.gesture.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.app.diagnostics.*
import afield.app.notifications.*
import afield.app.protocol.*
import afield.app.server.*
import afield.app.settings.*
import afield.audio.synth.*
import afield.data.table.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.history.*
import afield.document.persistence.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.graphics.color.*
import afield.graphics.paint.*
import afield.graphics.vulkan.*
import afield.math.functions.*
import afield.media.capture.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.completion.*
import afield.runtime.execution.*
import afield.runtime.kotlin.*
import afield.runtime.lifecycle.*
import afield.tools.automation.*
import afield.tools.docs.*
import afield.ui.controls.*
import afield.ui.layout.*
import afield.ui.notation.*
import afield.ui.output.*
import afield.ui.output.templates.*
import afield.ui.slides.*
import afield.ui.visualization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * One websocket handler, `dependency-graph`, which is how the dependency layer asks what it draws.
 *
 * The reply is [PropertyDependencies]' record for the document of the page that asked: every
 * producer to consumer flow it has seen, and every read that found nothing. Sending it with
 * `action = "clear"` forgets that document's record first, which is the palette's Clear Dependency
 * Record.
 *
 * Nothing calls [dependencyGraphHandlers]; the startup scan finds it by its `@RunsAt` annotation and
 * runs it while handlers are being installed. A page with no document session yet, or a record that
 * fails to build, is answered with an empty graph carrying the reason rather than with an error, so
 * the layer can say why it is empty.
 */

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
