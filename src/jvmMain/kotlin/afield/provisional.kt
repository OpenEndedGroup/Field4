package afield

import java.util.concurrent.ConcurrentHashMap

private val provisionalByDocument =
    ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, String>>>()

private fun currentDocument(): String? =
    try {
        thisSessionInfo.get()?.filename
    } catch (_: Exception) {
        null
    }

private fun documentKeyFor(box: Box): String? =
    if (box.isInsideInitialized()) box.inside.filename else currentDocument()

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
    return true
}

fun rejectProvisional(document: String, uid: String, property: String): Boolean =
    removeProvisional(document, uid, property)

fun acceptAllProvisional(document: String) {
    val entries = provisionalEntries(document)
    if (entries.isEmpty()) {
        sendProvisionalSet(document)
        return
    }
    entries.forEach { entry ->
        val target = thisSessionInfo.get().boxes[entry.uid] ?: return@forEach
        target.setPropertyRaw(entry.property, entry.contents, doResync = true)
    }
    clearProvisional(document)
}

fun rejectAllProvisional(document: String) {
    clearProvisional(document)
}

fun sendProvisionalSet(document: String) {
    val message = Message("set-provisional").payload(provisionalSet(document))
    _send(message, document = Regex.escape(document))
}
