// Three stages derived from the level: blockout, traversal and placement.
// Each reads the level as it was when its build started, and reports what it measured.
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.math.*

val json = jacksonObjectMapper()
typealias Cell = Pair<Int, Int>

// Which room each cell is in, and which neighbouring cells a door joins.
class Grid(source: String) {
    val level = json.readValue<Map<String, Any?>>(source)
    val rooms = level["rooms"] as Map<String, List<Int>>
    val room = buildMap { for ((id, r) in rooms) for (x in r[0] until r[0] + r[2]) for (y in r[1] until r[1] + r[3]) put(x to y, id) }
    val doors = buildSet {
        for ((_, d) in level["doors"] as Map<String, List<Any>>) {
            val a = (d[0] as Number).toInt() to (d[1] as Number).toInt()
            val b = if (d[2] == "e") a.first + 1 to a.second else a.first to a.second + 1
            add(a to b); add(b to a)
        }
    }
    fun cell(p: List<Number>) = p[0].toInt() to p[1].toInt()
    val spawn = cell(level["spawn"] as List<Number>)
    val loot = (level["loot"] as Map<String, List<Number>>).mapValues { (_, l) -> cell(l) to l[2].toDouble() }
    fun step(a: Cell, b: Cell) = room[a] != null && room[b] != null && (room[a] == room[b] || (a to b) in doors)
}

val blockout = StudyBuildStage("blockout", "Blockout", "blockout-v1", setOf("level"),
    resultNotes = { r -> listOf(StudyBuildNotice(r.facts["summary"].toString(), StudyBuildNoticeKind.INFO)) }) { c ->
    val g = Grid(c.inputs.getValue("level"))
    // A floor slab per room, and a wall piece on every cell edge between two rooms, or a room and outside, that is not a door.
    val pieces = mutableListOf<List<Double>>()
    for ((_, r) in g.rooms) pieces += listOf(r[0].toDouble(), r[1].toDouble(), -0.1, r[2].toDouble(), r[3].toDouble(), 0.1)
    val walls = mutableSetOf<Pair<Cell, Cell>>()
    for ((a, _) in g.room) for (b in listOf(a.first + 1 to a.second, a.first to a.second + 1, a.first - 1 to a.second, a.first to a.second - 1))
        if (g.room[b] != g.room[a] && (a to b) !in g.doors) walls += listOf(a, b).sortedWith(compareBy({ it.first }, { it.second })).let { it[0] to it[1] }
    for ((a, b) in walls) pieces += if (a.second == b.second) listOf(b.first - 0.05, a.second.toDouble(), 0.0, 0.1, 1.0, 1.5)
        else listOf(a.first.toDouble(), b.second - 0.05, 0.0, 1.0, 0.1, 1.5)
    val mesh = pieces.map { p -> boxMesh(p[3], p[4], p[5]).translated(p[0] + p[3] / 2, p[1] + p[4] / 2, p[2] + p[5] / 2) }.reduce { m, n -> m + n }
    c.file("blockout.obj").writeText(mesh.toObj("blockout"))
    val area = g.rooms.values.sumOf { it[2] * it[3] }
    // With Manifold installed, the pieces are also joined into one solid and measured.
    val solid = if (!afield.manifold.ManifoldLibrary.isAvailable) null else afield.manifold.Solid.scope {
        val joined = afield.manifold.Solid.union(pieces.map { p -> afield.manifold.Solid.cube(p[3], p[4], p[5]).translated(p[0], p[1], p[2]) })
        mapOf("status" to joined.status.toString(), "volume" to joined.volume)
    }
    val summary = "${g.rooms.size} rooms, $area cells of floor, ${walls.size} wall pieces, ${g.doors.size / 2} door${if (g.doors.size == 2) "" else "s"}" +
        (solid?.let { ", joined into one solid of volume ${"%.1f".format(it["volume"])} (${it["status"]})" } ?: "; install Manifold to check it as one solid")
    StudyBuildProduct(listOf("blockout.obj"), mapOf("rooms" to g.rooms.size, "floorCells" to area, "wallPieces" to walls.size, "solid" to solid, "summary" to summary))
}

