package afield.datalog

import afield.RunsAt
import afield.inMain
import afield.plugins
import afield.stdlib.LastRunNotes
import afield.stdlib.LineNotePictures
import afield.stdlib.LineNotes
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Line notes for a Datalog box ([LineNotes]): what a line's statement means in the model the box's
 * runs have built.
 *
 * A Datalog box accumulates: every run merges its facts and rules into one program, and the saturated
 * model of that program is the state the box is in. So a note needs no record of the last run — it
 * asks the model, and asking is pure. A rule's note is the facts it derives there (its body, evaluated
 * over the model, projected onto its head), a query's its answers, a fact's whether it is in the model
 * and which rules read its relation. A statement the model does not hold — text written or changed
 * since the line last ran — says so, rather than answering for text the model has never seen.
 * Directives are never run for a note: `#reset` and `#boxes` change the model.
 */
object DatalogLineNotes {

    /** Show at most this many derived facts or answers in a caption. */
    private const val CAPTIONED = 3

    /**
     * The note for [line] (1-based) of [source] against [datalog]'s model, which the box's last run left
     * [ranAt] milliseconds since the epoch (null: never run); null when the line is in no statement.
     */
    fun note(datalog: Datalog, source: String, line: Int, ranAt: Long?, now: Long = System.currentTimeMillis()): LineNotes.Note? {
        val statement = statementAt(source, line) ?: return parseProblem(source, line)
        val model = if (ranAt == null) "the box has not run" else "the box's model, as its last run left it ${LastRunNotes.ago(now - ranAt)}"
        statement.directive?.let { d ->
            return LineNotes.Note("#${d.name} is a directive: it runs when you run the line, never for a note", volatile = true)
        }
        statement.query?.let { q -> return answers(datalog, q.body, model) }
        val rule = statement.rule ?: return null
        // A bare atom with variables is a question, as the box's runs read it.
        if (rule.isFact && rule.head.terms.any { it is DlVar }) return answers(datalog, listOf(rule.head), model)
        val known = datalog.rules.any { canonical(it) == canonical(rule) }
        val head = rule.head
        val relation = "${head.pred}/${head.arity}"
        if (rule.isFact) {
            val row = head.terms.map { dlNormalize((it as DlConst).value) }
            val present = datalog.solve()[head.pred]?.contains(row) == true
            val readers = datalog.rules.filter { r -> r.body.any { it is DlAtom && it.pred == head.pred && it.arity == head.arity } }
                .map { "${it.head.pred}/${it.head.arity}" }.distinct()
            val read = if (readers.isEmpty()) "no rule reads $relation" else "read by the rules for " + readers.joinToString(", ")
            val where = if (present) "in the model" else "not in the model — run the line to add it"
            return LineNotes.Note("$head is $where · $read · $model", volatile = true)
        }
        if (!known) return LineNotes.Note("this rule is not in the model as written — run the line to add it · $model", volatile = true)
        val derived = derivedBy(datalog, rule)
        val total = datalog.rowsOf(head.pred).size
        val first = derived.take(CAPTIONED).joinToString(", ") { row -> head.pred + row.joinToString(", ", "(", ")") }
        val caption = "derives ${derived.size} of $relation's $total fact${if (total == 1) "" else "s"}" +
            (if (derived.isNotEmpty()) ": $first" + (if (derived.size > CAPTIONED) ", …" else "") else "") + " · $model"
        val columns = head.terms.mapIndexed { i, t -> (t as? DlVar)?.name ?: "#${i + 1}" }
        return LineNotes.Note(caption, LineNotePictures.table(columns, derived.take(LineNotePictures.SHOWN), derived.size), volatile = true)
    }

    private fun answers(datalog: Datalog, body: List<DlLiteral>, model: String): LineNotes.Note {
        val goal = body.joinToString(", ") { dlLiteralText(it) }
        val rows = datalog.query(goal)
        val shown = rows.rows.map { row -> row.map { dlFormat(it) } }
        val caption = when {
            rows.columns.isEmpty() -> "?- $goal: ${if (rows.isEmpty) "no" else "yes"} · $model"
            else -> "?- $goal: ${rows.size} answer${if (rows.size == 1) "" else "s"} · $model"
        }
        return LineNotes.Note(caption, LineNotePictures.table(rows.columns, shown.take(LineNotePictures.SHOWN), shown.size), volatile = true)
    }

    /** The head rows [rule]'s body yields over the model: the facts this rule, and no other, accounts for. */
    private fun derivedBy(datalog: Datalog, rule: DlRule): List<List<String>> {
        val rows = datalog.query(rule.body.joinToString(", ") { dlLiteralText(it) })
        return rows.rows.map { row ->
            rule.head.terms.map { term ->
                when (term) {
                    is DlVar -> dlFormat(row.getOrNull(rows.columns.indexOf(term.name)))
                    else -> term.toString()
                }
            }
        }.distinct()
    }

    /** A rule as text, with its anonymous variables renumbered in order, so the same rule parsed in two places compares equal. */
    private fun canonical(rule: DlRule): String {
        val names = LinkedHashMap<String, String>()
        return Regex("\\b_\\d+\\b").replace(rule.toString()) { names.getOrPut(it.value) { "_a${names.size}" } }
    }

    /** The statement [line] is in; a statement below one that does not parse is still found. */
    private fun statementAt(source: String, line: Int): DlStatement? {
        var text = source
        repeat(4) {
            try {
                return DatalogParser.statements(text).firstOrNull { line in it.firstLine..it.lastLine }
            } catch (e: DlException) {
                if (e.line < 1 || line >= e.line) return null
                // Everything above the failure parses; try again without it.
                text = text.lines().take(e.line - 1).joinToString("\n")
            }
        }
        return null
    }

    private fun parseProblem(source: String, line: Int): LineNotes.Note? {
        val problem = runCatching { DatalogParser.statements(source); null }.exceptionOrNull() as? DlException ?: return null
        return if (problem.line == line) LineNotes.Note("does not parse: ${problem.message}", volatile = true) else null
    }
}

/**
 * Datalog boxes' line notes. The model belongs to the main thread, which runs the box, so the note is
 * worked out there; a main thread busy for more than a moment gets no note rather than a racing one.
 */
@RunsAt(plugins)
fun installDatalogLineNotes() {
    LineNotes.register("code", { it.ke is DatalogExecution }) { box, source, line ->
        val execution = box.ke as? DatalogExecution ?: return@register null
        val answer = CompletableFuture<LineNotes.Note?>()
        inMain { answer.complete(runCatching { DatalogLineNotes.note(execution.datalog, source, line, execution.lastRunAt) }.getOrNull()) }
        runCatching { answer.get(2, TimeUnit.SECONDS) }.getOrNull()
    }
}
