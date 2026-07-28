package afield.docs

import afield.ai.KDocExtractor
import afield.ai.KotlinSourceIndex
import java.io.File
import kotlin.system.exitProcess

/*
FIELD_TEST
id: docs.kdoc-attachment.guard
runner: jvm-main
entrypoint: afield.docs.KDocAttachmentGuard
tags: [smoke, docs, reference, kdoc, verification]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "KDocAttachmentGuard ok"
*/
/**
 * Fails the build for a KDoc comment that does not attach to the declaration its author meant.
 *
 * ### The failure this exists to stop
 *
 * [KDocExtractor.extractKDocs] is the single source of every summary in the completion doc panel,
 * the semantic index and the generated [StdlibReferenceGenerator] reference pages. Its attachment
 * rule is: read the KDoc block, then walk *forward* line by line until
 * [KDocExtractor.parseDeclaration] recognises a declaration, and attach the block to that. The walk
 * does not stop at a line it cannot read — it skips it. So a KDoc sitting above something the
 * parser cannot see is not merely dropped: it **slides onto the next declaration** and is published
 * as that symbol's prose. `2026-07-26` shipped exactly that — a `seg(...)` row captioned with
 * `FLine.hoverColor`'s description — because a delegated property in between parsed as nothing.
 *
 * The failure is silent by construction: the wrong page is well-formed, the build is green, and only
 * a reader who knows both symbols can tell. Hence a guard rather than a review habit.
 *
 * ### What it reports
 *
 * Two findings, over every `.kt` file under `src/`:
 *
 * - [Kind.DROPPED] — the first line after the block that the extractor would look at is not a
 *   declaration [KDocExtractor.parseDeclaration] matches. The block is dropped or slides.
 * - [Kind.STACKED] — two KDoc blocks with no declaration between them, so the first documents
 *   nothing. The extractor stops at the second block and discards the first.
 *
 * ### Known-benign sites, and why each really is benign
 *
 * - **A file header block before `package`** — a licence or file note above the package directive
 *   documents the file, and nothing downstream looks for a symbol there.
 * - **An enum constant** — `HANN,` is not a declaration by this parser, and does not need to be:
 *   [StdlibReferenceGenerator] reads an enum's constants straight out of the class body with its own
 *   `enumConstantsAt`, so a KDoc on a constant is unused rather than misplaced.
 * - **A file overview stacked above the first declaration's own KDoc** — the shape
 *   `Functions.kt` has. [StdlibReferenceGenerator] finds the file overview itself (first block in the
 *   file, ending before the first declaration, separated from what follows by a blank or comment
 *   line) and prints it as the section prose, so the extractor dropping it costs nothing. The gap is
 *   load-bearing: **without** a blank line the generator treats the block as the following
 *   declaration's summary instead, and the file's row on the reference index goes empty — so this
 *   exemption deliberately does not cover the flush case.
 *
 * Everything else is a finding. There is no suppression comment on purpose: the fix is always to
 * move the block, or to teach [KDocExtractor.parseDeclaration] the declaration form it is missing.
 *
 * ### Running it
 *
 * ```
 * ./gradlew checkKDocAttachment          # also runs as part of `check`
 * ./gradlew checkKDocAttachment --args=--verbose   # …and print the benign sites too
 * ```
 * Needs no Field server, no GPU and no network: it reads source text. See
 * `dev_docs/kdoc_reference_contract.md` for how to write a KDoc that attaches.
 */
object KDocAttachmentGuard {

    /** Which way a KDoc block fails to reach its declaration. */
    enum class Kind {
        /** The next line the extractor would consider is not a declaration it can parse. */
        DROPPED,

        /** A second KDoc block follows before any declaration, so this one documents nothing. */
        STACKED,
    }

    /**
     * One mis-attached KDoc block.
     *
     * [line] is the 1-based line of the block's opening marker, which is where a reader has to go to fix
     * it;
     * [detail] carries the offending following text so the report is actionable without opening the
     * file.
     */
    data class Finding(
        val path: String,
        val line: Int,
        val kind: Kind,
        val detail: String,
    ) {
        /** `path:line: KIND — detail`, the one-line form the guard prints. */
        override fun toString(): String = "$path:$line: $kind — $detail"
    }

    /** A KDoc block found by [scanSource], with the brace scope it sits inside. */
    private data class Block(
        /** 1-based line of the block's opening marker. */
        val startLine: Int,
        /** 1-based line carrying the closing marker. */
        val endLine: Int,
        /** 1-based line of the `{` of the innermost enclosing scope, or null at file scope. */
        val enclosingBraceLine: Int?,
    )

