package afield.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lexer-lite the two server-surface censuses share, and the reason they can now see a
 * route that does not name its verb.
 *
 * ## What was wrong, and why a regex could not fix it
 *
 * [FieldApiCorsExposureTest] and [ServerSurfaceLedgerTest] both discovered routes with a
 * regex over raw text, and both asked only *"what is the nearest preceding `route("…")` in
 * this file"*. That answers a different question from *"which `route(…)` blocks am I inside
 * of"*, and the difference is measurable: `Server.kt:4313`'s `post("/stream")` is lexically
 * inside `route("/vision")`, is served at `/vision/stream`, and was filed in the mutating
 * census as `POST /stream` — a path that does not exist. Two `controllers.kt` reads were
 * filed the same way. Nothing detected it, because *both* censuses were wrong in the same
 * direction and the ledger page was written from one of them.
 *
 * Answering the real question needs brace nesting, and brace nesting over Kotlin source
 * needs a lexer, because **brace counting is wrong the moment it counts a brace inside a
 * string**. That is not a theoretical objection; it was measured on 2026-08-22 on this exact
 * tree, and the two cases that broke a naive scanner were not the obvious one:
 *
 *  - `Server.kt:1622` is a `"…${` whose template expression continues onto the *next line*.
 *    A scanner that ends a non-raw string at the newline swallows the `{` and leaves the `}`,
 *    so `Server.kt` came out brace-imbalanced by −1 and every route after that point could
 *    be attributed to the wrong block;
 *  - `MarkdownOutput.kt:129` ends a raw string with `""""`. Kotlin closes a raw string at the
 *    **last** `"""` of a quote run, not the first, so a scanner that stops at the first three
 *    quotes re-enters "string" mode for the rest of the line and eats a real `}`.
 *
 * [everyKotlinFileIsBraceBalancedAfterSanitising] is this file asserting its own premise: if
 * the sanitiser is wrong anywhere in the tree, that test says so by name and file, rather
 * than a census quietly filing a route under a plausible-looking wrong path.
 *
 * ## What it deliberately is not
 *
 * Not a Kotlin parser and not a front end. It knows five things — line comments, nesting
 * block comments, char literals, strings (raw and not) and `${…}` templates — and it blanks
 * exactly the *text* of those while preserving every offset, so a regex match's `range` still
 * indexes the original source. Everything else, including the contents of a `${…}`, stays
 * visible as code, which is correct: a template expression's braces are balanced code braces.
 */
internal class KotlinRouteScan(private val source: String) {

    /** The source with comment and literal *text* blanked, same length, same line breaks. */
    val sanitized: String = sanitize(source)

    /** `const val NAME = "value"` in this file, which is how `PROXY_ROOT` resolves. */
    private val constants: Map<String, String> =
        CONST_DEFINITION.findAll(source).associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * Every `route(…) {` block in the file: the offset of its opening brace, the offset just
     * past its closing brace, and the argument expression as written.
     *
     * The argument is read out of the *original* source because [sanitized] has blanked the
     * string literal it usually is. The greedy `(.+)` up to the last `)` before the brace is
     * inherited from [ServerSurfaceLedgerTest] and is load-bearing for
     * `route(PROXY_ROOT.removeSuffix("/"))`, the one non-literal prefix in the tree.
     */
    private val routeBlocks: List<RouteBlock> by lazy {
        val spans = braceSpans(sanitized)
        ROUTE_OPEN.findAll(sanitized).mapNotNull { match ->
            val argument = ROUTE_OPEN.find(source.substring(match.range))
                ?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val brace = sanitized.indexOf('{', match.range.first).takeIf { it in match.range }
                ?: return@mapNotNull null
            val end = spans[brace] ?: return@mapNotNull null
            RouteBlock(brace, end, argument)
        }.toList()
    }

    private class RouteBlock(val open: Int, val close: Int, val argument: String)

    /**
     * The resolved path prefix of every `route(…)` block enclosing [position], joined.
     *
     * `""` when the position is inside no `route(…)` at all — which is the common case, since
     * most of `Server.kt`'s routes are declared with a full path at the top of `routing { }`.
     * `null` when some enclosing prefix cannot be resolved to a literal or a `const val`,
     * because a route filed under a made-up path would sit in a ledger looking like a real
     * entry; the callers turn that into a failure that names the file.
     */
    fun enclosingPrefix(position: Int): String? {
        val enclosing = routeBlocks.filter { position > it.open && position < it.close }.sortedBy { it.open }
        val parts = enclosing.map { block ->
            val literal = LITERAL_PREFIX.find(block.argument)?.groupValues?.get(1)
            val resolved = literal
                ?: LEADING_IDENTIFIER.find(block.argument)?.groupValues?.get(1)?.let { constants[it] }
                ?: return null
            resolved.trimEnd('/')
        }
        return parts.filter { it.isNotEmpty() }.joinToString("")
    }

