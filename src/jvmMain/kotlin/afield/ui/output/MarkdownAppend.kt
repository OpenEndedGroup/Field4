package afield.ui.output

import afield.document.graph.Box
import afield.runtime.lifecycle.RunScope
import java.util.WeakHashMap

internal object MarkdownAppendHistory {
    private class Channel(var scopeId: Long, val entries: ArrayDeque<String>)
    private val channels = WeakHashMap<Box, MutableMap<String, Channel>>()

    @Synchronized
    fun replace(box: Box, channel: String, text: String, show: () -> Unit) {
        val scopeId = RunScope.current()?.takeIf { it.box === box }?.id ?: 0L
        val states = channels.getOrPut(box) { mutableMapOf() }
        val previous = states[channel]
        states[channel] = Channel(maxOf(previous?.scopeId ?: 0L, scopeId), ArrayDeque(listOf(text)))
        show()
    }

    @Synchronized
    fun append(box: Box, channel: String, text: String, maxEntries: Int, maxChars: Int, show: (String) -> Unit) {
        val scopeId = RunScope.current()?.takeIf { it.box === box }?.id ?: 0L
        val state = channels.getOrPut(box) { mutableMapOf() }.getOrPut(channel) { Channel(scopeId, ArrayDeque()) }
        // Old cleanup can remain visible until a newer run writes this channel. Afterwards it
        // cannot append over that newer output, even if its finally block runs late.
        if (scopeId != 0L && scopeId < state.scopeId) return
        state.scopeId = maxOf(state.scopeId, scopeId)
        var entry = text.takeLast(maxChars)
        if (entry.firstOrNull()?.isLowSurrogate() == true) entry = entry.drop(1)
        state.entries.addLast(entry)
        var characters = state.entries.sumOf { it.length } + (state.entries.size - 1) * 2
        while (state.entries.size > maxEntries || characters > maxChars) {
            characters -= state.entries.removeFirst().length + 2
        }
        show(state.entries.joinToString("\n\n"))
    }
}

/**
 * Append markdown to a named output channel, retaining at most [maxEntries] entries and [maxChars]
 * UTF-16 characters, including paragraph separators. Trim oldest entries first; a single oversized
 * entry keeps its tail. Calling [markdown] replaces the text/history. History persists across runs,
 * but a late append from an older run is ignored after a newer run has written this channel.
 */
fun Box.markdownAppend(
    channel: String,
    line: String,
    maxEntries: Int = 200,
    maxChars: Int = 32_768,
    width: Int? = null,
    open: Boolean = true,
) {
    require(maxEntries > 0 && maxChars > 0) { "markdown append limits must be positive" }
    MarkdownAppendHistory.append(this, channel, line, maxEntries, maxChars) { text ->
        display(channel, FieldMarkdown.card(text, width))
        if (open) displayOpen(channel)
    }
}
