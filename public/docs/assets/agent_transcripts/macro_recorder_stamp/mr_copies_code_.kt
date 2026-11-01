// Replay "stamp" as a copy three times, each 140 lower: the boxes it creates get new
// identities on every run, so each run makes another connected pair.
val document = thisBox.inside.filename
(1..3).forEach { i ->
    val run = OperationTranscript.runMacro(
        document,
        "stamp",
        replay = OperationTranscript.MacroReplayOptions(asCopy = true, offsetY = 140.0 * i),
    )
    println(run.message + run.errors.joinToString("") { " " + it })
}
val sources = thisBox.inside.boxes.values.filter { it.name == "Source" }
println(
    "${sources.size} Source boxes; Sinks wired to each: ${sources.map { s -> s.children.count { it.name == "Sink" } }}",
)
