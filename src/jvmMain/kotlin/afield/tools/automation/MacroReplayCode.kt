package afield.tools.automation

/**
 * The code a replayed `execute` / `launch` op runs, and where in the box it sits.
 *
 * A macro replays against the document as it is now, not as it was when the macro was recorded:
 *
 * - A whole-box launch (`type: launch`, `code` or `all`) runs the box's current text. Any
 *   `code:` or `recordedCode:` on the op is a reference to what ran at recording time; a recording
 *   made before the box's code was edited still launches the edited code.
 * - A fragment (`type: fragment`, the default for `execute`, `execute-fragment` and
 *   `launch-fragment`) with an explicit `code:` runs that code: an authored transcript uses it to
 *   run a snippet that is not in the box. A recorded fragment carries `recordedCode:` instead and
 *   is found again in the box's current text — the same text where it still occurs (nearest its
 *   recorded line), otherwise the same lines of the current text.
 * - `codeSource: recorded` runs the op's own `code:` / `recordedCode:` as written, for a macro
 *   that has to replay exactly what ran.
 * - An op with no code at all runs the box's current text.
 *
 * `note` says when what runs differs from what was recorded, for the replay log.
 */

/** The code a replayed macro op runs, the line it starts at, and how it differs from the recording. */
internal data class MacroReplayCode(val code: String, val line: Int, val note: String? = null) {
    companion object {
        private val wholeBoxTypes = setOf("launch", "code", "all")

        fun resolve(verb: String, payload: Map<*, *>, liveText: String?): MacroReplayCode {
            val explicit = payload["code"]?.toString().orEmpty()
            val recorded = payload["recordedCode"]?.toString().orEmpty()
            val line = payload["line"].asInt() ?: 0
            val live = liveText.orEmpty()
            val property = (payload["property"] ?: payload["prop"] ?: "code").toString()
            val type = payload["type"]?.toString()?.lowercase()
                ?: if (verb == "launch-fragment" || verb.startsWith("execute")) "fragment" else "launch"

            if (payload["codeSource"]?.toString()?.lowercase() == "recorded") {
                val written = explicit.ifEmpty { recorded }
                if (written.isNotEmpty()) return MacroReplayCode(written, line)
            }

            if (type in wholeBoxTypes) {
                val reference = explicit.ifEmpty { recorded }
                if (live.isNotBlank()) {
                    val note = if (reference.isNotBlank() && reference.trim() != live.trim()) {
                        "running the box's current `$property`, which has changed since this op was recorded"
                    } else null
                    return MacroReplayCode(live, line, note)
                }
                if (reference.isNotBlank()) {
                    return MacroReplayCode(reference, line, "the box's `$property` is empty; running the op's recorded code")
                }
                return MacroReplayCode("", line)
            }

            if (explicit.isNotEmpty()) return MacroReplayCode(explicit, line)
            if (recorded.isEmpty()) return MacroReplayCode(live, 0)
            if (live.isBlank()) {
                return MacroReplayCode(recorded, line, "the box's `$property` is empty; running the recorded fragment")
            }

            nearestOccurrenceLine(live, recorded, line)?.let { found ->
                val note = if (found != line) "the recorded fragment moved from line ${line + 1} to line ${found + 1}" else null
                return MacroReplayCode(recorded, found, note)
            }
            val liveLines = live.split("\n")
            val count = recorded.split("\n").size
            val start = line.coerceIn(0, maxOf(0, liveLines.size - count))
            val end = minOf(liveLines.size, start + count)
            return MacroReplayCode(
                liveLines.subList(start, end).joinToString("\n"),
                start,
                "the recorded fragment is no longer in the box's `$property`; running its current lines ${start + 1}-$end"
            )
        }

        /** The 0-based line of the occurrence of [fragment] in [text] closest to [line], if any. */
        private fun nearestOccurrenceLine(text: String, fragment: String, line: Int): Int? {
            var best: Int? = null
            var from = text.indexOf(fragment)
            while (from >= 0) {
                val at = text.substring(0, from).count { it == '\n' }
                if (best == null || kotlin.math.abs(at - line) < kotlin.math.abs(best - line)) best = at
                from = text.indexOf(fragment, from + 1)
            }
            return best
        }

        private fun Any?.asInt(): Int? = when (this) {
            is Number -> toInt()
            else -> this?.toString()?.trim()?.toIntOrNull()
        }
    }
}