// The shortest walk on the grid from one cell to another, or null.
fun rough(g: Grid, from: Cell, to: Cell): List<Cell>? {
    val back = mutableMapOf(from to from)
    val queue = ArrayDeque(listOf(from))
    while (queue.isNotEmpty()) {
        val a = queue.removeFirst()
        if (a == to) return generateSequence(to) { if (it == from) null else back.getValue(it) }.toList().reversed()
        for (b in listOf(a.first + 1 to a.second, a.first to a.second + 1, a.first - 1 to a.second, a.first to a.second - 1))
            if (b !in back && g.step(a, b)) { back[b] = a; queue.add(b) }
    }
    return null
}

// Whether a straight line between two cell centres stays on the floor and passes walls only through doors.
fun clear(g: Grid, a: Cell, b: Cell): Boolean {
    val n = (max(abs(b.first - a.first), abs(b.second - a.second)) * 8).coerceAtLeast(1)
    var last = a
    for (i in 1..n) {
        val t = i / n.toDouble()
        val c = (a.first + (b.first - a.first) * t).roundToInt() to (a.second + (b.second - a.second) * t).roundToInt()
        if (c == last) continue
        val ok = if (c.first != last.first && c.second != last.second)
            (g.step(last, c.first to last.second) && g.step(c.first to last.second, c)) && (g.step(last, last.first to c.second) && g.step(last.first to c.second, c))
            else g.step(last, c)
        if (!ok) return false
        last = c
    }
    return true
}

// The rough walk pulled tight: from each point, straight to the furthest later point in clear view.
fun relaxed(g: Grid, path: List<Cell>): List<Cell> {
    val out = mutableListOf(path.first())
    var i = 0
    while (i < path.size - 1) {
        var j = path.size - 1
        while (j > i + 1 && !clear(g, path[i], path[j])) j--
        out += path[j]; i = j
    }
    return out
}

fun length(p: List<Cell>) = p.zipWithNext().sumOf { (a, b) -> hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) }
fun turn(p: List<Cell>) = p.windowed(3).sumOf { (a, b, c) ->
    val u = atan2((b.second - a.second).toDouble(), (b.first - a.first).toDouble())
    val v = atan2((c.second - b.second).toDouble(), (c.first - b.first).toDouble())
    abs(Math.toDegrees(atan2(sin(v - u), cos(v - u))))
}

val traversal = StudyBuildStage("traversal", "Traversal", "traversal-v1", setOf("level"), resultNotes = { r ->
    (r.facts["loot"] as Map<String, Map<String, Any?>>).map { (id, m) ->
        if (m["reachable"] != true) StudyBuildNotice("$id cannot be reached from the spawn.", StudyBuildNoticeKind.ERROR)
        else StudyBuildNotice("$id: ${m["relaxed"]} along the relaxed path (rough ${m["steps"]}, budget ${m["budget"]}), " +
            "turning ${m["turn"]}° (rough ${m["roughTurn"]}°), ${m["doors"]} door${if (m["doors"] == 1) "" else "s"}, seen from ${m["seenBy"]} of the other points." +
            (m["shape"]?.let { " $it" } ?: ""), if (m["shape"] != null || m["withinBudget"] != true) StudyBuildNoticeKind.WARNING else StudyBuildNoticeKind.INFO)
    }
}) { c ->
    val g = Grid(c.inputs.getValue("level"))
    val points = mapOf("spawn" to g.spawn) + g.loot.mapValues { it.value.first }
    val paths = mutableMapOf<String, Map<String, Any?>>()
    val measures = linkedMapOf<String, Map<String, Any?>>()
    for ((id, target) in g.loot.toSortedMap()) {
        val (cell, budget) = target
        val walk = rough(g, g.spawn, cell)
        if (walk == null) { measures[id] = mapOf("reachable" to false, "budget" to budget); continue }
        val tight = relaxed(g, walk)
        fun r1(x: Double) = (x * 10).roundToInt() / 10.0
        // The route-shape measure: a relaxed path should be no longer, and turn no more, than the walk it came from.
        val shape = when {
            length(tight) > length(walk) + 1e-9 -> "Its relaxed path is longer than its rough walk."
            turn(tight) > turn(walk) + 1e-9 -> "Its relaxed path turns more than its rough walk."
            else -> null
        }
        measures[id] = mapOf("reachable" to true, "steps" to walk.size - 1, "relaxed" to r1(length(tight)), "budget" to budget,
            "withinBudget" to (length(tight) <= budget), "turn" to turn(tight).roundToInt(), "roughTurn" to turn(walk).roundToInt(),
            "doors" to walk.zipWithNext().count { (a, b) -> g.room[a] != g.room[b] },
            "seenBy" to points.count { (other, p) -> other != id && clear(g, p, cell) }, "shape" to shape)
        paths[id] = mapOf("rough" to walk.map { listOf(it.first, it.second) }, "relaxed" to tight.map { listOf(it.first, it.second) })
    }
    val outOfReach = measures.filter { it.value["withinBudget"] != true }.map { (id, m) ->
        if (m["reachable"] != true) "$id (no path)" else "$id (${m["relaxed"]} > ${m["budget"]})" }
    c.file("traversal.json").writeText(json.writeValueAsString(mapOf("loot" to measures, "paths" to paths)))
    StudyBuildProduct(listOf("traversal.json"), mapOf("loot" to measures, "outOfReach" to outOfReach,
        "sightlines" to measures.values.sumOf { (it["seenBy"] as? Int) ?: 0 }))
}

