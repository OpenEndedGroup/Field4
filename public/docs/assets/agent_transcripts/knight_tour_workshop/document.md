# Field Document: knight_tour_workshop

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. knight tours

<!-- field-box id="73b2ad1a-50a9-40ed-bef3-9456d8cf0d91" index="0" -->

```edn [field-box id="73b2ad1a-50a9-40ed-bef3-9456d8cf0d91" role="properties"]
{"id" "73b2ad1a-50a9-40ed-bef3-9456d8cf0d91" "index" 0 "name" "knight tours" "parents" ["root"] "children" ["cc20e577-5142-4a8b-8083-3c737fc5b084" "756af284-5020-45dd-baf4-7b3bc777e681" "2685e17b-ada6-4ad7-8de3-b127719b87d2" "0046694c-4358-43c9-913e-a17d8e55c785"] "properties" {"children" #{#afield/box "cc20e577-5142-4a8b-8083-3c737fc5b084" #afield/box "756af284-5020-45dd-baf4-7b3bc777e681" #afield/box "2685e17b-ada6-4ad7-8de3-b127719b87d2" #afield/box "0046694c-4358-43c9-913e-a17d8e55c785"} "parents" #{#afield/box "root"} "name" "knight tours" "_tombstone" false "frame" #afield/rect [60.0 60.0 240.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "73b2ad1a-50a9-40ed-bef3-9456d8cf0d91_code_.kt" "lines" 210 "characters" 8763 "sha256" "4f697dcb6aabc35a8a6e7a1dd164dc694920a492cc05f2b9d6ff3c1a40905db4"} {"name" "preamble" "language" "text" "lines" 15 "characters" 818 "sha256" "09c867bc9bfcc70225ff505869ec5376a2aba697d306780ce2ef86693c146d7b"}]}
```

