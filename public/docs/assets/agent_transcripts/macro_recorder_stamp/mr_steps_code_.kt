// Step through "stamp" one operation at a time, as the panel's Step button does.
val document = thisBox.inside.filename
(0 until 3).forEach { step ->
    val run = OperationTranscript.runMacro(
        document,
        "stamp",
        replay = OperationTranscript.MacroReplayOptions(step = step),
    )
    println("step ${step + 1}: ${run.message}" + run.errors.joinToString("") { " " + it })
}
println("the original pair is wired: ${thisBox.inside.boxes["stamp_source"]?.children?.map { it.name }}")
