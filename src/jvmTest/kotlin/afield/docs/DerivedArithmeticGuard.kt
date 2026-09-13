package afield.docs

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Checking the **arithmetic between two already-cited facts**.
 *
 * ## The gap this closes
 *
 * Three guards in this package check numbers in prose, and none of them checks a sum.
 * [CitationGuard] proves an anchor still exists at a cited site. [DerivedCountGuard] pins a
 * *population size* to a scrape of the code. [ImpossibilityClaimGuard] demands evidence for a
 * sentence that says something cannot happen. So a page may cite two facts, both of which resolve
 * perfectly, and then state a third number that is arithmetic over them — and nothing reads the
 * arithmetic.
 *
 * That is not a hypothetical. 2026-09-12 produced two instances in committed prose in one day, both
 * on pages that passed `citationGates`:
 *
 * - `public/docs/frame_budget.md` said a 10 ms sampler gives "roughly 0.6 chances per frame" at
 *   60 fps. A 16.67 ms frame over a 10 ms period is **1.67**. The published number was the
 *   reciprocal — 10 / 16.67 = 0.5999 — which is the most common shape of this error by some
 *   distance, because both operands are right and both are in the sentence.
 * - `dev_docs/profiling_stack.md` said `CPUSpan.notifyListeners` allocates "about 360 short-lived
 *   lists a second … whether or not anyone is listening", citing a `listeners.toList()` that
 *   returns the `EmptyList` singleton at size 0 and therefore allocates nothing.
 *
 * **Both were fixed in prose by `b84ce6d25` before this guard was written**, and that is recorded
 * here on purpose: this mechanism is not justified by those two sentences, which are gone. It is
 * justified by the family. A reciprocal is one keystroke from correct, it survives every existing
 * guard, and the corpus writes rates constantly — 381 lines in `dev_docs` and `public/docs` carry
 * two numerals and a derivation connective.
 *
 * ## The marker
 *
 * A published numeral is claimed by an HTML comment abutting it, carrying **the author's own
 * arithmetic**:
 *
 * > Sixty frames a second is **16.67** milliseconds a frame …
 *
 * with an HTML comment reading `derive: 1000/60` sitting between the `16.67` and the space after
 * it. The literal marker is not written out in this KDoc, for the reason
 * [DerivedCountGuard]'s KDoc gives about its own: a marker inside a `.kt` file is a marker this
 * guard never reads, so writing one here would be an example nothing checks. The canonical spelling
 * is written out once, in `dev_docs/citation_contracts.md`, on a page this guard *does* read.
 *
 * The adjacency rule, the invisibility and the refusal-inside-code-spans are inherited from
 * [DerivedCountGuard] rather than reinvented, and [DerivedCountGuard.stripCode] is reused verbatim,
 * so the two markers cannot drift apart in how they attach. Three properties carried over, each
 * argued at length there:
 *
 * - **Invisible when rendered.** The published sentence reads exactly as it did.
 * - **Adjacent, not line-scoped.** Only emphasis characters and at most one space may sit between
 *   the numeral and the marker. A "nearest numeral on the line" rule is ambiguous the first time a
 *   sentence carries two numbers, and this corpus's sentences carry two numbers constantly.
 * - **Never a line number.** Markers inside code spans and fenced blocks are ignored, so
 *   `` `Foo.kt:412` `` can never be pinned and a sibling branch's insertion can never turn this red.
 *
 * ## What arithmetic is supported, and why it is this small
 *
 * `+ - * /`, parentheses, unary minus, decimal literals with `,`/`_` grouping and an optional
 * exponent. That is the whole language. No functions, no variables, no `^`, no comparisons.
 *
 * The marker's only advantage over a Kotlin-side [DerivedCountGuard.Derivation] is that it is
 * **legible in place** — a reader of the sentence can audit the derivation without opening a test.
 * A richer language spends that advantage: `1000/60` is checkable by eye and
 * `round(hz*ms/1e3, 2)` is not. A full expression evaluator would also need its own test suite and
 * its own non-termination story, which is a liability in a guard rather than a feature.
 *
 * Totality is structural, not hoped for: [evaluate] returns [Verdict.Unparseable] for every bad
 * input and [Expr] never throws. There is no recursion depth a marker can reach that a hand-written
 * arithmetic expression in a sentence would survive being read, so the parser is left plain.
 *
 * ## Tolerance: the precision the prose already states
 *
 * The default rule is that **the computed value, rounded to the number of decimal places the
 * published numeral shows, must equal the published numeral.** So `16.67` against `1000/60` passes
 * (16.6667 → 16.67), `1.7` against `16.7/10` passes (1.67 → 1.7), and `0.6` against `16.67/10`
 * fails (1.67 ≠ 0.6).
 *
 * This needs no declaration in the common case, which is the entire point: a tolerance nobody has
 * to write is a tolerance nobody gets wrong, and the author has *already* declared their precision
 * by choosing how to spell the number. Rounding is [RoundingMode.HALF_UP] because that is what a
 * person does when they write 1.7 for 1.667.
 *
 * Where rounding is not the story — "roughly 360 times a second" for a quantity that computes to
 * 361.4 — the marker may widen explicitly with `~2` (absolute) or `~5%` (relative) after the
 * expression. Two mechanisms, both small, and the second one visible in the sentence's own markup
 * so a reader can see that the author claimed slack.
 *
 * **Word numerals are not accepted**, and unlike [DerivedCountGuard] that is not a courtesy to
 * prose but a consequence of the default rule. A count spelled "four" is exact; a derived quantity
 * spelled "two and a half" has thrown away the precision that the agreement test is built on.
 * There is nothing to round to. A derived number that wants checking must be written as a digit.
 *
 * ## Operands are literals, never references
 *
 * A `derive:` expression may not name another number, on this or any page. Two reasons, the second
 * decisive:
 *
 * 1. Resolution would need a key registry, which is the machinery [DerivedCountGuard] already
 *    carries and pays for in its `GUARDED_PAGES` restriction.
 * 2. It would destroy the legibility that is this marker's whole justification.
 *
 * **The limit, stated rather than discovered later: a wrong *operand* is invisible to this guard.**
 * If a page writes `1000/50` and publishes `20`, this passes. What it catches is the arithmetic
 * *step* — and that is where all three recorded instances went wrong, with operands that were
 * correct, cited, and sitting in the same sentence.
 *
 * ## A malformed marker fails open per marker, and closed on itself
 *
 * A marker whose expression will not parse does **not** stop the corpus being scanned. Every other
 * marker on every other page is still evaluated. That is `d6023c83b`'s pattern, copied on purpose:
 * `tools/discover_field_tests.py` was fail-closed tree-wide, and one bad annotation left the entire
 * smoke runner dead for twelve hours.
 *
 * But "fail open" must not slide into "silently skipped", because a typo that quietly un-guards a
 * published number is exactly the defect this exists to stop. So the malformed marker is collected
 * with its page, its line, its text and the parser's reason, and
 * [DerivedArithmeticContractTest.everyMarkerCarriesArithmeticThisCanEvaluate] fails on it. Open in
 * blast radius, closed in verdict.
 */