```kotlin [field-property box="73b2ad1a-50a9-40ed-bef3-9456d8cf0d91" name="code" path="73b2ad1a-50a9-40ed-bef3-9456d8cf0d91_code_.kt"]
import kotlin.random.Random
import kotlin.math.*
import afield.runtime.execution.begin

// Squares are numbered y * w + x. A tour is an IntArray of every square, starting at s, ending at t.

fun knightNeighbours(w: Int, h: Int): Array<IntArray> {
    val jumps = listOf(1 to 2, 2 to 1, -1 to 2, -2 to 1, 1 to -2, 2 to -1, -1 to -2, -2 to -1)
    return Array(w * h) { i ->
        jumps.mapNotNull { (dx, dy) ->
            val x = i % w + dx
            val y = i / w + dy
            if (x in 0 until w && y in 0 until h) y * w + x else null
        }.toIntArray()
    }
}

// Every move changes colour, so the endpoints' colours decide whether a tour can exist at all.
fun parityAllows(w: Int, h: Int, s: Int, t: Int): Boolean {
    val colour = { i: Int -> (i % w + i / w) % 2 }
    return if (w * h % 2 == 0) colour(s) != colour(t) else colour(s) == 0 && colour(t) == 0
}

// Every tour from s to t, each exactly once, lazily. Depth-first, Warnsdorff order (random tie-break
// if `random` is given), pruned by "a square still needs a way in and a way out".
fun allKnightTours(w: Int, h: Int, s: Int, t: Int, random: Random? = null): Sequence<IntArray> = sequence {
    if (!parityAllows(w, h, s, t)) return@sequence
    val n = w * h
    val nb = knightNeighbours(w, h)
    val adjacent = Array(n) { a -> BooleanArray(n).also { row -> nb[a].forEach { row[it] = true } } }
    val visited = BooleanArray(n)
    val free = IntArray(n) { nb[it].size } // unvisited neighbours of each square
    val path = IntArray(n)

    fun visit(c: Int) { visited[c] = true; nb[c].forEach { free[it]-- } }
    fun unvisit(c: Int) { visited[c] = false; nb[c].forEach { free[it]++ } }

    // squares that were next to x but aren't next to y need two free neighbours (in and out); t needs one
    fun stillPossible(x: Int, y: Int) = nb[x].all { c ->
        visited[c] || adjacent[y][c] || free[c] >= (if (c == t) 1 else 2)
    }

    suspend fun SequenceScope<IntArray>.extend(len: Int) {
        if (len == n) { yield(path.copyOf()); return }
        val x = path[len - 1]
        var moves = nb[x].filter { !visited[it] && (it != t || len == n - 1) }
        // a neighbour with at most one other way out has to be entered now; two of them is a dead end
        val forced = moves.filter { it != t && free[it] <= 1 }
        if (forced.any { free[it] == 0 } || forced.size > 1) return
        if (forced.size == 1) moves = forced
        moves = (if (random != null) moves.shuffled(random) else moves).sortedBy { free[it] }
        for (y in moves) {
            visit(y)
            path[len] = y
            if (stillPossible(x, y)) extend(len + 1)
            unvisit(y)
        }
    }

    visit(s)
    path[0] = s
    extend(1)
}

// An endless stream of distinct tours from s to t: a random walk over complete paths from s by
// Pósa rotation, yielding each new one that happens to end at t. Not exhaustive. Each rotation
// swaps a single edge, and about one rotation in (w * h / 2) lands on t, so neighbours in the
// stream differ by a few dozen edges: related, not independent.
// Bounded by maxSteps rotations, so a pair of squares with no tour ends the stream instead of hanging it.
fun rotationKnightTours(w: Int, h: Int, s: Int, t: Int, random: Random = Random.Default,
                        maxSteps: Long = 100_000_000): Sequence<IntArray> = sequence {
    if (!parityAllows(w, h, s, t)) return@sequence
    val n = w * h
    if (n == 1) { yield(intArrayOf(s)); return@sequence }
    val nb = knightNeighbours(w, h)
    val path = IntArray(n)
    val pos = IntArray(n) { -1 }
    var len = 1
    path[0] = s
    pos[s] = 0

    // the end jumps to an earlier square path[i]; reverse everything after i to keep a path
    fun rotate(): Boolean {
        val x = path[len - 1]
        val pivots = nb[x].filter { pos[it] in 0 until len - 2 }
        if (pivots.isEmpty()) return false
        var a = pos[pivots.random(random)] + 1
        var b = len - 1
        while (a < b) {
            val swap = path[a]; path[a] = path[b]; path[b] = swap
            pos[path[a]] = a; pos[path[b]] = b
            a++; b--
        }
        return true
    }

    var steps = 0L
    while (len < n) {
        if (++steps > maxSteps) return@sequence
        val x = path[len - 1]
        val open = nb[x].filter { pos[it] < 0 }
        if (open.isEmpty()) {
            if (!rotate()) return@sequence
        } else {
            val y = open.minBy { c -> nb[c].count { pos[it] < 0 } + random.nextDouble() }
            path[len] = y; pos[y] = len; len++
        }
    }

    val seen = HashSet<Long>()
    while (++steps <= maxSteps) {
        if (path[n - 1] == t && seen.add(path.fold(17L) { h, v -> h * 1_000_003L + v })) yield(path.copyOf())
        if (!rotate()) return@sequence
    }
}

// Independent tours: a fresh randomised search for each one. Not deduplicated, but repeats are
// vanishingly rare from 8×8 up.
fun randomKnightTours(w: Int, h: Int, s: Int, t: Int, random: Random = Random.Default) =
    generateSequence { allKnightTours(w, h, s, t, random).firstOrNull() }

// ---- draw ----
val cell = 10.0

fun board(w: Int, h: Int) = fline {
    moveTo(0, 0); lineTo(w * cell, 0); lineTo(w * cell, h * cell); lineTo(0, h * cell); close()
    filled = true
    stroked = false
    color = vec(1.0, 1.0, 1.0, 0.06)
}

// Sample the circumcircle A -> B -> C, choosing the sweep that passes through B.
// Split at B so all three original points occur exactly in the sampled stroke.
fun threePointArc(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>): List<Pair<Double, Double>> {
    val (ax, ay) = a
    val bx = b.first - ax; val by = b.second - ay
    val cx = c.first - ax; val cy = c.second - ay
    val cross = bx * cy - by * cx
    if (abs(cross) < 1e-9) return listOf(a, b, c)
    val bb = bx * bx + by * by
    val cc = cx * cx + cy * cy
    val ux = (bb * cy - cc * by) / (2 * cross)
    val uy = (bx * cc - cx * bb) / (2 * cross)
    val radius = hypot(ux, uy)
    val start = atan2(-uy, -ux)
    val middle = atan2(by - uy, bx - ux)
    val end = atan2(cy - uy, cx - ux)
    val tau = 2 * PI
    fun positive(angle: Double) = ((angle % tau) + tau) % tau
    val toMiddle = positive(middle - start)
    val toEnd = positive(end - start)
    val sweep = if (toMiddle <= toEnd) toEnd else toEnd - tau
    val firstSweep = if (sweep > 0) toMiddle else toMiddle - tau
    val result = mutableListOf(a)
    fun segment(angle: Double, delta: Double, endpoint: Pair<Double, Double>) {
        val steps = max(1, ceil(abs(delta) / (PI / 48)).toInt())
        for (j in 1 until steps) {
            val theta = angle + delta * j / steps
            result.add((ax + ux + radius * cos(theta)) to (ay + uy + radius * sin(theta)))
        }
        result.add(endpoint)
    }
    segment(start, firstSweep, b)
    segment(middle, sweep - firstSweep, c)
    return result
}

// Original straight knight-move polyline; arc data remains available to child views.
fun tourLine(tour: IntArray, w: Int) = fline {
    tour.forEachIndexed { k,i ->
        val x = (i % w + 0.5)*cell
        val y = (i / w + 0.5)*cell
        if(k==0) moveTo(x,y) else lineTo(x,y)
    }
    thickness = 1.0
    color = vec(0.98,0.85,0.3,1.0)
}

// 8×8, corner (0, 0) to (7, 6). Rows: the exhaustive search in order, the rotation walk, random restarts.
val w = 8
val s = 0
val t = 6 * w + 7
val rows = listOf(
    allKnightTours(w, w, s, t).take(6).toList(),
    rotationKnightTours(w, w, s, t, Random(1)).take(6).toList(),
    randomKnightTours(w, w, s, t, Random(1)).take(6).toList(),
)
// Compute arc geometry once for the existing arc-based child views.
val arcRows = rows.map { row -> row.map { tour ->
    val points = tour.map { i -> ((i % w + 0.5) * cell) to ((i / w + 0.5) * cell) }
    points.windowed(3).map { q -> threePointArc(q[0],q[1],q[2]) }
} }
// Typed graph properties, inherited by the connected growth view.
prop<List<List<IntArray>>>(knightTourRows) = rows
prop<List<List<List<List<Pair<Double, Double>>>>>>(knightArcRows) = arcRows
prop<Int>(knightBoardWidth) = w
prop<Int>(knightBoardHeight) = w
prop<Double>(knightCellSize) = cell

val gap = 12.0
val tiles = rows.flatMapIndexed { r, row ->
    row.flatMapIndexed { c, tour ->
        listOf(board(w, w), tourLine(tour, w)).map { it + vec(c * (w * cell + gap), r * (w * cell + gap), 0.0) }
    }
}
lines(*tiles.toTypedArray(), fitToSize = true)

// Refresh the connected derived views after publishing all shared properties.
val derivedViewIds = setOf("2685e17b-ada6-4ad7-8de3-b127719b87d2", "756af284-5020-45dd-baf4-7b3bc777e681", "cc20e577-5142-4a8b-8083-3c737fc5b084", "0046694c-4358-43c9-913e-a17d8e55c785")
children.filter { it.id in derivedViewIds }.forEach { it.begin() }
```

