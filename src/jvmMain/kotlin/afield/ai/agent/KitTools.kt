package afield.ai.agent

import afield.ai.core.AgentToolMeta
import afield.ai.core.SummarisesForToolLog
import afield.ai.core.ToolEffect
import afield.ai.core.ToolExposure
import afield.ai.core.ToolRegistry
import afield.stdlib.KitItemState
import afield.stdlib.Kits
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.io.File

@JsonClassDescription("Say which kit this document runs and what differs from it. A kit is the box code, box-held sources and asset files every document of one kind receives. Returns the kit's name, the revision the document records, the revision the kit is at now, and each role property and asset that is not current: changed in the kit (a migration installs it), edited in this document (a migration refuses it), a frozen copy that differs from the kit (a migration replaces it), or no longer in the kit. Use it before relying on a provider's behaviour, or when asked whether the document is up to date. It changes nothing, and does not migrate: a person migrates with tools/field_kit.py. A document with no kit returns success=true with kit empty. e.g. kit = `` to compare with the kit the document records.")
@AgentToolMeta("kit", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT, ToolEffect.FILESYSTEM_READ])
data class InspectKitRequest(
    @JsonPropertyDescription("A kit directory to compare with, instead of the one the document records; empty for the recorded one.")
    val kit: String = "",
)

data class KitToolResult(val success: Boolean, val message: String, val details: Map<String, Any?> = emptyMap()) : SummarisesForToolLog {
    override fun toolLogSummary() = "success=$success $message"
}

/** [InspectKitRequest] over [FieldGraphTools]' document. */
internal fun FieldGraphTools.inspectKit(q: InspectKitRequest, onOwner: (() -> KitToolResult) -> KitToolResult): KitToolResult = try {
    onOwner {
        val inspection = Kits.inspect(info.boxes.values + info.root, File(info.filename), q.kit.takeIf { it.isNotBlank() }?.let(::File))
        val differing = inspection.items.filter { it.state != KitItemState.CURRENT }.map { item ->
            mapOf("item" to item.display, "role" to item.role, "property" to item.property, "asset" to item.asset, "box" to item.box,
                "state" to item.state.name.lowercase(), "frozen" to item.frozen,
                "document" to item.document?.take(12), "recorded" to item.recorded?.take(12), "kit" to item.kit?.take(12))
        }
        val roles = inspection.items.filter { it.role != null }.groupBy { it.role!! }
            .mapValues { (_, items) -> mapOf("box" to items.first().box, "name" to items.first().boxName) }
        KitToolResult(true, inspection.summary(), mapOf("kit" to inspection.kit.orEmpty(), "recordedRevision" to inspection.recordedRevision,
            "kitRevision" to inspection.kitRevision, "kitDirectory" to inspection.kitDirectory, "kitFound" to inspection.kitFound,
            "roleProperty" to inspection.roleProperty, "launchOrder" to inspection.launchOrder, "roles" to roles,
            "itemsChecked" to inspection.items.size, "differences" to differing,
            "migrationRefusals" to inspection.migrationRefusals, "problems" to inspection.problems))
    }
} catch (e: Exception) { KitToolResult(false, e.message ?: "Could not inspect the kit") }

internal fun registerKitTools(tools: ToolRegistry) {
    tools.register(InspectKitRequest::class.java, { it.fieldGraph != null }) { ctx, q -> ctx.fieldGraph!!.kit(q) }
}
