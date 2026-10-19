import afield.ai.diagnostics.ConsoleMute
import afield.ai.diagnostics.LogDispatcher
import afield.ai.diagnostics.LogSink
import afield.ai.diagnostics.withSink
import afield.app.diagnostics.logging.lprintln

/*
Muting does not drop the event. Both console sinks check the mute set and return
early; no other sink checks it at all. So a muted line is still in the log widget,
still in the archive, and still delivered to a sink of yours.
*/
val arrived = mutableSetOf<String>()
val probe = LogSink { e -> if ("«logdemo»" in e.message) arrived += e.channel }

val channels = ["info,boxes", "info,mypage"] // the first is muted by default
LogDispatcher.withSink(probe) {
    channels.forEach { lprintln(it, "«logdemo» a line on $it") }
}

display(
    "main",
    Table(
            ["channel", "muted on the console", "reached my sink"],
            title = "muting keeps a line off the console, not out of the layer",
            note =
                "${arrived.size} of ${channels.size} arrived. Mute is for a channel too chatty " +
                    "for a terminal, not for one you want gone.",
        )
        .rows(channels.map { [it, ConsoleMute.isMuted(it), it in arrived] })
        .role("channel", CellRole.ACCENT)
        .roleBy("muted on the console") { if (it == true) CellRole.WARN else CellRole.DIM }
        .roleBy("reached my sink") { if (it == true) CellRole.OK else CellRole.BAD },
)