```text [field-property box="73b2ad1a-50a9-40ed-bef3-9456d8cf0d91" name="preamble"]
var Box.knightTourRows by Inheritance<List<List<IntArray>>>() /**/
val Once.knightTourRows by Onceing<List<List<IntArray>>>() /**/
val Why.knightTourRows by Whying() /**/
var Box.knightArcRows by Inheritance<List<List<List<List<Pair<Double, Double>>>>>>() /**/
val Once.knightArcRows by Onceing<List<List<List<List<Pair<Double, Double>>>>>>() /**/
val Why.knightArcRows by Whying() /**/
var Box.knightBoardWidth by Inheritance<Int>() /**/
val Once.knightBoardWidth by Onceing<Int>() /**/
val Why.knightBoardWidth by Whying() /**/
var Box.knightBoardHeight by Inheritance<Int>() /**/
val Once.knightBoardHeight by Onceing<Int>() /**/
val Why.knightBoardHeight by Whying() /**/
var Box.knightCellSize by Inheritance<Double>() /**/
val Once.knightCellSize by Onceing<Double>() /**/
val Why.knightCellSize by Whying() /**/
```

## 2. knight tour glued arcs

<!-- field-box id="cc20e577-5142-4a8b-8083-3c737fc5b084" index="1" -->

