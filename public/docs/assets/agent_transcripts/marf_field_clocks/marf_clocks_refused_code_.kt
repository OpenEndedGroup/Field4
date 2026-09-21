// Five of the ten address spellings from 03:38:58–03:42:10 UTC on 2026-09-21. Under
// MarfFieldClock.STAGES each was read at one instant per cycle, so a pattern with two
// steps in a cycle had one of them latched and the rest discarded — with no exception,
// no warning and no log line. Four of these five are now refused where you type them.
displayClearAll()
displayOpen("verdict")

val bank = marfValues(0, 1, 2, 3, 4)

val readOut: (String, MarfFieldClock) -> String = { spec, clock ->
    runCatching {
        bank.read(pat(spec).nums(), fields = clock, name = "addr")
            .stagesIn(PatArc(PatQ.ZERO, PatQ.of(8)))
            .take(8)
            .joinToString(" ") { s -> s.address.toInt().toString() }
    }.fold({ seq -> "reads as `$seq`" }, { e -> "**REFUSED** — ${e.message}" })
}

val spellings = listOf("<0 3> 1", "1 <0 3>", "<0 3>*2", "<0 3>, 1", "<0 3>")

val body = StringBuilder()
body.append("## What `STAGES` now refuses\n\n")
spellings.forEach { spec ->
    body.append("`address = \"$spec\"`\n\n")
    body.append(": ").append(readOut(spec, MarfFieldClock.STAGES)).append("\n\n")
}
body.append("## The same five under `CYCLES`, which is exempt\n\n")
body.append("A field is a control voltage there, every pattern means something, and\n")
body.append("nothing below raises. This is the reading that was always safe.\n\n")
spellings.forEach { spec ->
    body.append("- `\"$spec\"` ").append(readOut(spec, MarfFieldClock.CYCLES)).append("\n")
}

markdown("verdict", body.toString())
