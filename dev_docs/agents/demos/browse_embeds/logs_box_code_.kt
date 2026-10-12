// logs(...) streams Field's log into a panel; this one only shows the "demo" channel.
// Type in its find box (or press /) to narrow it; scroll up and new rows wait behind "↓ N new".
logs("logs") { channel("demo") }

val log = afield.ai.core.LogDispatcher
log.log(afield.ai.core.LogEvent("demo", "starting a job", blockId = "job (1)", boundary = afield.ai.core.BlockBoundary.BEGIN))
for (i in 1..3) log.log(afield.ai.core.LogEvent("demo", "step $i of 3", blockId = "job (1)"))
log.log(afield.ai.core.LogEvent("demo", "done", blockId = "job (1)", boundary = afield.ai.core.BlockBoundary.END))
log.log(afield.ai.core.LogEvent("demo warning", "a warning row is coloured"))
