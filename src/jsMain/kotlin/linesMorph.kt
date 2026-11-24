import kotlinx.browser.window
import org.w3c.dom.Element
import kotlin.js.Date
import kotlin.math.max
import kotlin.math.min

/*
 * Keyed updates for the `lines(...)` viewer.
 *
 * A display whose lines carry `FLine.key` arrives as `<g data-fline-key="...">` groups. When the stage already
 * holds groups with the same keys, the new display is reconciled into the old one rather than replacing it.
 * With a transition (`data-afield-transition`, seconds) the shared keys morph:
 *
 * - path `d` numbers are interpolated when both paths have the same command sequence (rectangles always do),
 * - `rgba(...)` fills and strokes are interpolated,
 * - text positions and sizes are interpolated.
 *
 * New keys fade in and vanished keys fade out behind everything else. Every frame writes the interpolated
 * values into the DOM, so an update that arrives mid-morph starts from wherever the drawing is now. Retargeting
 * is free, and a box can send a new layout as often as it likes.
 */

private val MORPH_TAGS = setOf("path", "circle", "text")

private class Lerp(val element: Element, val attr: String, val from: String, val to: String)

private class Fade(val element: Element, val from: Double, val to: Double, val removeAtEnd: Boolean)

private class Morph(val start: Double, val durationMs: Double, val lerps: List<Lerp>, val fades: List<Fade>, val texts: List<Pair<Element, String>>)

private val PATH_TOKENS = Regex("[A-Za-z]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

private fun tokens(d: String): List<String> = PATH_TOKENS.findAll(d).map { it.value }.toList()

private fun isNumberToken(t: String) = t.isNotEmpty() && !t[0].isLetter()

/** Interpolate two path strings with the same command sequence; null when they are not compatible. */
private fun lerpPath(a: List<String>, b: List<String>, t: Double): String? {
    if (a.size != b.size) return null
    val sb = StringBuilder()
    for (i in a.indices) {
        val x = a[i]
        val y = b[i]
        if (isNumberToken(x) != isNumberToken(y)) return null
        if (!isNumberToken(x)) {
            if (x != y) return null
            sb.append(x)
        } else {
            val v = x.toDouble() + (y.toDouble() - x.toDouble()) * t
            sb.append(' ').append(fmt(v))
        }
    }
    return sb.toString()
}

private fun fmt(v: Double): String {
    val r = kotlin.math.round(v * 1000.0) / 1000.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}

private val RGBA = Regex("rgba?\\(([^)]*)\\)")

private fun parseRgba(c: String?): DoubleArray? {
    val m = RGBA.matchEntire(c?.trim() ?: return null) ?: return null
    val parts = m.groupValues[1].split(',').map { it.trim().toDoubleOrNull() ?: return null }
    if (parts.size < 3) return null
    return doubleArrayOf(parts[0], parts[1], parts[2], parts.getOrElse(3) { 1.0 })
}

private fun lerpColor(a: DoubleArray, b: DoubleArray, t: Double): String {
    fun c(i: Int) = a[i] + (b[i] - a[i]) * t
    return "rgba(${kotlin.math.round(c(0)).toInt()},${kotlin.math.round(c(1)).toInt()},${kotlin.math.round(c(2)).toInt()},${fmt(c(3))})"
}

private fun lerpValue(attr: String, from: String, to: String, t: Double): String {
    if (t >= 1.0) return to
    return when (attr) {
        "d" -> lerpPath(tokens(from), tokens(to), t) ?: to
        "fill", "stroke" -> {
            val a = parseRgba(from)
            val b = parseRgba(to)
            if (a != null && b != null) lerpColor(a, b, t) else to
        }
        else -> {
            val a = from.toDoubleOrNull()
            val b = to.toDoubleOrNull()
            if (a != null && b != null) fmt(a + (b - a) * t) else to
        }
    }
}

private fun ease(t: Double): Double = if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).let { it * it * it } / 2

private fun morphables(group: Element): List<Element> {
    val out = ArrayList<Element>()
    val all = group.querySelectorAll("path,circle,text")
    for (i in 0 until all.length) (all.item(i) as? Element)?.let { out.add(it) }
    return out
}

