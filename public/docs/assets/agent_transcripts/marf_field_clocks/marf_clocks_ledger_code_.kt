// All ten spellings, all three clocks, side by side. `operations.yaml` in the
// marf_demo document records these being tried one after another about five seconds
// apart. Read the STAGES column: eight of the ten are one of three sequences.
displayClearAll()
displayOpen("ledger")

val bank = marfValues(0, 1, 2, 3, 4)
val spellings = listOf(
    "<0 3>", "<0 3> 1", "1 <0 3>", "<0 3>*2", "<0 3>!2",
    "<0 3>, 1", "<0 3> 1*8", "<0 3> 1 2", "<0 3> <0 4>", "3 <0 3>"
)

val cell: (String, MarfFieldClock) -> String = { spec, clock ->
    runCatching {
        bank.read(pat(spec).nums(), fields = clock, name = "l")
            .stagesIn(PatArc(PatQ.ZERO, PatQ.of(8)))
            .take(8)
            .joinToString("") { s -> s.address.toInt().toString() }
    }.fold({ seq -> "`$seq`" }, { _ -> "_refused_" })
}

val table = StringBuilder()
table.append("| address | `CYCLES` | `STAGES` | `STEPS` |\n")
table.append("| --- | --- | --- | --- |\n")
spellings.forEach { spec ->
    table.append("| `$spec` ")
    table.append("| ").append(cell(spec, MarfFieldClock.CYCLES)).append(" ")
    table.append("| ").append(cell(spec, MarfFieldClock.STAGES)).append(" ")
    table.append("| ").append(cell(spec, MarfFieldClock.STEPS)).append(" |\n")
}

val stagesDistinct = spellings.mapNotNull { spec ->
    runCatching {
        bank.read(pat(spec).nums(), fields = MarfFieldClock.STAGES, name = "l")
            .stagesIn(PatArc(PatQ.ZERO, PatQ.of(8))).take(8).map { s -> s.address }
    }.getOrNull()
}
val stepsDistinct = spellings.mapNotNull { spec ->
    runCatching {
        bank.read(pat(spec).nums(), fields = MarfFieldClock.STEPS, name = "l")
            .stagesIn(PatArc(PatQ.ZERO, PatQ.of(8))).take(8).map { s -> s.address }
    }.getOrNull()
}
table.append("\n")
table.append("`STAGES` now accepts ${stagesDistinct.size} of the ten and reads ")
table.append("${stagesDistinct.distinct().size} distinct sequence(s) out of them. ")
table.append("`STEPS` accepts ${stepsDistinct.size} and reads ")
table.append("${stepsDistinct.distinct().size}. ")
table.append("The one neither can read is the stack, `\"<0 3>, 1\"` — two layers on one ")
table.append("instant, one latched value per stage, and no choice of clock recovers the ")
table.append("second. That is refused too, and says so rather than naming a mode that ")
table.append("would not help.\n")

markdown("ledger", table.toString())
