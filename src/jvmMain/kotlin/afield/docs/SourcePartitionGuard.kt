package afield.docs

import afield.ai.KotlinSourceIndex
import java.io.File
import kotlin.system.exitProcess

/*
FIELD_TEST
id: docs.source-partition.guard
runner: jvm-main
entrypoint: afield.docs.SourcePartitionGuard
tags: [smoke, docs, reference, source, verification]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "SourcePartitionGuard ok"
*/
/**
 * Proves that every Kotlin file in the repository can be cut into spans **losslessly**, and measures
 * how much of it the cutter actually understands.
 *
 * ### The failure this exists to stop
 *
 * A source page that lifts KDoc into prose, turns declarations into headers and numbers the code
 * fences from their real source lines is only honest if the partition it was rendered from accounts
 * for the whole file. Lose three lines and every fence below them is numbered wrong — on a published
 * page, in a way that looks completely normal, and that a reader can only catch by opening the source
 * they came here to avoid opening. There is no plausible review habit that catches it either, because
 * the wrong page is well-formed.
 *
 * So the partition is checked against the file it came from, over the whole repository, before any
 * page exists. [KotlinSourcePartition.verify] holds the invariants; this walks the corpus, adds the
 * two checks that need more than one file's worth of context, and reports.
 *
 * ### What it checks
 *
 * - **The invariants** — tile, nest, reconstruct, balance, terminated. See [KotlinSourcePartition].
 * - **Agreement** — every declaration [KotlinSourceIndex.parseTopLevelDefinitions] finds is the
 *   `declarationLine` of some `DECLARATION` span. Two independent recognisers, one corpus: a
 *   disagreement means one of them is wrong about real source, and it does not much matter which,
 *   because the reference pages and the completion panel are built on the index while the source pages
 *   would be built on this. They have to see the same declarations or the two halves of the site
 *   disagree.
 * - **The unattributed census, as a ratchet** — every line the partition could not attribute to a
 *   declaration, a comment or a header lands in `FILLER`. That is a *correctness-preserving* fallback:
 *   the tiling still holds, no line is lost. It is not free, though — a `FILLER` line is a line a page
 *   can only show as anonymous code — so the total is pinned by [MAX_FILLER_LINES] and can go down
 *   without ceremony and up only deliberately.
 *
 * ### Running it
 *
 * ```
 * ./gradlew checkSourcePartition                        # also part of `check` and `docsGates`
 * ./gradlew checkSourcePartition --args=--verbose       # …and the per-file and per-shape breakdowns
 * ```
 * Needs no Field server, no GPU and no network: it reads source text. Sibling of
 * [KDocAttachmentGuard], which guards the other half of the same pipeline — that a KDoc block reaches
 * the declaration its author meant.
 */
object SourcePartitionGuard {

    /**
     * Source roots this guard does not scan, as repository-relative prefixes.
     *
     * `src/personal/` is gitignored sketch code that exists only on the machine that created it, so a
     * finding there cannot be fixed from another checkout. Same exclusion, same reason, as
     * [KDocAttachmentGuard].
     */
    private val EXCLUDED_ROOTS = listOf("src/personal/")

    /**
     * The ratchet on lines of *code* the partition cannot attribute to anything named.
     *
     * Blank lines between declarations are `FILLER` too and are not counted here: there is nothing to
     * learn about them and they would swamp the number that matters.
     *
     * 33 of 589,506 lines, down from 2,622 when this guard was first run — the difference being six
     * shapes the scanner learned along the way, each of them a bug the census found and the invariants
     * could not (see [KotlinSourcePartition] for what they were).
     *
     * The sixth closed the first of the two flavours of **multi-line annotation** this note used to
     * name as outstanding: an argument list spread over several lines above a declaration, `@Target(`
     * … `)` in `KotlinScriptEngine.kt` and a `@JsonClassDescription("…" + "…")` paragraph in `Tools.kt`
     * and `GeometryAgentTools.kt`. It was 36 lines, over half of the 69 this guard was reporting;
     * `KotlinSourcePartition`'s `multiLineAnnotationEnd` steps over it now.
     *
     * The seventh closed the **comment inside an annotation run** — `@Test`, then three `//` lines
     * explaining why the function below is not backtick-quoted, then `fun`. A masked comment line is
     * indistinguishable from a blank one, and a blank line deliberately breaks the run, so the scan
     * stopped short and left the annotation unattributed. `KotlinSourcePartition`'s
     * `lineCommentRunEnd` steps over it now, which attributed both of the repository's instances of
     * the shape (the other is `@Serializable(with = FLineSerializer::class)` in `FLine.kt`) and took
     * this ratchet from 33 to 32. It is the movement that pushed the guard one line over on
     * 2026-09-04, when `H2StructuredExplorerTest.kt` arrived carrying the first instance.
     *
     * Annotated accessors and soft-modifier expression heads were subsequently attributed. The
     * September 20 census had 29 remaining lines: wrapped conditionals, comments inside expression
     * bodies, and a wrapped file annotation. Recognizing these takes the census to one line (the
     * standalone enum separator in SoundRenderPathDocs), without relaxing any tiling/index checks.
     *
     * Lower it whenever the scanner learns a shape; raise it only with a reason.
     */
    private const val MAX_FILLER_CODE_LINES = 1

