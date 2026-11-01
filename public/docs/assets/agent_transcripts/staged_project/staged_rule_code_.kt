// The rule that places loot. It changes only after a replay on real placements: see
// "Change a rule without breaking what already works" in the Field docs.
val rule =
    """
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
"""
        .trimStart()

val loot = qualifiedSource(
    "loot",
    rule,
    label = "Loot rule",
    frozen = [FrozenRegion.after("// Checks: everything below this line stays as it is.")],
    control = QualifiedControl("shallow", "L1 4.0 0, L2 6.5 1", "no loot point is epic"),
    fixtures = mapOf(
        "keep" to
            QualifiedFixture(
                "L1 12.4 2, L2 19.1 3, L3 5.0 1",
                mapOf("L1" to "rare", "L2" to "epic guarded", "L3" to "common"),
            ),
        "crypt" to
            QualifiedFixture(
                "L1 8.2 1, L2 17.5 1, L3 24.0 3",
                mapOf("L1" to "common", "L2" to "rare", "L3" to "epic guarded"),
            ),
        "mill" to QualifiedFixture("L1 21.3 3, L2 9.9 2", mapOf("L1" to "epic guarded", "L2" to "rare")),
    ),
    // A placement build in this document can be kept as a fixture, with what it placed.
    retain = { attempt ->
        QualifiedFixture(attempt.file("rule-input.txt").readText(), attempt.record.facts["placed"])
    },
    replayVersion = "loot-replay-1",
) { replay ->
    QualifiedSource.evaluateKotlin(replay.source) as (String) -> Map<String, String>
}
