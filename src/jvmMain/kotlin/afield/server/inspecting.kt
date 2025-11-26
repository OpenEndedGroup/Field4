package afield.server

import afield.*
import io.ktor.websocket.*
import kotlinx.serialization.json.*
import org.json.JSONObject
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

// Type definition model mirroring the frontend expectations
data class TypeDef(
    val path: String,
    val outputPath: String? = null,
    val type: String? = null,               // "string" | "number" | "boolean" | "choice"
    val options: List<String>? = null,
    val min: Number? = null,
    val max: Number? = null,
    val step: Number? = null,
    val readOnly: Boolean? = null,
    val priority: Int? = null,
    val units: String? = null,
    val unitsHtml: String? = null,
    val getter: ((Any?, String) -> Any?)? = null,
    val setter: ((Any?, String, Any?) -> Unit)? = null,
    val action: ((Any?, String) -> Unit)? = null,
    val actionTitle: String? = null
)

// Utility to split paths ("a/b/*/c")
private fun splitPath(path: String) = path.split("/").filter { it.isNotBlank() }

// Matches a concrete path against a pattern (with '*' wildcards). Returns captured wildcards or null.
private fun matchPath(concrete: List<String>, pattern: List<String>): List<String>? {
    if (concrete.size != pattern.size) return null
    val caps = mutableListOf<String>()
    for (i in concrete.indices) {
        val p = pattern[i]
        if (p == "*") caps += concrete[i] else if (p != concrete[i]) return null
    }
    return caps
}

private fun buildOutputPathSegments(outputPath: String?, actualSegs: List<String>, wildcards: List<String>): List<String> {
    val capsIter = wildcards.iterator()
    val segs = if (outputPath != null) splitPath(outputPath) else actualSegs
    val out = mutableListOf<String>()
    segs.forEach { seg ->
        if (seg == "*") out += if (capsIter.hasNext()) capsIter.next() else "*"
        else out += seg
    }
    return out
}

// Reflection helpers ----------------------------------------------------------
private fun getProp(obj: Any?, key: String): Any? {
    if (obj == null) return null
    return when (obj) {
        is Map<*, *> -> obj[key]
        is List<*> -> key.toIntOrNull()?.let { idx -> obj.getOrNull(idx) }
        else -> {
            val prop = obj::class.memberProperties.firstOrNull { it.name == key } ?: return null
            prop.isAccessible = true
            prop.getter.call(obj)
        }
    }
}

private fun setProp(obj: Any?, key: String, value: Any?) {
    if (obj == null) return
    when (obj) {
        is MutableMap<*, *> -> (obj as MutableMap<String, Any?>)[key] = value
        is MutableList<*> -> key.toIntOrNull()?.let { idx ->
            val list = obj as MutableList<Any?>
            while (list.size <= idx) list.add(null)
            list[idx] = value
        }

        else -> {
            val prop = obj::class.memberProperties.firstOrNull { it.name == key } ?: return
            if (prop is KMutableProperty1<*, *>) {
                prop.isAccessible = true
                @Suppress("UNCHECKED_CAST") (prop as KMutableProperty1<Any?, Any?>).set(obj, value)
            }
        }
    }
}

// Extractor -------------------------------------------------------------------
data class ExtractResult(
    val data: Any,                // JSON-serializable (Map/List/scalars)
    val typeDefs: List<TypeDef>, val setters: MutableMap<String, MutableList<(Any?) -> Unit>>
)

