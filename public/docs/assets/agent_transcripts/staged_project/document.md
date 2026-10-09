# Field Document: staged_project

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Loot rule

<!-- field-box id="staged_rule" index="0" -->

### Narrative

From `code`

The rule that places loot. It changes only after a replay on real placements: see
"Change a rule without breaking what already works" in the Field docs.

```edn [field-box id="staged_rule" role="properties"]
{"id" "staged_rule" "index" 0 "name" "Loot rule" "parents" ["root"] "children" ["staged_builds"] "properties" {"children" #{#afield/box "staged_builds"} "parents" #{#afield/box "root"} "name" "Loot rule" "_tombstone" false "frame" #afield/rect [1660.0 40.0 300.0 100.0] "autorun" true} "blockProperties" [{"name" "code" "language" "kotlin" "path" "staged_rule_code_.kt" "lines" 36 "characters" 1975 "sha256" "1c62819dccf953a3af55664ca5e7d644ee419f04654810c6e1c0a1a40d898e13"}]}
```

```kotlin [field-property box="staged_rule" name="code" path="staged_rule_code_.kt"]
// The rule that places loot. It changes only after a replay on real placements: see
// "Change a rule without breaking what already works" in the Field docs.
val rule = """
// Placement for one loot point, from the length of its path from the spawn and the doors on the way.
fun tier(steps: Double, doors: Int) = when {
    doors >= 3 -> "epic guarded"
    doors >= 2 || steps > 16 -> "rare"
    else -> "common"
}

// Checks: everything below this line stays as it is.
fun place(points: String): Map<String, String> {
    val placed = points.split(", ").associate { point ->
        val (id, steps, doors) = point.split(" ")
        id to tier(steps.toDouble(), doors.toInt())
    }.toSortedMap()
    for ((id, placement) in placed)
        check(placement in setOf("common", "rare", "epic guarded")) { id + " got " + placement + ", which is not a placement" }
    check("epic guarded" in placed.values) { "no loot point is epic" }
    return placed
}
::place
""".trimStart()

val loot = qualifiedSource("loot", rule, label = "Loot rule",
    frozen = listOf(FrozenRegion.after("// Checks: everything below this line stays as it is.")),
    control = QualifiedControl("shallow", "L1 4.0 0, L2 6.5 1", "no loot point is epic"),
    fixtures = mapOf(
        "keep" to QualifiedFixture("L1 12.4 2, L2 19.1 3, L3 5.0 1", mapOf("L1" to "rare", "L2" to "epic guarded", "L3" to "common")),
        "crypt" to QualifiedFixture("L1 8.2 1, L2 17.5 1, L3 24.0 3", mapOf("L1" to "common", "L2" to "rare", "L3" to "epic guarded")),
        "mill" to QualifiedFixture("L1 21.3 3, L2 9.9 2", mapOf("L1" to "epic guarded", "L2" to "rare"))),
    // A placement build in this document can be kept as a fixture, with what it placed.
    retain = { attempt -> QualifiedFixture(attempt.file("rule-input.txt").readText(), attempt.record.facts["placed"]) },
    replayVersion = "loot-replay-1") { replay ->
    QualifiedSource.evaluateKotlin(replay.source) as (String) -> Map<String, String>
}
```

## 2. Level

<!-- field-box id="staged_level" index="1" -->

### Narrative

From `code`

The level: rooms and doors on a grid, kept as JSON in this box's `level` property.
A room is [x, y, w, h] in cells. A door is [x, y, side]: an opening from cell (x, y)
to its neighbour on side "e" (east) or "s" (south). Loot is [x, y, path budget].

