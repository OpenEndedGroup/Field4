// logs(...) streams Field's log into a panel; this one only shows the "demo" channel.
// Type in its find box (or press /) to narrow it; scroll up and new rows wait behind "↓ N new".
logs("logs") { channel("demo") }

val log = afield.ai.diagnostics.LogDispatcher
log.log(afield.ai.diagnostics.LogEvent("demo", "starting a job", blockId = "job (1)", boundary = afield.ai.diagnostics.BlockBoundary.BEGIN))
for (i in 1..3) log.log(afield.ai.diagnostics.LogEvent("demo", "step $i of 3", blockId = "job (1)"))
log.log(afield.ai.diagnostics.LogEvent("demo", "done", blockId = "job (1)", boundary = afield.ai.diagnostics.BlockBoundary.END))
log.log(afield.ai.diagnostics.LogEvent("demo warning", "a warning row is coloured"))
