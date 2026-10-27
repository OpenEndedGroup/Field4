/* The checked-in corpus: tasks by kind and split, and the reasons boxes were not mined. */
val corpus = AgentEvalCorpus.load()
println("${corpus.tasks.size} tasks from ${corpus.transcripts} transcripts (${corpus.codeBoxes} code boxes)")
val rows =
    corpus.tasks
        .groupingBy { it.kind + " / " + it.split }
        .eachCount()
        .toSortedMap()
        .entries
        .joinToString("") { "<tr><td>${it.key}</td><td>${it.value}</td></tr>" }
val dropped =
    corpus.rejectionCounts().entries.take(12).joinToString("") { "<tr><td>${it.key}</td><td>${it.value}</td></tr>" }
display(
    "main",
    "<table><tr><th>kind / split</th><th>tasks</th></tr>$rows</table>" +
        "<table><tr><th>dropped because</th><th>boxes</th></tr>$dropped</table>",
)
