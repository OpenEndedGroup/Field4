package afield.docs

import java.io.File
import kotlin.system.exitProcess

/*
FIELD_TEST
id: docs.source-pages.generate
runner: jvm-main
entrypoint: afield.docs.SourcePageGenerator
tags: [smoke, docs, reference, source, verification]
timeoutSec: 240
expect:
  exitCode: 0
  stdoutContains:
    - "SourcePageGenerator ok"
*/
/**
 * Renders Kotlin source files as docs pages: KDoc as prose, declarations as headings, and the bodies
 * in code fences **numbered from the lines they really occupy**.
 *
 * ### What this is for
 *
 * [StdlibReferenceGenerator] answers "what is already in the box?" — a symbol per row, a summary per
 * symbol. It is deliberately a *summary*, and a reader who wants the next question answered ("what
 * does it actually do?") has to leave the site. Meanwhile the published pages cite source constantly:
 * 172 `path:line` citations across the corpus, every one of them dead text. This is the destination
 * those two facts imply — the place a citation can point at, and the place a reference row can lead.
 *
 * ### The one property that makes it honest
 *
 * A page that lifts KDoc out of the source and puts the rest in fences is only trustworthy if the
 * fences still say where their code lives. So every fence carries `linenums="N"` set to its real first
 * line, and [verifyPage] checks the published artifact rather than the intent: it parses the fences
 * back out of the emitted markdown and asserts that each one is **verbatim** the source lines its
 * `linenums` claims. Nothing else about this generator matters if that fails, which is why it runs
 * over every page on every invocation rather than in a test somewhere.
 *
 * The cut comes from [KotlinSourcePartition], whose tiling invariant is what makes the fences able to
 * account for the file in the first place; `SourcePartitionGuard` holds that end.
 *
 * ### Why the pages are not committed
 *
 * Every other generated artifact in this repo is committed and drift-checked, and this one is not, for
 * a reason specific to what it contains: a source *mirror* changes whenever any line of its source
 * changes. Committing it would double every diff in the repository — edit a comment in `Functions.kt`
 * and a 10,000-line generated page changes with it — and make every source commit require a
 * regeneration step to keep a guard green. The reference pages are safe to commit precisely because
 * they summarise: they move when an API moves, not when a line does.
 *
 * So these are built into the docs tree by `tools/build_zensical_docs.py` and never tracked. The cost
 * is that a docs build without a compiled Kotlin tree has no source pages, which is handled where it
 * shows: `rewrite_source_citations` in `public/docs_hooks.py` links a citation only when its page was
 * generated, so the pages degrade to plain chips rather than to dead links.
 *
 * ### Running it
 *
 * ```
 * ./gradlew generateSourcePages                     # into build/source-pages
 * ./gradlew checkSourcePages                        # verify only, no output kept
 * ```
 */
object SourcePageGenerator {

    /** Where the pages live inside the docs tree, and therefore what their URLs look like. */
    const val SOURCE_REL = "reference/source"

    /**
     * A body longer than this ships collapsed.
     *
     * The number is a reading judgement rather than a measurement: past a screenful, a body stops
     * being context for the declaration above it and starts being the thing you scroll past to reach
     * the next declaration. Collapsed, the page stays a browsable index of the file; expanded, it is
     * the file. `Functions.kt` is 10,353 lines and is the case that makes this non-optional.
     */
    private const val FOLD_ABOVE_LINES = 40

    /**
     * The corpus, as a policy rather than a list.
     *
     * Two populations, for two different reasons. The **reference packages** are the surface a box can
     * call, so a reference row that cannot lead to the code is the gap this whole thing exists to
     * close. The **cited files** are wherever the published prose already points, which is a different
     * set: two thirds of the files cited in `public/docs` are outside those packages, `VisionPose.kt`
     * — the file that started this — among them.
     *
     * Everything else in `src/` is deliberately absent. 789 pages of internals is the shape
     * `20e64da5` deleted Dokka for; a page nobody has a reason to arrive at is not documentation.
     */
    private val REFERENCE_PACKAGE_ROOTS = listOf(
        "src/jvmMain/kotlin/afield/stdlib",
        "src/jvmMain/kotlin/afield/visualization",
        "src/jvmMain/kotlin/afield/four/synth",
    )

    /** One rendered page, and everything needed to check and index it. */
    data class Page(
        /** Docs-relative path, e.g. `reference/source/jvmMain/afield/graphics/VisionPose.md`. */
        val path: String,
        /** Repo-relative path of the source file it mirrors. */
        val source: String,
        val markdown: String,
        val lineCount: Int,
        val declarationCount: Int,
        /** Every line carrying a declaration heading on this page, ascending — the link targets. */
        val declarationLines: List<Int>,
        /** Pages under `public/docs` that cite this file, as (title, docs-relative path). */
        val citedBy: List<Pair<String, String>>,
    )

    /** One source file, read and cut, before any of it has been rendered. */
    private data class Prepared(
        val file: File,
        val relative: String,
        val partition: KotlinSourcePartition.Partition,
    )

    /** Where a KDoc `[Symbol]` reference can be sent: a page, and a line on it. */
    private data class Target(val page: String, val line: Int)

    /** A fence found in emitted markdown: where it claims to start, and what it contains. */
    private data class Fence(val startLine: Int, val lines: List<String>)