fun extractMappedData(roots: List<Any?>, specs: List<TypeDef>): ExtractResult {
    val dataList = mutableListOf<Any>()
    val setters = mutableMapOf<String, MutableList<(Any?) -> Unit>>()

    fun recordSetter(path: String, fn: (Any?) -> Unit) {
        setters.getOrPut(path) { mutableListOf() }.add(fn)
    }

    fun buildForRoot(root: Any?): Any {
        fun ensureContainer(target: MutableMap<String, Any?>, segs: List<String>, idx: Int): Any? {
            val key = segs[idx]
            if (idx == segs.lastIndex) return target
            val nextIsNum = segs.getOrNull(idx + 1)?.toIntOrNull() != null
            val next = target.getOrPut(key) { if (nextIsNum) mutableListOf<Any?>() else mutableMapOf<String, Any?>() }
            return next
        }

        fun setIntermediate(target: MutableMap<String, Any?>, segs: List<String>, value: Any?) {
            var cur: Any? = target
            for (i in segs.indices) {
                val seg = segs[i]
                val isLast = i == segs.lastIndex
                if (cur is MutableMap<*, *>) {
                    val map = cur as MutableMap<String, Any?>
                    if (isLast) map[seg] = value
                    else cur = map.getOrPut(seg) {
                        val nextIsNum = segs.getOrNull(i + 1)?.toIntOrNull() != null
                        if (nextIsNum) mutableListOf<Any?>() else mutableMapOf<String, Any?>()
                    }
                } else if (cur is MutableList<*>) {
                    val list = cur as MutableList<Any?>
                    val idx = seg.toIntOrNull() ?: return
                    while (list.size <= idx) list.add(null)
                    if (isLast) list[idx] = value
                    else {
                        val nextIsNum = segs.getOrNull(i + 1)?.toIntOrNull() != null
                        var next = list[idx]
                        if (next == null) {
                            next = if (nextIsNum) mutableListOf<Any?>() else mutableMapOf<String, Any?>()
                            list[idx] = next
                        }
                        cur = next
                    }
                } else return
            }
        }

        val out = mutableMapOf<String, Any?>()

        fun walk(node: Any?, path: List<String>) {
            // normal matches
            specs.forEach { spec ->
                val pattern = splitPath(spec.path)
                val caps = matchPath(path, pattern) ?: return@forEach
                val outSegs = buildOutputPathSegments(spec.outputPath, path, caps)
                setIntermediate(out, outSegs, node)
                recordSetter(outSegs.joinToString("/")) { newVal ->
                    // naive setter: follow path on original root
                    var cur: Any? = root
                    for (i in path.indices) {
                        if (i == path.lastIndex) {
                            val parent = cur
                            val key = path[i]
                            if (spec.setter != null) spec.setter.invoke(parent, key, newVal)
                            else setProp(parent, key, newVal)
                        } else cur = getProp(cur, path[i])
                    }
                }
            }

            // Materialize action entries even if property missing
            specs.forEach { spec ->
                if (spec.type == "action") {
                    val pattern = splitPath(spec.path)
                    if (pattern.size == path.size + 1) {
                        val fullPath = path + pattern.last()
                        val caps = matchPath(fullPath, pattern) ?: return@forEach
                        if (pattern.last() != "*") {
                            val outSegs = buildOutputPathSegments(spec.outputPath, fullPath, caps)
                            setIntermediate(out, outSegs, spec.actionTitle ?: pattern.last())
                        }
                    }
                }
            }

            when (node) {
                is Map<*, *> -> node.forEach { (k, v) ->
                    if (k != null) walk(v, path + k.toString())
                }

                is List<*> -> node.forEachIndexed { idx, v -> walk(v, path + idx.toString()) }
                else -> {}
            }
        }

        walk(root, emptyList())
        return out
    }

    roots.forEach { dataList += buildForRoot(it) }

    return ExtractResult(
        data = if (dataList.size == 1) dataList.first() else dataList, typeDefs = specs, setters = setters
    )
}

// Apply an incoming path update back to all matched setters
fun applyPathUpdate(setters: Map<String, List<(Any?) -> Unit>>, path: String, newValue: Any?) {
    setters[path]?.forEach { it(newValue) }
}

// Serialize helpers
fun ExtractResult.toMessage(debugUid: String): String = Json.encodeToString(
    JsonObject(
        mapOf(
            "action" to JsonPrimitive("setState"),
            "state" to toJsonElement(data),
            "typeDefs" to JsonArray(typeDefs.map { typeDefToJson(it) }),
            "debug_uid" to JsonPrimitive(debugUid)
        )
    )
)

// Flatten a JSON-friendly structure into path -> value map
private fun flattenState(node: Any?, path: List<String>, out: MutableMap<String, Any?>) {
    when (node) {
        is Map<*, *> -> node.forEach { (k, v) ->
            if (k != null) flattenState(v, path + k.toString(), out)
        }
        is List<*> -> node.forEachIndexed { idx, v ->
            flattenState(v, path + idx.toString(), out)
        }
        else -> out[path.joinToString("/")] = node
    }
}