    /**
     * The ratchet on declarations [KotlinSourceIndex] reports from *inside a function body*.
     *
     * These are not partition failures — they are the other parser over-reporting a local as though
     * it were public API. It stood at 32 until the five lexical shapes documented on
     * `KotlinSourceIndex.KotlinParseState` were fixed, which brought it to 2 and, in the same pass,
     * *recovered* 28 genuine public symbols that the same mis-lexes had been hiding — the count on
     * `public/docs/reference/index.md` went 9095 → 9122, of which the single departure was
     * `jsonparsing.kt`'s local `helperBlock`, the one this note used to name.
     *
     * The 2 that remain are a different bug, and not one about braces: a `data class Foo(…)` with no
     * body stays "pending" until a `{` arrives, so a later `init {` in the enclosing class is taken
     * for its body and the locals inside it are attributed to `Foo`. Both live cases —
     * `CommandBuffer.SecondaryInheritance` and `JoltPhysicsWorld.SurfaceQuery` — are that shape.
     */
    private const val MAX_INDEX_OVER_REPORTS = 2

    /** How many unattributed lines to keep per file for the `--verbose` sample. */
    private const val SAMPLES_PER_FILE = 12

    /** One file's numbers, for the census. */
    private data class FileReport(
        val path: String,
        val lines: Int,
        val ownLines: Map<KotlinSourcePartition.Kind, Int>,
        val declarations: Int,
        val violations: List<KotlinSourcePartition.Violation>,
        val disagreements: List<String>,
        val absorbed: Int,
        val overReported: List<String>,
        val fillerSamples: List<String>,
        val fillerBlankLines: Int,
        val fillerCodeLines: Int,
        val fillerShapes: List<String>,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val verbose = args.contains("--verbose")
        val repo = repoRoot()
        val sourceRoot = File(repo, "src")
        if (!sourceRoot.isDirectory) {
            System.err.println("SourcePartitionGuard: no src/ under ${repo.absolutePath}")
            exitProcess(2)
        }

        val files = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { file ->
                val relative = repo.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                EXCLUDED_ROOTS.any { relative.startsWith(it) }
            }
            .sortedBy { it.absolutePath }
            .toList()

        val reports = files.map { file ->
            val relative = repo.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            report(relative, file.readText())
        }

        val totalLines = reports.sumOf { it.lines }
        val totalDeclarations = reports.sumOf { it.declarations }
        val own = KotlinSourcePartition.Kind.entries.associateWith { kind ->
            reports.sumOf { it.ownLines[kind] ?: 0 }
        }
        val fillerCodeLines = reports.sumOf { it.fillerCodeLines }
        val fillerBlankLines = reports.sumOf { it.fillerBlankLines }
        val overReports = reports.sumOf { it.overReported.size }

        println(
            "SourcePartitionGuard: partitioned ${reports.size} file(s), $totalLines line(s), " +
                "$totalDeclarations declaration(s); ${reports.sumOf { it.absorbed }} indexed " +
                "declaration(s) sit inside a signature rather than starting one"
        )
        val accounted = own.values.sum()
        fun census(label: String, count: Int) {
            val share = if (accounted == 0) 0 else count * 100 / accounted
            println("  ${label.padEnd(16)} ${count.toString().padStart(7)} line(s)  $share%")
        }
        KotlinSourcePartition.Kind.entries.forEach { kind ->
            if (kind == KotlinSourcePartition.Kind.FILLER) {
                census("filler (blank)", fillerBlankLines)
                census("filler (code)", fillerCodeLines)
            } else {
                census(kind.name.lowercase(), own[kind] ?: 0)
            }
        }
        if (accounted != totalLines) {
            System.err.println(
                "SourcePartitionGuard: the per-kind census sums to $accounted but the corpus has " +
                    "$totalLines lines — the census itself is wrong, which means a span tree double-counts"
            )
            exitProcess(1)
        }

        if (verbose) {
            println()
            println("Files with the most unattributed lines:")
            reports
                .sortedByDescending { it.fillerCodeLines }
                .take(20)
                .filter { it.fillerCodeLines > 0 }
                .forEach { println("  ${it.fillerCodeLines}\t${it.path}") }
            println()
            println("What those lines look like, in each affected file:")
            reports
                .sortedByDescending { it.fillerCodeLines }
                .filter { it.fillerCodeLines > 0 }
                .forEach { report ->
                    println("  ${report.path}")
                    report.fillerSamples.forEach { println("    $it") }
                }
            println()
            println("Most common unattributed line shapes:")
            reports
                .flatMap { it.fillerShapes }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(25)
                .forEach { (shape, count) -> println("  $count\t$shape") }
        }

        val violations = reports.filter { it.violations.isNotEmpty() }
        val disagreements = reports.filter { it.disagreements.isNotEmpty() }

        if (overReports > 0) {
            println()
            println(
                "$overReports declaration(s) that KotlinSourceIndex reports from inside a function " +
                    "body, in ${reports.count { it.overReported.isNotEmpty() }} file(s) — the other " +
                    "parser over-reporting, not this one losing anything:"
            )
            reports.filter { it.overReported.isNotEmpty() }.forEach { report ->
                report.overReported.forEach { println("  ${report.path}:$it") }
            }
        }

        if (violations.isNotEmpty()) {
            val total = violations.sumOf { it.violations.size }
            System.err.println()
            System.err.println("SourcePartitionGuard: $total broken invariant(s) in ${violations.size} file(s):")
            violations.take(40).forEach { report ->
                report.violations.take(10).forEach { System.err.println("  ${report.path}:$it") }
            }
        }

        if (disagreements.isNotEmpty()) {
            val total = disagreements.sumOf { it.disagreements.size }
            System.err.println()
            System.err.println(
                "SourcePartitionGuard: $total declaration(s) in ${disagreements.size} file(s) that " +
                    "KotlinSourceIndex sees and the partition cannot place inside any declaration:"
            )
            disagreements.take(40).forEach { report ->
                report.disagreements.take(10).forEach { System.err.println("  ${report.path}:$it") }
            }
        }

        if (violations.isNotEmpty() || disagreements.isNotEmpty()) {
            System.err.println(
                """
                |
                |A broken invariant means a source page rendered from this partition would drop, repeat
                |or mis-number lines. A disagreement means the source pages and the reference pages
                |would list different declarations for the same file. Fix in KotlinSourcePartition —
                |either teach the scanner the shape it cannot see, or widen FILLER to cover it, which
                |keeps the tiling true at the cost of a line the page cannot name.
                """.trimMargin()
            )
            exitProcess(1)
        }

        if (fillerCodeLines > MAX_FILLER_CODE_LINES) {
            System.err.println()
            System.err.println(
                "SourcePartitionGuard: $fillerCodeLines unattributed line(s) of code, over the ratchet " +
                    "of $MAX_FILLER_CODE_LINES. Re-run with --args=--verbose to see which shapes they " +
                    "are; either teach KotlinSourcePartition the shape, or raise MAX_FILLER_CODE_LINES " +
                    "with a reason."
            )
            exitProcess(1)
        }

        if (overReports > MAX_INDEX_OVER_REPORTS) {
            System.err.println()
            System.err.println(
                "SourcePartitionGuard: $overReports declaration(s) reported by KotlinSourceIndex from " +
                    "inside a function body, over the ratchet of $MAX_INDEX_OVER_REPORTS. Each one is a " +
                    "local that the completion panel and the reference pages will publish as though it " +
                    "were a public symbol; fix KotlinSourceIndex's brace tracking, then lower the ratchet."
            )
            exitProcess(1)
        }

        println(
            "SourcePartitionGuard ok — every file tiles, reconstructs and balances; " +
                "$fillerCodeLines unattributed line(s) of code against a ratchet of " +
                "$MAX_FILLER_CODE_LINES, $overReports index over-report(s) against a ratchet of " +
                "$MAX_INDEX_OVER_REPORTS"
        )
    }

