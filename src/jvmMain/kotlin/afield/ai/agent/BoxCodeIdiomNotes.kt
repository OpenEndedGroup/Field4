package afield.ai.agent

/**
 * Notes for box code an agent has just written that reaches for a generic idiom where Field has its own.
 *
 * The knight-tour session (gpt-6.1-sol, 2026-11-06) was close to a run of one-shots, and both times the
 * person had to turn it round it was this: a growth grid hand-built as an SVG string in a document whose
 * other boxes drew with FLines ("don't use SVG here, just use FLines"), and a `data class Point2(x: Double,
 * y: Double)` with its own `plus`, `minus` and `rotated` ("there's no universe in which we need our own
 * Point2 class ... We have vec(1,2)"). A line in the instructions was tried for FLines once and withdrawn
 * (dev_docs/ai/AI4_FIRST_LOCAL_CONVERSATION.md); this says it at the moment the code is written, and only
 * when the code shows it.
 *
 * Replayed over every box-code write in the AI4 tool logs (616 writes, 2026-11-07), the point rule fired
 * once, on that `Point2`. The SVG rule matched 39 turns, nearly all eval tasks whose goal asks for SVG; it
 * therefore notes SVG only when another box in the document already draws with FLines and this code does not.
 *
 * A note changes nothing: the write has happened and the tool reports success.
 */
internal object BoxCodeIdiomNotes {
    private val POINT_CLASS = Regex(
        """(?:data\s+)?class\s+(\w+)\s*\(\s*(?:val|var)\s+\w+\s*:\s*(?:Double|Float)\s*,\s*(?:val|var)\s+\w+\s*:\s*(?:Double|Float)\s*\)""")
    private val VECTOR_OPERATOR = Regex("""operator\s+fun\s+(?:plus|minus|times)\b""")
    private val SVG_MARKUP = Regex("""["']<svg\b|<svg\s+xmlns""")
    private val DRAWS_WITH_FLINES = Regex("""\bfline\s*\{|\blines\s*\(""")

    /** What to tell the model about [code], given the code of the document's other boxes ([others]). */
    fun notes(code: String, others: Sequence<String>): List<String> = buildList {
        POINT_CLASS.find(code)?.takeIf { VECTOR_OPERATOR.containsMatchIn(code) }?.let { m ->
            add("`${m.groupValues[1]}` is a hand-made 2D vector. Field has `vec(x, y)`, a `Vector2f` with `+`, `-`, `*`, " +
                "`length()` and `normalized()`, rotated by `v * quat(angle)`; use `Vector2d` only when doubles matter.")
        }
        if (SVG_MARKUP.containsMatchIn(code) && !DRAWS_WITH_FLINES.containsMatchIn(code) && others.any { DRAWS_WITH_FLINES.containsMatchIn(it) })
            add("This builds SVG markup by hand, while other boxes in this document draw with FLines " +
                "(`fline { moveTo(x, y); lineTo(x, y) }`, shown with `lines(...)`). Unless SVG was asked for, draw it the same way.")
    }
}
