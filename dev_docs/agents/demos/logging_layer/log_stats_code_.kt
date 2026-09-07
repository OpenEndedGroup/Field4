import afield.logger.StatsHeatmap
import afield.logger.formatMillis

// A channel that reports a number every frame gets coloured by whether each value rose
// or fell since that channel last reported the same key. The contract is only a string
// format: key=value or key: value, optionally with a unit.
//
// formatMillis emits a unit that matches that grammar, which is why a slow compile
// reads compile=1.63s rather than compile=1629ms.
val durations = listOf(120_000L, 3_400_000L, 47_000_000L, 250_000_000L, 1_630_000_000L, 12_400_000_000L)
val ladder = Table(
    listOf("nanoseconds", "formatted"),
    title = "formatMillis keeps digits where they matter",
    note = "more digits below a millisecond, plain integers past 100ms, decimal seconds past a second"
)
    .rows(durations.map { listOf(it, formatMillis(it)) })
    .role("formatted", CellRole.ACCENT)

val line = "fps=61.2 upload=3.4MB net:102% dropped=0"
val torn = Table(
    listOf("fragment", "treated as"),
    title = "how a stats line is torn into colourable tokens",
    note = "history is kept per channel and per key, so two unrelated metrics sharing one " +
        "channel do not colour each other"
)
    .rows(StatsHeatmap.fragments("logdemo", line).map {
        listOf("'${it.text}'", if (it.style == null) "plain text" else "number")
    })
    .roleBy("treated as") { if (it == "number") CellRole.ACCENT else CellRole.DIM }

markdown("main", """
    ${ladder.h}

    ${torn.h}
""".trimIndent())
