/*
The level: rooms and doors on a grid, kept as JSON in this box's `level` property.
A room is [x, y, w, h] in cells. A door is [x, y, side]: an opening from cell (x, y)
to its neighbour on side "e" (east) or "s" (south). Loot is [x, y, path budget].
*/
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

val json = jacksonObjectMapper()
val first =
    """
{"rooms": {"hall": [0, 0, 6, 4], "yard": [6, 0, 6, 4], "armory": [12, 0, 5, 4],
           "crypt": [0, 4, 6, 5], "vault": [6, 4, 6, 5], "well": [12, 4, 5, 5]},
 "doors": {"d1": [5, 2, "e"], "d2": [11, 1, "e"], "d3": [2, 3, "s"], "d4": [8, 3, "s"]},
 "spawn": [1, 1],
 "loot": {"L1": [15, 2, 24], "L2": [15, 7, 20], "L3": [2, 7, 16]}}
"""
Box.ioPersistentProperties.add("level")
Box.ioSeparateProperties["level"] = "json"
prop<String?>(level)
if (level == null) level = json.writeValueAsString(json.readTree(first))

// Every check a level must pass. Edits that break one are refused and change nothing.
fun problems(level: Map<String, Any?>): List<String> {
    val out = mutableListOf<String>()
    val rooms = (level["rooms"] as? Map<String, List<Int>>).orEmpty()
    val cells = mutableMapOf<Pair<Int, Int>, String>()
    for ((id, r) in rooms) {
        if (r.size != 4 || r[2] < 1 || r[3] < 1) {
            out += "room $id needs [x, y, w, h] with w and h of at least 1"
            continue
        }
        for (x in r[0] until r[0] + r[2]) for (y in r[1] until r[1] + r[3]) cells.put(x to y, id)?.let {
            out += "rooms $it and $id overlap at ($x, $y)"
        }
    }
    fun roomAt(x: Any?, y: Any?) = cells[(x as Number).toInt() to (y as Number).toInt()]
    for ((id, d) in (level["doors"] as? Map<String, List<Any>>).orEmpty()) {
        val (x, y) = (d[0] as Number).toInt() to (d[1] as Number).toInt()
        val other = when (d[2]) {
            "e" -> roomAt(x + 1, y)
            "s" -> roomAt(x, y + 1)
            else -> null
        }
        if (roomAt(x, y) == null || other == null || other == roomAt(x, y))
            out += "door $id at ($x, $y) does not open between two rooms"
    }
    val spawn = level["spawn"] as? List<Any>
    if (spawn == null || roomAt(spawn[0], spawn[1]) == null) out += "the spawn must be inside a room"
    for ((id, l) in (level["loot"] as? Map<String, List<Any>>).orEmpty()) if (roomAt(l[0], l[1]) == null)
        out += "loot $id must be inside a room"
    if ((level["loot"] as? Map<*, *>).isNullOrEmpty()) out += "a level needs at least one loot point"
    return out
}

val plan = SourceStudy(
    java.io.File(BoxStudies.directory(thisBox), "level"),
    "json",
    { level.orEmpty() },
    BoxStudies.replacer(thisBox, "level"),
    admit = { source, _ ->
        val found = problems(json.readValue(source))
        if (found.isNotEmpty()) throw StudyRejection(found.joinToString("; "))
        StudyAdmission(source)
    },
)
if (plan.resume() == SourceStudy.EMPTY)
    plan.edit(SourceStudy.EMPTY, SourceStudy.hash(plan.liveSource()), plan.liveSource(), "The level as first drawn")

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
        else ->
            throw StudyRejection(
                "Unknown operation '${op["op"]}'; use room, removeRoom, door, removeDoor, loot, removeLoot or spawn",
            )
    }
    return json.writeValueAsString(level)
}

