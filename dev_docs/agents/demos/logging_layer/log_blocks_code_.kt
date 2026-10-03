import afield.ai.core.LogDispatcher
import afield.ai.core.LogEvent
import afield.ai.core.LogSink
import afield.ai.core.withSink
import afield.logger.lblock
import afield.logger.lprintln

// lblock brackets a scope with a BEGIN and an END event sharing a blockId, so a
// consumer can fold what is between them.
val all = mutableListOf<LogEvent>()
val probe = LogSink { e -> all += e }

LogDispatcher.withSink(probe) {
    lblock("info,logdemo", "«logdemo» outer") {
        lprintln("info,logdemo", "«logdemo» inside outer")
        lblock("info,logdemo", "«logdemo» inner") {
            lprintln("info,logdemo", "«logdemo» inside inner")
        }
    }
}

// An END event's message is only its own id, so it is picked up by blockId rather
// than by text.
val mineIds = all.filter { "«logdemo»" in it.message }.mapNotNull { it.blockId }.toSet()
val mine = all.filter { "«logdemo»" in it.message || it.blockId in mineIds }
val deepest = mine.filter { it.boundary == null }.maxByOrNull { it.indent.length }

// The table's footnote prints the deepest line's raw indent. The nesting lives in a
// thread-local `indent` string, and what it holds is not spaces: it is one block UUID
// and a tab per level. PinnedConsole parses those tokens back out to find the open
// block. StdoutLog does not — it concatenates the indent into the line, so on a plain
// terminal a line inside an lblock is prefixed by a raw 36-character UUID.
display("main", Table(
    listOf("depth", "boundary", "block", "message"),
    title = "two nested lblocks, as the dispatcher saw them",
    note = "the deepest line's raw indent: '" +
        (deepest?.indent?.replace("\t", "\\t") ?: "") +
        "' — block ids and tabs, not spaces"
)
    .rows(mine.map { e ->
        listOf(
            e.indent.split("\t").count { it.isNotEmpty() },
            e.boundary?.name ?: "—",
            e.blockId?.take(8) ?: "—",
            e.message.substringBefore(" - '")
        )
    })
    .role("message", CellRole.ACCENT))