    @JvmStatic
    fun main(args: Array<String>) {
        val verifyOnly = args.contains("--check")
        val repo = locateRepoRoot()
        val explicitOut = args.firstOrNull { !it.startsWith("--") }?.takeIf { it.isNotBlank() }
        val outRoot = when {
            verifyOnly -> null
            explicitOut != null -> File(explicitOut)
            else -> File(repo, "build/source-pages")
        }

        val sources = collectSources(repo)
        if (sources.isEmpty()) {
            System.err.println("SourcePageGenerator: no sources selected under ${repo.absolutePath}")
            exitProcess(1)
        }

        val citations = citationIndex(repo)
        val prepared = sources.map { file ->
            val relative = file.relativeTo(repo).path.replace('\\', '/')
            Prepared(file, relative, KotlinSourcePartition.partition(file.readText()))
        }
        val crossReferences = crossReferenceIndex(prepared)
        val pages = prepared.map { render(it, citations, crossReferences) }

        val failures = mutableListOf<String>()
        pages.forEach { page ->
            failures += verifyPage(repo, page)
        }
        if (pages.sumOf { it.declarationCount } < 500) {
            failures += "only ${pages.sumOf { it.declarationCount }} declaration(s) across " +
                "${pages.size} page(s) — the partition looks broken, not the corpus small"
        }

        if (outRoot != null && failures.isEmpty()) {
            outRoot.mkdirs()
            pages.forEach { page ->
                val target = File(outRoot, page.path)
                target.parentFile?.mkdirs()
                target.writeText(page.markdown)
            }
            File(outRoot, "$SOURCE_REL/index.md").writeText(renderIndexPage(pages))
            File(outRoot, "$SOURCE_REL/$ANCHOR_INDEX").writeText(renderAnchorIndex(pages))
        }

        val totalLines = pages.sumOf { it.lineCount }
        val linked = pages.count { it.citedBy.isNotEmpty() }
        println(
            "SourcePageGenerator: ${pages.size} page(s), $totalLines source line(s), " +
                "${pages.sumOf { it.declarationCount }} declaration(s), $linked page(s) cited by prose" +
                (outRoot?.let { " -> ${it.absolutePath}" } ?: " (verify only)")
        )

        if (failures.isNotEmpty()) {
            System.err.println("SourcePageGenerator: ${failures.size} problem(s):")
            failures.take(40).forEach { System.err.println("  $it") }
            System.err.println(
                """
                |
                |A fence whose `linenums` does not match the source it shows is the one failure this
                |generator cannot be allowed to ship: the page looks right and every line number on it
                |is a lie. Fix in KotlinSourcePartition (the cut) or in SourcePageGenerator (the
                |rendering) — never by relaxing the check.
                """.trimMargin()
            )
            exitProcess(1)
        }

        println(
            "SourcePageGenerator ok — every fence on every page is verbatim source at the line it claims"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Corpus
    // ---------------------------------------------------------------------------------------------

    /** The files that get a page: the reference packages, plus whatever the published prose cites. */
    private fun collectSources(repo: File): List<File> {
        val fromPackages = REFERENCE_PACKAGE_ROOTS
            .map { File(repo, it) }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        val cited = citedFileNames(repo).mapNotNull { name -> locateByName(repo, name) }
        return (fromPackages + cited)
            .distinctBy { it.canonicalPath }
            .sortedBy { it.relativeToOrSelf(repo).path }
    }

    /** Every `Foo.kt` named by a `path:line` citation anywhere under `public/docs`. */
    private fun citedFileNames(repo: File): Set<String> {
        val docs = File(repo, "public/docs")
        if (!docs.isDirectory) return emptySet()
        val out = mutableSetOf<String>()
        docs.walkTopDown().filter { it.isFile && it.extension == "md" }.forEach { page ->
            CITATION.findAll(page.readText()).forEach { out += it.groupValues[1].substringAfterLast('/') }
        }
        return out
    }

    /**
     * The one `src/` file called [name], or null when there is none or more than one.
     *
     * Refusing an ambiguous name rather than picking one is the same rule `CitationGuard.locate`
     * follows, for the same reason: six basenames in `src/` are duplicated, and a page generated for
     * the wrong `Transforms.kt` would be wrong in a way nothing downstream could detect.
     */
    private fun locateByName(repo: File, name: String): File? {
        if (!name.endsWith(".kt")) return null
        val matches = File(repo, "src").walkTopDown()
            .filter { it.isFile && it.name == name }
            .toList()
        return matches.singleOrNull()
    }

    /** `Foo.kt:123` or `Foo.kt:123#anchor`, as written in a citation. */
    private val CITATION = Regex("""`([A-Za-z0-9_./-]+\.kt):(\d+)(?:-\d+)?(?:#[^`]+)?`""")

    /** Which pages cite which source file, keyed by bare filename. */
    private fun citationIndex(repo: File): Map<String, List<Pair<String, String>>> {
        val docs = File(repo, "public/docs")
        if (!docs.isDirectory) return emptyMap()
        val out = mutableMapOf<String, MutableList<Pair<String, String>>>()
        docs.walkTopDown().filter { it.isFile && it.extension == "md" }.sortedBy { it.path }.forEach { page ->
            val text = page.readText()
            val names = CITATION.findAll(text).map { it.groupValues[1].substringAfterLast('/') }.toSet()
            if (names.isEmpty()) return@forEach
            val relative = page.relativeTo(docs).path.replace('\\', '/')
            val title = text.lineSequence()
                .firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")?.trim()
                ?: relative.removeSuffix(".md")
            names.forEach { out.getOrPut(it) { mutableListOf() } += title to relative }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    /**
     * The docs-relative page path for a source file.
     *
     * `src/jvmMain/kotlin/afield/graphics/VisionPose.kt` becomes
     * `reference/source/jvmMain/afield/graphics/VisionPose.md`. The source set stays in the path and
     * the `kotlin/` segment does not: the six duplicated basenames in `src/` are all distinct by full
     * path, and dropping the source set would collide `jsMain/afield/Main.kt` with
     * `jsVision/afield/Main.kt`.
     */
    fun pagePathFor(relativeSource: String): String {
        val trimmed = relativeSource
            .removePrefix("src/")
            .replace("/kotlin/", "/")
            .removeSuffix(".kt")
        return "$SOURCE_REL/$trimmed.md"
    }

    /**
     * Every declaration name that resolves to exactly one place in the corpus.
     *
     * A KDoc that writes `[FLine]` is doing the thing these pages exist to encourage, and it should
     * come out as a link. Whether it can depends on the name being unambiguous: `size` is a member of
     * dozens of types and points nowhere useful, so a name claimed by more than one file is dropped
     * from the index entirely and left as plain code on the page. Same-file references are resolved
     * before this is consulted, which is where most of them are and where context makes them
     * unambiguous regardless.
     */
    private fun crossReferenceIndex(prepared: List<Prepared>): Map<String, Target> {
        val seen = mutableMapOf<String, MutableList<Target>>()
        prepared.forEach { entry ->
            val page = pagePathFor(entry.relative)
            val headed = headedDeclarationLines(entry.partition)
            entry.partition.allSpans()
                .filter {
                    it.kind == KotlinSourcePartition.Kind.DECLARATION &&
                        it.name.isNotEmpty() &&
                        it.declarationLine in headed
                }
                .forEach { span ->
                    val targets = seen.getOrPut(span.name) { mutableListOf() }
                    if (targets.none { it.page == page }) targets += Target(page, span.declarationLine)
                }
        }
        return seen.filterValues { it.size == 1 }.mapValues { (_, targets) -> targets.single() }
    }

    private fun render(
        entry: Prepared,
        citations: Map<String, List<Pair<String, String>>>,
        crossReferences: Map<String, Target>,
    ): Page {
        val file = entry.file
        val relative = entry.relative
        val partition = entry.partition
        val citedBy = citations[file.name].orEmpty().distinct()
        val pagePath = pagePathFor(relative)
        val upToDocs = "../".repeat(pagePath.count { it == '/' })

        val body = StringBuilder()
        body.appendLine(
            "<!-- DO NOT EDIT — generated by afield.docs.SourcePageGenerator from $relative. -->"
        )
        body.appendLine("<!-- Regenerate: ./gradlew generateSourcePages -->")
        // The class is how `assets/sticky-headings.js` recognises a source page: the sticky-hierarchy
        // treatment is worth it on a file-length page of nested declarations and would be noise on a
        // hand-written one.
        body.appendLine("# ${file.name} { .field-source-page $SEARCH_EXCLUDE }")
        body.appendLine()
        body.append("`$relative` · ${partition.lineCount} lines")
        if (citedBy.isNotEmpty()) {
            body.append(" · cited by ")
            body.append(citedBy.joinToString(", ") { (title, page) -> "[$title]($upToDocs$page)" })
        }
        body.appendLine()
        body.appendLine()

        val declarationLines = mutableListOf<Int>()
        val headed = headedDeclarationLines(partition)
        val localDeclarations = partition.allSpans()
            .filter {
                it.kind == KotlinSourcePartition.Kind.DECLARATION &&
                    it.name.isNotEmpty() &&
                    it.declarationLine in headed
            }
            .associate { it.name to it.declarationLine }
        val links = Links(upToDocs, localDeclarations, crossReferences)
        val fences = Fences(body, partition)
        emitSpans(body, partition, partition.spans, depth = 2, headings = declarationLines, links = links, fences = fences)
        fences.flush()

        return Page(
            path = pagePath,
            source = relative,
            markdown = body.toString(),
            lineCount = partition.lineCount,
            declarationCount = declarationLines.size,
            declarationLines = declarationLines.sorted(),
            citedBy = citedBy,
        )
    }

    /**
     * Emits a level of the span tree, moving each declaration's own doc comment *below* its heading.
     *
     * A comment span comes before the declaration it documents in the source and has to come after it
     * on the page, or the prose reads as a footnote to whatever was above. Whether a block belongs to
     * the declaration below it is decided by **adjacency**, which is the rule
     * [StdlibReferenceGenerator] already applies for the same distinction: a KDoc written flush against
     * a declaration documents it, and one separated by a blank line is a file overview or a section
     * note and stays where the author put it.
     */
    private fun emitSpans(
        out: StringBuilder,
        partition: KotlinSourcePartition.Partition,
        spans: List<KotlinSourcePartition.Span>,
        depth: Int,
        headings: MutableList<Int>,
        links: Links,
        fences: Fences,
    ) {
        var pending: String? = null
        spans.forEachIndexed { index, span ->
            val isComment = span.kind == KotlinSourcePartition.Kind.DOC ||
                span.kind == KotlinSourcePartition.Kind.COMMENT
            if (isComment) {
                val text = prose(partition, span, links)
                if (text.isBlank()) return@forEachIndexed
                val next = spans.getOrNull(index + 1)
                val documentsNext = next != null &&
                    next.kind == KotlinSourcePartition.Kind.DECLARATION &&
                    next.startLine == span.endLine + 1
                if (documentsNext) {
                    pending = if (pending == null) text else "$pending\n\n$text"
                } else {
                    fences.flush()
                    out.appendLine(text)
                    out.appendLine()
                }
                return@forEachIndexed
            }
            emitSpan(out, partition, span, depth, headings, pending, links, fences)
            pending = null
        }
        pending?.let {
            fences.flush()
            out.appendLine(it)
            out.appendLine()
        }
    }

    /** Emits one non-comment span and its children, with [doc] as the prose under its heading. */
    private fun emitSpan(
        out: StringBuilder,
        partition: KotlinSourcePartition.Partition,
        span: KotlinSourcePartition.Span,
        depth: Int,
        headings: MutableList<Int>,
        doc: String?,
        links: Links,
        fences: Fences,
    ) {
        when (span.kind) {
            KotlinSourcePartition.Kind.DOC, KotlinSourcePartition.Kind.COMMENT -> Unit

            KotlinSourcePartition.Kind.HEADER -> {
                fences.add(span.startLine, span.endLine, label = "package and imports")
            }

            KotlinSourcePartition.Kind.FILLER -> {
                // Blank runs between declarations carry nothing a reader needs; anything with code in
                // it does, even when the scanner could not name it.
                fences.add(span.startLine, span.endLine, label = null)
            }

            KotlinSourcePartition.Kind.DECLARATION -> {
                // A private declaration cannot be called from a box, so it gets no heading: it stays
                // in the page as code, where someone reading the file will find it, and stays out of
                // the outline, the reading rail and the anchor set, which are all answering "what can
                // I use?". Three private colour constants in a row produced three headings and three
                // one-line code blocks on `alife_field.kt` before this.
                if (!span.isCallable(partition)) {
                    doc?.let {
                        fences.flush()
                        out.appendLine(it)
                        out.appendLine()
                    }
                    fences.add(span.startLine, span.endLine, label = null)
                    return
                }

                fences.flush()
                val heading = "#".repeat(depth.coerceAtMost(6))
                val whole = wholeLineHeading(partition, span)
                val signature = whole ?: signatureOf(partition, span)
                val highlighted = highlightDeclaration(
                    signature,
                    endsWithCall = whole == null && endsWithCall(partition, span),
                )
                out.appendLine("$heading $highlighted { #L${span.declarationLine} $SEARCH_EXCLUDE }")
                out.appendLine()
                headings += span.declarationLine
                doc?.let {
                    out.appendLine(it)
                    out.appendLine()
                }

                // A short one-liner is already entirely in its own heading; repeating it underneath in
                // a fence of one line says nothing twice.
                if (whole != null) return

                if (span.children.isEmpty()) {
                    fences.add(span.startLine, span.endLine, label = "body")
                    return
                }

                // A container: its own signature lines, then its members, then whatever closes it.
                // There is nothing to interleave — KotlinSourcePartition's children *tile* the body
                // interior, so the only text belonging to the container itself is the head before the
                // first member and the tail after the last.
                val firstChild = span.children.first()
                val lastChild = span.children.last()
                // The head of a container is its signature line and whatever precedes it. When that is
                // *only* the signature line, and the first member gets a heading of its own, a fence
                // for it holds nothing the heading above has not already said — usually a single `{`.
                // Kept when there are annotations to show, or when the members are unheaded and will
                // merge with it.
                val headIsBareSignature = span.startLine == span.declarationLine &&
                    firstChild.startLine - 1 == span.declarationLine &&
                    firstChild.kind == KotlinSourcePartition.Kind.DECLARATION &&
                    firstChild.isCallable(partition)
                if (firstChild.startLine > span.startLine && !headIsBareSignature) {
                    fences.add(span.startLine, firstChild.startLine - 1, label = "declaration")
                }
                emitSpans(out, partition, span.children, depth + 1, headings, links, fences)
                if (lastChild.endLine < span.endLine) {
                    fences.add(lastChild.endLine + 1, span.endLine, label = null, skipIfOnlyClosers = true)
                }
            }
        }
    }

    /**
     * Collects fence ranges and merges the ones that touch, so a run of code with nothing said about
     * it comes out as one block.
     *
     * Spans tile the file, so consecutive spans are always contiguous — which means "adjacent code
     * blocks with nothing between them" is exactly "ranges where the next `from` is the previous `to`
     * plus one". Before this, four unheaded private declarations in `charts.kt` published as four
     * separate one-line code blocks with gaps between them, when the source is one continuous passage.
     *
     * Prose and headings are what break a run: both call [flush] before they write. Nothing else does,
     * which is what lets a private declaration, the blank line after it, and the next private
     * declaration end up in the same fence.
     */
    private class Fences(
        private val out: StringBuilder,
        private val partition: KotlinSourcePartition.Partition,
    ) {
        private var from = 0
        private var to = 0
        private var label: String? = null

        fun add(from: Int, to: Int, label: String?, skipIfOnlyClosers: Boolean = false) {
            if (to < from) return
            // A container's lone closing brace is worth dropping on its own and worth keeping when it
            // can join the code above it.
            if (skipIfOnlyClosers && this.from == 0 && onlyClosers(from, to)) return
            when {
                this.from == 0 -> {
                    this.from = from
                    this.to = to
                    this.label = label
                }

                from == this.to + 1 -> {
                    this.to = to
                    if (this.label == null) this.label = label
                }

                else -> {
                    flush()
                    this.from = from
                    this.to = to
                    this.label = label
                }
            }
        }

        fun flush() {
            if (from == 0) return
            val (f, t, l) = Triple(from, to, label)
            from = 0
            to = 0
            label = null
            writeFence(out, partition, f, t, l)
        }

        private fun onlyClosers(from: Int, to: Int): Boolean =
            (from..to).all { line ->
                val text = partition.lines.getOrNull(line - 1).orEmpty()
                text.isBlank() || text.all { it in CLOSERS }
            }
    }

    /**
     * A fence for source lines [from]..[to], numbered from [from], collapsed when long.
     *
     * Trailing blank lines are trimmed off the *rendered* fence — never off its start — because a
     * fence's first line is what `linenums` promises and a blank line at the end of a body carries
     * nothing. [verifyPage] checks the promise that remains.
     */
    private fun writeFence(
        out: StringBuilder,
        partition: KotlinSourcePartition.Partition,
        from: Int,
        to: Int,
        label: String?,
    ) {
        // Trim blank lines off both ends — and move `from` with the leading trim, so the number the
        // fence declares still belongs to the line it now starts with.
        var first = from
        while (first < to && partition.lines.getOrNull(first - 1)?.isBlank() == true) first++
        var last = to
        while (last > first && partition.lines.getOrNull(last - 1)?.isBlank() == true) last--
        if (last < first) return
        val from = first
        val lines = (from..last).mapNotNull { partition.lines.getOrNull(it - 1) }
        if (lines.isEmpty() || lines.all { it.isBlank() }) return

        val count = lines.size
        val folded = count > FOLD_ABOVE_LINES
        val indent = if (folded) "    " else ""
        if (folded) {
            out.appendLine("""??? note "${label ?: "source"} — $count lines, from line $from"""")
            out.appendLine()
        }
        out.appendLine("$indent```diffstatic {data-lang=kotlin data-start-line=$from}")
        // Every line goes out exactly as it is, indent prefix and all — including a line that is
        // whitespace rather than empty. Normalising those to "" cost nothing visually and broke
        // verbatim equality on 15 lines across two files, which is precisely the check's whole point.
        lines.forEach { out.appendLine("$indent$it") }
        out.appendLine("$indent```")
        out.appendLine()
    }

    /**
     * Everything needed to turn a name in a comment into a link, from one page's point of view.
     *
     * [local] is consulted before [global]: a KDoc that says `[latch]` in the file that declares
     * `latch` means that one, and resolving it in-page is both correct and cheaper than reasoning
     * about the other twelve `latch`es in the tree.
     */
    private data class Links(
        val upToDocs: String,
        val local: Map<String, Int>,
        val global: Map<String, Target>,
    )

    /**
     * Turns a KDoc `[Symbol]` reference into a link to that symbol's own line.
     *
     * This is the feature that makes cross-referencing in source worth doing: write `[FLine]` in a
     * comment and the published page sends the reader to `FLine`'s declaration. The rule for when it
     * does is the one the rest of the site follows — a reference is linked only when it resolves, and
     * an unresolved one degrades to plain inline code rather than leaking raw brackets or becoming a
     * link to nothing.
     *
     * Both KDoc forms are handled, and three things are deliberately left alone: text inside a fenced
     * block (it is code, and rewriting it would corrupt it), a `[text](url)` markdown link (the
     * lookahead for `(` is what stops `[wfc_examples.md](../wfc_examples.md)` becoming nonsense), and
     * a reference whose name matches nothing.
     */
    private fun linkifySymbols(text: String, links: Links): String {
        if ('[' !in text) return text
        var inFence = false
        return text.split("\n").joinToString("\n") { line ->
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                return@joinToString line
            }
            if (inFence) return@joinToString line
            var out = KDOC_ALTERNATE.replace(line) { match ->
                val display = match.groupValues[1]
                resolve(match.groupValues[2], links)?.let { "[$display]($it)" } ?: "`$display`"
            }
            out = KDOC_SIMPLE.replace(out) { match ->
                val name = match.groupValues[1]
                resolve(name, links)?.let { "[$name]($it)" } ?: "`$name`"
            }
            out
        }
    }

    /** The URL for a KDoc reference to [name], or null when it does not resolve. */
    private fun resolve(name: String, links: Links): String? {
        val candidates = listOf(name, name.substringAfterLast('.'))
        candidates.forEach { candidate ->
            links.local[candidate]?.let { return "#L$it" }
        }
        candidates.forEach { candidate ->
            links.global[candidate]?.let { return "${links.upToDocs}${it.page}#L${it.line}" }
        }
        return null
    }

    /** `[display][Symbol]`. */
    private val KDOC_ALTERNATE = Regex("""\[([^\]\[]+)]\[([A-Za-z_][\w.]*)]""")

    /** `[Symbol]`, but never the `[text]` half of a markdown link. */
    private val KDOC_SIMPLE = Regex("""\[([A-Za-z_][\w.]*)](?!\()""")

    /** Characters a line can consist entirely of and still carry nothing worth a fence. */
    private val CLOSERS = setOf('}', ')', ']', ',', ';', ' ', '\t')

    /** Longest declaration that is allowed to live entirely inside its own heading. */
    private const val WHOLE_LINE_HEADING_LIMIT = 96

    /**
     * The declaration's whole line, when it is a single short line and nothing is gained by a fence.
     *
     * `private const val ALIFE_LABEL = "#c8cee0"` as a heading followed by a one-line code block
     * showing the same characters is the page saying it twice. Above the limit, or over more than one
     * line, the heading keeps the signature and the fence carries the rest.
     */
    private fun wholeLineHeading(
        partition: KotlinSourcePartition.Partition,
        span: KotlinSourcePartition.Span,
    ): String? {
        if (span.startLine != span.endLine || span.children.isNotEmpty()) return null
        val line = partition.lines.getOrNull(span.startLine - 1)?.trim() ?: return null
        if (line.length > WHOLE_LINE_HEADING_LIMIT) return null
        return line.replace("`", "'")
    }

    /**
     * Every line on [partition]'s page that will carry a heading, and therefore an `#L…` anchor.
     *
     * The single definition of "can be linked to", used by the three things that must agree about it:
     * the page (which emits the headings), this generator's own KDoc cross-references, and
     * [StdlibReferenceGenerator]'s reference rows. They did not agree for one build — private
     * declarations stopped getting headings while the cross-referencer still pointed at them, and
     * zensical found 752 links to anchors that no longer existed.
     */
    internal fun headedDeclarationLines(partition: KotlinSourcePartition.Partition): Set<Int> {
        val out = mutableSetOf<Int>()
        fun walk(spans: List<KotlinSourcePartition.Span>) {
            spans.forEach { span ->
                if (span.kind != KotlinSourcePartition.Kind.DECLARATION) {
                    walk(span.children)
                    return@forEach
                }
                // Descent stops at a private declaration, exactly as the emitter's does: a public
                // member of a private class is no more reachable than the class, and treating it as
                // headed produced 98 links to anchors the page never emitted.
                if (!span.isCallable(partition)) return@forEach
                out += span.declarationLine
                walk(span.children)
            }
        }
        walk(partition.spans)
        return out
    }

    /**
     * Whether a declaration is one a box could reach — the test for giving it a heading.
     *
     * Reads the modifiers off the declaration line rather than trusting the partition's `name`,
     * because that is where `private` is written. Deliberately only `private`: `internal` is reachable
     * from live-coded box code compiled into the same module, so hiding it would be wrong.
     */
    private fun KotlinSourcePartition.Span.isCallable(
        partition: KotlinSourcePartition.Partition,
    ): Boolean {
        // An enum entry is a value in a list, not a section of a file. `FaustControlType` has seven,
        // and giving each one a heading turned a five-line enum into seven screens of rules and
        // whitespace with no code between them — while the `enum class … {` line it belonged to was
        // stranded in a code block of its own. Unheaded, they coalesce with that line into the one
        // block the source actually is. An enum's *methods* are unaffected and still get headings.
        if (declKind == "enum entry") return false
        val line = partition.code.getOrNull(declarationLine - 1)?.trim().orEmpty()
        // Only the modifiers *before* the declaration keyword decide the declaration's own
        // visibility. Scanning the whole line read `class WfcRules private constructor(` as a private
        // class — the `private` belongs to the constructor — so it lost its heading, its members were
        // never split out, and its whole 355-line body came out as one fence with every member's KDoc
        // asterisks showing inside it.
        for (token in line.split(' ', '\t')) {
            val word = token.takeWhile { it.isLetter() }
            if (word in DECLARATION_KEYWORDS) return true
            if (word == "private") return false
        }
        return true
    }

    /** Keywords that end the modifier run, and so end the search for this declaration's visibility. */
    private val DECLARATION_KEYWORDS = setOf(
        "class", "interface", "object", "fun", "val", "var", "typealias", "init", "constructor",
    )

    /**
     * The declaration's signature, as a heading.
     *
     * Cut at the first top-level `=` or `{`, which is where the declaration stops and its *value*
     * begins. Without that, `private const val ALIFE_NOTE_STYLE = "color:#8a90a0;font-size:10px;…"`
     * is the heading, wrapping to three lines and burying the name it exists to announce — and the
     * value is on the very next line anyway, in the fence, where it belongs. A trailing `(` from a
     * multi-line parameter list goes too, for the same reason.
     */
    private fun signatureOf(
        partition: KotlinSourcePartition.Partition,
        span: KotlinSourcePartition.Span,
    ): String {
        val raw = partition.lines.getOrNull(span.declarationLine - 1)?.trim().orEmpty()
        var depth = 0
        var cut = raw.length
        var at = 0
        while (at < raw.length) {
            when (raw[at]) {
                '(', '[', '<' -> depth++
                ')', ']', '>' -> depth--
                '{' -> if (depth == 0) { cut = at; at = raw.length }
                '=' -> {
                    val next = raw.getOrNull(at + 1)
                    val previous = raw.getOrNull(at - 1)
                    val comparison = next == '=' || previous in setOf('=', '!', '<', '>')
                    if (depth == 0 && !comparison) { cut = at; at = raw.length }
                }
            }
            at++
        }
        val trimmed = raw.take(cut).trim()
        val cleaned = trimmed.removeSuffix("(").trim()
            .ifEmpty { span.name.ifEmpty { span.declKind } }
        return cleaned.replace("`", "'").take(160)
    }

    /** Whether the declaration line's signature was cut at an opening parenthesis. */
    private fun endsWithCall(
        partition: KotlinSourcePartition.Partition,
        span: KotlinSourcePartition.Span,
    ): Boolean = partition.lines.getOrNull(span.declarationLine - 1)?.trim()?.endsWith("(") == true

    /**
     * A declaration's signature, highlighted, with its own name bold even when the signature was cut.
     *
     * `highlightSignature` marks the declaration's name `nf` — the class the stylesheet bolds — only
     * when a `(` follows it, which is how it tells a function name from a value name. A multi-line
     * signature is cut at that very parenthesis, so `fun points(` became `fun points` and the name
     * came out as an ordinary identifier. Highlighting the text *with* the parenthesis and then
     * removing the one span it produced gets both: a bold name and a heading that does not trail off
     * mid-signature.
     */
    private fun highlightDeclaration(signature: String, endsWithCall: Boolean): String {
        if (!endsWithCall) return StdlibReferenceGenerator.highlightSignature(signature)
        val html = StdlibReferenceGenerator.highlightSignature("$signature(")
        val tail = """<span class="p">(</span></code>"""
        return if (html.endsWith(tail)) html.removeSuffix(tail) + "</code>" else html
    }

    /** A comment span as markdown prose: markers stripped, headings demoted, fences given the site's treatment. */
    private fun prose(
        partition: KotlinSourcePartition.Partition,
        span: KotlinSourcePartition.Span,
        links: Links,
    ): String {
        val block = (span.startLine..span.endLine)
            .mapNotNull { partition.lines.getOrNull(it - 1) }
            .joinToString("\n")
        val stripped = if (span.kind == KotlinSourcePartition.Kind.DOC) {
            StdlibReferenceGenerator.stripKDocMarkers(block)
        } else {
            stripPlainCommentMarkers(block)
        }
        val treated = bannersToHeadings(
            StdlibReferenceGenerator.fencesToDiffstatic(
                StdlibReferenceGenerator.demoteHeadings(stripped)
            )
        )
        return linkifySymbols(rebaseRelativeLinks(treated, links.upToDocs), links).trim()
    }

    /**
     * Re-points a KDoc's relative links at this page's depth.
     *
     * A link written in a KDoc is authored for where that KDoc has always been published — a category
     * page at `reference/<category>.md` — so `wfc.kt` says `../wfc_examples.md` and means
     * `docs/wfc_examples.md`. Rendered five directories deeper, that same text points at nothing, and
     * zensical says so: three broken links on the first build of these pages, all of this shape.
     *
     * So each relative target is resolved as the reference page would have resolved it, then
     * re-expressed from here. Absolute URLs, in-page fragments and site-root paths are left alone.
     */
    private fun rebaseRelativeLinks(text: String, upToDocs: String): String =
        MARKDOWN_LINK.replace(text) { match ->
            val target = match.groupValues[2]
            if (
                target.startsWith("http://") || target.startsWith("https://") ||
                target.startsWith("#") || target.startsWith("/") || target.startsWith("mailto:")
            ) {
                match.value
            } else {
                "[${match.groupValues[1]}](${upToDocs + normalisePath("reference/$target")})"
            }
        }

    /** `a/b/../c` as `a/c`, for link targets only — no filesystem involved. */
    private fun normalisePath(path: String): String {
        val out = mutableListOf<String>()
        path.split('/').forEach { part ->
            when {
                part == "." || part.isEmpty() -> Unit
                part == ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.size - 1) else out += part
                else -> out += part
            }
        }
        return out.joinToString("/")
    }

    /** `[text](target)`, the only link form KDoc prose reaches this generator in. */
    private val MARKDOWN_LINK = Regex("""\[([^\]]*)]\(([^)\s]+)\)""")

    /**
     * Rule characters a banner comment can be drawn with.
     *
     * Box-drawing characters are here because that is what the source actually uses:
     * `// ── construction ──────────────` is the house style in `charts.kt` and its neighbours.
     */
    private const val RULE_CHARS = "=\\-*#_~─━═╌┄"

    /** A line of nothing but rule characters — the drawn part of a banner comment. */
    private val BANNER_RULE = Regex("""^\s*[$RULE_CHARS]{3,}\s*${'$'}""")

    /**
     * `── construction ──────────` — a banner drawn on one line rather than three.
     *
     * Leaving this alone published worse than the three-line case, because the trailing run is long:
     * justified prose pushed the title out to the right margin and wrapped the rule onto the next two
     * lines, so the page showed a floating word above two stray horizontal lines.
     */
    private val BANNER_INLINE = Regex(
        """^\s*[$RULE_CHARS]{2,}\s+(\S.*?)\s+[$RULE_CHARS]{3,}\s*${'$'}"""
    )

    /**
     * Turns an ASCII banner comment into a real heading.
     *
     * Field's source marks its sections like this:
     *
     * ```
     * // ==========================================
     * // GrayScottState
     * // ==========================================
     * ```
     *
     * Strip the slashes and markdown reads the *last* two lines as a setext heading and the first as a
     * paragraph of equals signs — which is exactly how it published: a wall of `=`, then a stray
     * right-floating title. The author meant a section heading, so that is what this emits. Level 1,
     * because a banner sits above the declarations under it and those are level 2.
     *
     * A rule with no title above or below it is dropped: it was drawing a line, and the page already
     * has rules between sections.
     */
    private fun bannersToHeadings(text: String): String {
        if (text.none { it in RULE_CHARS }) return text
        val lines = text.split("\n")
        val out = mutableListOf<String>()
        var index = 0
        var inFence = false
        while (index < lines.size) {
            val line = lines[index]
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                out += line
                index++
                continue
            }
            if (!inFence) {
                val inline = BANNER_INLINE.matchEntire(line)
                if (inline != null && inline.groupValues[1].any { it.isLetterOrDigit() }) {
                    out += "# ${inline.groupValues[1]}"
                    index++
                    continue
                }
            }
            if (inFence || !BANNER_RULE.matches(line)) {
                out += line
                index++
                continue
            }
            val title = lines.getOrNull(index + 1)?.trim().orEmpty()
            val closing = lines.getOrNull(index + 2).orEmpty()
            if (title.isNotEmpty() && !BANNER_RULE.matches(title) && BANNER_RULE.matches(closing)) {
                out += "# $title"
                index += 3
            } else {
                // A lone rule: it was drawing a line, not titling anything.
                index++
            }
        }
        return out.joinToString("\n")
    }

    /**
     * A plain comment block or `//` run as prose.
     *
     * [StdlibReferenceGenerator.stripKDocMarkers] knows the doc-comment shape and leaves a `//` run
     * untouched, which published `// FloatArray members give a data class reference equality.` with
     * the slashes still on it. These are the author's sentences either way; the punctuation that made
     * them a comment is not part of what they say.
     */
    private fun stripPlainCommentMarkers(block: String): String =
        block.lines().joinToString("\n") { line ->
            var text = line.trim()
            listOf("/**", "*/", "/*", "//", "*").firstOrNull { text.startsWith(it) }?.let {
                text = text.removePrefix(it)
            }
            text.removeSuffix("*/").trimEnd().let { if (it.startsWith(" ")) it.substring(1) else it }
        }.trim()

    // ---------------------------------------------------------------------------------------------
    // Verification
    // ---------------------------------------------------------------------------------------------

    /** Opening fence of an emitted source block, with the line it claims to start at. */
    private val FENCE_OPEN = Regex("""^\s*```diffstatic \{data-lang=kotlin data-start-line=(\d+)}\s*$""")
    private val FENCE_CLOSE = Regex("""^\s*```\s*$""")

    /**
     * Reads the fences back out of [page]'s markdown and checks each against the source it claims.
     *
     * Deliberately parses the artifact rather than trusting the renderer: the failure being guarded
     * against is a page whose line numbers are wrong, and the only evidence that cannot be
     * circularly reassuring is the published text itself.
     */
    fun verifyPage(repo: File, page: Page): List<String> {
        val source = File(repo, page.source).readLines()
        val failures = mutableListOf<String>()
        var covered = 0

        fencesIn(page.markdown).forEach { fence ->
            val start = fence.startLine
            if (start < 1 || start > source.size) {
                failures += "${page.path}: a fence claims line $start, outside ${page.source}'s ${source.size} lines"
                return@forEach
            }
            fence.lines.forEachIndexed { offset, shown ->
                val at = start + offset
                val actual = source.getOrNull(at - 1)
                when {
                    actual == null ->
                        failures += "${page.path}: fence from $start runs past the end of ${page.source} at $at"
                    actual != shown ->
                        failures += "${page.path}:$at — the fence shows `${shown.trim().take(60)}` " +
                            "but ${page.source}:$at is `${actual.trim().take(60)}`"
                }
            }
            covered += fence.lines.size
        }

        if (covered == 0 && source.size > 5) {
            failures += "${page.path}: no fences at all, for a ${source.size}-line file"
        }
        return failures
    }

    /** Every fence in [markdown], dedented, with the line number it declares. */
    private fun fencesIn(markdown: String): List<Fence> {
        val out = mutableListOf<Fence>()
        val lines = markdown.split("\n")
        var index = 0
        while (index < lines.size) {
            val open = FENCE_OPEN.matchEntire(lines[index])
            if (open == null) {
                index++
                continue
            }
            val indent = lines[index].takeWhile { it == ' ' }
            val body = mutableListOf<String>()
            index++
            while (index < lines.size && !FENCE_CLOSE.matchEntire(lines[index]).let { it != null }) {
                body += lines[index].removePrefix(indent)
                index++
            }
            index++
            out += Fence(open.groupValues[1].toInt(), body)
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Index
    // ---------------------------------------------------------------------------------------------

    /** The listing page for the source tree, one row per file. */
    private fun renderIndexPage(pages: List<Page>): String = buildString {
        appendLine("<!-- DO NOT EDIT — generated by afield.docs.SourcePageGenerator. -->")
        appendLine("# Field's source, page by page")
        appendLine()
        appendLine(
            "Every file below is rendered with its KDoc as prose, its declarations as headings, and " +
                "its code in fences numbered from the lines they really occupy — so a citation like " +
                "`VisionPose.kt:82` lands on the declaration that contains line 82, and the number " +
                "beside it is the number in the file."
        )
        appendLine()
        appendLine(
            "**${pages.size} files, ${pages.sumOf { it.lineCount }} lines.** These are the files the " +
                "reference pages describe, plus every file the published prose cites. The rest of " +
                "`src/` is not here on purpose: a page nobody has a reason to arrive at is not " +
                "documentation."
        )
        appendLine()
        appendLine("| File | Lines | Declarations | Cited by |")
        appendLine("| --- | --- | --- | --- |")
        pages.sortedBy { it.source.substringAfterLast('/').lowercase() }.forEach { page ->
            val name = page.source.substringAfterLast('/')
            val href = page.path.removePrefix("$SOURCE_REL/")
            val cites = page.citedBy.joinToString(", ") { (title, target) -> "[$title](../../$target)" }
            appendLine("| [$name]($href) | ${page.lineCount} | ${page.declarationCount} | $cites |")
        }
    }

    /**
     * Keeps a page out of the search index, one heading at a time.
     *
     * Zensical indexes a page as a list of sections, each headed by an `h1`–`h6`, and drops a section
     * whose heading carries this attribute — so excluding a page means marking every heading on it.
     *
     * Not a preference. The first build with source pages in it produced a **17 MB** `search.json`
     * that the browser refused to load at all (`net::ERR_FAILED`), which is a worse search experience
     * than no source pages: the whole site's search stopped working. Indexing 175,000 lines of code
     * text was never going to be useful anyway, and the loss is small because the *reference* pages
     * already make every public symbol searchable and every one of them links here.
     */
    private const val SEARCH_EXCLUDE = "data-search-exclude=\"true\""

    /**
     * The docs URL for a source file, and optionally a line in it — the contract other code links by.
     *
     * Field's completion panel can send a reader from a symbol straight to its page with this, and
     * anything else that knows a repo-relative path and a line can do the same. Site-relative, with no
     * leading slash, because the site is served from a subpath in production and from the root
     * locally.
     *
     * The `#L…` fragment exists only when [line] is a line that carries a *declaration*: those are the
     * lines the page gives a heading, and therefore an anchor. A line in the middle of a body has no
     * anchor of its own, and a URL carrying one lands at the top of the page rather than nowhere —
     * which is why the citation linker in `docs_hooks` resolves to the enclosing declaration first
     * rather than passing a body line through.
     */
    fun docsUrlFor(relativeSource: String, line: Int? = null): String {
        val page = pagePathFor(relativeSource).removeSuffix(".md")
        return if (line == null) "$page/" else "$page/#L$line"
    }

    /** Where the citation linker looks for the basename → page/anchor map. */
    const val ANCHOR_INDEX = "anchors.json"

    /**
     * The map `public/docs_hooks.py` uses to turn a citation into a link: basename to page path and
     * the declaration lines on it.
     *
     * Declaration lines rather than every line, because a citation frequently points *into* a body —
     * 55 of the 77 bare citations in `public/docs` do — and there is no anchor on line 1096 of
     * `stats.kt` to jump to. The linker picks the greatest declaration line at or below the cited one,
     * which lands the reader on the section that contains it with the fence numbered so the exact line
     * is visible. Written by hand rather than through a JSON library because this is the only JSON
     * this module emits and the values are integers and slash-separated paths.
     *
     * A basename that two pages share is **omitted**: `Transforms.kt` exists twice in `src/`, and a
     * citation resolving to the wrong one is the failure `CitationGuard.locate` refuses for the same
     * reason. Omitted means the citation stays an unlinked chip, which is the honest outcome.
     */
    private fun renderAnchorIndex(pages: List<Page>): String {
        val byName = pages.groupBy { it.source.substringAfterLast('/') }
        val unique = byName.filterValues { it.size == 1 }.mapValues { (_, v) -> v.single() }
        return buildString {
            appendLine("{")
            unique.entries.sortedBy { it.key }.forEachIndexed { index, (name, page) ->
                val lines = page.declarationLines.joinToString(",")
                val comma = if (index == unique.size - 1) "" else ","
                appendLine("""  "$name": {"page": "${page.path}", "lines": [$lines]}$comma""")
            }
            appendLine("}")
        }
    }

    /** The repository root, found the same way the sibling generators find it. */
    private fun locateRepoRoot(): File {
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
