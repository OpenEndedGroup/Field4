package afield.datalog

import afield.graphquery.GraphPathRef
import afield.graphquery.GraphWalkOptions
import afield.graphquery.graphPaths
import java.util.IdentityHashMap

/**
 * Any live object graph as Datalog base relations, by way of `afield.graphquery`.
 *
 * The two layers answer different halves of the same question. `graphPaths` addresses slots by
 * pattern and hands back live lenses, but a glob cannot follow an edge and then follow it again, and
 * it has nothing to say about two addresses that turn out to be the same object. Datalog has
 * recursion, negation and aggregation and no idea what an object is. Joining them costs one walk:
 *
 * ```kotlin
 * val d = Datalog()
 * d.addObjectGraph(rig)
 * d.add(dlObjectGraphRules)
 * d.query("alias(A, B)")                                  // two names for one object
 * d.query("reaches(\"carrier\", P), writable(P)")         // settable, anywhere below carrier
 * ```
 *
 * Identity is what makes it worth the trip. [dlObjectGraphSchema]'s `at(Path, ObjectId)` gives every
 * non-scalar value a number, so aliasing needs no special relation — it is a join of `at` with
 * itself. The walk already visits a shared node under every alias (its cycle detection is
 * path-scoped, not visit-once), so the facts are there to be joined.
 */

/** What [addObjectGraph] defines. Rendered by [dlObjectGraphTable]. */
val dlObjectGraphSchema: List<Pair<String, String>> = listOf(
    "slot(Path)" to "every addressable slot the walk reached",
    "link(Parent, Child, Segment)" to "one edge of the walk, labelled by the child's own name",
    "segment(Path, Name)" to "a slot's own name, the last part of its address",
    "depth(Path, N)" to "how many edges from the root the slot sits at",
    "at(Path, ObjectId)" to "the identity of the object a slot holds; shared objects share a number",
    "value(Path, V)" to "a scalar the slot holds — number, string, boolean",
    "type(Path, TypeName)" to "the runtime class of the slot's current value",
    "writable(Path)" to "the slot has a setter",
)

/**
 * Rules worth having on top of [addObjectGraph], as source you can `add`.
 *
 * `alias/2` is the one that pays for the whole bridge. The rest is the reachability a glob cannot
 * express, and it is written over `link/3` so it follows the walk rather than string-matching paths.
 */
val dlObjectGraphRules: String = """
    #datalog
    % two different addresses for one and the same object
    alias(A, B) :- at(A, O), at(B, O), A != B.

    % transitive reachability through the walk's own edges
    reaches(A, B) :- link(A, B, _).
    reaches(A, C) :- reaches(A, B), link(B, C, _).

    % a slot nothing points at: the root, and anything the walk could only reach as a root
    unreached(P) :- slot(P), !link(_, P, _).

    % a leaf holds a scalar and has no children
    leaf(P) :- slot(P), !link(P, _, _).
""".trimIndent()

/** [dlObjectGraphSchema] as a table, so nothing has to restate it. */
fun dlObjectGraphTable(): afield.Table {
    val table = afield.Table(listOf("relation", "one row per"), title = "an object graph as facts")
    table.color("relation", "#8cf")
    dlObjectGraphSchema.forEach { (signature, meaning) -> table.row(signature, meaning) }
    return table
}

/**
 * Walk [root] and load it into this program. Returns `this`.
 *
 * [pattern] is `graphPaths`' pattern and scoping it is the difference between a cheap load and an
 * expensive one — a fully literal prefix costs O(depth) where `**` walks everything. [options] is
 * passed through untouched, so `descendInto` and `maxRefs` still prune the walk rather than the
 * facts.
 *
 * Strings longer than [maxTextLength] are truncated rather than dropped, so `contains(V, "x")` keeps
 * working without a novel ending up inside a join key.
 */
fun Datalog.addObjectGraph(
    root: Any?,
    pattern: String = "**",
    options: GraphWalkOptions = GraphWalkOptions(),
    maxTextLength: Int = 4000,
): Datalog {
    val refs = root.graphPaths(pattern, options).toList()

    // Identity, not equality: two equal-but-distinct objects are two nodes, and one object reached
    // by two paths is one node. That is the whole point of `at/2`, and `==` would defeat it.
    val ids = IdentityHashMap<Any, Long>()
    fun idOf(value: Any): Long = ids.getOrPut(value) { (ids.size + 1).toLong() }

    // The root is a slot too, and it is the one every `reaches` query starts from.
    val rootPath = "<root>"
    fact("slot", rootPath)
    fact("depth", rootPath, 0)
    root?.let {
        fact("at", rootPath, idOf(it))
        fact("type", rootPath, it::class.simpleName ?: it::class.java.name)
    }

    refs.forEach { ref ->
        val path = ref.path
        fact("slot", path)
        fact("segment", path, ref.name)
        fact("depth", path, ref.depth)
        if (ref.writable) fact("writable", path)
        fact("link", parentPathOf(ref), path, ref.name)

        val value = runCatching { ref.get() }.getOrNull()
        if (value != null) {
            fact("type", path, value::class.simpleName ?: value::class.java.name)
            when (value) {
                is String -> fact("value", path, value.take(maxTextLength))
                is Number, is Boolean, is Char -> fact("value", path, value)
                // Only a value that can be *shared* gets an identity; two equal Ints being "the
                // same object" is a fact about boxing, not about the graph.
                else -> fact("at", path, idOf(value))
            }
        }
    }
    return this
}

/**
 * The address of a ref's parent slot, in the same spelling [addObjectGraph] gave it.
 *
 * Derived from the public `path` rather than from `pathSegments`, which is `internal` to
 * `afield.graphquery` and is not this package's to depend on.
 */
private fun parentPathOf(ref: GraphPathRef): String {
    val raw = ref.rawPath
    val cut = raw.lastIndexOf('/')
    return if (cut < 0) "<root>" else raw.substring(0, cut)
}