    /** The result of classifying one block: a finding, or a reason it is benign. */
    private sealed interface Verdict {
        data class Hit(val finding: Finding) : Verdict
        data class Benign(val reason: String, val at: Int) : Verdict
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val verbose = args.contains("--verbose")
        val repo = repoRoot()
        val sourceRoot = File(repo, "src")
        if (!sourceRoot.isDirectory) {
            System.err.println("KDocAttachmentGuard: no src/ under ${repo.absolutePath}")
            exitProcess(2)
        }

        val files = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.absolutePath }
            .toList()

        val findings = mutableListOf<Finding>()
        val benign = mutableListOf<String>()
        var blocks = 0

        files.forEach { file ->
            val rel = repo.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            val text = file.readText()
            val lines = text.split("\n")
            val scan = scanSource(text)
            val fileBlocks = scan.blocks
            blocks += fileBlocks.size
            val firstDeclarationLine = try {
                KotlinSourceIndex.parseTopLevelDefinitions(text).minOfOrNull { it.line }
            } catch (t: Throwable) {
                // A parser failure must not silence the guard; treat the file as declaration-free,
                // which is the conservative choice (fewer benign exemptions, not more).
                null
            }
            fileBlocks.forEachIndexed { index, block ->
                when (
                    val verdict =
                        classify(rel, lines, scan.code, fileBlocks, index, block, firstDeclarationLine)
                ) {
                    null -> Unit
                    is Verdict.Hit -> findings += verdict.finding
                    is Verdict.Benign -> benign += "$rel:${block.startLine}: benign (${verdict.reason})"
                }
            }
        }

        println("KDocAttachmentGuard: scanned ${files.size} file(s), $blocks KDoc block(s)")
        if (verbose) {
            benign.forEach { println("  $it") }
            println("KDocAttachmentGuard: ${benign.size} benign site(s)")
        }

        if (findings.isEmpty()) {
            println("KDocAttachmentGuard ok — every KDoc block attaches to a declaration")
            return
        }

