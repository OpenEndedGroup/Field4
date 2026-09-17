@file:afield.CompletionCategory("Visualization")

package afield

import afield.graphics.FLine
import afield.stdlib.Functions
import afield.stdlib.linalg.Vector3f

/**
 * `.h2` adapters for the Field types where the *reflective* default in `H2Reflect.kt` reads badly.
 *
 * `H2Reflect` lifted `.h2` from "types someone registered" to "everything", and for a data class or
 * a plain domain object a property tree is exactly right. Four families it is wrong for, each for
 * its own reason:
 *
 *  - **[Box]** — reflection would touch `Box.ki`, a `lazyVar` that *builds a K2 REPL engine on
 *    read*. Exploring a box would compile one. This is the concrete case behind the side-effect
 *    limit `H2Reflect`'s KDoc admits it cannot detect, and the reason `Box` is served here.
 *  - **[Functions.Fn]** — its only non-derived field is a private `(Double) -> Double`. A function's
 *    content is its *values*, so the adapter samples it.
 *  - **[FLine]** — a polyline's content is its node list, and `nodes` is one property among four.
 *  - **[Throwable]** — a `java.*` type, refused by the platform-package narrowing, and the value a
 *    box produces most often when something is wrong.
 *
 * Each adapter returns a shape the existing explorer machinery already renders well —
 * a `LinkedHashMap` gets map paging, and a `List<Map<String, Any?>>` hits the explorer's RECORD
 * mode and becomes a real table with one column per key. That is deliberate: an adapter that
 * invented its own rendering would have to be maintained in three places.
 */

/** How many samples an [Functions.Fn] is drawn at. */
private const val H2_FN_SAMPLES = 512

/** How many stack frames a [Throwable] shows before the tail is dropped. */
private const val H2_STACK_FRAMES = 64