// Placement waits until every loot point can be reached within its path budget.
val reachable = StudyBuildGate("reachable loot", dependencies = listOf("traversal")) { evidence ->
    val far = evidence.facts("traversal")["outOfReach"] as List<*>
    if (far.isEmpty()) null else "Out of reach within its path budget: ${far.joinToString()}."
}

val placement = StudyBuildStage("placement", "Placement", "placement-v1", setOf("reachable loot", "rule"), listOf("traversal"),
    inputHelp = StudyBuildInputHelp("Needs the loot rule", "Run the Loot rule box; placement follows its rule."),
    resultNotes = { r -> listOf(StudyBuildNotice((r.facts["placed"] as Map<*, *>).entries.joinToString { "${it.key} ${it.value}" } +
        "; ${r.facts["encounters"]} guarded.", StudyBuildNoticeKind.INFO)) }) { c ->
    val measured = json.readValue<Map<String, Any?>>(c.dependencyFile("traversal", "traversal.json"))["loot"] as Map<String, Map<String, Any?>>
    // The rule's input: each loot point with its relaxed path length and the doors on the way.
    val input = measured.toSortedMap().entries.joinToString(", ") { (id, m) -> "$id ${m["relaxed"]} ${m["doors"]}" }
    val place = QualifiedSource.evaluateKotlin(c.inputs.getValue("rule")) as (String) -> Map<String, String>
    val placed = place(input)
    c.file("rule-input.txt").writeText(input)
    c.file("placement.json").writeText(json.writeValueAsString(placed))
    StudyBuildProduct(listOf("rule-input.txt", "placement.json"), mapOf("placed" to placed, "encounters" to placed.values.count { "guarded" in it }))
}

val stages = listOf(blockout, traversal, placement)
val gates = listOf(reachable)
thisBox.properties["levelStages"] = stages
thisBox.properties["levelGates"] = gates

val builds = studyBuilds("stages", stages, gates = gates) {
    buildMap {
        (runCatching { thisBox.named("Level").getPropertyRaw("level") }.getOrNull() as? String)?.let { put("level", it) }
        (runCatching { thisBox.named("Loot rule").getPropertyRaw("lootRule") }.getOrNull() as? String)?.let { put("rule", it) }
    }
}
showStudyBuilds("stages")

// Draw the current traversal's relaxed paths over the plan, with the Level box's drawing, whenever it changes.
// The watcher stops when this runner closes, which happens when the box runs again or is stopped.
kotlin.concurrent.thread(isDaemon = true) {
    var shown: String? = null
    while (true) {
        val current = runCatching { builds.stages().first { it.id == "traversal" }.current }.getOrElse { break }
        if (current != null && current.id != shown) {
            val draw = thisBox.named("Level").properties["drawLevel"] as? (String, List<List<List<Number>>>) -> List<FLine>
            if (draw != null) {
                val found = json.readValue<Map<String, Any?>>(builds.currentFile("traversal", "traversal.json"))
                val paths = (found["paths"] as Map<String, Map<String, Any?>>).values.map { it["relaxed"] as List<List<Number>> }
                BoxStudies.show(thisBox, "paths", linesToHTML(draw(current.inputs.getValue("level"), paths), 440.0, 240.0, true))
                shown = current.id
            }
        }
        Thread.sleep(500)
    }
}
