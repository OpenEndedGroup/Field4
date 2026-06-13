package afield

import afield.logger.lprintln
import afield.stdlib.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val provisionalByDocument =
    ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, String>>>()

private val shaderProvisionalProperties = setOf(
    "vertex",
    "fragment",
    "stackSL",
    "vertex_fragment",
    "mesh",
    "task",
    "compute",
    "raygen",
    "miss",
    "closesthit",
    "anyhit",
    "intersection",
    "callable",
    "vertex_s",
    "fragment_s",
    "mesh_s",
    "task_s",
    "compute_s",
    "raygen_s",
    "miss_s",
    "closesthit_s",
    "anyhit_s",
    "intersection_s",
    "callable_s"
)

private val graphicsShaderProperties = setOf("vertex", "fragment", "vertex_fragment", "vertex_s", "fragment_s")
private val computeShaderProperties = setOf("compute", "compute_s")
private val meshShaderProperties = setOf("task", "mesh", "fragment", "task_s", "mesh_s", "fragment_s")
private val rayShaderProperties = setOf(
    "raygen",
    "miss",
    "closesthit",
    "anyhit",
    "intersection",
    "callable",
    "raygen_s",
    "miss_s",
    "closesthit_s",
    "anyhit_s",
    "intersection_s",
    "callable_s"
)

private fun currentDocument(): String? =
    try {
        thisSessionInfo.get()?.filename
    } catch (_: Exception) {
        null
    }

private fun documentKeyFor(box: Box): String? =
    if (box.isInsideInitialized()) box.inside.filename else currentDocument()

private fun reloadShaderRuntimeForProvisionalProperty(target: Box, property: String) {
    if (property !in shaderProvisionalProperties) return
    runCatching {
        runBlocking {
            withContext(Runloop.runLoop.main) {
                if (property in graphicsShaderProperties && runCatching { target._shader != null }.getOrDefault(false)) {
                    reloadShader(target)
                }
                if (property in computeShaderProperties && runCatching { target._computeShader != null }.getOrDefault(false)) {
                    reloadComputeShader(target)
                }
                if (property in meshShaderProperties && runCatching { target._meshShader != null }.getOrDefault(false)) {
                    reloadMeshShader(target)
                }
                if (property in rayShaderProperties && runCatching { target._rayTracingShader != null }.getOrDefault(false)) {
                    reloadRayTracingShader(target)
                }
            }
        }
    }.onFailure { throwable ->
        lprintln(
            "warning,ai4",
            "Unable to refresh shader runtime after provisional decision for ${target.id}/$property: ${
                throwable.message ?: throwable::class.java.name
            }"
        )
    }
}

private fun documentMap(document: String): ConcurrentHashMap<String, ConcurrentHashMap<String, String>> =
    provisionalByDocument.computeIfAbsent(document) { ConcurrentHashMap() }

private fun propertyMap(document: String, uid: String): ConcurrentHashMap<String, String> =
    documentMap(document).computeIfAbsent(uid) { ConcurrentHashMap() }

fun markProvisional(box: Box, property: String, contents: String? = null) {
    val document = documentKeyFor(box) ?: return
    val value = contents ?: (box.getPropertyRaw(property) as? String) ?: return
    setProvisional(document, box.id, property, value)
}

fun setProvisional(document: String, uid: String, property: String, contents: String) {
    val props = propertyMap(document, uid)
    props[property] = contents
    sendProvisionalSet(document)
}

fun clearProvisional(box: Box, property: String): Boolean {
    val document = documentKeyFor(box) ?: return false
    return removeProvisional(document, box.id, property)
}

fun removeProvisional(document: String, uid: String, property: String): Boolean {
    val docMap = provisionalByDocument[document] ?: run {
        sendProvisionalSet(document)
        return false
    }
    val props = docMap[uid] ?: run {
        sendProvisionalSet(document)
        return false
    }
    val removed = props.remove(property) != null
    if (props.isEmpty()) docMap.remove(uid)
    if (docMap.isEmpty()) provisionalByDocument.remove(document)
    sendProvisionalSet(document)
    return removed
}

fun clearProvisional(document: String) {
    provisionalByDocument.remove(document)
    sendProvisionalSet(document)
}

fun provisionalSet(document: String): ProvisionalSet =
    ProvisionalSet(provisionalEntries(document))

fun provisionalEntries(document: String): List<ProvisionalEntry> {
    val docMap = provisionalByDocument[document] ?: return emptyList()
    return docMap.flatMap { (uid, props) ->
        props.map { (property, contents) ->
            ProvisionalEntry(uid = uid, property = property, contents = contents)
        }
    }
}

fun acceptProvisional(document: String, uid: String, property: String): Boolean {
    val docMap = provisionalByDocument[document] ?: return false
    val props = docMap[uid] ?: return false
    val contents = props[property] ?: return false
    val target = thisSessionInfo.get().boxes[uid] ?: run {
        removeProvisional(document, uid, property)
        return false
    }
    target.setPropertyRaw(property, contents, doResync = true)
    removeProvisional(document, uid, property)
    reloadShaderRuntimeForProvisionalProperty(target, property)
    return true
}

fun rejectProvisional(document: String, uid: String, property: String): Boolean {
    val target = thisSessionInfo.get().boxes[uid]
    val removed = removeProvisional(document, uid, property)
    if (removed && target != null) {
        reloadShaderRuntimeForProvisionalProperty(target, property)
    }
    return removed
}

fun acceptAllProvisional(document: String) {
    val entries = provisionalEntries(document)
    if (entries.isEmpty()) {
        sendProvisionalSet(document)
        return
    }
    entries.forEach { entry ->
        val target = thisSessionInfo.get().boxes[entry.uid] ?: return@forEach
        target.setPropertyRaw(entry.property, entry.contents, doResync = true)
        reloadShaderRuntimeForProvisionalProperty(target, entry.property)
    }
    clearProvisional(document)
}

fun rejectAllProvisional(document: String) {
    val entries = provisionalEntries(document)
    clearProvisional(document)
    entries.forEach { entry ->
        val target = thisSessionInfo.get().boxes[entry.uid] ?: return@forEach
        reloadShaderRuntimeForProvisionalProperty(target, entry.property)
    }
}

fun sendProvisionalSet(document: String) {
    val message = Message("set-provisional").payload(provisionalSet(document))
    _send(message, document = Regex.escape(document))
}