@RunsAt(newRoot)
fun registerH2DomainAdapters() {

    // ── Box ──────────────────────────────────────────────────────────
    // A box as an ordered map: identity, geometry, code size, run state, its property bag, and its
    // four kinds of edge.
    //
    // Read-safety is the whole design. Every entry below is a property-bag lookup or a field read.
    // `ki` and `liveKi` are never touched — `ki` compiles on read — and neither is anything else
    // whose getter does work.
    //
    // The edges are flattened to **label tables**, not to the `Box` objects themselves, and that is
    // not squeamishness: a box graph is cyclic by construction (`398cb0ca9` made `nestInto` create
    // the `parents`/`children` edge that pairs with `nested`/`nestedIn`, so a nested box and its
    // container now point at each other), and `PagedListBrowsable`/`PagedMapBrowsable` carry no
    // cycle guard — the Inspector resolves `dir()` on click, so handing the pager a cycle lets a
    // person walk parent→child→parent without end. A table of `name`/`id`/`edge` rows also happens
    // to be what someone asking "what is this box connected to" wants to read.
    registerH2Value<Box> { box ->
        val name = box.name.ifBlank { box.id }
        // `Box.code` is declared `String` and can be null. `var code by Delegation<String>()` is
        // declared with no `defaultValue`, so the delegate answers null for a box that has never
        // held code, and the non-null type is a lie the compiler cannot see through. Found by this
        // adapter's own test on a bare `Box("id")`, as an NPE inside `String.length`. The same trap
        // is waiting on `customization`; every other entry below either has a `defaultValue` or is
        // a real field. `H2Reflect`'s reflective path survives this by catching per property — a
        // hand-written adapter has to know.
        @Suppress("USELESS_ELVIS")
        val code: String = box.code ?: ""
        H2ValueSnapshot(
            value = linkedMapOf<String, Any?>(
                "name" to box.name.ifBlank { "(unnamed)" },
                "id" to box.id,
                "frame" to box.frame,
                "viewport" to box.viewport,
                "code" to "${code.length} chars, ${code.count { it == '\n' } + 1} lines",
                "running" to box.currentRunningStates.toMap(),
                "runGeneration" to box.runGeneration.toMap(),
                // The property bag itself: a real map, so it gets real map paging. This is the entry
                // a person exploring a box is usually after — `prop<T>(…)`, keyframe targets,
                // `_code_` sidecars and every marker all live in here.
                "properties" to box.properties.toMap(),
                "markers" to box.markers.keys.toList(),
                "nestedIn" to (box.nestedIn?.let { "${it.name.ifBlank { it.id }} (${it.id})" } ?: "(none)"),
                "edges" to boxEdgeRows(box),
                // `liveKi` rather than `ki`: reading `ki` would build an engine for a box that has
                // never run. This reports whether one exists, which is the question, and costs
                // nothing.
                "engine" to if (box.liveKi != null) "live" else "not built",
                "jobs" to box.jobs.size
            ),
            summary = "Box $name",
            title = "Box $name"
        )
    }

    // ── Functions.Fn ─────────────────────────────────────────────────
    // A function as a sampled `x`, `f(x)` table.
    //
    // `evalSafe` rather than `eval`, so a sample landing exactly on an endpoint of an
    // exclusive-feeling domain cannot walk outside it, and `runCatching` per sample because a
    // function assembled from a graph can divide by zero at one x and be perfectly well behaved at
    // the next — one bad sample must not empty the table.
    registerH2Value<Functions.Fn> { fn ->
        val domain = fn.domain
        val usable = domain.min.isFinite() && domain.max.isFinite() && domain.max > domain.min
        // An empty, infinite or NaN domain still deserves a picture. 0..1 is the stdlib's own
        // `Domain.UNIT` default, and the summary says the sampling window was substituted so nobody
        // reads the table as though it were the declared domain.
        val lo = if (usable) domain.min else 0.0
        val hi = if (usable) domain.max else 1.0
        val label = fn.name?.takeIf { it.isNotBlank() } ?: "Fn"

        val rows = (0 until H2_FN_SAMPLES).map { i ->
            val x = lo + (hi - lo) * i / (H2_FN_SAMPLES - 1).toDouble()
            val y = runCatching { fn.evalSafe(x, outside = Double.NaN, edge = Functions.EdgeMode.CLAMP) }
                .getOrElse { Double.NaN }
            linkedMapOf<String, Any?>("x" to x, "f(x)" to y)
        }

        H2ValueSnapshot(
            value = rows,
            summary = "$label · $H2_FN_SAMPLES samples over " +
                (if (usable) "[${domain.min}, ${domain.max}]" else "[0, 1] (declared domain $domain is not samplable)") +
                (fn.graph?.let { " · graph" } ?: ""),
            title = label
        )
    }

    // ── FLine ────────────────────────────────────────────────────────
    // A polyline as its node table: one row per drawing instruction.
    //
    // `endpoint` reads the last stored control point rather than calling `Node.end`, which **throws
    // on a `Close`** — a `Close` is `Node(0)` and stores no point of its own, so its end is the
    // start of the subpath it closes and a lone node cannot know it (see `FLine.Node.end`'s KDoc).
    // A `Close` row therefore carries blanks, which is honest, instead of an exception.
    registerH2Value<FLine> { line ->
        val nodes = line.nodes.toList()
        val rows = nodes.mapIndexed { i, node ->
            val p: Vector3f? = node.storage.lastOrNull()
            linkedMapOf<String, Any?>(
                "i" to i,
                "kind" to (node::class.simpleName ?: "Node"),
                "points" to node.storage.size,
                "x" to p?.x,
                "y" to p?.y,
                "z" to p?.z,
                "props" to node.properties.size
            )
        }
        H2ValueSnapshot(
            value = rows,
            summary = "FLine · ${nodes.size} nodes · ${line.properties.size} properties (uid ${line.uid})",
            title = "FLine"
        )
    }

    // ── Throwable ────────────────────────────────────────────────────
    // An exception as message, cause chain and stack.
    //
    // `Throwable` is `java.lang`, so the platform-package narrowing in `H2Reflect` refuses it, and
    // the reflective default would in any case find `stackTrace`, `suppressed` and
    // `localizedMessage` — the JVM's names, not the three things a person wants. The cause chain is
    // walked with an identity guard because `initCause(this)` is legal and does happen.
    registerH2Value<Throwable> { t ->
        val causes = ArrayList<String>()
        val seen = ArrayList<Throwable>()
        var cur: Throwable? = t.cause
        while (cur != null && seen.none { it === cur }) {
            seen.add(cur)
            causes.add("${cur::class.simpleName ?: "Throwable"}: ${cur.message ?: "(no message)"}")
            cur = cur.cause
        }

        val frames = t.stackTrace.take(H2_STACK_FRAMES).map { it.toString() }
        H2ValueSnapshot(
            value = linkedMapOf<String, Any?>(
                "type" to (t::class.qualifiedName ?: t::class.java.name),
                "message" to (t.message ?: "(no message)"),
                "causes" to causes,
                "stack" to frames,
                "framesShown" to "${frames.size} of ${t.stackTrace.size}",
                "suppressed" to t.suppressed.map { "${it::class.simpleName}: ${it.message}" }
            ),
            summary = "${t::class.simpleName ?: "Throwable"}: ${t.message ?: "(no message)"}",
            title = t::class.simpleName ?: "Throwable"
        )
    }
}

/** One row per edge: the four kinds a box has, in one table, so they can be read against each other. */
internal fun boxEdgeRows(box: Box): List<Map<String, Any?>> {
    fun row(kind: String, other: Box) = linkedMapOf<String, Any?>(
        "edge" to kind,
        "name" to other.name.ifBlank { "(unnamed)" },
        "id" to other.id
    )
    val out = ArrayList<Map<String, Any?>>()
    box.parents.forEach { out.add(row("parent", it)) }
    box.children.forEach { out.add(row("child", it)) }
    box.nested.forEach { out.add(row("nested", it)) }
    box.nestedIn?.let { out.add(row("nestedIn", it)) }
    return out
}
