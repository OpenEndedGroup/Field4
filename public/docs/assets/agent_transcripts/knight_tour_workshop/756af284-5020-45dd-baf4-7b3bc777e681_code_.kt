import kotlin.math.*

// Uniform scale about each triple's original B. 1.0 restores the source arcs.
val arcScale = 0.5
// Signed shift along the original A-to-C normal, applied after scaling.
val arcPush = 0.0
require(arcScale > 0.0)
val boardWidth = knightBoardWidth
val boardHeight = knightBoardHeight
val cell = knightCellSize
val boardW = boardWidth * cell
val boardH = boardHeight * cell
val gap = 12.0

// B is tour[k+1], not the midpoint of the sampled arc's list.
val arcCentres = knightArcRows.mapIndexed { r,row -> row.mapIndexed { t,arcs ->
    val tour = knightTourRows[r][t]
    check(arcs.size == tour.size - 2)
    arcs.indices.map { k ->
        val b = tour[k+1]
        ((b % boardWidth + 0.5) * cell) to ((b / boardWidth + 0.5) * cell)
    }
} }
val arcOffsets = knightArcRows.map { row -> row.map { arcs -> arcs.map { arc ->
    val dx = arc.last().first - arc.first().first
    val dy = arc.last().second - arc.first().second
    val length = hypot(dx,dy)
    require(length > 1e-9)
    (-dy / length * arcPush) to (dx / length * arcPush)
} } }
// Reuse the parent's sampling without mutating it or fitting circles again.
val scaledRows = knightArcRows.mapIndexed { r,row -> row.mapIndexed { t,arcs ->
    arcs.mapIndexed { k,arc ->
        val b = arcCentres[r][t][k]
        val offset = arcOffsets[r][t][k]
        arc.map { p ->
            (b.first + arcScale * (p.first - b.first) + offset.first) to
                (b.second + arcScale * (p.second - b.second) + offset.second)
        }
    }
} }

var checkedArcs = 0
var maxScaleError = 0.0
var maxAnchorError = 0.0
knightArcRows.forEachIndexed { r,row -> row.forEachIndexed { t,arcs -> arcs.forEachIndexed { k,arc ->
    val b = arcCentres[r][t][k]
    val offset = arcOffsets[r][t][k]
    val shiftedB = (b.first + offset.first) to (b.second + offset.second)
    val scaled = scaledRows[r][t][k]
    check(scaled.size == arc.size)
    val bIndex = arc.indexOf(b)
    check(bIndex >= 0) { "The source arc must contain B exactly" }
    val anchorError = hypot(scaled[bIndex].first-shiftedB.first, scaled[bIndex].second-shiftedB.second)
    maxAnchorError = max(maxAnchorError,anchorError)
    scaled.zip(arc).forEach { (q,p) ->
        val error = abs(hypot(q.first-shiftedB.first,q.second-shiftedB.second) - arcScale*hypot(p.first-b.first,p.second-b.second))
        maxScaleError = max(maxScaleError,error)
        check(error < 1e-9)
    }
    check(anchorError < 1e-9)
    checkedArcs++
} } }

val tiles = scaledRows.flatMapIndexed { r,row -> row.flatMapIndexed { t,arcs ->
    val background = fline {
        moveTo(0,0); lineTo(boardW,0); lineTo(boardW,boardH); lineTo(0,boardH); close()
        filled = true; stroked = false
        color = vec(1.0,1.0,1.0,0.06)
    }
    val path = fline {
        for(arc in arcs) arc.forEachIndexed { k,p ->
            if(k==0) moveTo(p.first,p.second) else lineTo(p.first,p.second)
        }
        thickness = 0.7
        color = vec(0.98,0.85,0.3,0.8)
    }
    val offset = vec(t*(boardW+gap),r*(boardH+gap)+24.0,0.0)
    listOf(background,path).map { it + offset }
} }
val title = afield.ai.learning.pen.HersheyFont.load("futural").text("Arc scale / " + arcScale + " about B / push " + arcPush, size=7.0).apply {
    thickness=0.5
    color=vec(0.85,0.85,0.85,1.0)
}
lines(listOf(title)+tiles, w=900.0, h=500.0, fitToSize=true)
println("Scaled " + checkedArcs + " arcs about B by " + arcScale + ". Maximum scale error: " + maxScaleError + "; B anchor error: " + maxAnchorError)