```edn [field-box id="staged_level" role="properties"]
{"id" "staged_level" "index" 1 "name" "Level" "parents" ["root"] "children" ["staged_builds"] "properties" {"children" #{#afield/box "staged_builds"} "parents" #{#afield/box "root"} "name" "Level" "_tombstone" false "frame" #afield/rect [760.0 40.0 300.0 100.0] "autorun" true} "blockProperties" [{"name" "code" "language" "kotlin" "path" "staged_level_code_.kt" "lines" 134 "characters" 7966 "sha256" "6b01854fea14e59deed06c9af6546c61b12084b0b79106c0a5ad1d99dec7a018"}]}
```

```kotlin [field-property box="staged_level" name="code" path="staged_level_code_.kt"]
// The level: rooms and doors on a grid, kept as JSON in this box's `level` property.
// A room is [x, y, w, h] in cells. A door is [x, y, side]: an opening from cell (x, y)
// to its neighbour on side "e" (east) or "s" (south). Loot is [x, y, path budget].
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

val json = jacksonObjectMapper()
val first = """
{"rooms": {"hall": [0, 0, 6, 4], "yard": [6, 0, 6, 4], "armory": [12, 0, 5, 4],
           "crypt": [0, 4, 6, 5], "vault": [6, 4, 6, 5], "well": [12, 4, 5, 5]},
 "doors": {"d1": [5, 2, "e"], "d2": [11, 1, "e"], "d3": [2, 3, "s"], "d4": [8, 3, "s"]},
 "spawn": [1, 1],
 "loot": {"L1": [15, 2, 24], "L2": [15, 7, 20], "L3": [2, 7, 16]}}
"""
Box.ioPersistentProperties.add("level")
Box.ioSeparateProperties["level"] = "json"
if (getPropertyRaw("level") == null) setPropertyRaw("level", json.writeValueAsString(json.readTree(first)))

// Every check a level must pass. Edits that break one are refused and change nothing.
fun problems(level: Map<String, Any?>): List<String> {
    val out = mutableListOf<String>()
    val rooms = (level["rooms"] as? Map<String, List<Int>>).orEmpty()
    val cells = mutableMapOf<Pair<Int, Int>, String>()
    for ((id, r) in rooms) {
        if (r.size != 4 || r[2] < 1 || r[3] < 1) { out += "room $id needs [x, y, w, h] with w and h of at least 1"; continue }
        for (x in r[0] until r[0] + r[2]) for (y in r[1] until r[1] + r[3])
            cells.put(x to y, id)?.let { out += "rooms $it and $id overlap at ($x, $y)" }
    }
    fun roomAt(x: Any?, y: Any?) = cells[(x as Number).toInt() to (y as Number).toInt()]
    for ((id, d) in (level["doors"] as? Map<String, List<Any>>).orEmpty()) {
        val (x, y) = (d[0] as Number).toInt() to (d[1] as Number).toInt()
        val other = when (d[2]) { "e" -> roomAt(x + 1, y); "s" -> roomAt(x, y + 1); else -> null }
        if (roomAt(x, y) == null || other == null || other == roomAt(x, y)) out += "door $id at ($x, $y) does not open between two rooms"
    }
    val spawn = level["spawn"] as? List<Any>
    if (spawn == null || roomAt(spawn[0], spawn[1]) == null) out += "the spawn must be inside a room"
    for ((id, l) in (level["loot"] as? Map<String, List<Any>>).orEmpty())
        if (roomAt(l[0], l[1]) == null) out += "loot $id must be inside a room"
    if ((level["loot"] as? Map<*, *>).isNullOrEmpty()) out += "a level needs at least one loot point"
    return out
}

val plan = SourceStudy(java.io.File(BoxStudies.directory(thisBox), "level"), "json",
    { getPropertyRaw("level", "").toString() }, BoxStudies.replacer(thisBox, "level"),
    admit = { source, _ ->
        val found = problems(json.readValue(source))
        if (found.isNotEmpty()) throw StudyRejection(found.joinToString("; "))
        StudyAdmission(source)
    })
if (plan.resume() == SourceStudy.EMPTY) plan.edit(SourceStudy.EMPTY, SourceStudy.hash(plan.liveSource()), plan.liveSource(), "The level as first drawn")

// One edit: a list of operations, applied together or not at all.
fun edited(source: String, operations: List<Map<String, Any?>>): String {
    val level = json.readValue<MutableMap<String, Any?>>(source)
    fun section(name: String) = (level.getOrPut(name) { mutableMapOf<String, Any?>() } as MutableMap<String, Any?>)
    for (op in operations) when (op["op"]) {
        "room" -> section("rooms")[op["id"].toString()] = op["at"]
        "removeRoom" -> section("rooms").remove(op["id"].toString())
        "door" -> section("doors")[op["id"].toString()] = op["at"]
        "removeDoor" -> section("doors").remove(op["id"].toString())
        "loot" -> section("loot")[op["id"].toString()] = op["at"]
        "removeLoot" -> section("loot").remove(op["id"].toString())
        "spawn" -> level["spawn"] = op["at"]
        else -> throw StudyRejection("Unknown operation '${op["op"]}'; use room, removeRoom, door, removeDoor, loot, removeLoot or spawn")
    }
    return json.writeValueAsString(level)
}

// The plan view: rooms, doors, the spawn and the loot, and paths when there are any.
val drawLevel = { source: String, paths: List<List<List<Number>>> ->
    val level = json.readValue<Map<String, Any?>>(source)
    val s = 24.0
    val out = mutableListOf<FLine>()
    for ((id, r) in (level["rooms"] as Map<String, List<Int>>)) out += fline {
        rect(r[0] * s, r[1] * s, r[2] * s, r[3] * s); color = vec(0.75, 0.8, 0.85, 1.0); thickness = 1.5
    }
    for ((_, d) in (level["doors"] as Map<String, List<Any>>)) {
        val x = (d[0] as Number).toDouble(); val y = (d[1] as Number).toDouble()
        out += fline {
            if (d[2] == "e") { moveTo((x + 1) * s, y * s + 5); lineTo((x + 1) * s, (y + 1) * s - 5) }
            else { moveTo(x * s + 5, (y + 1) * s); lineTo((x + 1) * s - 5, (y + 1) * s) }
            color = vec(1.0, 0.75, 0.3, 1.0); thickness = 5.0
        }
    }
    for (path in paths) out += fline {
        path.forEachIndexed { i, p ->
            val x = (p[0].toDouble() + 0.5) * s; val y = (p[1].toDouble() + 0.5) * s
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        color = vec(0.35, 0.85, 1.0, 0.9); thickness = 2.0
    }
    val spawn = level["spawn"] as List<Number>
    out += fline { circle((spawn[0].toDouble() + 0.5) * s, (spawn[1].toDouble() + 0.5) * s, 7.0); color = vec(0.4, 1.0, 0.5, 1.0); filled = true }
    for ((_, l) in (level["loot"] as Map<String, List<Number>>)) out += fline {
        val x = (l[0].toDouble() + 0.5) * s; val y = (l[1].toDouble() + 0.5) * s
        moveTo(x, y - 8); lineTo(x + 8, y); lineTo(x, y + 8); lineTo(x - 8, y); close()
        color = vec(1.0, 0.85, 0.2, 1.0); filled = true
    }
    out
}
thisBox.properties["drawLevel"] = drawLevel

fun showPlan() = BoxStudies.show(thisBox, "plan", linesToHTML(drawLevel(plan.revision().source, emptyList()), 440.0, 240.0, true))
showPlan()

// The edit level.edit makes, kept in a property so other boxes can make it too.
val editLevel = { expectedRevision: String, operations: List<Map<String, Any?>>, description: String ->
    val live = plan.liveSource()
    if (live != plan.revision().source) throw StudyRejection("The level property was edited by hand; inspect it and edit through level.edit.")
    val revision = plan.edit(expectedRevision, SourceStudy.hash(live), edited(live, operations), description.ifBlank { "level.edit" })
    showPlan()
    revision.id
}
thisBox.properties["editLevel"] = editLevel
thisBox.properties["levelPlan"] = plan

aiTool("level.inspect") {
    describe("Read the level: rooms [x, y, w, h], doors [x, y, side] (side e or s opens to the east or south neighbour), the spawn [x, y] " +
        "and loot [x, y, path budget], with the revision to quote to level.edit. Changes nothing.")
    effects(afield.ai.core.ToolEffect.READ_DOCUMENT)
    action { mapOf("revision" to plan.currentId(), "level" to json.readTree(plan.revision().source),
        "editedOutsideTools" to (plan.liveSource() != plan.revision().source)) }
}
aiTool("level.edit") {
    describe("Change the level with a list of operations applied together: {op: room|door|loot, id, at} adds or replaces one; " +
        "{op: removeRoom|removeDoor|removeLoot, id} removes one; {op: spawn, at: [x, y]} moves the spawn. Quote the revision from level.inspect. " +
        "An edit that leaves rooms overlapping, a door not between two rooms, or the spawn or loot outside a room is refused and changes nothing. " +
        "Every build of the level goes stale; rebuild it.")
    effects(afield.ai.core.ToolEffect.MUTATE_DOCUMENT)
    val expectedRevision by param<String>("revision from level.inspect")
    val operations by param<List<Map<String, Any?>>>("the operations")
    val description by param<String>("what the edit is for", default = "")
    action { mapOf("revision" to editLevel(expectedRevision(), operations(), description()), "next" to "Rebuild the stages in the Builds box.") }
}
```

