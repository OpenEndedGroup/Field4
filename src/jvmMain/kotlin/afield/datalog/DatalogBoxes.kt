package afield.datalog

import afield.Box
import afield.Rect
import afield.Table
import afield.nested

/**
 * The Field bridge: the live box graph as Datalog base relations.
 *
 * This is the reason for having a Datalog in here rather than shelling out to one. A Field document
 * *is* a labelled graph — boxes, parent/child edges, nesting, and a property bag per box — and the
 * questions you actually ask of it while working ("which boxes does nothing run into?", "which two
 * boxes both bind the same property?", "what is downstream of this one?") are recursive joins,
 * which is precisely what `afield.graphquery`'s path language cannot express and this can.
 *
 * ```kotlin
 * val d = Datalog()
 * d.addBoxGraph(thisBox)
 * d.add("""
 *     reaches(A, B) :- child(A, B).
 *     reaches(A, C) :- reaches(A, B), child(B, C).
 *     orphan(X) :- box(X), !child(_, X).
 * """)
 * println(d.query("orphan(X), name(X, N)").table())
 * ```
 */

/** What [addBoxGraph] defines, for `#help` in the box language and for the docs. */
val dlBoxRelationSchema: List<Pair<String, String>> = listOf(
    "box(Id)" to "every live box in the document",
    "name(Id, Name)" to "a box's display name (only when it has one)",
    "child(Parent, Child)" to "a graph edge, in the direction execution flows",
    "nested(Outer, Inner)" to "a box nested inside another",
    "frame(Id, X, Y, W, H)" to "a box's position and size on the canvas",
    "prop(Id, Key, Value)" to "one scalar property; a set of boxes gives a row each",
    "running(Id, Strand)" to "a strand currently executing in that box",
)

/**
 * [dlBoxRelationSchema] as a table, so a box (or `#help`, or the documentation) can print the schema
 * rather than restate it — a restatement is a copy that goes stale.
 */
fun dlBoxRelationTable(): Table {
    val table = Table(listOf("relation", "one row per"), title = "what a Field document looks like as facts")
    table.color("relation", "#8cf")
    dlBoxRelationSchema.forEach { (signature, meaning) -> table.row(signature, meaning) }
    return table
}

/**
 * Load [root]'s whole document into this program as base relations. Returns `this`.
 *
 * The graph is read once, as a snapshot — a Datalog answer is about the document as it was when you
 * asked. Call [Datalog.clearFacts] on the relations and re-add to refresh, or just build a new
 * [Datalog]; the schema-level names are stable so the rules you wrote still apply.
 *
 * Long text properties (a box's Kotlin source, for instance) are truncated at [maxTextLength] rather
 * than dropped, so `contains(Text, "renderer")` still works while a fifty-kilobyte shader does not
 * end up inside a join key.
 */
fun Datalog.addBoxGraph(
    root: Box,
    includeProperties: Boolean = true,
    maxTextLength: Int = 4000,
): Datalog {
    val boxes = documentBoxes(root)
    val ids = boxes.map { it.id }.toSet()

    boxes.forEach { box ->
        fact("box", box.id)
        val name = box.name
        if (name.isNotBlank()) fact("name", box.id, name)
        box.children.forEach { child -> if (child.id in ids) fact("child", box.id, child.id) }
        runCatching { box.nested }.getOrNull()?.forEach { inner ->
            if (inner.id in ids) fact("nested", box.id, inner.id)
        }
        val frame = box.frame
        fact("frame", box.id, frame.x, frame.y, frame.w, frame.h)
        box.currentRunningStates.keys.forEach { strand -> fact("running", box.id, strand) }
        if (includeProperties) addProperties(box, maxTextLength)
    }
    return this
}

/**
 * Every box of the document [root] belongs to, tombstones excluded.
 *
 * Prefers the session's own index, because that is the set the server considers to exist; falls back
 * to a transitive walk for a box whose session is not initialised (a detached or test box).
 */
private fun documentBoxes(root: Box): List<Box> {
    val alive = { box: Box -> box.getPropertyRaw("_tombstone") != true }
    if (root.isInsideInitialized()) {
        // The session's index does not contain the document root — it is held separately, in
        // `SessionInformation.root`. Leaving it out cost every top-level `child/2` edge, since an
        // edge is only emitted when both ends are in the box set: measured live, a fresh document
        // reported one box and no edges at all.
        val out = LinkedHashSet<Box>()
        out.add(root.inside.root)
        out.add(root)
        out.addAll(root.inside.boxes.values)
        return out.filter(alive).sortedBy { it.id }
    }
    val seen = LinkedHashSet<Box>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val box = queue.removeFirst()
        if (!seen.add(box)) continue
        box.children.forEach { queue.add(it) }
        box.parents.forEach { queue.add(it) }
    }
    return seen.filter(alive).sortedBy { it.id }
}

/** Property values worth joining on. Topology and internals are covered by the other relations. */
private fun Datalog.addProperties(box: Box, maxTextLength: Int) {
    box.properties.forEach { (key, value) ->
        if (key.startsWith("_") || key in skippedProperties) return@forEach
        when (value) {
            null -> Unit
            is String -> fact("prop", box.id, key, value.take(maxTextLength))
            is Number, is Boolean -> fact("prop", box.id, key, value)
            is Box -> fact("prop", box.id, key, value.id)
            is Rect -> {
                fact("prop", box.id, "$key.x", value.x)
                fact("prop", box.id, "$key.y", value.y)
                fact("prop", box.id, "$key.w", value.w)
                fact("prop", box.id, "$key.h", value.h)
            }
            is Collection<*> -> value.forEach { element ->
                when (element) {
                    is Box -> fact("prop", box.id, key, element.id)
                    is String, is Number, is Boolean -> fact("prop", box.id, key, element)
                    else -> Unit
                }
            }
            else -> Unit
        }
    }
}

private val skippedProperties = setOf("children", "parents", "nested", "nestedIn", "frame", "name", "layerFrames")

/**
 * A [Datalog] preloaded with this box's document and, optionally, a program.
 *
 * The one-liner for a Kotlin box:
 * ```kotlin
 * datalog("deep(X) :- child(A, X), child(_, A).").query("deep(X), name(X, N)").table()
 * ```
 */
fun Box.datalog(program: String = ""): Datalog {
    val d = Datalog()
    d.addBoxGraph(this)
    if (program.isNotBlank()) d.add(program)
    return d
}