        System.err.println("KDocAttachmentGuard: ${findings.size} mis-attached KDoc block(s):")
        findings.forEach { System.err.println("  $it") }
        System.err.println(
            """
            |
            |Each of these is either dropped or attached to the WRONG declaration, and a dropped
            |block's prose is published under whatever symbol parses next. Fix by either
            |  - moving the block so the very next line is a declaration
            |    (KDocExtractor.parseDeclaration reads: class / interface / object / fun / val / var /
            |    typealias, after modifiers and after annotations *on their own line*), or
            |  - teaching KDocExtractor.parseDeclaration the form it cannot see, or
            |  - deleting the block if it documents nothing.
            |See dev_docs/kdoc_reference_contract.md.
            """.trimMargin()
        )
        exitProcess(1)
    }

    /**
     * The verdict for one block, or null when it attaches cleanly.
     *
     * Follows [KDocExtractor.extractKDocs]'s forward walk: blank lines, comment lines and whole-line
     * annotations are stepped over, a following KDoc block ends the walk, and the line the walk stops
     * on decides. [code] marks which lines carry anything outside a comment, which is how a
     * declaration-shaped line *inside* a block comment — the extractor cannot tell, and will happily
     * caption a symbol with prose meant for a file — becomes a finding rather than a pass.
     */
    private fun classify(
        path: String,
        lines: List<String>,
        code: BooleanArray,
        blocks: List<Block>,
        index: Int,
        block: Block,
        firstDeclarationLine: Int?,
    ): Verdict? {
        var i = block.endLine // 0-based index of the line *after* the block's last line
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty()) { i++; continue }

            val isCommentLine = !code.getOrElse(i) { true }
            if (isCommentLine && trimmed.startsWith("/**")) {
                // Stacked: this block documents nothing at all.
                if (isFileOverview(lines, blocks, index, block, firstDeclarationLine)) {
                    return Verdict.Benign("file overview, printed by StdlibReferenceGenerator", i + 1)
                }
                return Verdict.Hit(
                    Finding(
                        path, block.startLine, Kind.STACKED,
                        "a second KDoc block follows at line ${i + 1} with no declaration between them, " +
                            "so this one is discarded"
                    )
                )
            }

            // A line with no code on it is a comment line. The extractor has no idea — it feeds every
            // line to the parser — so a declaration-shaped line inside a block comment captures the
            // KDoc, which is worse than dropping it.
            if (isCommentLine) {
                if (KDocExtractor.parseDeclaration(trimmed) != null) {
                    return Verdict.Hit(
                        Finding(
                            path, block.startLine, Kind.DROPPED,
                            "the extractor attaches this block to line ${i + 1}, which is *inside a comment* " +
                                "(`${excerpt(trimmed)}`) — it parses lines, not syntax"
                        )
                    )
                }
                i++
                continue
            }

            val declarationPart = afterAnnotations(trimmed)
            if (isEnumConstant(lines, block, declarationPart)) {
                return Verdict.Benign("enum constant (StdlibReferenceGenerator reads these itself)", i + 1)
            }

            // The extractor skips any line beginning with `@`, so an annotation alone on its line is
            // transparent. An annotation with the declaration behind it on the same line takes that
            // declaration with it, and the block slides.
            if (trimmed.startsWith("@")) {
                if (declarationPart.isNotEmpty() && KDocExtractor.parseDeclaration(declarationPart) != null) {
                    return Verdict.Hit(
                        Finding(
                            path, block.startLine, Kind.DROPPED,
                            "line ${i + 1} puts an annotation and the declaration together " +
                                "(`${excerpt(trimmed)}`); the extractor skips the whole line. Break the " +
                                "annotation onto its own line"
                        )
                    )
                }
                // An annotation whose argument list runs onto following lines (`@Target(` and a list of
                // targets, the shape in KotlinScriptEngine.kt) is also transparent: none of the
                // continuation lines parse as a declaration either, so the extractor walks over the lot
                // and still lands on the declaration underneath. Step over the whole argument list so
                // those continuation lines are not mistaken for the attachment point.
                var depth = parenBalance(trimmed)
                i++
                while (depth > 0 && i < lines.size) {
                    depth += parenBalance(lines[i])
                    i++
                }
                continue
            }

            if (KDocExtractor.parseDeclaration(trimmed) != null) return null

            if (trimmed.startsWith("package ") || trimmed.startsWith("import ") || trimmed == "package") {
                return Verdict.Benign("file header above the package directive", i + 1)
            }
            return Verdict.Hit(
                Finding(
                    path, block.startLine, Kind.DROPPED,
                    "line ${i + 1} is not a declaration KDocExtractor.parseDeclaration can read " +
                        "(`${excerpt(trimmed)}`), so this block slides onto whatever parses next"
                )
            )
        }
        return Verdict.Hit(
            Finding(path, block.startLine, Kind.DROPPED, "nothing follows this block — it documents nothing")
        )
    }

    /**
     * Whether [block] is the file's own overview as [StdlibReferenceGenerator] defines one: the first
     * KDoc in the file, with no declaration above it, and separated from what follows by at least one
     * blank or `//` line.
     *
     * The blank line is the whole condition, not a formality: with it the generator prints the block
     * as the file's section prose, without it the generator reads the block as the *following*
     * declaration's summary — and if that declaration is not published (internal, or a `typealias`
     * the reference skips) the file's row goes empty. `imaging_simd.kt` shipped in exactly that state.
     */
    private fun isFileOverview(
        lines: List<String>,
        blocks: List<Block>,
        index: Int,
        block: Block,
        firstDeclarationLine: Int?,
    ): Boolean {
        if (index != 0 || blocks.isEmpty()) return false
        if (firstDeclarationLine != null && firstDeclarationLine <= block.endLine) return false
        var probe = block.endLine
        var sawGap = false
        while (probe < lines.size) {
            val trimmed = lines[probe].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//")) { sawGap = true; probe++; continue }
            break
        }
        return sawGap
    }

    /**
     * Whether [text] is an enum constant of the `enum class` this block sits inside.
     *
     * Needs both halves: the enclosing scope has to be an enum body (checked by reading back from the
     * `{` far enough to clear a multi-line constructor header) *and* the line has to look like a
     * constant rather than, say, a statement the extractor would also fail to parse.
     */
    private fun isEnumConstant(lines: List<String>, block: Block, text: String): Boolean {
        val braceLine = block.enclosingBraceLine ?: return false
        val headerStart = (braceLine - ENUM_HEADER_LOOKBACK).coerceAtLeast(1)
        val header = lines.subList(headerStart - 1, braceLine).joinToString(" ")
        if (!ENUM_HEADER.containsMatchIn(header)) return false
        return ENUM_CONSTANT.containsMatchIn(text)
    }

    /** How many lines back from an enum body's `{` to look for the `enum class` keyword. */
    private const val ENUM_HEADER_LOOKBACK = 6

    private val ENUM_HEADER = Regex("""\benum\s+class\b""")

    /** `HANN`, `HAMMING(2),`, `GAUSSIAN;` or a constant with a body — all shapes of enum entry. */
    private val ENUM_CONSTANT = Regex("""^[A-Z][A-Za-z0-9_]*\s*(\(|\{|,|;|$)""")

    private val ANNOTATION = Regex("""^@[\w:.]+\s*(\([^)]*\))?\s*""")

    /** [text] with every leading annotation removed, so "is there a declaration on this line too?". */
    private fun afterAnnotations(text: String): String {
        var s = text
        while (true) {
            val m = ANNOTATION.find(s) ?: return s.trim()
            if (m.range.first != 0) return s.trim()
            s = s.substring(m.range.last + 1).trimStart()
        }
    }

    /**
     * How much deeper in parentheses [line] leaves the reader, ignoring anything inside double quotes
     * (an annotation's message string is full of unbalanced brackets).
     */
    private fun parenBalance(line: String): Int {
        var depth = 0
        var inString = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '\\' && inString -> i++
                ch == '"' -> inString = !inString
                !inString && ch == '(' -> depth++
                !inString && ch == ')' -> depth--
            }
            i++
        }
        return depth
    }

    private fun excerpt(text: String): String =
        if (text.length <= EXCERPT_CHARS) text else text.take(EXCERPT_CHARS) + "…"

    private const val EXCERPT_CHARS = 60

    /**
     * The KDoc blocks in [text] and a per-line "does this line carry any code?" mask.
     *
     * Lexed rather than grepped, for two reasons. A KDoc-looking *string literal* is not a comment,
     * and the completion tests are full of such fixtures. And a closing marker that appears *inside* a
     * KDoc really does end the block — a wildcard `.wav` glob written into a comment on `2026-07-27`
     * ended one early and broke the build — so the guard has to see the boundaries the compiler sees.
     * Kotlin block comments nest, so depth is counted.
     */
    private data class Scan(val blocks: List<Block>, val code: BooleanArray)

    private fun scanSource(text: String): Scan {
        val out = mutableListOf<Block>()
        val braceStack = ArrayDeque<Int>()
        val code = BooleanArray(text.count { it == '\n' } + 1)
        var line = 1
        var i = 0
        val n = text.length
        fun markCode() {
            if (line - 1 in code.indices) code[line - 1] = true
        }
        while (i < n) {
            val ch = text[i]
            when {
                ch == '\n' -> { line++; i++ }

                ch.isWhitespace() -> i++

                ch == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') i++
                }

                ch == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val isKDoc = i + 2 < n && text[i + 2] == '*' && !(i + 3 < n && text[i + 3] == '/')
                    val startLine = line
                    var depth = 0
                    while (i < n) {
                        if (text[i] == '\n') { line++; i++; continue }
                        if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') { depth++; i += 2; continue }
                        if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                            depth--
                            i += 2
                            if (depth == 0) break
                            continue
                        }
                        i++
                    }
                    if (isKDoc) out += Block(startLine, line, braceStack.lastOrNull())
                }

                ch == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"' -> {
                    markCode()
                    i += 3
                    while (i < n && !(text[i] == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"')) {
                        if (text[i] == '\n') line++ else markCode()
                        i++
                    }
                    i = (i + 3).coerceAtMost(n)
                    markCode()
                }

                ch == '"' || ch == '\'' -> {
                    markCode()
                    val quote = ch
                    i++
                    while (i < n && text[i] != quote) {
                        if (text[i] == '\\') { i++ }
                        else if (text[i] == '\n') { line++; break }
                        i++
                    }
                    i++
                }

                ch == '{' -> { markCode(); braceStack.addLast(line); i++ }
                ch == '}' -> { markCode(); braceStack.removeLastOrNull(); i++ }

                else -> { markCode(); i++ }
            }
        }
        return Scan(out, code)
    }

    /**
     * The repository root, found by walking up from the working directory looking for the pair of
     * directories only the repo root has — the same rule [StdlibReferenceGenerator] uses, so both
     * tasks agree about where the tree is however Gradle sets the working directory.
     */
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
