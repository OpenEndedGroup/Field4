// Not a hand-built fixture: this is what afield.stdlib.arrow returns when you ask for a
// head at both ends. Its appendHead ends each arrowhead with close(), so the node list is
//   MoveTo LineTo | MoveTo LineTo LineTo Close | MoveTo LineTo LineTo Close
// and the first Close has a whole arrowhead after it.
val a = arrow(vec(0.0, 0.0), vec(300.0, 120.0), startHead = true, endHead = true)

val closes = a.nodes.withIndex().filter { it.value is FLine.Close }.map { it.index }
val interior = closes.filter { it < a.nodes.size - 1 }

val ghost = { l: FLine -> l.copy { thickness = 9.0; color = vec(0.45, 0.5, 0.58, 0.55) } }
val cyan = { l: FLine -> l.copy { thickness = 3.0; color = vec(0.35, 0.85, 1.0, 0.95) } }

// Everything here threw IndexOutOfBoundsException on this exact line before 2026-08-01.
val reversed = a.byReversing()
val subdivided = a.bySubdividing(1)
val dashes = a.byDashing(14f, 8f).map { cyan(it).copy { thickness = 3.0 } }
val hull = a.byConvexHull2D()

showPlaneGrid(
    "arrow(startHead, endHead)\n${a.nodes.size} nodes, Closes at $closes" to listOf(cyan(a)),
    "byReversing\n${reversed.nodes.size} nodes, ${reversed.nodes.count { it is FLine.Close }} Closes kept"
        to listOf(ghost(a), cyan(reversed)),
    "bySubdividing(1)\n${subdivided.nodes.count { it is FLine.Close }} Closes kept"
        to listOf(ghost(a), cyan(subdivided)),
    "byDashing(14, 8)\n${dashes.size} dashes" to (listOf(ghost(a)) + dashes),
    columns = 2,
    cellWidth = 420,
    cellHeight = 230,
)

// The sweep, run live: every one of these used to throw on this arrow.
val ops = listOf<Pair<String, (FLine) -> Any?>>(
    "byReversing" to { l -> l.byReversing() },
    "bySubdividing" to { l -> l.bySubdividing(1) },
    "bySimplifying" to { l -> l.bySimplifying(2f) },
    "byConvexHull2D" to { l -> l.byConvexHull2D() },
    "byFiltering" to { l -> l.byFiltering { _, _ -> true } },
    "windingNumber" to { l -> l.windingNumber(vec(150f, 60f)) },
    "contains" to { l -> l.contains(vec(150f, 60f)) },
    "centroid2D" to { l -> l.centroid2D() },
    "fast2DBounds" to { l -> l.fast2DBounds() },
    "byDashing" to { l -> l.byDashing(14f, 8f).size },
)
val results = ops.map { (name, op) ->
    val r = runCatching { op(a.copy()) }
    if (r.isSuccess) "  ok    $name" else "  THREW $name: ${r.exceptionOrNull()}"
}
val bad = results.count { it.contains("THREW") }

(listOf(
    "arrow(startHead = true, endHead = true): ${a.nodes.size} nodes, " +
        "Closes at $closes, of which ${interior.size} interior",
    "convex hull: ${hull.nodes.size} nodes",
    "",
    "$bad of ${ops.size} operations throw on it:",
) + results).joinToString("\n")
