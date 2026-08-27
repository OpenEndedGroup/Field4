package afield.docs

/**
 * Reading Kotlin *as text*, which several guards in this package need and none of them should
 * reimplement.
 *
 * Extracted 2026-08-27 from [DocumentedApiEvaluationTest], where it was written, and immediately
 * needed a second caller in [GateInputDeclarationTest]. Both ask the same question — *what does this
 * source file actually do, as opposed to what does it talk about* — and both got it wrong the same
 * way before this existed: a call written in a KDoc example scored as a call, and a path named in a
 * comment scored as a path a guard reads.
 */
internal object KotlinSourceText {

    /**
     * Kotlin source with `//` and block comments blanked, string literals left intact.
     *
     * Both halves are deliberate and they pull in opposite directions.
     *
     * **Comments out**, because a KDoc worked example is not an evaluation. `afield/onnx/Clip.kt`
     * carries three `clipEncode("…")` calls in its own documentation, and the un-stripped scan read
     * the declaration site's KDoc as proof that the declaration is exercised.
     *
     * **Strings in**, because in this tree an enormous amount of genuinely-evaluated code *is* a
     * string literal: every jvm-main smoke that stages a live box holds the box's Kotlin in a
     * `""" … """` constant and hands it to the real compiler and evaluator. Stripping those would
     * discard the strongest evidence there is — `ContextSearchOrderLedgerSmokeTest`'s `fromContext`
     * evidence is exactly this shape.
     *
     * Approximate in one known way, stated rather than hidden: a block-comment opener inside a
     * *character* literal is not modelled. (Written as a description rather than as the two
     * characters, because Kotlin block comments nest and a literal opener in this KDoc would swallow
     * the rest of the file — a trap this tree has hit before.) There are none in this tree and the
     * failure would be conservative anyway — it
     * blanks too much, which can only move an API from "evaluated" to "not evaluated", i.e. toward a
     * red build that a person then looks at.
     */
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    out.append(text, i, end); i = end
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '"') { if (text[j] == '\\') j++; j++ }
                    val end = (j + 1).coerceAtMost(text.length)
                    out.append(text, i, end); i = end
                }
                // A character literal, consumed whole so that the quote in `'"'` cannot open a
                // string. This branch is here because of one line in this very function: without it
                // the `'"'` two cases up opens a bogus string that runs to the next real quote and
                // carries whatever comment text lies between into the output verbatim. Bounded at
                // eight characters, which covers `'A'`, so an apostrophe inside a backtick
                // identifier degrades to being an ordinary character rather than swallowing a line.
                c == '\'' -> {
                    var j = i + 1
                    while (j < text.length && j <= i + 8 && text[j] != '\'') { if (text[j] == '\\') j++; j++ }
                    val end = if (j < text.length && j <= i + 8 && text[j] == '\'') j + 1 else i + 1
                    out.append(text, i, end); i = end
                }
                text.startsWith("//", i) -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    repeat(end - i) { out.append(' ') }; i = end
                }
                text.startsWith("/*", i) -> {
                    // Kotlin block comments nest, and this tree's KDoc relies on it.
                    var depth = 0; var j = i
                    while (j < text.length) {
                        if (text.startsWith("/*", j)) { depth++; j += 2 }
                        else if (text.startsWith("*/", j)) { depth--; j += 2; if (depth == 0) break }
                        else j++
                    }
                    repeat(j - i) { out.append(if (text[i + it] == '\n') '\n' else ' ') }; i = j
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