object DerivedArithmeticGuard {

    /**
     * `<!--derive: …-->`, wherever it appears, with the expression text captured.
     *
     * Non-greedy up to the first `-->` so that an expression containing a subtraction terminates
     * correctly: in `10-5-->` the shortest body that can be followed by `-->` is `10-5`, because
     * `-->` must match exactly three characters and neither `10` nor `10-` nor `10--` leaves them.
     * Checked by [DerivedArithmeticContractTest.aSubtractionDoesNotConfuseTheMarkerTerminator].
     */
    val MARKER = Regex("""<!--\s*derive:\s*(.*?)\s*-->""")

    /**
     * A marker with a numeral properly abutting it.
     *
     * Shaped to match [DerivedCountGuard]'s `ATTACHED` so the two markers attach by the same rule:
     * the `[*`_]{0,3}` allows the numeral's own closing emphasis (`**1.7**`) and the optional single
     * space allows `1.7 <!--…-->` for an author who finds the tight form unreadable.
     *
     * The numeral alternative is digits only — see the class KDoc on why a word numeral has no
     * precision for the default agreement rule to use — and it accepts `,`/`_` grouping, a decimal
     * part and an exponent, because published prose here writes `11,500`, `16.67` and `1.5e-6`.
     */
    val ATTACHED = Regex(
        """\b([0-9][0-9,_]*(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)([*`_]{0,3})[ ]?""" +
            """<!--\s*derive:\s*(.*?)\s*-->"""
    )

