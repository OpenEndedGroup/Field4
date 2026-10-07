package afield

/**
 * # Rounds: a run of tool activity between two pieces of text
 *
 * The chat panel folds each round to one line ("4 tool calls · 22 s · 1 failed · 1 edit · 3 build
 * steps"); the chat archive gives agents the same line when they read a chat. Both describe a round with
 * [AiChatRound], so what a reader sees and what a lens-writing agent reads agree.
 */
class AiChatRound(val blocks: List<AiChatBlock>, families: AiChatToolFamilies?) {
    /** Every tool call: a tool program counts as the calls it made. */
    val calls: List<AiChatToolCall> = blocks.flatMap { b -> b.programCalls().ifEmpty { listOfNotNull(b.toolCall()) } }

    /** Time in top-level calls; a program's own time already covers the calls inside it. */
    val ms: Long = blocks.sumOf { it.toolCall()?.ms ?: 0L }

    val failed: Int = calls.count { !it.ok } + blocks.count { it.title.startsWith("Failed") || it.title.startsWith("Error") }

    /** Families that have a `count` noun, in catalog order, with how many calls fell in each. */
    val families: List<Pair<AiChatToolFamilies.Family, Int>> = families?.let { f ->
        val counts = calls.groupingBy { f.familyOf(it.name) }.eachCount()
        f.families.filter { it.count.size >= 2 }.mapNotNull { fam -> counts[fam.name]?.let { fam to it } }
    } ?: emptyList()

    /** The live item of a running turn, if this round holds it. */
    val live: AiChatBlock? = blocks.lastOrNull { it.role == "progress-open" }

    fun callsText(): String = if (calls.size == 1) "1 tool call" else "${calls.size} tool calls"
    fun failedText(): String = "$failed failed"
    fun familyText(family: AiChatToolFamilies.Family, n: Int): String = "$n ${if (n == 1) family.count[0] else family.count[1]}"

    /** The summary line as plain text. */
    fun line(): String = buildList {
        add(callsText())
        if (ms > 0) add(formatAiChatDuration(ms))
        if (failed > 0) add(failedText())
        families.forEach { (f, n) -> add(familyText(f, n)) }
    }.joinToString(" · ")

    companion object {
        /** A run of progress blocks folds into a round when it holds a tool call and something besides it. */
        fun folds(run: List<AiChatBlock>): Boolean = run.size >= 2 && run.any { it.toolCall() != null }
    }
}

fun formatAiChatDuration(ms: Long): String = when {
    ms < 1000 -> "<1 s"
    ms < 10_000 -> "${ms / 1000}.${(ms / 100) % 10} s"
    ms < 60_000 -> "${ms / 1000} s"
    ms < 3_600_000 -> "${ms / 60_000} min"
    else -> "${ms / 3_600_000} h ${(ms % 3_600_000) / 60_000} min"
}
