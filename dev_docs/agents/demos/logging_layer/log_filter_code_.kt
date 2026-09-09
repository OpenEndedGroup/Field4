import afield.logger.lprintln
import afield.server.LogStream

// The third matcher, and the loosest. LogStream is the sink behind the log widget, and
// its filter asks channel.contains(tag, ignoreCase = true) — a substring, not a tag.
//
// This is a real subscription against the real archive. sinceEpochMs keeps the
// existing 20,000-record ring out of the replay, so what arrives is only what I emit.
val pushed = mutableListOf<String>()
LogStream.addSubscription(
    "logdemo-substring",
    LogStream.Filter(
        channels = listOf("kotlin"),
        sinceEpochMs = System.currentTimeMillis(),
        limit = 50,
        tail = true
    )
) { json -> pushed += json }

val emitted = listOf("info,kotlin", "info,kotlinjs", "info,graphics")
try {
    emitted.forEach { lprintln(it, "«logdemo» a line on $it") }
} finally {
    LogStream.unsubscribe("logdemo-substring")
}

val matched = pushed.filter { "«logdemo»" in it }
    .map { it.substringAfter("\"channel\":\"").substringBefore("\"") }
    .toSet()

// info,kotlinjs matches because "kotlin" is a prefix of "kotlinjs". The pinned console,
// which splits on commas, would not pin it. Same word, two different answers — and 108
// pairs of a real tag and a real channel in this tree differ the same way.
display("main", Table(
    listOf("channel emitted", "pushed to the subscriber"),
    title = "a LogStream filter of [\"kotlin\"] against three channels",
    note = "${matched.size} of ${emitted.size} matched: kotlin is a tag in the first and only a " +
        "substring in the second. The pinned console splits on commas, so it would take only the first."
)
    .rows(emitted.map { listOf(it, it in matched) })
    .role("channel emitted", CellRole.ACCENT)
    .roleBy("pushed to the subscriber") { if (it == true) CellRole.OK else CellRole.DIM })