    /**
     * A declaration's full path: the enclosing prefix joined to what it declares.
     *
     * The leading-slash case is the one that was broken. `get("/controls")` inside
     * `route("/controllers")` is served at `/controllers/controls`, and returning `/controls`
     * because the path "looks absolute" is how both censuses came to agree on a wrong answer.
     */
    fun qualify(position: Int, rawPath: String): String? {
        val prefix = enclosingPrefix(position) ?: return null
        if (prefix.isEmpty()) return rawPath.takeIf { it.startsWith('/') }
        return prefix + "/" + rawPath.trimStart('/')
    }

    /** The path a `route(…) { handle { … } }` or a bodyless `route(…) { get { … } }` serves. */
    fun enclosingRoutePath(position: Int): String? = enclosingPrefix(position)?.takeIf { it.isNotEmpty() }

    /**
     * `handle { … }` — a route that answers **every** HTTP method, verb unnamed.
     *
     * This is the shape both censuses were blind to. Thirteen of them in `Server.kt` accept
     * `POST`, `PUT` and `DELETE` as readily as `GET`, and the mutating census's headline
     * number was computed without them.
     */
    fun allMethodHandlers(): List<MatchResult> = ALL_METHOD_HANDLER.findAll(sanitized).toList()

    /**
     * A verb with no path argument — `route("/controllers") { get { … } }`.
     *
     * Two in the tree. Matched against [sanitized] so that a `get {` inside a comment or a
     * documentation string cannot enter a census.
     */
    fun bodylessVerbs(): List<MatchResult> = BODYLESS_VERB.findAll(sanitized).toList()

    /** 1-based line number of [position], for a failure message a human can act on. */
    fun lineOf(position: Int): Int = source.take(position).count { it == '\n' } + 1

    internal companion object {
        private val CONST_DEFINITION = Regex("""const val ([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]*)"""")
        private val ROUTE_OPEN = Regex("""^[ \t]*route\(\s*(.+)\)\s*\{[ \t]*$""", RegexOption.MULTILINE)
        private val LITERAL_PREFIX = Regex("""^"(/[^"]*)"$""")
        private val LEADING_IDENTIFIER = Regex("""^([A-Za-z_][A-Za-z0-9_]*)""")
        val ALL_METHOD_HANDLER = Regex("""^[ \t]*handle[ \t]*\{""", RegexOption.MULTILINE)
        val BODYLESS_VERB = Regex("""^[ \t]*(get|post|put|delete|patch)[ \t]*\{""", RegexOption.MULTILINE)

        /** Every `.kt` file the two censuses read, in a stable order. */
        fun serverKotlinFiles(root: File): List<File> =
            File(root, "src/jvmMain/kotlin/afield").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }
                .toList()

        /**
         * Blank the text of comments and literals, preserving length, offsets and line breaks.
         *
         * A hand-rolled state machine rather than a regex because the two cases that broke the
         * naive version — a `${…}` across a line break, and a raw string closed by `""""` —
         * are both about *where a literal ends*, which a regex over lines cannot see.
         */
        fun sanitize(source: String): String {
            val out = source.toCharArray()
            val n = source.length
            // Each frame is either code (with a brace depth) or a string. A `${` pushes a code
            // frame onto a string frame; the `}` that takes that frame's depth below zero pops
            // back into the string. That is the whole trick, and it is why a template spanning
            // a line break is handled and a naive scanner is not.
            val kinds = ArrayDeque<Int>()
            val depths = ArrayDeque<Int>()
            kinds.addLast(CODE); depths.addLast(0)
            fun blank(from: Int, to: Int) {
                for (k in from until minOf(to, n)) if (out[k] != '\n') out[k] = ' '
            }
            var i = 0
            while (i < n) {
                when (kinds.last()) {
                    CODE -> when {
                        source.startsWith("//", i) -> {
                            val end = source.indexOf('\n', i).let { if (it < 0) n else it }
                            blank(i, end); i = end
                        }
                        source.startsWith("/*", i) -> {
                            // Kotlin block comments nest, so this counts rather than searching.
                            var depth = 0
                            var j = i
                            while (j < n) {
                                if (source.startsWith("/*", j)) { depth++; j += 2 } else if (source.startsWith("*/", j)) {
                                    depth--; j += 2
                                    if (depth == 0) break
                                } else j++
                            }
                            blank(i, j); i = j
                        }
                        source.startsWith("\"\"\"", i) -> {
                            blank(i, i + 3); i += 3
                            kinds.addLast(RAW); depths.addLast(0)
                        }
                        source[i] == '"' -> {
                            blank(i, i + 1); i++
                            kinds.addLast(STRING); depths.addLast(0)
                        }
                        source[i] == '\'' -> {
                            var j = i + 1
                            while (j < n && source[j] != '\'') { if (source[j] == '\\') j++; j++ }
                            blank(i, j + 1); i = minOf(j + 1, n)
                        }
                        source[i] == '{' -> { depths.addLast(depths.removeLast() + 1); i++ }
                        source[i] == '}' -> {
                            val depth = depths.removeLast() - 1
                            depths.addLast(depth)
                            if (depth < 0 && kinds.size > 1) {
                                blank(i, i + 1)
                                kinds.removeLast(); depths.removeLast()
                            }
                            i++
                        }
                        else -> i++
                    }
                    else -> {
                        val raw = kinds.last() == RAW
                        when {
                            !raw && source[i] == '\\' -> { blank(i, i + 2); i += 2 }
                            raw && source.startsWith("\"\"\"", i) -> {
                                // Kotlin closes a raw string at the LAST `"` of the run, so the
                                // content of `"""x""""` is `x"`. Consuming three would re-enter
                                // string mode for the rest of the line and eat a real brace.
                                var run = 0
                                while (i + run < n && source[i + run] == '"') run++
                                blank(i, i + run); i += run
                                kinds.removeLast(); depths.removeLast()
                            }
                            !raw && source[i] == '"' -> {
                                blank(i, i + 1); i++
                                kinds.removeLast(); depths.removeLast()
                            }
                            source[i] == '$' && i + 1 < n && source[i + 1] == '{' -> {
                                blank(i, i + 2); i += 2
                                kinds.addLast(CODE); depths.addLast(0)
                            }
                            else -> { blank(i, i + 1); i++ }
                        }
                    }
                }
            }
            return String(out)
        }

        /** Matched brace pairs of [sanitized], as open-offset -> offset just past the close. */
        fun braceSpans(sanitized: String): Map<Int, Int> {
            val open = ArrayDeque<Int>()
            val spans = HashMap<Int, Int>()
            sanitized.forEachIndexed { index, ch ->
                if (ch == '{') open.addLast(index)
                else if (ch == '}' && open.isNotEmpty()) spans[open.removeLast()] = index
            }
            return spans
        }

        private const val CODE = 0
        private const val STRING = 1
        private const val RAW = 2
    }
}

