package afield

import java.util.concurrent.atomic.AtomicLong

/**
 * A bounded, server-side record of what each box has said: printed text, error diagnostics,
 * `display(...)` channels, and execution verdicts.
 *
 * # Why this exists
 *
 * Until now a box's output only existed on the wire. `println` left through
 * [dispatchExecutionOutput] as an `output` message to whichever websocket asked for the run, and
 * `display` left as a `set-output` broadcast; nothing on the server kept either. So the only way to
 * learn what a box printed was to be a websocket client — which is why every external agent drives
 * Field through `tools/field_backdoor.py`, and why Field's own agent could not "run a box and read
 * what it printed" at all.
 *
 * This log is fed at the three places every such message originates — [dispatchExecutionOutput],
 * the document broadcast `_send(message, document)` (for `set-output`), and [ExecutionTrace.finish]
 * (for verdicts) — so it sees browser-started runs, backdoor runs and in-Field-agent runs alike,
 * once each, whether or not any browser is attached.
 *
 * # Shape
 *
 * Entries carry a global, monotonically increasing [Entry.seq]. A reader takes [mark] before it
 * acts and asks for [since] that mark afterwards; this is how the agent tools report "what this run
 * printed" without racing a box that prints forever.
 *
 * Bounds: [maxEntriesPerBox] per box (oldest dropped), [maxTextChars] per entry, [maxBoxes] boxes
 * (least-recently-written dropped), and [maxTotalChars] across everything (oldest entries of the
 * least-recently-written boxes dropped first). A box in a tight `println` loop therefore costs a
 * fixed amount of memory, and a reader that falls behind sees [Page.dropped] rather than silently
 * missing lines.
 *
 * A `display` replaces its channel on screen, so a new `display` entry replaces the box's previous
 * entry for that channel here too. Without that, one animated box re-displaying 4 KB of HTML per frame
 * filled its whole ring with frames nobody will read, and the per-box bounds alone allowed
 * 400 × 4000 chars × 4096 boxes — gigabytes — before [maxTotalChars] existed (review, 2026-09-29).
 * A deleted box's history is dropped by the `-delete-box` action.
 */
object BoxOutputLog {

    /** One thing a box said. [kind] is `out`, `err`, `display`, or `verdict`. */
    data class Entry(
        val seq: Long,
        val uid: String,
        val kind: String,
        val text: String,
        val channel: String = "",
        val property: String = "",
        val line: Int = 0,
        val atMillis: Long = System.currentTimeMillis(),
        /** For `verdict` entries: whether setup completed. */
        val success: Boolean? = null,
    )

    data class Page(val entries: List<Entry>, val marker: Long, val dropped: Boolean)

    const val maxEntriesPerBox = 400
    const val maxTextChars = 4000
    const val maxBoxes = 4096
    /** About 32 MB of UTF-16 across all boxes. */
    const val maxTotalChars = 16_000_000L

    private val sequence = AtomicLong(0)
    private val lock = Any()
    private var totalChars = 0L
    private val byBox = object : LinkedHashMap<String, ArrayDeque<Entry>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<Entry>>?): Boolean =
            size > maxBoxes
    }

    /** The current sequence number; everything recorded afterwards has a larger [Entry.seq]. */
    fun mark(): Long = sequence.get()

    fun record(
        uid: String,
        kind: String,
        text: String,
        channel: String = "",
        property: String = "",
        line: Int = 0,
        success: Boolean? = null,
    ) {
        if (uid.isBlank()) return
        val clipped = if (text.length <= maxTextChars) text else text.take(maxTextChars) + "…"
        synchronized(lock) {
            val entry = Entry(sequence.incrementAndGet(), uid, kind, clipped, channel, property, line, success = success)
            val deque = byBox.getOrPut(uid) { ArrayDeque() }
            if (kind == "display") {
                val it = deque.iterator()
                while (it.hasNext()) {
                    val old = it.next()
                    if (old.kind == "display" && old.channel == channel) { totalChars -= old.text.length; it.remove() }
                }
            }
            deque.addLast(entry)
            totalChars += clipped.length
            while (deque.size > maxEntriesPerBox) totalChars -= deque.removeFirst().text.length
            while (totalChars > maxTotalChars) {
                val eldest = byBox.entries.firstOrNull() ?: break
                val dropped = eldest.value.removeFirstOrNull()
                if (dropped == null) { byBox.remove(eldest.key); continue }
                totalChars -= dropped.text.length
                if (eldest.value.isEmpty()) byBox.remove(eldest.key)
            }
        }
    }

    /** Entries for [uid] with `seq > marker`, oldest first. */
    fun since(uid: String, marker: Long, limit: Int = maxEntriesPerBox): Page = synchronized(lock) {
        val deque = byBox[uid] ?: return@synchronized Page(emptyList(), mark(), false)
        val all = deque.filter { it.seq > marker }
        // If the oldest entry we still hold is newer than marker + 1, and entries between were
        // written for this box, some were dropped. We cannot know for sure whether the gap was this
        // box's, so we only say "dropped" when the deque is full — the only way it loses entries.
        val dropped = deque.size >= maxEntriesPerBox && (deque.firstOrNull()?.seq ?: 0) > marker + 1
        val page = if (all.size <= limit) all else all.takeLast(limit)
        Page(page, page.lastOrNull()?.seq ?: marker, dropped || all.size > limit)
    }

    /** Forget a box's history (tests, and a deleted box). */
    fun clear(uid: String) = synchronized(lock) {
        byBox.remove(uid)?.let { removed -> totalChars -= removed.sumOf { it.text.length.toLong() } }
        Unit
    }

    /** Characters currently held, for tests and diagnostics. */
    fun heldChars(): Long = synchronized(lock) { totalChars }

    // ---- taps ---------------------------------------------------------------------------------

    internal fun observeOutput(output: Output) {
        val kind = when (output.kind) {
            "error", "err" -> "err"
            "normal", "" -> "out"
            else -> return // "ok", "clear", annotations: editor bookkeeping, not something the box said
        }
        // Printed non-strings arrive as value-visualizer HTML; its style attributes are full of numbers
        // (`12px`, `1.4`) that would pollute any check or reader looking for the printed values.
        val text = if (kind == "err" || output.html.trimStart().startsWith("<")) htmlToText(output.html) else output.html
        if (text.isBlank()) return
        record(output.uid, kind, text.trimEnd('\n'), property = output.property, line = output.line)
    }

    internal fun observeBroadcast(message: Message) {
        if (message.name != "set-output") return
        val payload = message.payload as? kotlinx.serialization.json.JsonObject ?: return
        fun s(k: String) = (payload[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        if (s("property") != "outputarea") return
        record(s("uid"), "display", s("value"), channel = s("channel").ifBlank { "output" })
    }

    internal fun observeVerdict(report: ExecutionReport) {
        record(
            report.uid, "verdict",
            if (report.success) "${report.mode}: ${report.stage}" else "${report.mode}: failed\n${report.error}",
            property = report.property,
            success = report.success
        )
    }

    internal fun htmlToText(html: String): String = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
}