```edn [field-box id="cc20e577-5142-4a8b-8083-3c737fc5b084" role="properties"]
{"id" "cc20e577-5142-4a8b-8083-3c737fc5b084" "index" 1 "name" "knight tour glued arcs" "parents" ["root" "73b2ad1a-50a9-40ed-bef3-9456d8cf0d91"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "73b2ad1a-50a9-40ed-bef3-9456d8cf0d91"} "name" "knight tour glued arcs" "_tombstone" false "frame" #afield/rect [620.0 60.0 240.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cc20e577-5142-4a8b-8083-3c737fc5b084_code_.kt" "lines" 116 "characters" 5075 "sha256" "6aa2be65eb74bccdca5327ef9041ab1f898ec1ba78539a3569df3069d596b767"} {"name" "preamble" "language" "text" "lines" 15 "characters" 1508 "sha256" "6a3824d08189eef7b04e5aa0fdde9553115de3bcc5d74182a7b29127cc985954"}]}
```

```kotlin [field-property box="cc20e577-5142-4a8b-8083-3c737fc5b084" name="code" path="cc20e577-5142-4a8b-8083-3c737fc5b084_code_.kt"]
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
```

```text [field-property box="cc20e577-5142-4a8b-8083-3c737fc5b084" name="preamble"]
var Box.knightTourRows by Inheritance<List<List<IntArray>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightTourRows by Onceing<List<List<IntArray>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightTourRows by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightArcRows by Inheritance<List<List<List<List<Pair<Double, Double>>>>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightArcRows by Onceing<List<List<List<List<Pair<Double, Double>>>>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightArcRows by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightBoardWidth by Inheritance<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightBoardWidth by Onceing<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightBoardWidth by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightBoardHeight by Inheritance<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightBoardHeight by Onceing<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightBoardHeight by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightCellSize by Inheritance<Double>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightCellSize by Onceing<Double>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightCellSize by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
```

## 3. knight tour perpendicular arcs

<!-- field-box id="756af284-5020-45dd-baf4-7b3bc777e681" index="2" -->

```edn [field-box id="756af284-5020-45dd-baf4-7b3bc777e681" role="properties"]
{"id" "756af284-5020-45dd-baf4-7b3bc777e681" "index" 2 "name" "knight tour perpendicular arcs" "parents" ["root" "73b2ad1a-50a9-40ed-bef3-9456d8cf0d91"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "73b2ad1a-50a9-40ed-bef3-9456d8cf0d91"} "name" "knight tour perpendicular arcs" "_tombstone" false "frame" #afield/rect [340.0 240.0 240.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "756af284-5020-45dd-baf4-7b3bc777e681_code_.kt" "lines" 86 "characters" 3511 "sha256" "cdc73da4956dac8f51868d6eb8f10c269f490a133dde9c385f9aef9320911b1f"} {"name" "preamble" "language" "text" "lines" 15 "characters" 1508 "sha256" "6a3824d08189eef7b04e5aa0fdde9553115de3bcc5d74182a7b29127cc985954"}]}
```

```kotlin [field-property box="756af284-5020-45dd-baf4-7b3bc777e681" name="code" path="756af284-5020-45dd-baf4-7b3bc777e681_code_.kt"]
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
```

