package afield

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.js.JsName
import kotlin.math.max
import kotlin.math.min

var _uid = 0

fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is Map<*, *> -> toJsonElement()
    is Collection<*> -> toJsonElement()
    is ByteArray -> this.toList().toJsonElement()
    is CharArray -> this.toList().toJsonElement()
    is ShortArray -> this.toList().toJsonElement()
    is IntArray -> this.toList().toJsonElement()
    is LongArray -> this.toList().toJsonElement()
    is FloatArray -> this.toList().toJsonElement()
    is DoubleArray -> this.toList().toJsonElement()
    is BooleanArray -> this.toList().toJsonElement()
    is Array<*> -> toJsonElement()
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Enum<*> -> JsonPrimitive(this.toString())
    else -> {
        throw IllegalStateException("Can't serialize unknown type: $this")
    }
}

fun Map<*, *>.toJsonElement(): JsonElement {
    val map = mutableMapOf<String, JsonElement>()
    this.entries.forEach { e ->
        map.put(e.key as String, e.value.toJsonElement())
    }
    return JsonObject(map)
}

fun Collection<*>.toJsonElement(): JsonElement {
    return JsonArray(this.map { it.toJsonElement() })
}

fun Array<*>.toJsonElement(): JsonElement {
    return JsonArray(this.map { it.toJsonElement() })
}

@Serializable
data class Message(
    @JsName("name")
    val name: String,
    @JsName("uid")
    val uid: String = "message_${_uid++}",
    @JsName("replyTo")
    var replyTo: String? = null,
    @JsName("originalName")
    var originalName: String? = null,
    @JsName("payload")
    var payload: JsonElement? = null
) {

    fun toName(replyTo: String): Message {
        return Message(name = replyTo, uid = uid, replyTo = replyTo, originalName = this.name, payload = payload)
    }
}


@Serializable
enum class OutputKind {
    normal, error
}

@Serializable
data class Output(
    val line: Int,
    val html: String,
    val kind: String,
    val property: String = "code",
    val annotation: String = "",
    val frame: Int
)

@Serializable
data class Execute(
    val uid: String,
    val type: String,
    val line: Int,
    val code: String,
    val singleLine: Boolean,
    val strand: String = "default"
)

@Serializable
data class AiAssist(val uid: String, val prompt: String, val extraPrompt: String, val startLine: Int, val property: String, val kind: String)

@Serializable
data class Chat_input(val value: String, val phase: Int)

@Serializable
data class LocalEval(val uid: String, val prompt: String, val kind: String)

@Serializable
data class Stop_box(val uid: String)

@Serializable
data class IsRunning(val uid: String, val isRunning: Boolean, val watermark: Int, val strand: String = "default")

@Serializable
data class Metadata(
    val icon: String,
    val tail: String,
    val text: String,
    val displayText: String,
    val documentation: String = "",
    val prefix: String = ""
)


@Serializable
data class Completion(
    val boundsStart: Int,
    val boundsEnd: Int,
    val columnsStart: Int = -1,
    val lineStart: Int = -1,
    val useLineColumns: Boolean = false,
    val metadata: List<Metadata>,
)

@Serializable
data class CompletionRequest(
    val uid: String,
    val line: Int,
    val allCode: String,
    val column: Int,
    val position: Int,
)

@Serializable
data class Hello(val filename: String, val username: String)

@Serializable
data class Set_string(val uid: String, val property: String, val value: String)

@Serializable
data class Set_output(val uid: String, val property: String, val channel: String, val value: String)

@Serializable
data class Notify(val text: String, val category: String)

@Serializable
data class Set_topology(val uid: String, val kind: String, val value: List<String>)

@Serializable
data class Action_Desc(val id: String, val description: String)

@Serializable
data class Describe_actions(val uid: String, val value: List<Action_Desc>)

@Serializable
class Directory(val command: String, val value: List<String> = emptyList<String>())

@Serializable
class RequestHashtags(val boxes: List<String>, val getOrSet: Boolean, val value: String = "")


@Serializable
data class Perform_action(val uid_currentSelection: List<String>, val uid_action: String)

@Serializable
data class Set_rect(val uid: String, val property: String, val value: Rect)

@Serializable
data class Set_null(val uid: String, val property: String)

@Serializable
data class Set_number(val uid: String, val property: String, val number: Double)

@Serializable
data class Set_boolean(val uid: String, val property: String, val number: Boolean)


@Serializable
data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
    fun intersects(r: Rect): Boolean {
        return ((r.x < this.x + this.w && r.w + r.x > x && r.y < y + this.h) && r.y + r.h > this.y)
    }

    fun intersects(x : Double) = this.x<=x && this.x+this.w>x

    fun intersects(x0 : Double, x1 : Double) = this.x<=x1 && this.x+this.w>x0

    fun union(r: Rect) = Rect(
        min(x, r.x),
        min(y, r.y),
        max(x + w, r.x + r.w) - min(x, r.x),
        max(y + h, r.y + r.h) - min(y, r.y)
    )

    val left: Double get() = x
    val top: Double get() = y
    val right: Double get() = x + w
    val bottom: Double get() = y + h
    val cx: Double get() = x + w / 2.0
    val cy: Double get() = y + h / 2.0
}