private fun normalizeStateNode(node: Any?, expectedRoots: Int): Any? {
    // If inspector sent a single-element list but we have a single root map, unwrap it.
    if (expectedRoots == 1 && node is List<*> && node.size == 1) {
        return node[0]
    }
    return node
}

// -----------------------------------------------------------------------------
// WebSocket handling skeleton (assumes `send(text: String)` exists)
class DebugSocketBridge(
    private val roots: List<Any?>, private val specs: List<TypeDef>, var sendFn: (String) -> Unit
) {
    private var extract: ExtractResult = extractMappedData(roots, specs)

    fun sendInitial(debugUid: String) {
        sendFn(extract.toMessage(debugUid))
    }

    fun handleMessage(text: String) {
        val msgElement = Json.parseToJsonElement(text)
        val msg = jsonElementToMap(msgElement)
        when (msg["action"]) {
            "hello" -> {
                val uid = msg["debug_uid"]?.toString() ?: "server"
                sendInitial(uid)
            }
            "pathUpdate" -> {
                val path = msg["path"] as? String ?: return
                val newValue = msg["newValue"]
                applyPathUpdate(extract.setters, path, newValue)
                sendFn(extract.toMessage(msg["debug_uid"]?.toString() ?: "server"))
            }
            "pathAction" -> {
                val path = msg["path"] as? String ?: return
                val segs = splitPath(path)
                specs.forEach { spec ->
                    val pattern = splitPath(spec.path)
                    val caps = matchPath(segs, pattern)
                    if (caps != null && spec.type == "action" && spec.action != null) {
                        roots.forEach { r ->
                            var parent: Any? = r
                            for (i in 0 until segs.size - 1) {
                                parent = getProp(parent, segs[i])
                            }
                            val key = segs.last()
                            spec.action.invoke(parent, key)
                        }
                    }
                }
            }

            "stateUpdate" -> {
                val incomingRaw = msg["state"] ?: return
                val expectedRoots = roots.count { it != null }.coerceAtLeast(1)
                val incomingState = normalizeStateNode(incomingRaw, expectedRoots)
                val currentFlat = mutableMapOf<String, Any?>()
                flattenState(extract.data, emptyList(), currentFlat)
                val incomingFlat = mutableMapOf<String, Any?>()
                flattenState(incomingState, emptyList(), incomingFlat)
                incomingFlat.forEach { (path, value) ->
                    val cur = currentFlat[path]
                    if (cur != value) {
                        applyPathUpdate(extract.setters, path, value)
                    }
                }
                extract = extractMappedData(roots.filterNotNull(), specs)
                sendFn(extract.toMessage(msg["debug_uid"]?.toString() ?: "server"))
            }

            "requestTypeDefs" -> {
                val payload = JsonObject(
                    mapOf(
                        "action" to JsonPrimitive("typeDefs"),
                        "typeDefs" to JsonArray(specs.map { typeDefToJson(it) }),
                        "debug_uid" to JsonPrimitive(msg["debug_uid"]?.toString() ?: "server")
                    )
                )
                sendFn(Json.encodeToString(payload))
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Example usage:
//
// val typeDefs = listOf(
//     TypeDef(path = "user/name", type = "string", priority = 10),
//     TypeDef(path = "user/age", type = "number", min = 0, max = 120, units = "yr", priority = 9),
//     TypeDef(path = "settings/theme", type = "choice", options = listOf("dark", "light", "system"))
// )
//
// val roots = listOf(appState) // or any list of roots
//
// val bridge = DebugSocketBridge(roots, typeDefs) { txt -> send(txt) } // send is your existing function
// bridge.sendInitial("server")
// // On incoming WS text:
// // bridge.handleMessage(incomingText)

val knownDebugInspectors = mutableMapOf<String, DebugSocketBridge>()

@RunsAt(installHandlers)
fun initInspecting() {
    rawHandlers.getOrPut("__inspecting__") { mutableSetOf() }.add { text ->
        val s = thisSession.get()
        try {
            val t = JSONObject(text)
            if (t.has("debug_uid") && t.has("type") && t.getString("type") == "inspector") {
                knownDebugInspectors[t.getString("debug_uid")]?.sendFn = {
                    inMain {
                        s.send(it)
                    }
                }
                knownDebugInspectors[t.getString("debug_uid")]?.handleMessage(text)
            }
            Handled.yes
        } catch (e: Exception) {
            Handled.no
        }
    }
}

fun Box.inspect(
    channel: String = "debug", name: String = "inspect", a: Any?, typeDefs: List<TypeDef>
): DebugSocketBridge {
    val aa = a ?: "null"
    val ln = Inspector.linkFor(aa, "/inspect")
    val uid = this.id + "_" + name
    display(
        channel, """
<iframe src='${ln}&uid=${uid}' style='border:none;padding:0;margin:0'>
"""
    )
    val roots = if (a is Collection<*>) a.toList() else mutableListOf(a)
    val s = thisSession.get()
    val eval = knownDebugInspectors.computeIfAbsent(uid) {
        DebugSocketBridge(roots, typeDefs) {
            inMain {
                s.send(it)
            }
        }
    }
    return eval
}

// Simple demo initializer for quick testing (prints outbound JSON to stdout)
fun initDemo(): Pair<MutableMap<String, Any>, List<TypeDef>> {
    val demoState = mutableMapOf(
        "user" to mutableMapOf("name" to "Demo User", "age" to 42),
        "settings" to mutableMapOf("theme" to "dark", "debug" to true),
        "items" to mutableListOf(
            mutableMapOf("id" to 1, "label" to "One", "enabled" to true),
            mutableMapOf("id" to 2, "label" to "Two", "enabled" to false)
        )
    )

    val demoTypeDefs = listOf(
        TypeDef(path = "user/name", type = "string", priority = 10),
        TypeDef(path = "user/age", type = "number", min = 0, max = 120, priority = 9, units = "yr"),
        TypeDef(path = "settings/theme", type = "choice", options = listOf("dark", "light", "system"), priority = 8),
        TypeDef(path = "settings/debug", type = "boolean", priority = 7),
        TypeDef(path = "items/*/label", type = "string", priority = 5),
        TypeDef(path = "items/*/enabled", type = "boolean", priority = 4)
    )

    return demoState to demoTypeDefs
}

// Helpers to avoid external JSON deps ----------------------------------------
private fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Map<*, *> -> {
        val mapped = value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) }
        JsonObject(mapped)
    }

    is List<*> -> JsonArray(value.map { toJsonElement(it) })
    else -> JsonPrimitive(value.toString())
}

private fun jsonElementToValue(el: JsonElement): Any? = when (el) {
    is JsonNull -> null
    is JsonPrimitive -> {
        when {
            el.isString -> el.content
            el.booleanOrNull != null -> el.boolean
            el.longOrNull != null -> {
                val l = el.long
                if (el.content.contains(".")) el.double else l
            }

            else -> el.content
        }
    }

    is JsonObject -> el.mapValues { jsonElementToValue(it.value) }
    is JsonArray -> el.map { jsonElementToValue(it) }
    else -> null
}

private fun jsonElementToMap(el: JsonElement): Map<String, Any?> {
    val obj = el as? JsonObject ?: return emptyMap()
    return obj.mapValues { jsonElementToValue(it.value) }
}

private fun typeDefToJson(td: TypeDef): JsonObject {
    val entries = mutableMapOf<String, JsonElement>()
    entries["path"] = JsonPrimitive(td.path)
    td.outputPath?.let { entries["outputPath"] = JsonPrimitive(it) }
    td.type?.let { entries["type"] = JsonPrimitive(it) }
    td.actionTitle?.let { entries["actionTitle"] = JsonPrimitive(it) }
    td.options?.let { entries["options"] = JsonArray(it.map { o -> JsonPrimitive(o) }) }
    td.min?.let { entries["min"] = JsonPrimitive(it) }
    td.max?.let { entries["max"] = JsonPrimitive(it) }
    td.step?.let { entries["step"] = JsonPrimitive(it) }
    td.readOnly?.let { entries["readOnly"] = JsonPrimitive(it) }
    td.priority?.let { entries["priority"] = JsonPrimitive(it) }
    td.units?.let { entries["units"] = JsonPrimitive(it) }
    td.unitsHtml?.let { entries["unitsHtml"] = JsonPrimitive(it) }
    return JsonObject(entries)
}