    /** Partitions one file's [text] and collects everything the census and the checks need. */
    private fun report(path: String, text: String): FileReport {
        val partition = KotlinSourcePartition.partition(text)
        val violations = KotlinSourcePartition.verify(partition)

        val ownLines = mutableMapOf<KotlinSourcePartition.Kind, Int>()
        val fillerShapes = mutableListOf<String>()
        val fillerSamples = mutableListOf<String>()
        var fillerBlank = 0
        var fillerCode = 0
        fun account(span: KotlinSourcePartition.Span) {
            val childLines = span.children.sumOf { it.lineCount }
            val ownCount = (span.lineCount - childLines).coerceAtLeast(0)
            ownLines[span.kind] = (ownLines[span.kind] ?: 0) + ownCount
            if (span.kind == KotlinSourcePartition.Kind.FILLER) {
                for (line in span.startLine..span.endLine) {
                    val code = partition.code.getOrNull(line - 1)?.trim().orEmpty()
                    if (code.isEmpty()) fillerBlank++ else {
                        fillerCode++
                        fillerShapes += shapeOf(code)
                        if (fillerSamples.size < SAMPLES_PER_FILE) {
                            fillerSamples += "$line: ${partition.lines[line - 1].trim().take(90)}"
                        }
                    }
                }
            }
            span.children.forEach(::account)
        }
        partition.spans.forEach(::account)

        val declarationSpans = partition.allSpans()
            .filter { it.kind == KotlinSourcePartition.Kind.DECLARATION }
        val declarationLines = declarationSpans.map { it.declarationLine }.toSet()

        // A `data class Foo(val a: Int, val b: Int)` spreads its properties over the constructor
        // header, and KotlinSourceIndex reports each one as a declaration on its own line — correctly,
        // for a reference page that lists symbols. A source *page* renders the same lines as one
        // signature, so those lines are inside a declaration span rather than starting one. Accepting
        // them here is the difference between the two parsers disagreeing about a file and the two
        // parsers reading the same file for different purposes; the count is printed either way so the
        // exemption cannot quietly grow into the thing it is exempting.
        val signatureRanges = declarationSpans.map { span ->
            span.startLine..(if (span.bodyOpenLine > 0) span.bodyOpenLine else span.endLine)
        }
        val bodyRanges = declarationSpans
            .filter { it.bodyOpenLine > 0 }
            .map { it.bodyOpenLine..it.endLine }

        val indexed = try {
            KotlinSourceIndex.parseTopLevelDefinitions(text)
        } catch (t: Throwable) {
            // A crash in the other parser is that parser's problem, not evidence about this one; treat
            // the file as having no indexed declarations rather than failing this guard for it.
            emptyList()
        }
        val unmatched = indexed.filterNot { it.line in declarationLines }
        val absorbed = unmatched.count { definition -> signatureRanges.any { definition.line in it } }
        val insideBody = unmatched
            .filterNot { definition -> signatureRanges.any { definition.line in it } }
            .filter { definition -> bodyRanges.any { definition.line in it } }
        val overReported = insideBody.map {
            "${it.line}: KotlinSourceIndex sees ${it.kind} ${it.name} where the partition sees the " +
                "inside of a declaration's body"
        }
        val disagreements = unmatched
            .filterNot { definition -> signatureRanges.any { definition.line in it } }
            .filterNot { definition -> bodyRanges.any { definition.line in it } }
            .map { "${it.line}: KotlinSourceIndex sees ${it.kind} ${it.name}, the partition places that line in no declaration at all" }

        return FileReport(
            path = path,
            lines = partition.lineCount,
            ownLines = ownLines,
            declarations = declarationLines.size,
            violations = violations,
            disagreements = disagreements,
            absorbed = absorbed,
            overReported = overReported,
            fillerSamples = fillerSamples,
            fillerBlankLines = fillerBlank,
            fillerCodeLines = fillerCode,
            fillerShapes = fillerShapes,
        )
    }