```text [field-property box="756af284-5020-45dd-baf4-7b3bc777e681" name="preamble"]
var Box.knightTourRows by Inheritance<List<List<IntArray>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightTourRows by Onceing<List<List<IntArray>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightTourRows by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightArcRows by Inheritance<List<List<List<List<Pair<Double, Double>>>>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightArcRows by Onceing<List<List<List<List<Pair<Double, Double>>>>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightArcRows by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightBoardWidth by Inheritance<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightBoardWidth by Onceing<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightBoardWidth by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightBoardHeight by Inheritance<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightBoardHeight by Onceing<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightBoardHeight by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightCellSize by Inheritance<Double>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightCellSize by Onceing<Double>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightCellSize by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
```

## 4. knight tour arc growth

<!-- field-box id="2685e17b-ada6-4ad7-8de3-b127719b87d2" index="3" -->

```edn [field-box id="2685e17b-ada6-4ad7-8de3-b127719b87d2" role="properties"]
{"id" "2685e17b-ada6-4ad7-8de3-b127719b87d2" "index" 3 "name" "knight tour arc growth" "parents" ["root" "73b2ad1a-50a9-40ed-bef3-9456d8cf0d91"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "73b2ad1a-50a9-40ed-bef3-9456d8cf0d91"} "name" "knight tour arc growth" "_tombstone" false "frame" #afield/rect [340.0 60.0 240.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "2685e17b-ada6-4ad7-8de3-b127719b87d2_code_.kt" "lines" 86 "characters" 3641 "sha256" "2aa0bae2ece267987696a3c6df7978c632f2811d74e17eac822eaf92ddae3d17"} {"name" "preamble" "language" "text" "lines" 15 "characters" 1508 "sha256" "6a3824d08189eef7b04e5aa0fdde9553115de3bcc5d74182a7b29127cc985954"}]}
```

```kotlin [field-property box="2685e17b-ada6-4ad7-8de3-b127719b87d2" name="code" path="2685e17b-ada6-4ad7-8de3-b127719b87d2_code_.kt"]
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
```

```text [field-property box="2685e17b-ada6-4ad7-8de3-b127719b87d2" name="preamble"]
var Box.knightTourRows by Inheritance<List<List<IntArray>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightTourRows by Onceing<List<List<IntArray>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightTourRows by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightArcRows by Inheritance<List<List<List<List<Pair<Double, Double>>>>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightArcRows by Onceing<List<List<List<List<Pair<Double, Double>>>>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightArcRows by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightBoardWidth by Inheritance<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightBoardWidth by Onceing<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightBoardWidth by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightBoardHeight by Inheritance<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightBoardHeight by Onceing<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightBoardHeight by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightCellSize by Inheritance<Double>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightCellSize by Onceing<Double>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightCellSize by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
```

## 5. knight tour line experiments

<!-- field-box id="0046694c-4358-43c9-913e-a17d8e55c785" index="4" -->

```edn [field-box id="0046694c-4358-43c9-913e-a17d8e55c785" role="properties"]
{"id" "0046694c-4358-43c9-913e-a17d8e55c785" "index" 4 "name" "knight tour line experiments" "parents" ["root" "73b2ad1a-50a9-40ed-bef3-9456d8cf0d91"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "73b2ad1a-50a9-40ed-bef3-9456d8cf0d91"} "name" "knight tour line experiments" "_tombstone" false "frame" #afield/rect [60.0 240.0 240.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "0046694c-4358-43c9-913e-a17d8e55c785_code_.kt" "lines" 113 "characters" 6005 "sha256" "e38435efe1bcee87d692811775a94fb36f18be115574e5306b337e942ba76ef8"} {"name" "preamble" "language" "text" "lines" 15 "characters" 1508 "sha256" "6a3824d08189eef7b04e5aa0fdde9553115de3bcc5d74182a7b29127cc985954"}]}
```