## 3. Builds

<!-- field-box id="staged_builds" index="2" -->

### Narrative

From `code`

Three stages derived from the level: blockout, traversal and placement.
Each reads the level as it was when its build started, and reports what it measured.

```edn [field-box id="staged_builds" role="properties"]
{"id" "staged_builds" "index" 2 "name" "Builds" "parents" ["root" "staged_level" "staged_rule"] "children" ["staged_corpus"] "properties" {"children" #{#afield/box "staged_corpus"} "parents" #{#afield/box "root" #afield/box "staged_level" #afield/box "staged_rule"} "name" "Builds" "_tombstone" false "frame" #afield/rect [760.0 520.0 300.0 100.0] "autorun" true} "blockProperties" [{"name" "code" "language" "kotlin" "path" "staged_builds_code_.kt" "lines" 188 "characters" 11880 "sha256" "bd18874f2aec89776ef9cfc41032ff2e365ade52e5057621ea1778e9c57495f1"}]}
```

```kotlin [field-property box="staged_builds" name="code" path="staged_builds_code_.kt"]
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
```

## 4. Corpus

<!-- field-box id="staged_corpus" index="3" -->

### Narrative

From `code`

Seeded variants of the level, built by the same stages and shown side by side.
Nothing here asks a model: it is the machine run many times. Run this box to build them.

