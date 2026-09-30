package afield

import afield.macros.gatherPreambleUpwardsReport
import afield.macros.preambleSupplierTag
import afield.macros.stripPreambleSupplierTag
import afield.macros.PREAMBLE_SELF_SUPPLIER
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Why did this value resolve here?" — [ContextResolution.explain], the answer as JSON, shared by the
 * backdoor's `why` command, the MCP tool `field_why` and the in-app AI tool `WhyDoesThisResolve`.
 *
 * Nothing here has a walk of its own. A name declared in the box's gathered preamble
 * (`val Box._x by InheritanceFromContext<Double>() /*from:<uid>*/ /**/`) is resolved with the walk that
 * declaration's delegate performs — the same one the Monaco hover uses — and the answer also names the
 * preamble line and the supplier its `from:` tag records. A name with no declaration is resolved as
 * `fromContextWithNameOrNull(name)` would; a type as `fromContext<T>()` would (a value matches when its
 * class, a superclass or an interface has that name, so no class loader is needed).
 */
object ContextWhy {
    /** At most this many shadowed candidates are listed; the count is always given. */
    private const val MAX_SHADOWED = 24

    fun explain(box: Box, name: String? = null, type: String? = null): JsonObject {
        require(!name.isNullOrBlank() || !type.isNullOrBlank()) { "why needs a name or a type" }
        val gathered = runCatching { gatherPreambleUpwardsReport(box) }.getOrNull()
        val declaration = name?.let { n -> gathered?.let { findDeclaration(box, n, it.text) } }

        val resolution: ContextResolution.Resolution?
        val mechanism: ContextResolution.Mechanism
        val delegate: String?
        if (declaration != null && type == null) {
            val hover = resolveInheritedDeclarationForHover(box, name, gathered!!.text)
            resolution = hover?.resolution
            delegate = hover?.delegate
            mechanism = when (hover?.delegate) {
                "InheritanceFromContext" -> ContextResolution.Mechanism.INHERITANCE_FROM_CONTEXT
                else -> ContextResolution.Mechanism.INHERITANCE
            }
        } else if (name != null) {
            val accept: (Any?) -> Boolean = if (type == null) { v -> v != null } else { v -> v != null && isOfType(v, type) }
            mechanism = ContextResolution.Mechanism.FROM_CONTEXT_NAMED
            delegate = null
            resolution = ContextResolution.walk(
                box,
                ContextResolution.Query(name, listOf(name), accept, { accept(it.value) }),
                ContextResolution.Policy.fromContext(includeLocalVariables = false, selfCandidate = false),
                mechanism,
                explain = true,
            )
        } else {
            mechanism = ContextResolution.Mechanism.FROM_CONTEXT
            delegate = null
            resolution = ContextResolution.walk(
                box,
                ContextResolution.Query(type!!, null, { it != null && isOfType(it, type) }, { it.value != null && isOfType(it.value, type) }, { isOfType(it, type) }),
                ContextResolution.Policy.fromContext(includeLocalVariables = false),
                mechanism,
                explain = true,
            )
        }

        return buildJsonObject {
            put("box", boxJson(box))
            put("query", name ?: type)
            name?.let { put("name", it) }
            type?.let { put("type", it) }
            put("mechanism", mechanism.name)
            delegate?.let { put("delegate", it) }
            put("found", resolution != null)
            if (resolution != null) {
                put("supplier", boxJson(resolution.supplier))
                put("matchKind", resolution.matchKind.name)
                resolution.matchedName?.let { put("matchedName", it) }
                put("depth", resolution.depth)
                put("path", JsonArray(resolution.path.map { boxJson(it) }))
                put("crossesEdges", resolution.path.size > 1)
                put("value", valueText(resolution.value))
                put("valueType", resolution.value?.javaClass?.name?.let { JsonPrimitive(it) } ?: JsonNull)
                put("ambiguous", resolution.ambiguous)
                put("competing", JsonArray(resolution.competing.map { candidateJson(it) }))
                put("shadowedCount", resolution.shadowed.size)
                put("shadowed", JsonArray(resolution.shadowed.take(MAX_SHADOWED).map { candidateJson(it) }))
            }
            declaration?.let { d ->
                put("declaration", buildJsonObject {
                    put("line", d.text)
                    put("inOwnPreamble", d.inOwnPreamble)
                    put("tag", d.tag?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("suppliedBy", d.suppliedBy?.let { boxJson(it) } ?: JsonNull)
                })
            }
            if (gathered != null && name != null) {
                val bare = name.removePrefix("_")
                val mentions = { line: String -> Regex("""\bBox\.(_?${Regex.escape(bare)})\b""").containsMatchIn(line) }
                put("stalePreambleLines", JsonArray(gathered.stale.filter(mentions).map { JsonPrimitive(stripPreambleSupplierTag(it).trim()) }))
                put("preambleConflicts", JsonArray(gathered.conflicts.filter { it.receiverAndName.substringAfterLast('.').removePrefix("_") == bare }.map { c ->
                    buildJsonObject {
                        put("declares", c.receiverAndName)
                        put("kept", stripPreambleSupplierTag(c.kept).trim())
                        put("keptSupplier", c.keptSupplier?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("discarded", JsonArray(c.discarded.map { (line, supplier) ->
                            buildJsonObject {
                                put("line", stripPreambleSupplierTag(line).trim())
                                put("supplier", supplier?.let { JsonPrimitive(it) } ?: JsonNull)
                            }
                        }))
                    }
                }))
            }
            put("summary", summary(box, name ?: type!!, mechanism, delegate, resolution, declaration))
        }
    }

    /** One line of the box's gathered preamble that declares `Box.<name>`, and where its tag says it came from. */
    class Declaration(val text: String, val tag: String?, val suppliedBy: Box?, val inOwnPreamble: Boolean)

    private fun findDeclaration(box: Box, name: String, gatheredText: String): Declaration? {
        val regex = Regex("""\b(val|var)\s+Box\.${Regex.escape(name)}\s+by\b""")
        val line = gatheredText.lines().firstOrNull { regex.containsMatchIn(it) } ?: return null
        val tag = preambleSupplierTag(line)
        val own = (box.getPropertyRaw("preamble") as? String)?.lines()?.any { it.trim() == line.trim() } == true
        val supplier = when (tag) {
            null -> if (own) box else null
            PREAMBLE_SELF_SUPPLIER -> box
            else -> findBox(box, tag)
        }
        return Declaration(stripPreambleSupplierTag(line).trim(), tag, supplier, own)
    }

    private fun findBox(from: Box, uid: String): Box? {
        if (from.id == uid) return from
        val seen = HashSet<Box>()
        var fringe = listOf(from)
        while (fringe.isNotEmpty()) {
            val next = ArrayList<Box>()
            for (b in fringe) for (p in b.parents) if (seen.add(p)) {
                if (p.id == uid) return p
                next += p
            }
            fringe = next
        }
        return if (from.isInsideInitialized()) from.inside.boxes[uid] else null
    }

    /** Whether [value]'s class, one of its superclasses or one of their interfaces is called [type] (a fully-qualified or simple name). */
    fun isOfType(value: Any, type: String): Boolean {
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue += value.javaClass
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            if (c.name == type || c.simpleName == type || c.canonicalName == type) return true
            c.superclass?.let { queue += it }
            queue += c.interfaces
        }
        return false
    }

    private fun boxJson(b: Box): JsonObject = buildJsonObject {
        put("uid", b.id)
        put("name", b.name)
    }

    private fun candidateJson(c: ContextResolution.Candidate): JsonObject = buildJsonObject {
        put("supplier", boxJson(c.supplier))
        put("matchKind", c.matchKind.name)
        c.matchedName?.let { put("matchedName", it) }
        put("depth", c.depth)
        put("path", JsonArray(c.path.map { JsonPrimitive(it.id) }))
        put("value", valueText(c.value))
    }

    private fun valueText(v: Any?): String = when (v) {
        null -> "null"
        is Box -> "box '${v.name.ifBlank { v.id }}'"
        else -> runCatching { v.toString() }.getOrElse { v.javaClass.name }.let { if (it.length > 200) it.take(199) + "…" else it }
    }

    private fun label(b: Box) = "'" + b.name.ifBlank { b.id } + "'"

    private fun summary(
        box: Box,
        query: String,
        mechanism: ContextResolution.Mechanism,
        delegate: String?,
        r: ContextResolution.Resolution?,
        d: Declaration?,
    ): String = buildString {
        val how = delegate ?: when (mechanism) {
            ContextResolution.Mechanism.FROM_CONTEXT -> "fromContext"
            ContextResolution.Mechanism.FROM_CONTEXT_NAMED -> "fromContextWithNameOrNull"
            else -> mechanism.name.lowercase()
        }
        if (r == null) {
            append("${label(box)}: nothing up the graph supplies `$query` ($how)")
        } else {
            val slot = when (r.matchKind) {
                ContextResolution.MatchKind.SELF -> "the box itself"
                ContextResolution.MatchKind.VARIABLE -> "variable `${r.matchedName}`"
                ContextResolution.MatchKind.PROPERTY -> "property `${r.matchedName}`"
            }
            val where = if (r.supplier === box) "this box" else label(r.supplier)
            append("${label(box)} reads `$query` ($how) from $slot of $where")
            if (r.path.size > 1) append(", path " + r.path.joinToString(" → ") { label(it) })
            append(" = ${valueText(r.value)}")
            if (r.ambiguous) append("; ambiguous: " + r.competing.joinToString(", ") { label(it.supplier) } + " also supplies it at the same distance with a different value, and this one is first in parents order")
            val hidden = r.shadowed.map { it.supplier }.filter { it !== r.supplier }.distinct()
            if (hidden.isNotEmpty()) append("; shadows " + hidden.joinToString(", ") { label(it) })
        }
        if (d != null) {
            append("; declared by `${d.text.removeSuffix("/**/").trim()}`")
            when {
                d.suppliedBy != null && d.suppliedBy !== box -> append(" from the preamble of ${label(d.suppliedBy)}")
                d.inOwnPreamble -> append(" in this box's own preamble")
                d.tag != null -> append(" (tagged from ${d.tag}, not an ancestor)")
            }
        }
    }

    /** [json] as plain maps, lists and primitives — what a Jackson-serialised AI tool result carries. */
    fun plain(json: JsonElement): Any? = when (json) {
        is JsonNull -> null
        is JsonPrimitive -> if (json.isString) json.content else json.content.toBooleanStrictOrNull() ?: json.content.toLongOrNull() ?: json.content.toDoubleOrNull() ?: json.content
        is JsonArray -> json.map { plain(it) }
        is JsonObject -> json.mapValues { plain(it.value) }
    }
}
