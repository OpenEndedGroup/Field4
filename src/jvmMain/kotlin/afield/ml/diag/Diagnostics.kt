package afield.ml.diag

/**
 * Where a FieldML compile says something is wrong, and how badly.
 *
 * This is the package the rest of the front end shares, and the only one that depends on nothing:
 * the lexer, the parser, the inferencer and the lowerer all import it, and it imports none of them.
 * A stage with something to report either adds a [Diagnostic] to the list it returns or, where
 * carrying on would mean inventing a tree, throws [CompileAbort] and lets its own entry point turn
 * that back into a diagnostic.
 *
 * Only an error stops a compile. Each stage runs if no earlier diagnostic was an error, so a
 * [Severity] of `Warning` or `Info` is collected and reported without holding the run up.
 *
 * Nothing outside `afield.ml` imports these types. They leave the front end inside
 * `CompilationResult`, and the box runtime reads them from there to put a message on a line.
 */

/**
 * One character position in the compiled source, in the three forms its readers need.
 *
 * The three do not share a base: [index] counts from zero, [line] and [column] count from one. The
 * box runtime subtracts that one back out when it maps a diagnostic onto an editor line.
 */
data class SourcePos(
    /**
     * Characters from the start of the source, counting from zero.
     *
     * Slicing the source uses this, and so does the completion engine when it asks which node the
     * cursor is inside.
     */
    val index: Int,
    /** Lines from the start of the source, counting from one. */
    val line: Int,
    /**
     * Characters from the start of the line, counting from one.
     *
     * The parser compares columns, which is how the value of a top-level binding can end at the
     * next line that starts no further right than the `let` did.
     */
    val column: Int,
)

/**
 * A half-open span of source: [start] is the first character, [end] is the position just past the
 * last.
 *
 * So the text a range covers is the source between the two [SourcePos.index] values, and a range
 * whose ends are equal covers nothing. The `EOF` token carries one of those, at the end of the
 * source.
 */
data class SourceRange(
    /** The first character of the span. */
    val start: SourcePos,
    /** The position just past the last character of the span. */
    val end: SourcePos,
) {
    companion object {
        /**
         * The span from the start of [first] to the end of [second].
         *
         * The parser builds a node's range this way whenever the node covers its outermost
         * children, as an operator application covers both of its operands.
         */
        fun spanning(first: SourceRange, second: SourceRange): SourceRange =
            SourceRange(first.start, second.end)
    }
}

/**
 * One thing a stage has to say about one place in the source.
 *
 * Diagnostics accumulate in stage order, and a stage can report an error and still have produced a
 * tree, so the trees and the diagnostics have to be read together.
 */
data class Diagnostic(
    /** How much it matters. */
    val severity: Severity,
    /**
     * The sentence shown to whoever wrote the program. It is also the exception message when the
     * diagnostic arrives as a [CompileAbort].
     */
    val message: String,
    /**
     * What the message is about, or null when there is nothing to point at.
     *
     * Every diagnostic the front end builds today carries a range. The box runtime falls back to
     * the first line of the fragment for one that does not.
     */
    val range: SourceRange?,
)

/**
 * How much a [Diagnostic] matters.
 *
 * `Error` is the only one anything constructs today, and the only one the front end tests for: a
 * stage runs only if no earlier diagnostic was an error, so a `Warning` or an `Info` would be
 * collected and reported without stopping a compile.
 */
enum class Severity {
    Error,
    Warning,
    Info,
}

/**
 * The exception a stage throws to stop where it stands, carrying the [Diagnostic] that says why.
 *
 * The parser and the inferencer both use it, and both catch it in their own entry point and return
 * it as their diagnostic list, so it does not escape the stage that threw it. Throwing is how a
 * front end that reports one error at a time avoids threading a failure result back through every
 * recursive call.
 */
class CompileAbort(val diagnostic: Diagnostic) : RuntimeException(diagnostic.message)
