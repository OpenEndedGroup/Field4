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
