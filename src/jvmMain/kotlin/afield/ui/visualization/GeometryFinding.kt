package afield.ui.visualization

import afield.geometry.path.div
import afield.geometry.path.minus
import afield.geometry.path.plus
import afield.geometry.path.times
import afield.lang.remotejs.JsFun

import afield.geometry.path.length
import kotlinx.serialization.json.*

/** Review metadata shared by checkers and agents. A finding never changes build acceptance. */
data class GeometryFinding(
    val key: String,
    val kind: String,
    val stage: String,
    val runId: String,
    val producer: String,
    val consequence: String,
    val prompt: String,
) {
    fun json(): JsonObject = buildJsonObject {
        put("key", key); put("kind", kind); put("stage", stage); put("runId", runId)
        put("producer", producer); put("consequence", consequence); put("prompt", prompt)
    }.also(::validateGeometryFinding)
}

internal fun validateGeometryFinding(value: JsonObject) {
    require(value.keys == setOf("key", "kind", "stage", "runId", "producer", "consequence", "prompt")) {
        "A finding requires key, kind, stage, runId, producer, consequence and prompt"
    }
    for (field in value.keys) {
        val text = (value[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val limit = if (field in setOf("consequence", "prompt")) 4000 else 256
        require(text != null && text.isNotBlank() && text.length <= limit) { "Finding $field must contain 1..$limit characters" }
    }
    require(value.getValue("kind").jsonPrimitive.content in setOf("error", "warning", "question")) {
        "Finding kind must be error, warning or question"
    }
}

internal fun sameFindingOccurrence(a: JsonObject, b: JsonObject): Boolean =
    listOf("key", "stage", "runId", "producer").all { a[it] == b[it] }