private val MORPH_ATTRS = mapOf(
    "path" to listOf("d", "fill", "stroke", "opacity"),
    "circle" to listOf("cx", "cy", "r", "fill", "opacity"),
    "text" to listOf("x", "y", "font-size", "fill", "opacity"),
)

/**
 * Put the children of [sourceSvg] into [stageGroup], keeping and morphing keyed groups for [seconds].
 * [prepare] is applied to each newly cloned element (the viewer's non-scaling-stroke setup). Returns true when
 * it reconciled by key, false when it simply replaced the stage, which it does whenever either side has no keys.
 */
internal fun reconcileLinesStage(view: dynamic, stageGroup: Element, sourceSvg: Element, seconds: Double, prepare: (Element) -> Unit): Boolean {
    finishMorph(view, jumpToEnd = false)
    // Groups still fading out from an earlier update go now; the drawing has moved on.
    val leaving = stageGroup.querySelectorAll("[data-afield-leaving]")
    for (i in 0 until leaving.length) (leaving.item(i) as? Element)?.let { it.parentNode?.removeChild(it) }

    // The interactive SVG wraps its lines in one outer <g>, so the keyed groups are its children. That wrapper is kept
    // on the stage from one display to the next (marked data-afield-keyed-root), and the reconcile happens inside it.
    val sourceRoot = singleGroupChild(sourceSvg)
    val stageRoot: Element = if (sourceRoot == null) stageGroup else {
        val existing = stageGroup.firstElementChild?.takeIf { it.hasAttribute("data-afield-keyed-root") && stageGroup.childElementCount == 1 }
        val root = existing ?: run {
            while (stageGroup.firstChild != null) stageGroup.removeChild(stageGroup.firstChild!!)
            stageGroup.ownerDocument!!.createElementNS(sourceRoot.namespaceURI, "g").also {
                it.setAttribute("data-afield-keyed-root", "true")
                stageGroup.appendChild(it)
            }
        }
        for (i in 0 until sourceRoot.attributes.length) sourceRoot.attributes.item(i)?.let { root.setAttribute(it.name, it.value) }
        root
    }
    val sourceParent = sourceRoot ?: sourceSvg

    val old = HashMap<String, Element>()
    var child = stageRoot.firstElementChild
    while (child != null) {
        child.getAttribute("data-fline-key")?.let { old[it] = child!! }
        child = child.nextElementSibling
    }

    val incoming = ArrayList<Element>()
    val sourceChildren = sourceParent.childNodes
    var incomingKeyed = 0
    for (index in 0 until sourceChildren.length) {
        val c = sourceChildren.item(index) as? Element ?: continue
        val cloned = c.cloneNode(true) as Element
        prepare(cloned)
        if (cloned.hasAttribute("data-fline-key")) incomingKeyed++
        incoming.add(cloned)
    }

    if (old.isEmpty() || incomingKeyed == 0) {
        while (stageRoot.firstChild != null) stageRoot.removeChild(stageRoot.firstChild!!)
        incoming.forEach { stageRoot.appendChild(it) }
        if (seconds > 0.0 && incomingKeyed > 0) {
            // A first keyed display fades in, so a drawing that appears is not a jump either.
            startMorph(view, seconds, emptyList(), incoming.filter { it.hasAttribute("data-fline-key") }.map { Fade(it, 0.0, 1.0, false) }, emptyList())
        }
        return false
    }

    val lerps = ArrayList<Lerp>()
    val fades = ArrayList<Fade>()
    val texts = ArrayList<Pair<Element, String>>()
    val animate = seconds > 0.0
    val kept = HashSet<String>()
    for (element in incoming) {
        val key = element.getAttribute("data-fline-key") ?: continue
        val previous = old[key] ?: run {
            if (animate) fades.add(Fade(element, 0.0, 1.0, false))
            null
        } ?: continue
        kept.add(key)
        if (!animate) continue
        // A group caught part-way through fading in carries on from where it had got to.
        previous.getAttribute("opacity")?.toDoubleOrNull()?.let { if (it < 1.0) fades.add(Fade(element, it, 1.0, false)) }
        val before = morphables(previous)
        val after = morphables(element)
        for (i in 0 until min(before.size, after.size)) {
            val a = before[i]
            val b = after[i]
            val tag = b.tagName.lowercase()
            if (a.tagName.lowercase() != tag || tag !in MORPH_TAGS) continue
            for (attr in MORPH_ATTRS[tag].orEmpty()) {
                val to = b.getAttribute(attr)
                // A hovered element's own colour is the one it returns to, not the hover colour it shows.
                val from = (if (attr == "fill" || attr == "stroke") a.getAttribute("data-orig-$attr") else null)
                    ?: a.getAttribute(attr) ?: if (attr == "opacity") "1" else null
                if (from == null || to == null && attr != "opacity") continue
                val target = to ?: "1"
                if (from == target) continue
                lerps.add(Lerp(b, attr, from, target))
                b.setAttribute(attr, from)
            }
            if (tag == "text" && a.textContent != b.textContent) {
                texts.add(b to (b.textContent ?: ""))
                b.textContent = a.textContent
            }
        }
    }

    // Vanished keys stay for the fade, drawn behind the new drawing; without a transition they just go.
    val departing = old.filterKeys { it !in kept }.values
    while (stageRoot.firstChild != null) stageRoot.removeChild(stageRoot.firstChild!!)
    if (animate) departing.forEach {
        it.setAttribute("data-afield-leaving", "true")
        it.setAttribute("pointer-events", "none")
        stageRoot.appendChild(it)
        fades.add(Fade(it, (it.getAttribute("opacity")?.toDoubleOrNull() ?: 1.0), 0.0, true))
    }
    incoming.forEach { stageRoot.appendChild(it) }
    fades.forEach { if (!it.removeAtEnd) it.element.setAttribute("opacity", fmt(it.from)) }
    if (lerps.isNotEmpty() || fades.isNotEmpty()) startMorph(view, seconds, lerps, fades, texts)
    return true
}