/**
 * [KotlinRouteScan] asserting its own premise, because a census built on a broken lexer reads
 * as evidence.
 *
 * The tree-wide balance invariant is the useful one: it is not a unit test over an invented
 * snippet, it is the claim that this sanitiser is right about **every** `.kt` file the two
 * censuses read. It was red before the two fixes described in [KotlinRouteScan]'s KDoc, and
 * the failure named the file both times.
 */
class KotlinRouteScanTest {

    /**
     * Every file in the census input is brace-balanced after sanitising, with no underflow.
     *
     * Two failures, not one, and they mean different things. A non-zero final depth means a
     * `{` was blanked without its `}` — the `${` case. An underflow means a `}` survived that
     * should have been blanked, or a real `}` was eaten — the `""""` case. Both would silently
     * mis-attribute every route after the offending line, which is why this is worth a test of
     * its own rather than a comment.
     */
    @Test
    fun everyKotlinFileIsBraceBalancedAfterSanitising() {
        val files = KotlinRouteScan.serverKotlinFiles(repositoryRoot())
        assertTrue(files.size >= 700, "only ${files.size} Kotlin files found (floor 700); the census input moved")

        val offenders = files.mapNotNull { file ->
            val sanitized = KotlinRouteScan.sanitize(file.readText())
            var depth = 0
            var underflows = 0
            sanitized.forEach { ch ->
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth < 0) { underflows++; depth = 0 }
                }
            }
            if (depth == 0 && underflows == 0) null
            else "${file.name}   final depth $depth, $underflows underflow(s)"
        }
        assertTrue(
            offenders.isEmpty(),
            "KotlinRouteScan.sanitize left these files brace-unbalanced, so brace nesting — and " +
                "therefore every route path the censuses derive from it — is unreliable in them:\n" +
                offenders.joinToString("\n") { "  $it" } +
                "\n\nA non-zero final depth is a `{` blanked without its `}`: look for a `\${…}` " +
                "template spanning a line break. An underflow is a real `}` eaten: look for a raw " +
                "string closed by `\"\"\"\"`. Both were real cases in this tree on 2026-08-22.",
        )
    }

    /**
     * The counterfactual, floored at a number rather than at "differs".
     *
     * A sanitiser that returned its input unchanged would pass the balance test above on this
     * tree, because the raw brace counts happen to balance too. So this asserts how much work
     * it is actually doing — and asserts the artefact is non-empty first, so that blank against
     * blank cannot pass at zero differences.
     */
    @Test
    fun sanitisingHidesHundredsOfBracesThatNaiveCountingWouldTrust() {
        val server = File(repositoryRoot(), "src/jvmMain/kotlin/afield/Server.kt")
        val text = server.readText()
        assertTrue(text.length > 100_000, "Server.kt is ${text.length} chars; the artefact under test is missing")

        val sanitized = KotlinRouteScan.sanitize(text)
        assertEquals(text.length, sanitized.length, "sanitize must preserve offsets or every regex range is wrong")
        assertEquals(
            text.count { it == '\n' }, sanitized.count { it == '\n' },
            "sanitize must preserve line breaks or every reported line number is wrong",
        )

        val hiddenOpen = text.count { it == '{' } - sanitized.count { it == '{' }
        val hiddenClose = text.count { it == '}' } - sanitized.count { it == '}' }
        println("--- Server.kt braces hidden by sanitising: $hiddenOpen open, $hiddenClose close ---")
        // Measured 2026-08-22: 248 of each, out of 1215 in the raw text.
        assertTrue(
            hiddenOpen >= 200 && hiddenClose >= 200,
            "sanitising hid only $hiddenOpen open and $hiddenClose close braces in Server.kt (floor 200 " +
                "each; measured 248 on 2026-08-22). Either the file changed a great deal or sanitize " +
                "has stopped recognising literals, in which case brace nesting is naive counting again.",
        )
    }

    /** The two lexical traps that were actually measured, as the smallest cases that show them. */
    @Test
    fun theTwoTrapsThatBrokeTheNaiveScannerAreHandled() {
        // A `${…}` whose expression body spans a line break — Server.kt:1622's shape.
        val template = "fun a() {\n    x(\"v=${'$'}{run {\n        1\n    }}\")\n}\n"
        val templateClean = KotlinRouteScan.sanitize(template)
        assertEquals(
            0, templateClean.count { it == '{' } - templateClean.count { it == '}' },
            "a template expression spanning a line break must stay balanced; got:\n$templateClean",
        )

        // A raw string closed by four quotes — MarkdownOutput.kt:129's shape. The `}` after it
        // is real code and must survive.
        val quadruple = "fun b() {\n    val s = \"\"\"a=\"1\"\"\"\"\n    if (s != \"\") {\n        p()\n    }\n}\n"
        val quadrupleClean = KotlinRouteScan.sanitize(quadruple)
        assertEquals(
            0, quadrupleClean.count { it == '{' } - quadrupleClean.count { it == '}' },
            "a raw string closed by `\"\"\"\"` must not swallow the following code; got:\n$quadrupleClean",
        )

        // And a brace inside an ordinary string must not be counted as code.
        val braceInString = KotlinRouteScan.sanitize("val s = \"{\"\n")
        assertEquals(0, braceInString.count { it == '{' }, "a brace inside a string literal must be blanked")
    }

    /**
     * The nesting answer the censuses depend on, on the two real routes that were mis-filed.
     *
     * Named rather than derived, because these two paths are the whole reason this class
     * exists: if `enclosingPrefix` ever returns `""` for them again, both censuses go back to
     * publishing a path that Field does not serve.
     */
    @Test
    fun theRoutesThatWereMisfiledResolveToTheirRealPaths() {
        val controllers = File(repositoryRoot(), "src/jvmMain/kotlin/afield/server/controllers.kt")
        val controllersScan = KotlinRouteScan(controllers.readText())
        val controls = controllers.readText().indexOf("get(\"/controls\")")
        assertTrue(controls > 0, "controllers.kt no longer declares get(\"/controls\"); re-derive this test")
        assertEquals("/controllers/controls", controllersScan.qualify(controls, "/controls"))

        val server = File(repositoryRoot(), "src/jvmMain/kotlin/afield/Server.kt")
        val serverText = server.readText()
        val stream = serverText.indexOf("post(\"/stream\")")
        assertTrue(stream > 0, "Server.kt no longer declares post(\"/stream\"); re-derive this test")
        assertEquals("/vision/stream", KotlinRouteScan(serverText).qualify(stream, "/stream"))

        // And a route declared at the top level of `routing { }` must not acquire a prefix.
        val health = serverText.indexOf("get(\"/health\")")
        assertTrue(health > 0, "Server.kt no longer declares get(\"/health\"); re-derive this test")
        assertEquals("/health", KotlinRouteScan(serverText).qualify(health, "/health"))
    }

    private fun repositoryRoot(): File {
        var candidate: File? = File(".").absoluteFile
        while (candidate != null && !File(candidate, "settings.gradle.kts").isFile) candidate = candidate.parentFile
        assertTrue(candidate != null, "could not locate the repository root from ${File(".").absolutePath}")
        return candidate
    }
}