```kotlin [field-property box="0046694c-4358-43c9-913e-a17d8e55c785" name="code" path="0046694c-4358-43c9-913e-a17d8e55c785_code_.kt"]
import kotlin.math.*
import afield.math.linalg.*

// Zipped layers: use [a, b, c] or a scalar. Short lists hold their last value.
// All geometry parameters are deliberately UNCLAMPED.
// Trims are fractions of the original move removed from each end.
val startTrim = [0]
val endTrim = [0]
val nextPull = [5.0]
val previousPull = [-15.0, -10.0, -5.0, 0, 5.0, 10.0, 15.0]
val rotationRadians = [0]
val perpendicularOffset = [0.0]
val alongSlide = 7 { it*4 }
val strokeWidth = 7 { 1/(1+it.d) }
val gap = 12.0
val cell = knightCellSize
val w = knightBoardWidth
val h = knightBoardHeight
val boardW = w*cell
val boardH = h*cell

data class StrokeParameters(
    val startTrim: Double=0.0, val endTrim: Double=0.0,
    val nextPull: Double=0.0, val previousPull: Double=0.0,
    val rotation: Double=0.0, val offset: Double=0.0, val slide: Double=0.0
)
data class QuadraticStroke(val start: Vector2f, val control: Vector2f, val end: Vector2f)
// Convert only at the parameter boundary; geometry stays strongly typed.
fun parameterValues(value: Any): List<Double> = when(value) {
    is Number -> [value.toDouble()]
    is List<*> -> {
        require(value.isNotEmpty()) { "Parameter lists need at least one value." }
        value.map { require(it is Number) { "Parameter values must be numbers." }; it.toDouble() }
    }
    else -> error("Use a number or a nonempty list of numbers.")
}
fun held(values: List<Double>, index: Int) = values.getOrElse(index) { values.last() }
val channels = [startTrim,endTrim,nextPull,previousPull,rotationRadians,perpendicularOffset,alongSlide,strokeWidth].map { parameterValues(it) }
val layerCount = channels.maxOf { it.size }
val parameterSets = (0 until layerCount).map { i ->
    StrokeParameters(held(channels[0],i),held(channels[1],i),held(channels[2],i),held(channels[3],i),held(channels[4],i),held(channels[5],i),held(channels[6],i))
}
val layerWidths = (0 until layerCount).map { held(channels[7],it) }
// Hold-last and scalar checks, including deliberately out-of-range values.
check(parameterValues(-1.0) == [-1.0])
check((0..3).map { held(parameterValues([11.0,-1.0]),it) } == [11.0,-1.0,-1.0,-1.0])
check((0..2).map { held(parameterValues(4.0),it) } == [4.0,4.0,4.0])

// For move A->B, previous means the point before A; next means the point after B.
// Control pulls use unit directions from the trimmed midpoint to those ORIGINAL neighbours.
// Zero-length pull directions contribute zero; neither pulls nor trims are restricted.
fun makeStroke(a: Vector2f,b: Vector2f,previous: Vector2f?,next: Vector2f?,p: StrokeParameters): QuadraticStroke {
    val delta=b-a
    val start=a+delta*p.startTrim
    val end=b-delta*p.endTrim
    val middle=(start+end)*0.5
    var control=middle
    if(next!=null) control=control+(next-middle).normalized(1e-12f)*p.nextPull
    if(previous!=null) control=control+(previous-middle).normalized(1e-12f)*p.previousPull
    // Axes follow the ORIGINAL A->B orientation, then rotate. Over-trimming does not flip them.
    val rotation=quat(p.rotation)
    val axis=delta.normalized(1e-12f)*rotation
    val normal=vec(-axis.y(),axis.x())
    val translation=normal*p.offset+axis*p.slide
    fun transform(q: Vector2f) = middle+(q-middle)*rotation+translation
    return QuadraticStroke(transform(start),transform(control),transform(end))
}

// Mathematical checks include extension, reversed endpoints and parameters outside [0,1].
fun near(a: Vector2f,b: Vector2f) = (a-b).length()<1e-5
val aTest=vec(0.0,0.0)
val bTest=vec(10.0,0.0)
val identity=makeStroke(aTest,bTest,null,null,StrokeParameters())
check(near(identity.start,aTest) && near(identity.end,bTest) && near(identity.control,vec(5.0,0.0)))
val extended=makeStroke(aTest,bTest,null,null,StrokeParameters(startTrim=-1.0,endTrim=-1.0))
check(near(extended.start,vec(-10.0,0.0)) && near(extended.end,vec(20.0,0.0)))
val reversed=makeStroke(aTest,bTest,null,null,StrokeParameters(startTrim=2.0,endTrim=2.0))
check(near(reversed.start,vec(20.0,0.0)) && near(reversed.end,vec(-10.0,0.0)))
val moved=makeStroke(aTest,bTest,null,null,StrokeParameters(rotation=PI/2,offset=2.0,slide=3.0))
check(near(moved.start,vec(3.0,-2.0)) && near(moved.end,vec(3.0,8.0)))
val pulled=makeStroke(aTest,bTest,null,vec(10.0,10.0),StrokeParameters(nextPull=11.0))
check(abs((pulled.control-vec(5.0,0.0)).length()-11.0)<1e-5)
val pushed=makeStroke(aTest,bTest,vec(0.0,10.0),null,StrokeParameters(previousPull=-11.0))
check(near(pushed.control,vec(5.0,0.0)+(vec(5.0,-10.0).normalized(1e-12f)*11.0)))

val pointRows = knightTourRows.map { row -> row.map { tour ->
    tour.map { i -> vec((i%w+0.5)*cell,(i/w+0.5)*cell) }
} }
val strokeRows = pointRows.map { row -> row.map { points ->
    parameterSets.map { parameters -> (0 until points.size-1).map { k ->
        makeStroke(points[k],points[k+1],points.getOrNull(k-1),points.getOrNull(k+2),parameters)
    } }
} }
val tiles = strokeRows.flatMapIndexed { r,row -> row.flatMapIndexed { c,layers ->
    val background=fline {
        moveTo(0,0); lineTo(boardW,0); lineTo(boardW,boardH); lineTo(0,boardH); close()
        filled=true; stroked=false; color=vec(1.0,1.0,1.0,0.06)
    }
    val paths=layers.mapIndexed { layerIndex,strokes -> fline {
        for(q in strokes) {
            moveTo(q.start.x,q.start.y)
            // Exact quadratic represented as a cubic (no sampling approximation).
            val c1=q.start+(q.control-q.start)*(2.0/3.0)
            val c2=q.end+(q.control-q.end)*(2.0/3.0)
            cubicTo(c1.x,c1.y,c2.x,c2.y,q.end.x,q.end.y)
        }
        thickness=layerWidths[layerIndex]
        color=vec(9.98/(10+r+c),0.65,0.3,0.9)
    } }
    (listOf(background)+paths).map { it+vec(c*(w*cell+gap),r*(h*cell+gap),0.0) }
} }
lines(*tiles.toTypedArray(),fitToSize=true)
println("Parametric quadratic moves: " + strokeRows.sumOf { row -> row.sumOf { layers -> layers.sumOf { it.size } } } + ". " + parameterSets + ". Layers: $layerCount. Unclamped geometry and hold-last checks passed; inherited tours unchanged.")
```

```text [field-property box="0046694c-4358-43c9-913e-a17d8e55c785" name="preamble"]
var Box.knightTourRows by Inheritance<List<List<IntArray>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightTourRows by Onceing<List<List<IntArray>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightTourRows by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightArcRows by Inheritance<List<List<List<List<Pair<Double, Double>>>>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightArcRows by Onceing<List<List<List<List<Pair<Double, Double>>>>>>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightArcRows by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightBoardWidth by Inheritance<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightBoardWidth by Onceing<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightBoardWidth by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightBoardHeight by Inheritance<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightBoardHeight by Onceing<Int>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightBoardHeight by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
var Box.knightCellSize by Inheritance<Double>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Once.knightCellSize by Onceing<Double>() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
val Why.knightCellSize by Whying() /*from:73b2ad1a-50a9-40ed-bef3-9456d8cf0d91*/ /**/
```