```edn [field-box id="staged_corpus" role="properties"]
{"id" "staged_corpus" "index" 3 "name" "Corpus" "parents" ["root" "staged_builds"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "staged_builds"} "name" "Corpus" "_tombstone" false "frame" #afield/rect [1660.0 700.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "staged_corpus_code_.kt" "lines" 80 "characters" 5545 "sha256" "d3be0ec56733e66a137f84e8ab2693bdfe7283bdcf7082ca25976b66b08bfdb0"}]}
```

```kotlin [field-property box="staged_corpus" name="code" path="staged_corpus_code_.kt"]
// Seeded variants of the level, built by the same stages and shown side by side.
// Nothing here asks a model: it is the machine run many times. Run this box to build them.
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

val json = jacksonObjectMapper()
val seeds = 0..5

// Seed 0 is the level as it is. Other seeds shift each door along its wall, and may drop a room with one door.
fun variant(source: String, seed: Int): String {
    if (seed == 0) return source
    val random = java.util.Random(seed.toLong())
    val level = json.readValue<MutableMap<String, Any?>>(source)
    val rooms = (level["rooms"] as Map<String, List<Int>>).toMutableMap()
    val doors = (level["doors"] as Map<String, List<Any>>).toMutableMap()
    val loot = (level["loot"] as Map<String, List<Any>>).toMutableMap()
    fun roomAt(x: Int, y: Int) = rooms.entries.firstOrNull { (_, r) -> x in r[0] until r[0] + r[2] && y in r[1] until r[1] + r[3] }?.key
    val spawn = level["spawn"] as List<Int>
    val leaves = rooms.keys.filter { id -> id != roomAt(spawn[0], spawn[1]) &&
        doors.values.count { d -> val x = (d[0] as Number).toInt(); val y = (d[1] as Number).toInt()
            id == roomAt(x, y) || id == (if (d[2] == "e") roomAt(x + 1, y) else roomAt(x, y + 1)) } == 1 }
    if (leaves.isNotEmpty() && random.nextBoolean()) {
        val gone = leaves[random.nextInt(leaves.size)]
        rooms.remove(gone)
        doors.entries.removeIf { (_, d) -> val x = (d[0] as Number).toInt(); val y = (d[1] as Number).toInt()
            roomAt(x, y) == null || (if (d[2] == "e") roomAt(x + 1, y) else roomAt(x, y + 1)) == null }
        loot.entries.removeIf { (_, l) -> roomAt((l[0] as Number).toInt(), (l[1] as Number).toInt()) == null }
    }
    for ((id, d) in doors.toMap()) {
        val x = (d[0] as Number).toInt(); val y = (d[1] as Number).toInt()
        val a = roomAt(x, y); val b = if (d[2] == "e") roomAt(x + 1, y) else roomAt(x, y + 1)
        val shift = random.nextInt(5) - 2
        val (nx, ny) = if (d[2] == "e") x to y + shift else x + shift to y
        val na = roomAt(nx, ny); val nb = if (d[2] == "e") roomAt(nx + 1, ny) else roomAt(nx, ny + 1)
        if (na == a && nb == b) doors[id] = listOf(nx, ny, d[2])
    }
    if (loot.isEmpty()) return source
    return json.writeValueAsString(level + mapOf("rooms" to rooms, "doors" to doors, "loot" to loot))
}

fun runCorpus() {
    val stages = thisBox.named("Builds").properties["levelStages"] as List<StudyBuildStage>
    val gates = thisBox.named("Builds").properties["levelGates"] as List<StudyBuildGate>
    val draw = thisBox.named("Level").properties["drawLevel"] as (String, List<List<List<Number>>>) -> List<FLine>
    val level = thisBox.named("Level").getPropertyRaw("level") as String
    val rule = thisBox.named("Loot rule").getPropertyRaw("lootRule") as String
    val tiles = seeds.map { seed ->
        BoxStudies.show(thisBox, "gallery", "<p style='color:#999'>Building variant ${seed + 1} of ${seeds.count()}…</p>")
        val source = variant(level, seed)
        val builds = studyBuilds("variant$seed", stages, workers = 1, gates = gates) { mapOf("level" to source, "rule" to rule) }
        fun build(stage: String): StudyBuildRecord? {
            val state = builds.stages().first { it.id == stage }
            return state.current ?: state.fingerprint?.let { builds.await(builds.start(stage, it).id) }
        }
        build("blockout")
        val walk = build("traversal")
        val placed = build("placement")
        val traversal = walk?.let { json.readValue<Map<String, Any?>>(builds.currentFile("traversal", "traversal.json")) }
        val paths = (traversal?.get("paths") as? Map<String, Map<String, Any?>>).orEmpty().values.map { it["relaxed"] as List<List<Number>> }
        val measures = (traversal?.get("loot") as? Map<String, Map<String, Any?>>).orEmpty()
        val worst = measures.values.mapNotNull { m -> (m["relaxed"] as? Number)?.toDouble()?.div((m["steps"] as Number).toDouble().coerceAtLeast(1.0)) }.maxOrNull()
        val caption = listOf(
            if (seed == 0) "the level as it is" else "seed $seed · ${(json.readValue<Map<String, Any?>>(source)["rooms"] as Map<*, *>).size} rooms",
            "within budget ${measures.values.count { it["withinBudget"] == true }} of ${measures.size} · sightlines ${walk?.facts?.get("sightlines") ?: "–"}",
            "longest relaxed/rough ${worst?.let { "%.2f".format(it) } ?: "–"}",
            placed?.let { "placed: " + (it.facts["placed"] as Map<*, *>).entries.joinToString { e -> "${e.key} ${e.value}" } }
                ?: "placement waits: " + (builds.stages().first { it.id == "placement" }.reason ?: "").removePrefix("Waiting for reachable loot: "))
        "<div style='width:420px'>" + drawToSVG(draw(source, paths), 420.0, 230.0) +
            caption.joinToString("") { "<p style='margin:4px 0 0;color:#bbb;font:12px/1.35 Iosevka,monospace'>$it</p>" } + "</div>"
    }
    BoxStudies.show(thisBox, "gallery", "<div style='display:grid;grid-template-columns:repeat(2,420px);gap:18px;background:#000;padding:16px;border-radius:12px'>" +
        tiles.joinToString("") + "</div>")
}

// Running this box builds every variant; the stages run on their own threads, so Field stays responsive.
kotlin.concurrent.thread {
    try { runCorpus() } catch (e: Exception) {
        BoxStudies.show(thisBox, "gallery", "<p style='color:#ff6969;user-select:text'>The corpus stopped: ${e.message}</p>")
    }
}
```