// The plan view: rooms, doors, the spawn and the loot, and paths when there are any, at s pixels a cell.
val drawLevel = { source: String, paths: List<List<List<Number>>>, s: Double ->
    val level = json.readValue<Map<String, Any?>>(source)
    val out = mutableListOf<FLine>()
    for ((id, r) in (level["rooms"] as Map<String, List<Int>>)) out += fline {
        rect(r[0] * s, r[1] * s, r[2] * s, r[3] * s)
        color = vec(0.75, 0.8, 0.85, 1.0)
        thickness = 1.5
    }
    for ((_, d) in (level["doors"] as Map<String, List<Any>>)) {
        val x = (d[0] as Number).toDouble()
        val y = (d[1] as Number).toDouble()
        out += fline {
            if (d[2] == "e") {
                moveTo((x + 1) * s, y * s + s / 5)
                lineTo((x + 1) * s, (y + 1) * s - s / 5)
            } else {
                moveTo(x * s + s / 5, (y + 1) * s)
                lineTo((x + 1) * s - s / 5, (y + 1) * s)
            }
            color = vec(1.0, 0.75, 0.3, 1.0)
            thickness = s / 5
        }
    }
    for (path in paths) out += fline {
        path.forEachIndexed { i, p ->
            val x = (p[0].toDouble() + 0.5) * s
            val y = (p[1].toDouble() + 0.5) * s
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        color = vec(0.35, 0.85, 1.0, 0.9)
        thickness = 2.0
    }
    val spawn = level["spawn"] as List<Number>
    out += fline {
        circle((spawn[0].toDouble() + 0.5) * s, (spawn[1].toDouble() + 0.5) * s, s * 0.3)
        color = vec(0.4, 1.0, 0.5, 1.0)
        filled = true
    }
    for ((_, l) in (level["loot"] as Map<String, List<Number>>)) out += fline {
        val x = (l[0].toDouble() + 0.5) * s
        val y = (l[1].toDouble() + 0.5) * s
        val r = s / 3
        moveTo(x, y - r)
        lineTo(x + r, y)
        lineTo(x, y + r)
        lineTo(x - r, y)
        close()
        color = vec(1.0, 0.85, 0.2, 1.0)
        filled = true
    }
    out
}
thisBox.properties["drawLevel"] = drawLevel

fun showPlan() = BoxStudies.show(
    thisBox,
    "plan",
    linesToHTML(drawLevel(plan.revision().source, emptyList(), 24.0), 440.0, 240.0, true),
)
showPlan()

// The edit level.edit makes, kept in a property so other boxes can make it too.
val editLevel = { expectedRevision: String, operations: List<Map<String, Any?>>, description: String ->
    val live = plan.liveSource()
    if (live != plan.revision().source)
        throw StudyRejection("The level property was edited by hand; inspect it and edit through level.edit.")
    val revision = plan.edit(
        expectedRevision,
        SourceStudy.hash(live),
        edited(live, operations),
        description.ifBlank { "level.edit" },
    )
    showPlan()
    revision.id
}
thisBox.properties["editLevel"] = editLevel
thisBox.properties["levelPlan"] = plan

aiTool("level.inspect") {
    describe(
        "Read the level: rooms [x, y, w, h], doors [x, y, side] (side e or s opens to the east or south neighbour), the spawn [x, y] " +
            "and loot [x, y, path budget], with the revision to quote to level.edit. Changes nothing.",
    )
    effects(afield.ai.tools.ToolEffect.READ_DOCUMENT)
    action {
        mapOf(
            "revision" to plan.currentId(),
            "level" to json.readTree(plan.revision().source),
            "editedOutsideTools" to (plan.liveSource() != plan.revision().source),
        )
    }
}
aiTool("level.edit") {
    describe(
        "Change the level with a list of operations applied together: {op: room|door|loot, id, at} adds or replaces one; " +
            "{op: removeRoom|removeDoor|removeLoot, id} removes one; {op: spawn, at: [x, y]} moves the spawn. Quote the revision from level.inspect. " +
            "An edit that leaves rooms overlapping, a door not between two rooms, or the spawn or loot outside a room is refused and changes nothing. " +
            "Every build of the level goes stale; rebuild it.",
    )
    effects(afield.ai.tools.ToolEffect.MUTATE_DOCUMENT)
    val expectedRevision by param<String>("revision from level.inspect")
    val operations by param<List<Map<String, Any?>>>("the operations")
    val description by param<String>("what the edit is for", default = "")
    action {
        mapOf(
            "revision" to editLevel(expectedRevision(), operations(), description()),
            "next" to "Rebuild the stages in the Builds box.",
        )
    }
}