    /** The repo-relative markdown trees this guard reads. Both are declared `citationGates` inputs. */
    val CORPUS_ROOTS = listOf("dev_docs", "public/docs")

    /** One marked numeral: what the page published, and the arithmetic the author claimed for it. */
    data class Marked(
        val page: String,
        val pageLine: Int,
        /** Exactly as written — `16.67`, `11,500`, `1.5e-6`. */
        val literal: String,
        /** The expression text, before `~tolerance`. */
        val expression: String,
        /** The `~tolerance` text as written, or null. */
        val tolerance: String?,
        /** The whole marker body, for failure messages. */
        val body: String,
    ) {
        /** The published value. Never null: [ATTACHED] only matches shapes [parseLiteral] accepts. */
        val statedValue: Double get() = parseLiteral(literal)!!

        /**
         * Decimal places the author chose to publish, which is the default tolerance.
         *
         * An exponent literal reports 0 rather than the digits after its point, and deliberately:
         * `1.5e-6` written with one decimal place in the mantissa is not a claim about the sixth
         * decimal place of the value. An exponent numeral should carry an explicit `~` tolerance,
         * and [DerivedArithmeticContractTest] has no such marker in the corpus to be wrong about.
         */
        val statedDecimals: Int
            get() {
                if ('e' in literal || 'E' in literal) return 0
                val dot = literal.indexOf('.')
                return if (dot < 0) 0 else literal.length - dot - 1
            }

        fun describe(): String = "$page:$pageLine `$literal` <!--derive: $body-->"
    }

    /**
     * A marker that claims nothing, because no numeral abuts it.
     *
     * Reported rather than ignored, for the reason [DerivedCountGuard.Dangling] gives: a marker
     * written after the noun rather than after the number is the likeliest authoring slip, and if it
     * were ignored the result would be a page that looks guarded carrying a number that is not.
     */
    data class Dangling(val page: String, val pageLine: Int, val body: String, val text: String)

    /** Everything [scan] found on one page. */
    data class PageScan(val page: String, val marked: List<Marked>, val dangling: List<Dangling>)

    /** What checking one [Marked] concluded. */
    sealed interface Verdict {
        /** The published number agrees with its own arithmetic. */
        data class Agrees(val computed: Double) : Verdict

        /** It does not. [computed] is what the expression evaluates to. */
        data class Disagrees(val computed: Double, val why: String) : Verdict

        /**
         * The marker could not be evaluated at all. **Not** a silent skip — see the class KDoc on
         * failing open per marker and closed on the marker itself.
         */
        data class Unparseable(val reason: String) : Verdict
    }

    // ---------------------------------------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------------------------------------

    /** Every `derive:` marker on [pageFile], attached or dangling. */
    fun scan(pageFile: File, pageName: String = pageFile.name): PageScan =
        scanText(pageFile.readText(), pageName)

