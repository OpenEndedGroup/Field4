import kotlin.math.*

val arcScale = 0.33
val cell = knightCellSize
val boardW = knightBoardWidth * cell
val boardH = knightBoardHeight * cell
val gap = 12.0
fun distance(a: Pair<Double,Double>, b: Pair<Double,Double>) = hypot(a.first-b.first,a.second-b.second)

// Signed IDs encode both arc identity and traversal direction.
fun entry(arcs: List<List<Pair<Double,Double>>>, id: Int) = if(id>0) arcs[id-1].first() else arcs[-id-1].last()
fun exit(arcs: List<List<Pair<Double,Double>>>, id: Int) = if(id>0) arcs[id-1].last() else arcs[-id-1].first()
fun joinLength(arcs: List<List<Pair<Double,Double>>>, route: List<Int>) =
    route.zipWithNext().sumOf { (a,b) -> distance(exit(arcs,a),entry(arcs,b)) }

// Try every starting arc in both directions, then improve each open route by 2-opt.
// Reversing a block also reverses all its arcs, preserving every internal join length.
fun shortRoute(arcs: List<List<Pair<Double,Double>>>): List<Int> {
    val n = arcs.size
    if(n==0) return emptyList()
    var best = (1..n).toList()
    var bestLength = joinLength(arcs,best)
    for(start in 1..n) for(direction in listOf(1,-1)) {
        val route = mutableListOf(start*direction)
        val used = BooleanArray(n)
        used[start-1] = true
        while(route.size<n) {
            val p = exit(arcs,route.last())
            var choice = 0
            var shortest = Double.POSITIVE_INFINITY
            for(k in 1..n) if(!used[k-1]) for(sign in listOf(1,-1)) {
                val d = distance(p,entry(arcs,k*sign))
                if(d<shortest) { shortest=d; choice=k*sign }
            }
            route.add(choice)
            used[abs(choice)-1] = true
        }
        while(true) {
            var bestDelta = -1e-9
            var lo = -1
            var hi = -1
            for(i in 0 until n) for(j in i until n) {
                var old = 0.0
                var fresh = 0.0
                if(i>0) {
                    old += distance(exit(arcs,route[i-1]),entry(arcs,route[i]))
                    fresh += distance(exit(arcs,route[i-1]),exit(arcs,route[j]))
                }
                if(j<n-1) {
                    old += distance(exit(arcs,route[j]),entry(arcs,route[j+1]))
                    fresh += distance(entry(arcs,route[i]),entry(arcs,route[j+1]))
                }
                if(fresh-old<bestDelta) { bestDelta=fresh-old; lo=i; hi=j }
            }
            if(lo<0) break
            val reversed = route.subList(lo,hi+1).reversed().map { -it }
            reversed.forEachIndexed { k,id -> route[lo+k]=id }
        }
        val length = joinLength(arcs,route)
        if(length<bestLength) { bestLength=length; best=route.toList() }
    }
    return best
}

// Geometry comes from the source; the only transform is scaling about the exact B.
val scaledRows = knightArcRows.mapIndexed { r,row -> row.mapIndexed { t,arcs ->
    val tour = knightTourRows[r][t]
    arcs.mapIndexed { k,arc ->
        val b = tour[k+1]
        val bx = (b % knightBoardWidth + 0.5)*cell
        val by = (b / knightBoardWidth + 0.5)*cell
        arc.map { p -> (bx+arcScale*(p.first-bx)) to (by+arcScale*(p.second-by)) }
    }
} }
val routeRows = scaledRows.map { row -> row.map { shortRoute(it) } }
var totalOriginalJoins = 0.0
var totalShortJoins = 0.0
var checkedArcs = 0
var pathCount = 0
val tiles = scaledRows.flatMapIndexed { r,row -> row.flatMapIndexed { t,arcs ->
    val route = routeRows[r][t]
    check(route.map { abs(it) }.sorted() == (1..arcs.size).toList())
    val originalLength = joinLength(arcs,(1..arcs.size).toList())
    val shortLength = joinLength(arcs,route)
    check(shortLength <= originalLength+1e-8)
    totalOriginalJoins += originalLength
    totalShortJoins += shortLength
    checkedArcs += route.size
    // Exactly one moveTo; all subsequent samples, including arc-to-arc joins, use lineTo.
    val path = fline {
        var first = true
        for(id in route) {
            val arc = arcs[abs(id)-1]
            val ordered = if(id>0) arc else arc.asReversed()
            for(p in ordered) {
                if(first) { moveTo(p.first,p.second); first=false }
                else lineTo(p.first,p.second)
            }
        }
        thickness=0.7
        color=vec(0.98,0.85,0.3,0.9)
    }
    pathCount++
    val background = fline {
        moveTo(0,0); lineTo(boardW,0); lineTo(boardW,boardH); lineTo(0,boardH); close()
        filled=true; stroked=false
        color=vec(1.0,1.0,1.0,0.06)
    }
    val offset=vec(t*(boardW+gap),r*(boardH+gap)+24.0,0.0)
    listOf(background,path).map { it+offset }
} }
val title = afield.ai.learning.pen.HersheyFont.load("futural").text("Quarter arcs / short joins / multistart + 2-opt",size=7.0).apply {
    thickness=0.5; color=vec(0.85,0.85,0.85,1.0)
}
lines(listOf(title)+tiles,w=900.0,h=500.0,fitToSize=true)
println("Visited " + checkedArcs + " arcs exactly once across " + pathCount + " open paths. Join length: " + totalOriginalJoins + " in tour order -> " + totalShortJoins + " after heuristic optimisation (not a global optimum guarantee).")
