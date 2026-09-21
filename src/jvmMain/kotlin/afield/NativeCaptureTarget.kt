package afield

internal data class NativeCaptureBox<W : Any>(val parents: List<String>, val windows: List<W>)

internal class NativeCaptureTargetException(message: String) : IllegalArgumentException(message)

/** Resolve only within one document snapshot, preferring the target's nearest parent layer. */
internal fun <W : Any> resolveNativeCaptureTarget(boxes: Map<String, NativeCaptureBox<W>>, uid: String?): W {
    if (uid != null && uid !in boxes) {
        throw NativeCaptureTargetException("No box '$uid' in this document for native capture")
    }
    var layer = if (uid == null) boxes.keys.toSet() else setOf(uid)
    val seen = mutableSetOf<String>()
    while (layer.isNotEmpty()) {
        seen.addAll(layer)
        val windows = layer.flatMap { boxes[it]?.windows.orEmpty() }.distinct()
        if (windows.size == 1) return windows.single()
        if (windows.size > 1) {
            throw NativeCaptureTargetException(
                "Ambiguous native capture: ${windows.size} windows " +
                    (if (uid == null) "in this document; specify --uid" else "at the nearest parent layer of '$uid'; target a renderer box explicitly")
            )
        }
        if (uid == null) break
        layer = layer.flatMap { boxes[it]?.parents.orEmpty() }.filter { it in boxes && it !in seen }.toSet()
    }
    error("No live native window in this document" + (uid?.let { " for box '$it' and its parents" } ?: "") +
        "; open and start its renderer before capturing")
}
