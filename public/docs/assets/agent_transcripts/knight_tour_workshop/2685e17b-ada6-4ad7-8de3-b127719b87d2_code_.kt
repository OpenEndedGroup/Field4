import kotlin.math.*
import afield.ui.output.displayClear

// Selection in the parent's computed rows (0: exhaustive, 1: rotation, 2: random).
// Change either selector and rerun this box; no tour search or arc sampling is repeated.
val sourceRow = 0
val sourceTour = 0
val tour = knightTourRows[sourceRow][sourceTour]
val arcs = knightArcRows[sourceRow][sourceTour]
val boardWidth = knightBoardWidth
val boardHeight = knightBoardHeight
val cell = knightCellSize
val boardW = boardWidth * cell
val boardH = boardHeight * cell
val columns = 8
val points = tour.map { i -> ((i % boardWidth + 0.5) * cell) to ((i / boardWidth + 0.5) * cell) }
val triples = points.windowed(3)
check(tour.size == boardWidth * boardHeight && tour.toSet().size == tour.size)
check(arcs.size == tour.size - 2)
arcs.forEachIndexed { i, arc ->
    check(arc.first() == triples[i][0] && arc.last() == triples[i][2] && triples[i][1] in arc)
}
// All geometry, markers and lettering are FLines. No hand-authored SVG/HTML.
val font = afield.ai.learning.pen.HersheyFont.load("futural")
val tiles = arcs.indices.flatMap { stage ->
    val background = fline {
        moveTo(0,0); lineTo(boardW,0); lineTo(boardW,boardH); lineTo(0,boardH); close()
        filled = true; stroked = false
        color = vec(1.0,1.0,1.0,0.06)
    }
    val grid = fline {
        for(k in 1 until boardWidth) {
            val v = k * cell
            moveTo(v,0); lineTo(v,boardH)
        }
        for(k in 1 until boardHeight) {
            val v = k * cell
            moveTo(0,v); lineTo(boardW,v)
        }
        thickness = 0.35
        color = vec(1.0,1.0,1.0,0.045)
    }
    val earlier = fline {
        for(i in 0 until stage) {
            arcs[i].forEachIndexed { k,p ->
                if(k==0) moveTo(p.first,p.second) else lineTo(p.first,p.second)
            }
        }
        thickness = 0.55
        color = vec(0.71,0.65,0.46,0.58)
    }
    val newest = fline {
        arcs[stage].forEachIndexed { k,p ->
            if(k==0) moveTo(p.first,p.second) else lineTo(p.first,p.second)
        }
        thickness = 1.15
        color = vec(1.0,0.855,0.31,1.0)
    }
    val markers = triples[stage].mapIndexed { j,p ->
        fline {
            for(k in 0 until 20) {
                val theta = 2*PI*k/20
                val x = p.first+1.25*cos(theta)
                val y = p.second+1.25*sin(theta)
                if(k==0) moveTo(x,y) else lineTo(x,y)
            }
            close(); filled=true; stroked=false
            color = if(j==1) vec(0.44,0.88,0.90,1.0) else vec(1.0,0.96,0.84,1.0)
        }
    }
    val label = font.text((stage+1).toString().padStart(2,'0') + " / " + (stage+1) + "-" + (stage+3), size=5.0).apply {
        thickness = 0.45
        color = vec(0.83,0.83,0.83,1.0)
    } + vec(0.0,boardH+14.0,0.0)
    val offset = vec((stage%columns)*(boardW+24.0),(stage/columns)*(boardH+36.0)+32.0,0.0)
    (listOf(background,grid,earlier,newest) + markers + label).map { it + offset }
}
val title = font.text("Tour " + (sourceRow+1) + ":" + (sourceTour+1) + " / " + arcs.size + " growing arc stages", size=9.0).apply {
    thickness=0.65; color=vec(0.9,0.9,0.9,1.0)
}
val legend = font.text("Gold: new arc   Muted: earlier   White: A/C   Cyan: B   Read across, then down", size=5.0).apply {
    thickness=0.4; color=vec(0.65,0.65,0.65,1.0)
} + vec(0.0,15.0,0.0)
displayClear("growth") // Remove the obsolete hand-authored SVG output.
lines(listOf(title,legend)+tiles, w=900.0, h=1050.0, fitToSize=true)
println("Shared tour " + sourceRow + ":" + sourceTour + "; " + arcs.size + " cumulative FLine stages; geometry inherited, not recomputed.")
