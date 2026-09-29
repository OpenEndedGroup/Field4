@file:afield.CompletionCategory("Workspace")

package afield.stdlib

/**
 * One number a person typed in a study's source — a *knob* — as everything language-neutral sees it:
 * the fast model that names sliders ([KnobNames]), the keys a card saves ([StudyKeys]), a study's
 * setNumbers tool.
 *
 * Each language finds its own knobs (StackSL from the compiler's wires, RectSL from its tokens) and
 * maps them here; [operand] is what that language says reads the number — `mix amount`,
 * `->wall width` — and is part of the program's shape, so names and keys survive a number changing
 * but not the program's structure changing.
 */
data class StudyKnob(
    /** Index among the program's knobs, in source order — the number a tool call names. */
    val index: Int,
    /** 1-based line of the number. */
    val line: Int,
    /** The number as typed. */
    val typed: String,
    /** What reads it, in the language's own words. */
    val operand: String,
    /** Its value now. */
    val value: Double = 0.0,
)

/**
 * One knob to set, by index, and the value to write there — what every study's setNumbers request
 * carries a list of. Each study's request class keeps its own element type (its JSON schema, with
 * whatever the property descriptions say, is what a model has been shown), and implements this.
 */
interface NumberEdit {
    val knob: Int
    val value: Double
}

/** [NumberEdit]s as knob index to value, the form a language's rewriter takes. */
fun List<NumberEdit>.numbers(): Map<Int, Double> = associate { it.knob to it.value }

/**
 * Publish [numbers] (knob index to value) written into the live source by [withNumbers] — the
 * language's own rewriter, which keeps each number's spelling — as a new revision, with the usual
 * guards: the knob indices refer to the live source the caller read, so a stale revision or hash is
 * refused, and a value a word cannot hold is refused with the rewriter's reason.
 */
fun <T> SourceStudy<T>.setNumbers(expectedRevision: String, expectedSourceHash: String, numbers: Map<Int, Double>, description: String,
                                  withNumbers: (source: String, numbers: Map<Int, Double>) -> String): SourceRevision<T> {
    if (numbers.isEmpty()) throw StudyRejection("Name at least one knob to set")
    val live = liveSource()
    if (expectedRevision != currentId() || expectedSourceHash != SourceStudy.hash(live)) {
        throw StudyRejection("Conflict: inspect again; knob numbers refer to the live source you read.")
    }
    val rewritten = try { withNumbers(live, numbers) } catch (e: StudyRejection) { throw e } catch (e: IllegalArgumentException) {
        throw StudyRejection(e.message ?: "Could not set those numbers")
    }
    return edit(expectedRevision, expectedSourceHash, rewritten, description)
}