    /**
     * The same scan over markdown held in a string.
     *
     * Exists so [DerivedArithmeticContractTest] can exercise the parser on deliberately malformed
     * markdown without writing a fixture into the checkout. `TestProofDir`'s KDoc records that bug
     * shipping three times — relative-path test writes accumulating junk in every nightly agent
     * worktree — and a guard about documentation honesty should not reintroduce it for its own
     * convenience.
     */
    fun scanText(text: String, pageName: String): PageScan {
        val lines = DerivedCountGuard.stripCode(text)
        val marked = mutableListOf<Marked>()
        val dangling = mutableListOf<Dangling>()
        for (idx in lines.indices) {
            val line = lines[idx]
            if ("derive:" !in line) continue
            val attachedEnds = mutableSetOf<Int>()
            for (m in ATTACHED.findAll(line)) {
                attachedEnds.add(m.range.last)
                val body = m.groupValues[3]
                val split = splitTolerance(body)
                marked.add(
                    Marked(pageName, idx + 1, m.groupValues[1], split.first, split.second, body)
                )
            }
            for (m in MARKER.findAll(line)) {
                if (m.range.last in attachedEnds) continue
                dangling.add(Dangling(pageName, idx + 1, m.groupValues[1], line.trim()))
            }
        }
        return PageScan(pageName, marked, dangling)
    }

    /** Every tracked-shape markdown page under [CORPUS_ROOTS], sorted, repo-relative. */
    fun corpusPages(root: File): List<File> {
        val out = mutableListOf<File>()
        out.addAll(markdownUnder(File(root, "dev_docs")))
        out.addAll(markdownUnder(File(root, "public/docs")))
        return out.sortedBy { it.path }
    }

    private fun markdownUnder(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        val out = mutableListOf<File>()
        for (f in dir.walkTopDown().onEnter { it.name != "build" && it.name != ".git" }) {
            if (f.isFile && f.extension == "md") out.add(f)
        }
        return out
    }

    /**
     * `expr ~tol` → `expr` and `~tol`; `expr` → `expr` and null.
     *
     * Split on the **last** `~`, so an expression may not itself contain one — which it cannot,
     * because `~` is not an operator this language has. Splitting on the last rather than the first
     * means a marker with two tolerances reports the trailing one and the leading `~` becomes an
     * unknown token, which fails loudly rather than being quietly honoured.
     */
    private fun splitTolerance(body: String): Pair<String, String?> {
        val at = body.lastIndexOf('~')
        if (at < 0) return body to null
        return body.substring(0, at).trim() to body.substring(at).trim()
    }

    // ---------------------------------------------------------------------------------------------
    // Evaluation
    // ---------------------------------------------------------------------------------------------

    /** `16.67` / `11,500` / `1.5e-6` → a Double; null for anything this does not accept. */
    fun parseLiteral(literal: String): Double? =
        literal.replace(",", "").replace("_", "").toDoubleOrNull()

    /** Check one marker: does the published number agree with the author's own arithmetic? */
    fun evaluate(m: Marked): Verdict {
        if (m.expression.isBlank()) return Verdict.Unparseable("the marker carries no expression")
        val computed = Expr.eval(m.expression)
            ?: return Verdict.Unparseable(
                "cannot evaluate `${m.expression}` — this accepts +, -, *, / parentheses and " +
                    "decimal literals, and nothing else"
            )
        if (!computed.isFinite()) {
            return Verdict.Unparseable("`${m.expression}` evaluates to $computed")
        }
        val stated = m.statedValue
        if (m.tolerance == null) {
            val rounded = BigDecimal(computed).setScale(m.statedDecimals, RoundingMode.HALF_UP)
            if (rounded.compareTo(BigDecimal(stated).setScale(m.statedDecimals, RoundingMode.HALF_UP)) == 0) {
                return Verdict.Agrees(computed)
            }
            return Verdict.Disagrees(
                computed,
                "`${m.expression}` is $computed, which to the ${m.statedDecimals} decimal place(s) " +
                    "the page publishes is $rounded, not ${m.literal}"
            )
        }
        val spec = m.tolerance.removePrefix("~").trim()
        val relative = spec.endsWith("%")
        val magnitude = parseLiteral(spec.removeSuffix("%").trim())
            ?: return Verdict.Unparseable(
                "cannot read the tolerance `${m.tolerance}` — write `~2` for an absolute slack or " +
                    "`~5%` for a relative one"
            )
        if (magnitude < 0.0) return Verdict.Unparseable("a negative tolerance `${m.tolerance}`")
        val allowed = if (relative) abs(computed) * magnitude / 100.0 else magnitude
        if (abs(computed - stated) <= allowed) return Verdict.Agrees(computed)
        return Verdict.Disagrees(
            computed,
            "`${m.expression}` is $computed, which is further from ${m.literal} than the declared " +
                "tolerance ${m.tolerance} (allows ±$allowed)"
        )
    }

