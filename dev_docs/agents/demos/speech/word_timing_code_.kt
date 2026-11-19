val said = speak("Every word arrives with its own start and end.", voice = "M2")
val hop = 240 // 10 ms at 24 kHz
val loudness = (said.samples.size / hop) { i -> said.samples.slice(i * hop until (i + 1) * hop).maxOf { abs(it) } }
showSignals(
    "level" to loudness,
    x = loudness.indices.map { it * 0.01 },
    marks = said.words.map { it.text to it.start },
    channel = "main",
    xTitle = "seconds",
)
said.words.forEach {
    println("%-8s %.2f s  %s".format(it.text, it.start, it.phones.joinToString(" ") { p -> p.symbol }))
}