## 5. Start here

<!-- field-box id="staged_start" index="4" -->

### Narrative

From `code`

The brief, for you and for the agent. It is also kept as guides/start-here.md, which every agent in this document can read.

```edn [field-box id="staged_start" role="properties"]
{"id" "staged_start" "index" 4 "name" "Start here" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Start here" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0] "autorun" true} "blockProperties" [{"name" "code" "language" "kotlin" "path" "staged_start_code_.kt" "lines" 29 "characters" 1813 "sha256" "d45190898784c946d43fc77a3a1a121b0f5c586cfc95a8e38261fa9b20541b72"}]}
```

```kotlin [field-property box="staged_start" name="code" path="staged_start_code_.kt"]
// The brief, for you and for the agent. It is also kept as guides/start-here.md, which every agent in this document can read.
val brief = """
# A staged level

This document holds one level of a puzzle shooter-looter as rooms and doors on a grid (the **Level** box), and a
machine that builds from it (the **Builds** box): a blockout, the paths from the spawn to every loot point, and the
loot placement, which follows a rule kept in the **Loot rule** box. The **Corpus** box runs the same machine on
seeded variants of the level and shows them side by side.

How to work here:

- The level changes only through `level.inspect` and `level.edit`, which quotes the revision it read. An edit made
  against an older revision is refused.
- Everything else is a build with recorded inputs, so a result is either current or visibly stale. After an edit,
  rebuild the stages in the Builds box.
- Every build reports what it measured in its notes: path lengths against their budgets, how much a relaxed path
  turns against its rough walk, doors on the way, sightlines.
- Placement waits behind a gate until every loot point can be reached within its path budget.
- The loot rule changes only through `loot.candidate`, `loot.test` and `loot.apply`, after a replay on its fixtures
  and a control that must fail. Only the person approves a change to a fixture's recorded outcome.
- Run the corpus before you call a rule change done: a change that suits this level can break a variant.

Evaluate the results, not only the checks. Look at the paths and the placement, and say what you see. The checks
only cover what someone thought to measure.
""".trimIndent()

val guide = "%document/guides/start-here.md".file
if (!guide.exists()) { guide.parentFile.mkdirs(); guide.writeText(brief + "\n") }
markdown(guide.readText())
```

