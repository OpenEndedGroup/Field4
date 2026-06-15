package afield.debug

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ProbeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

@Serializable
sealed interface ProbeRequest {
    val requestId: String
}

@Serializable
@SerialName("probe")
data class ProbeVariableRequest(
    override val requestId: String,
    val className: String? = null,
    val sourceName: String? = null,
    val sourceNamePrefix: String? = null,
    val line: Int,
    val variableName: String,
    val threadName: String? = null,
    val maxHits: Int = 1,
    val timeoutMs: Long = 1_500L
) : ProbeRequest

@Serializable
sealed interface ProbeResponse {
    val requestId: String
}

@Serializable
@SerialName("ack")
data class AckResponse(
    override val requestId: String
) : ProbeResponse

@Serializable
@SerialName("error")
data class ErrorResponse(
    override val requestId: String,
    val error: String,
    val detail: String? = null
) : ProbeResponse

@Serializable
@SerialName("complete")
data class CompleteResponse(
    override val requestId: String,
    val hits: Int,
    val error: String? = null,
    val detail: String? = null,
    val scope: ScopeSnapshotPayload? = null
) : ProbeResponse

@Serializable
data class ValuePayload(
    val kind: String,
    val type: String? = null,
    val text: String
)

@Serializable
data class ScopeSymbolPayload(
    val name: String,
    val resolution: String,
    val type: String? = null,
    val preview: String = "",
    val frameIndex: Int = -1,
    val declaringType: String? = null,
    val method: String? = null,
    val line: Int = -1
)

@Serializable
data class ScopeSnapshotPayload(
    val symbols: List<ScopeSymbolPayload> = emptyList(),
    val omittedCount: Int = 0
)

@Serializable
@SerialName("sample")
data class SampleResponse(
    override val requestId: String,
    val hitIndex: Int,
    val threadName: String,
    val declaringType: String,
    val method: String,
    val line: Int,
    val resolution: String,
    val value: ValuePayload
) : ProbeResponse