    /**
     * The arithmetic, as a recursive-descent parser over a tiny token set.
     *
     * Total by construction: [eval] returns null for every input it does not accept, and no path
     * throws. Kept as plain code rather than routed through a library for the reason the class KDoc
     * gives — the value of this language is that it is small enough to read, and that has to be
     * true of its implementation too.
     */
    internal object Expr {

        /** `expr` → its value, or null if it does not parse or does not consume the whole input. */
        fun eval(text: String): Double? {
            val tokens = tokenize(text) ?: return null
            if (tokens.isEmpty()) return null
            val p = Cursor(tokens)
            val v = p.expr() ?: return null
            if (!p.atEnd()) return null
            return v
        }

        /** A number, or a one-character operator. */
        private data class Token(val number: Double?, val op: Char?)

        /** `null` when the text contains a character this language has no token for. */
        private fun tokenize(text: String): List<Token>? {
            val out = mutableListOf<Token>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == ' ' || c == '\t') { i++; continue }
                if (c in "+-*/()") { out.add(Token(null, c)); i++; continue }
                if (!c.isDigit() && c != '.') return null
                var j = i
                while (j < text.length && (text[j].isDigit() || text[j] in ".,_")) j++
                // An exponent, but only when it is really one: `e` followed by optional sign and at
                // least one digit. Without the digit check a trailing `e` would be swallowed and the
                // literal would parse as something the author did not write.
                if (j < text.length && (text[j] == 'e' || text[j] == 'E')) {
                    var k = j + 1
                    if (k < text.length && (text[k] == '+' || text[k] == '-')) k++
                    if (k < text.length && text[k].isDigit()) {
                        while (k < text.length && text[k].isDigit()) k++
                        j = k
                    }
                }
                val n = parseLiteral(text.substring(i, j)) ?: return null
                out.add(Token(n, null))
                i = j
            }
            return out
        }

        /** Position in the token list. A class rather than a fold, so the grammar reads as grammar. */
        private class Cursor(private val tokens: List<Token>) {
            private var pos = 0

            fun atEnd(): Boolean = pos >= tokens.size

            private fun peekOp(): Char? = tokens.getOrNull(pos)?.op

            /** `term (('+'|'-') term)*` */
            fun expr(): Double? {
                var acc = term() ?: return null
                while (true) {
                    val op = peekOp()
                    if (op != '+' && op != '-') return acc
                    pos++
                    val rhs = term() ?: return null
                    acc = if (op == '+') acc + rhs else acc - rhs
                }
            }

            /** `factor (('*'|'/') factor)*`. Division by zero is an evaluation failure, not an Inf. */
            private fun term(): Double? {
                var acc = factor() ?: return null
                while (true) {
                    val op = peekOp()
                    if (op != '*' && op != '/') return acc
                    pos++
                    val rhs = factor() ?: return null
                    if (op == '/' && rhs == 0.0) return null
                    acc = if (op == '*') acc * rhs else acc / rhs
                }
            }

            /** `'-'? primary`. A leading `+` is accepted too; nobody writes it, and refusing it costs. */
            private fun factor(): Double? {
                val op = peekOp()
                if (op == '-') { pos++; val v = factor() ?: return null; return -v }
                if (op == '+') { pos++; return factor() }
                return primary()
            }

            /** `number | '(' expr ')'` */
            private fun primary(): Double? {
                val t = tokens.getOrNull(pos) ?: return null
                if (t.number != null) { pos++; return t.number }
                if (t.op != '(') return null
                pos++
                val v = expr() ?: return null
                if (peekOp() != ')') return null
                pos++
                return v
            }
        }
    }
}