/** The one `<g>` an SVG wraps all its content in, or null when it has some other shape. */
private fun singleGroupChild(svg: Element): Element? {
    var found: Element? = null
    var c = svg.firstElementChild
    while (c != null) {
        if (c.tagName.lowercase() != "g" || found != null) return null
        found = c
        c = c.nextElementSibling
    }
    return found
}

private fun startMorph(view: dynamic, seconds: Double, lerps: List<Lerp>, fades: List<Fade>, texts: List<Pair<Element, String>>) {
    val morph = Morph(Date.now(), max(1.0, seconds * 1000.0), lerps, fades, texts)
    view.morph = morph
    fun tick() {
        if (view.morph !== morph) return
        val t = min(1.0, (Date.now() - morph.start) / morph.durationMs)
        applyMorph(morph, ease(t))
        if (t < 1.0) window.requestAnimationFrame { tick() } else view.morph = null
    }
    applyMorph(morph, 0.0)
    window.requestAnimationFrame { tick() }
}

private fun applyMorph(morph: Morph, e: Double) {
    for (l in morph.lerps) {
        val value = lerpValue(l.attr, l.from, l.to, e)
        // A hovered element shows its hover colour; the morph moves the colour it will return to.
        if ((l.attr == "fill" || l.attr == "stroke") && l.element.hasAttribute("data-orig-${l.attr}")) {
            l.element.setAttribute("data-orig-${l.attr}", value)
        } else l.element.setAttribute(l.attr, value)
    }
    for (f in morph.fades) {
        f.element.setAttribute("opacity", fmt(f.from + (f.to - f.from) * e))
        if (e >= 1.0) {
            if (f.removeAtEnd) f.element.parentNode?.removeChild(f.element) else f.element.removeAttribute("opacity")
        }
    }
    // Text swaps at the midpoint, when a label is moving fastest and least readable.
    if (e >= 0.5) for ((el, text) in morph.texts) if (el.textContent != text) el.textContent = text
}

/** Stop a running morph, either leaving the DOM where it is ([jumpToEnd] false) or at its final values. */
internal fun finishMorph(view: dynamic, jumpToEnd: Boolean) {
    val morph = view.morph as? Morph ?: return
    view.morph = null
    if (jumpToEnd) applyMorph(morph, 1.0)
}
