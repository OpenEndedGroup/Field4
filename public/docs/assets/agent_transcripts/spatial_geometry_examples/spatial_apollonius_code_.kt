// CircularDirichlet minimises |x - p|^2 / w^2 — the multiplicatively weighted, or
// Apollonius, diagram. Setting two sites' scores equal and clearing denominators gives the
// equation of a CIRCLE, which degenerates to a line only when the weights match. So a cell
// here is not a polygon: its boundary is made of ArcPieces and LinePieces.
val positions = listOf(
    vec(0.0, 0.0), vec(2.2, 0.3), vec(0.9, 2.0), vec(-1.4, 1.1), vec(1.1, -1.6),
)
val clip = CircularDirichlet.Aabb(vec(-3.4, -3.4), vec(4.6, 4.6))

fun diagramOf(weights: List<Double>) =
    CircularDirichlet.build(positions.mapIndexed { i, p -> vec(p.x, p.y, weights[i]) })

val equal = diagramOf(List(5) { 1.0 })
val mixed = diagramOf(listOf(1.0, 1.35, 0.8, 1.1, 1.6))
val swallowed = diagramOf(listOf(1.0, 2.6, 1.0, 1.0, 1.0))

fun arcs(d: CircularDirichlet.Diagram) =
    d.cells.sumOf { c -> c.pieces.count { it is CircularDirichlet.ArcPiece } }
fun straights(d: CircularDirichlet.Diagram) =
    d.cells.sumOf { c -> c.pieces.count { it is CircularDirichlet.LinePiece } }
fun fullCircles(d: CircularDirichlet.Diagram) = d.cells.sumOf { c ->
    c.pieces.count { it is CircularDirichlet.ArcPiece && it.sweepAngle > 6.283 }
}
fun enclosed(d: CircularDirichlet.Diagram) = d.cells.count { c ->
    c.pieces.size == 1 &&
        (c.pieces[0] as? CircularDirichlet.ArcPiece)?.let { it.sweepAngle > 6.283 } == true
}

// All-or-nothing, because the piece type is decided by an exact equality of weights.
println("equal weights : ${straights(equal)} straight, ${arcs(equal)} arcs")
println("mixed weights : ${straights(mixed)} straight, ${arcs(mixed)} arcs")
println("one at 2.6    : ${fullCircles(swallowed)} full-circle pieces, ${enclosed(swallowed)} cells wholly enclosed")

// Every boundary sample is a score tie between its two sites, and no third site beats it.
fun score(s: Vec3, p: Vec2) =
    ((p.x - s.x) * (p.x - s.x) + (p.y - s.y) * (p.y - s.y)).toDouble() / (s.z * s.z).toDouble()
val sites = positions.mapIndexed { i, p -> vec(p.x, p.y, listOf(1.0, 1.35, 0.8, 1.1, 1.6)[i]) }
var worstTie = 0.0
mixed.cells.forEach { cell ->
    cell.pieces.forEach { piece ->
        val p = piece.samplePoint()
        val own = score(sites[cell.siteIndex], p)
        val other = score(sites[piece.neighborSite], p)
        worstTie = maxOf(worstTie, kotlin.math.abs(own - other) / maxOf(1.0, own))
    }
}
println()
println("worst relative score gap across every boundary sample: %.3e".format(worstTie))

val palette = okhslSet(5, saturation = 0.8, lightness = 0.66)
val cloud = pointCloudFLine(positions, color = vec(1.0, 1.0, 1.0, 0.95), pointSize = 1.6)
fun panel(d: CircularDirichlet.Diagram) =
    d.toFLines(clip = clip, maxChordError = 0.01f, palette = palette, thickness = 1.6) + cloud

showPlaneGrid(
    "equal weights — a plain Voronoi diagram\n${straights(equal)} straight bisectors, ${arcs(equal)} arcs" to panel(equal),
    "weights 1.0 / 1.35 / 0.8 / 1.1 / 1.6\n${arcs(mixed)} arcs, ${straights(mixed)} straight" to panel(mixed),
    "one site at weight 2.6\n${fullCircles(swallowed)} complete circles, ${enclosed(swallowed)} cells enclosed" to panel(swallowed),
    columns = 2,
    cellWidth = 430,
    cellHeight = 280,
)