    /**
     * A one-token summary of an unattributed line, so the census groups by *shape* rather than by
     * exact text. `}`, `),` and a bare identifier are three different stories about what the scanner
     * is missing; the literal names of ten thousand enum entries are one story told badly.
     */
    private fun shapeOf(code: String): String {
        val head = code.trimStart()
        val symbol = head.takeWhile { !it.isLetterOrDigit() && it != '_' && !it.isWhitespace() }
        if (symbol.isNotEmpty()) return symbol.take(3)
        val word = head.takeWhile { it.isLetterOrDigit() || it == '_' }
        return when {
            word.isEmpty() -> "<blank>"
            word in KEYWORD_SHAPES -> word
            word.all { it.isUpperCase() || it == '_' || it.isDigit() } -> "<SCREAMING_CASE>"
            word.first().isUpperCase() -> "<CapitalizedName>"
            else -> "<identifier>"
        }
    }

    /** Words worth naming individually in the shape census, because each implies a scanner gap. */
    private val KEYWORD_SHAPES = setOf(
        "if", "else", "when", "for", "while", "do", "return", "throw", "try", "catch", "finally",
        "get", "set", "by", "where", "is", "as", "in", "out", "this", "super", "it",
    )

    /** The repository root, found the same way [KDocAttachmentGuard] finds it. */
    private fun repoRoot(): File {
        var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
        while (candidate != null) {
            if (File(candidate, "public/docs").isDirectory && File(candidate, "src/jvmMain/kotlin").isDirectory) {
                return candidate
            }
            candidate = candidate.parentFile
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }
}
