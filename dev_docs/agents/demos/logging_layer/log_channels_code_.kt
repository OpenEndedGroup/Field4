import afield.ai.core.LogDispatcher
import afield.ai.core.LogEvent
import afield.ai.core.LogSink
import afield.logger.lprintln

// A channel is a comma-separated list of tags. Not a level, not a logger name, and
// not a hierarchy — "error,mypage,disk" is three flat tags.
//
// A LogSink is a fun interface with one method, so a lambda is a sink. This one keeps
// only my own lines: the dispatcher is global, and a live Field is logging its own
// frame rate the whole time this box runs.
val seen = mutableListOf<LogEvent>()
val probe = LogSink { e -> if ("mypage" in e.channel) seen += e }

LogDispatcher.addSink(probe)
try {
    lprintln("info,mypage", "a plain line")
    lprintln("warning,mypage", "something to look at")
    lprintln("error,mypage,disk", "three tags, and no hierarchy among them")
} finally {
    // Always. The sink list outlives your box, and LogDispatcher.log wraps every sink
    // in runCatching — so a leaked sink that throws fails silently, forever.
    LogDispatcher.removeSink(probe)
}

display("main", Table(listOf("channel", "tags", "message"), title = "what one sink saw")
    .rows(seen.map { listOf(it.channel, it.channel.split(",").size, it.message) })
    .role("channel", CellRole.ACCENT))
