package afield.docs

import afield.ai.KotlinSourceIndex
import java.io.File
import kotlin.system.exitProcess

/*
FIELD_TEST
id: docs.codebase-reference.generate
runner: jvm-main
entrypoint: afield.docs.StdlibReferenceGenerator
tags: [smoke, docs, reference, stdlib, verification]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "StdlibReferenceGenerator ok"
*/
/**
 * Emits Field's **codebase reference** — the browsable answer to "what is already in the box?" —
 * from the KDoc that lives next to the code.
 *
 * ### Why this exists, and why it is not Dokka
 *
 * `20e64da5` removed Dokka on purpose: the durable facts belong in KDoc beside the code, and a
 * generated wall of one-page-per-symbol HTML is not documentation anyone reads. Field already has
 * two *interactive* ways into that KDoc — the completion panel's recursive code browser
 * (`public/docs/completion_doc_browser.md`) and the semantic index — but both need you to already
 * know the name you are looking for. Nothing answered the browsing question. This generator does,
 * with ordinary Zensical markdown inside the existing docs site: a page per completion category,
 * a section per source file, the file's own KDoc as the orientation prose, and a table per type.
 *
 * ### One parser, not two
 *
 * Declarations come from [KotlinSourceIndex.parseTopLevelDefinitions] — the *same* scan that feeds
 * the in-app completion doc browser — so a symbol listed here is by construction a symbol the doc
 * panel can resolve. Only the signature text is re-read from source here (the index stores names
 * and lines, not signatures); see [signatureAt].
 *
 * ### Honesty rules
 *
 * - Nothing is invented. Every summary is the first paragraph of a real KDoc comment; a symbol
 *   with no KDoc is listed with an empty summary rather than a guessed one.
 * - `[Symbol]` KDoc references are linkified only when the target is a type this reference
 *   actually emits — the same "links only appear when they resolve" rule the completion panel
 *   follows. Everything else degrades to plain inline code.
 * - The "see also" links from a reference section to a worked-example page are *evidence-based*:
 *   a page is linked only when it literally mentions [MIN_EVIDENCE_HITS] or more distinct public
 *   symbol names from that source file (see [evidenceLinks]). No hand-maintained topic map to rot.
 *
 * ### File overview rule
 *
 * The first KDoc block in a file is treated as the *file's* overview when it ends before the first
 * declaration **and** is separated from that declaration by at least one blank or comment line —
 * and it is then not also shown as that declaration's own summary. Verified against the corpus:
 * `spectral.kt`, `voronoi.kt` and `wfc.kt` have genuine file-level blocks and are picked up;
 * `booleannetworks.kt`'s opening KDoc sits flush against `data class BoolNode` and is correctly
 * left attached to it.
 *
 * ### Running it
 *
 * Regenerate the committed pages (needs no Field server, no GPU, no network):
 * ```
 * ./gradlew generateCodebaseReference
 * ```
 * Run with **no** output directory and it emits to a temp dir and then **drift-checks** the
 * committed pages byte-for-byte instead — which is what the `docs.codebase-reference.generate`
 * FIELD_TEST does, so an API or KDoc change that nobody regenerated fails the build. This mirrors
 * [afield.examples.AgentTranscriptMaterializer] and the `tools/linalg-generator --check` mode that
 * guards `public/docs/linear_algebra_api.md`.
 *
 * The pages are **committed** rather than built on the fly: the Zensical docs build must not gain a
 * JVM step, and a generated reference is only trustworthy if an API change shows up as a reviewable
 * diff.
 */
object StdlibReferenceGenerator {

    /** Repo-relative home of the committed reference pages. */
    private const val REFERENCE_REL = "public/docs/reference"

    /** How many distinct entry points a docs page must call before it earns a "Used in" link. */
    private const val MIN_EVIDENCE_HITS = 2

    /** At most this many docs pages are linked from one source file's section. */
    private const val MAX_EVIDENCE_LINKS = 3

    /**
     * Source roots to document, relative to the repo. `afield.stdlib` is the live-coding standard
     * library; `afield.visualization` is the value-visualizer/plot surface a box reaches for next.
     */
    private val SOURCE_ROOTS = listOf(
        "src/jvmMain/kotlin/afield/stdlib",
        "src/jvmMain/kotlin/afield/visualization",
    )

    /**
     * Paths (repo-relative prefixes) skipped inside [SOURCE_ROOTS].
     *
     * `afield/stdlib/linalg` is machine-generated from the typed algebra model and already has its
     * own, better reference at `linear_algebra_api.md`; duplicating ~1500 generated swizzle members
     * here would drown every hand-written symbol in the section.
     */
    private val EXCLUDE_PREFIXES = listOf(
        "src/jvmMain/kotlin/afield/stdlib/linalg/",
    )

    /**
     * Completion categories that are not part of the public surface. `Internal` is exactly the
     * label the source uses for plumbing that completion should de-rank, so it has no place in a
     * "what can I call?" reference.
     */
    private val EXCLUDE_CATEGORIES = setOf("Internal")

    /** Page order, and the prose that introduces each category page. */
    private val CATEGORY_BLURBS: Map<String, String> = linkedMapOf(
        "Math" to "Numerics: signal processing in both domains, estimation and fitting, " +
            "interpolation, statistics, randomness and units. Everything here is plain " +
            "`Double`/`Float` Kotlin with no GPU and no window, so it runs in a box, a test or a " +
            "headless docs capture equally well.",
        "Data" to "Structures and processes over data rather than over pixels: clustering and " +
            "nearest-neighbour search, generative and constraint systems, graph dynamics, and the " +
            "I/O helpers (files, JSON, subprocesses, OSC-adjacent devices) that get data into a box.",
        "Graphics" to "Everything that ends up as an `FLine`, a colour or a shader: drawing " +
            "helpers, contouring, geometry, colour spaces, keyframing and the small shading " +
            "languages' host side.",
        "Function" to "The function algebra — Field's first-class, evolvable, graph-rewritable " +
            "notion of a function. Build them, compose them, fit them, mutate them, and rewrite " +
            "their graphs with user-authored patterns.",
        "Visualization" to "Turning a value into something you can look at, without leaving the box.",
        "Audio" to "Sound analysis and the network protocols that carry control data alongside it.",
        "Workspace" to "The box's own surroundings: properties, persistence, and the handful of " +
            "names every box already has in scope.",
    )

    /** Docs pages that are not worked examples and would only add noise as "see also" targets. */
    private val EVIDENCE_PAGE_BLOCKLIST = setOf(
        "index.md", "installation.md", "survivalGuide.md", "funFeatures.md", "aiFeatures.md",
        "staging.md", "building_and_testing.md", "sketch1.md", "sketch2.md",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = locateRepoRoot()
        val explicitOut = args.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("afield.codebaseReference.out")?.takeIf { it.isNotBlank() }
        val outRoot = explicitOut?.let { File(it) }
            ?: File.createTempFile("codebase-reference", "").let { probe ->
                probe.delete(); probe.mkdirs(); probe
            }
        outRoot.mkdirs()

        val sources = collectSources(repo)
        if (sources.isEmpty()) {
            System.err.println("StdlibReferenceGenerator: no sources found under ${repo.absolutePath}")
            exitProcess(1)
        }

        val documents = sources.map { readDocument(repo, it) }.filter { it.category !in EXCLUDE_CATEGORIES }
        val typeAnchors = buildTypeAnchorIndex(documents)
        val evidence = buildEvidenceIndex(repo, documents)

        val pages = linkedMapOf<String, String>()
        val categories = CATEGORY_BLURBS.keys.filter { category -> documents.any { it.category == category } } +
            documents.map { it.category }.distinct().filter { it !in CATEGORY_BLURBS.keys }.sorted()
        categories.forEach { category ->
            val inCategory = documents.filter { it.category == category }.sortedBy { it.relativePath }
            pages["${slug(category)}.md"] = renderCategoryPage(category, inCategory, typeAnchors, evidence)
        }
        pages["index.md"] = renderIndexPage(categories, documents, evidence)

        pages.forEach { (name, text) -> File(outRoot, name).writeText(text) }

        val symbolCount = documents.sumOf { it.symbolCount() }
        println(
            "StdlibReferenceGenerator: ${pages.size} page(s), ${documents.size} source file(s), " +
                "$symbolCount public symbol(s) -> ${outRoot.absolutePath}"
        )
        categories.forEach { category ->
            val inCategory = documents.filter { it.category == category }
            println(
                "  ${slug(category)}.md — ${inCategory.size} file(s), " +
                    "${inCategory.sumOf { it.symbolCount() }} symbol(s)"
            )
        }

        val failures = mutableListOf<String>()
        if (symbolCount < 500) failures += "only $symbolCount symbols emitted — the parse looks broken"
        pages.forEach { (name, text) ->
            if (text.length < 200) failures += "$name is suspiciously short (${text.length} chars)"
        }

        // Drift guard: the FIELD_TEST path (no explicit output dir) additionally asserts that the
        // committed pages are exactly what the current source produces.
        if (failures.isEmpty() && explicitOut == null) {
            val committed = File(repo, REFERENCE_REL)
            if (committed.isDirectory) {
                println("StdlibReferenceGenerator: drift-checking committed pages at ${committed.absolutePath}")
                pages.forEach { (name, text) ->
                    val existing = File(committed, name)
                    when {
                        !existing.isFile -> failures += "$name: missing from $REFERENCE_REL"
                        existing.readText() != text -> failures += "$name: committed page is out of date"
                    }
                }
                committed.listFiles { f -> f.isFile && f.name.endsWith(".md") }
                    ?.map { it.name }
                    ?.filter { it !in pages.keys }
                    ?.forEach { failures += "$it: committed but no longer generated — delete it" }
                if (failures.isNotEmpty()) failures += "Regenerate with: ./gradlew generateCodebaseReference"
            } else {
                println("StdlibReferenceGenerator: no committed pages at ${committed.absolutePath} — skipping drift check")
            }
        }

        if (failures.isNotEmpty()) {
            System.err.println("StdlibReferenceGenerator FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("StdlibReferenceGenerator ok — $symbolCount symbol(s) across ${pages.size} page(s)")
    }

    // ── model ───────────────────────────────────────────────────────────────────────────────────

    /** One public declaration, ready to render. */
    private data class Symbol(
        val kind: String,
        /** Qualified as the parser saw it: `Owner.member` for members, bare for top level. */
        val qualifiedName: String,
        val simpleName: String,
        val owner: String?,
        val signature: String,
        val summary: String,
        val line: Int,
    )

    /** One source file's worth of reference material. */
    private data class SourceDocument(
        val relativePath: String,
        val fileName: String,
        val category: String,
        val overview: String?,
        val types: List<TypeEntry>,
        val topLevelFunctions: List<Symbol>,
        val topLevelValues: List<Symbol>,
    ) {
        fun symbolCount(): Int =
            types.size + types.sumOf { it.members.size } + topLevelFunctions.size + topLevelValues.size

        /**
         * Search patterns that, if found in a docs page's *code*, mean the page really uses this
         * file's API.
         *
         * Only entry points count — top-level functions, types and values, never members. A member
         * name like `transform`, `origin` or `previous` is an ordinary English word that turns up in
         * unrelated example code; a top-level call `stft(` or a type `Spectrogram` does not.
         */
        fun evidenceTokens(): Set<Pair<String, String>> = buildSet {
            topLevelFunctions.forEach { if (it.simpleName.length >= 3) add("call" to it.simpleName) }
            types.forEach { if (it.symbol.simpleName.length >= 4) add("type" to it.symbol.simpleName) }
            topLevelValues.forEach { if (it.simpleName.length >= 5) add("name" to it.simpleName) }
        }
    }

    private data class TypeEntry(
        val symbol: Symbol,
        val members: List<Symbol>,
        val enumConstants: List<String> = emptyList(),
    )

    // ── reading ─────────────────────────────────────────────────────────────────────────────────

    private fun collectSources(repo: File): List<File> =
        SOURCE_ROOTS
            .map { File(repo, it) }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { file ->
                val rel = repo.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                EXCLUDE_PREFIXES.none { rel.startsWith(it) }
            }
            .sortedBy { it.absolutePath }

    private fun readDocument(repo: File, file: File): SourceDocument {
        val rel = repo.toPath().relativize(file.toPath()).toString().replace('\\', '/')
        val text = file.readText()
        val lines = text.split("\n")
        val definitions = KotlinSourceIndex.parseTopLevelDefinitions(text)
        val overview = fileOverview(lines, definitions.minOfOrNull { it.line })
        val overviewOwner = overview?.second

        fun toSymbol(def: KotlinSourceIndex.ParsedDefinition): Symbol {
            val summaryRaw = if (def.line == overviewOwner) null else def.kdoc
            return Symbol(
                kind = def.kind,
                qualifiedName = def.name,
                simpleName = def.name.substringAfterLast('.'),
                owner = def.owner,
                signature = signatureAt(lines, def.line, def.kind),
                summary = firstParagraph(summaryRaw),
                line = def.line,
            )
        }

        val visible = definitions.filter { it.visibility == "public" }
        val topLevel = visible.filter { it.owner == null }
        val types = topLevel
            .filter { it.kind in TYPE_KINDS }
            .map { def ->
                val members = visible
                    .filter { it.owner == def.name }
                    .filterNot { it.simpleName() == "Companion" }
                    .sortedBy { it.line }
                    .map(::toSymbol)
                val symbol = toSymbol(def)
                val constants =
                    if (symbol.signature.contains("enum class")) enumConstantsAt(lines, def.line) else emptyList()
                TypeEntry(symbol, members, constants)
            }
        val functions = topLevel.filter { it.kind == "fun" }.sortedBy { it.line }.map(::toSymbol)
        val values = topLevel.filter { it.kind == "val" || it.kind == "var" }.sortedBy { it.line }.map(::toSymbol)

        return SourceDocument(
            relativePath = rel,
            fileName = file.name,
            category = fileCategory(text) ?: inferredCategory(rel),
            overview = overview?.first,
            types = types,
            topLevelFunctions = functions,
            topLevelValues = values,
        )
    }

    private fun KotlinSourceIndex.ParsedDefinition.simpleName(): String = name.substringAfterLast('.')

    private val TYPE_KINDS = setOf("class", "interface", "object", "typealias")

    private fun fileCategory(text: String): String? =
        Regex("""(?m)^\s*@file:(?:[\w.]+\.)?CompletionCategory\s*\(\s*"([^"]+)"""")
            .find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Last-resort bucket for a file that carries no `@file:CompletionCategory`. */
    private fun inferredCategory(relativePath: String): String =
        if ("/visualization/" in relativePath) "Visualization" else "Uncategorised"

    /**
     * The file's own opening KDoc, plus the line of the declaration it would otherwise have been
     * attached to (so that declaration does not repeat it as its summary).
     *
     * Returns null unless the block ends before [firstDeclarationLine] *and* at least one blank or
     * comment line separates the two — a KDoc flush against a declaration documents that
     * declaration, not the file.
     */
    private fun fileOverview(
        lines: List<String>,
        firstDeclarationLine: Int?,
    ): Pair<String, Int?>? {
        val start = lines.indexOfFirst { it.trimStart().startsWith("/**") }
        if (start < 0) return null
        var end = start
        while (end < lines.size && "*/" !in lines[end]) end++
        if (end >= lines.size) return null

        // The block must precede the first declaration in the file.
        if (firstDeclarationLine != null && firstDeclarationLine <= end + 1) return null

        // …and must not be flush against it.
        var probe = end + 1
        var sawGap = false
        while (probe < lines.size) {
            val trimmed = lines[probe].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("@")) {
                if (trimmed.isEmpty() || trimmed.startsWith("//")) sawGap = true
                probe++
                continue
            }
            break
        }
        if (!sawGap) return null

        val body = lines.subList(start, end + 1).joinToString("\n")
        val cleaned = stripKDocMarkers(body)
        if (cleaned.isBlank()) return null
        // The KDocExtractor attaches this same block to the next declaration; remember which one so
        // the declaration's own row stays empty rather than echoing the file overview.
        val attachedTo = (probe + 1).takeIf { probe < lines.size }
        return cleaned to attachedTo
    }

    private fun stripKDocMarkers(block: String): String =
        block.split("\n").joinToString("\n") { raw ->
            var s = raw.trim()
            if (s.startsWith("/**")) s = s.removePrefix("/**").trim()
            if (s.endsWith("*/")) s = s.removeSuffix("*/").trim()
            if (s.startsWith("*")) s = s.removePrefix("*").let { if (it.startsWith(" ")) it.drop(1) else it }
            s
        }.trim()

    /**
     * The declaration's own text, read back from source starting at [line] (1-based).
     *
     * The index stores where a declaration is, not what it looks like, so the header is recovered
     * here by accumulating source until the brackets balance and a body marker (`{`, `=`) or the
     * end of the header is reached. Leading annotations are dropped; whitespace is collapsed.
     */
    private fun signatureAt(lines: List<String>, line: Int, kind: String): String {
        if (line - 1 !in lines.indices) return ""
        // A typealias *is* its right-hand side, so `=` opens the interesting part rather than ending
        // the header. Everywhere else `=` starts an initializer or expression body and is the stop.
        val stopAtAssign = kind != "typealias"
        val builder = StringBuilder()
        var depth = 0
        var index = line - 1
        var done = false
        while (index < lines.size && !done && index < line - 1 + MAX_SIGNATURE_LINES) {
            val raw = lines[index]
            var i = 0
            while (i < raw.length) {
                val ch = raw[i]
                val previous = raw.getOrNull(i - 1)
                when {
                    ch == '/' && raw.getOrNull(i + 1) == '/' -> { i = raw.length; continue }
                    ch == '(' || ch == '[' -> { depth++; builder.append(ch) }
                    ch == ')' || ch == ']' -> { depth--; builder.append(ch) }
                    ch == '{' && depth <= 0 -> done = true
                    ch == '=' && depth <= 0 && stopAtAssign &&
                        raw.getOrNull(i + 1) != '=' &&
                        previous != '=' && previous != '!' && previous != '<' && previous != '>' -> done = true
                    else -> builder.append(ch)
                }
                if (done) break
                i++
            }
            if (done) break
            builder.append(' ')
            index++
            // A complete header at bracket depth zero ends the scan; a continuation line is only
            // consumed while a bracket is still open or the next line clearly continues the header.
            if (depth <= 0) {
                val next = lines.getOrNull(index)?.trim().orEmpty()
                val continues = next.startsWith(":") || next.startsWith(",") ||
                    next.startsWith("where ") || builder.trim().endsWith("=")
                if (!continues) break
            }
        }
        return tidySignature(builder.toString())
    }

    private const val MAX_SIGNATURE_LINES = 40

    /**
     * The constants of an `enum class` declared at [line], in source order.
     *
     * An enum's constants *are* its API — a reference that lists `enum class WindowType` and stops
     * has told the reader nothing. Reads the body between the opening brace and the first `;` (or
     * the closing brace), taking the leading identifier of each depth-one comma-separated entry, so
     * `HANN`, `HAMMING(2)` and a trailing-comma list all come out right.
     */
    private fun enumConstantsAt(lines: List<String>, line: Int): List<String> {
        var index = line - 1
        if (index !in lines.indices) return emptyList()
        val body = StringBuilder()
        var depth = 0
        var started = false
        while (index < lines.size && index < line - 1 + MAX_ENUM_LINES) {
            val raw = lines[index].substringBefore("//")
            for (ch in raw) {
                when {
                    ch == '{' -> { depth++; if (depth == 1) { started = true; continue } }
                    ch == '}' -> { depth--; if (depth == 0 && started) return parseEnumEntries(body.toString()) }
                    ch == ';' && depth == 1 -> return parseEnumEntries(body.toString())
                }
                if (started && depth >= 1) body.append(ch)
            }
            body.append('\n')
            index++
        }
        return if (started) parseEnumEntries(body.toString()) else emptyList()
    }

    private const val MAX_ENUM_LINES = 200

    private fun parseEnumEntries(body: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        fun flush() {
            val entry = current.toString().trim()
            current.setLength(0)
            val name = Regex("^([A-Za-z_][A-Za-z0-9_]*)").find(entry)?.groupValues?.get(1) ?: return
            if (name.isNotEmpty() && name !in out) out += name
        }
        for (ch in body) {
            when {
                ch == '(' || ch == '[' || ch == '{' -> { depth++; current.append(ch) }
                ch == ')' || ch == ']' || ch == '}' -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 -> flush()
                else -> current.append(ch)
            }
        }
        flush()
        return out
    }

    private val LEADING_ANNOTATION = Regex("""^@\w[\w.]*\s*(\([^)]*\))?\s*""")

    private fun tidySignature(raw: String): String {
        var s = raw.replace(Regex("\\s+"), " ").trim()
        while (true) {
            val m = LEADING_ANNOTATION.find(s) ?: break
            if (m.range.first != 0) break
            s = s.substring(m.range.last + 1).trimStart()
        }
        s = s.replace("( ", "(").replace(" )", ")").replace(" ,", ",").replace(",)", ")")
        s = s.trimEnd().trimEnd(':').trimEnd()
        // A trailing comma survives when a parameter list is cut short by the line budget.
        if (s.endsWith(",")) s = s.dropLast(1)
        return s.trim()
    }

    /**
     * The KDoc's first paragraph, as a single line of markdown.
     *
     * Stops at the first blank line or the first block tag (`@param`, `@return`, …) — the same
     * "summary sentence" convention KDoc itself uses. Never synthesises text: no KDoc means "".
     */
    private fun firstParagraph(kdoc: String?): String {
        if (kdoc.isNullOrBlank()) return ""
        val out = mutableListOf<String>()
        for (raw in kdoc.split("\n")) {
            val s = raw.trim()
            if (s.isEmpty()) { if (out.isNotEmpty()) break else continue }
            if (s.startsWith("@")) break
            if (s.startsWith("```")) break
            out += s
        }
        return out.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    // ── cross-references ────────────────────────────────────────────────────────────────────────

    /** Type simple name -> the `page.md#anchor` that documents it, for KDoc `[Symbol]` linkification. */
    private fun buildTypeAnchorIndex(documents: List<SourceDocument>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val ambiguous = mutableSetOf<String>()
        documents.forEach { doc ->
            doc.types.forEach { type ->
                val name = type.symbol.simpleName
                val target = "${slug(doc.category)}.md#${typeAnchor(doc, type.symbol)}"
                if (out.putIfAbsent(name, target) != null) ambiguous += name
            }
        }
        // A name that two files both define cannot be linked unambiguously, so it is not linked at all.
        ambiguous.forEach { out.remove(it) }
        return out
    }

    /**
     * For each source file, the worked-example pages that demonstrably use it.
     *
     * Evidence, not opinion. A page qualifies only when at least [MIN_EVIDENCE_HITS] distinct symbol
     * names from that file appear **in the page's code** — fenced blocks and inline code spans, not
     * prose — and only names that are *unique to one source file* in the whole corpus count. Both
     * filters matter: matching prose made every page look like it was about `Functions.kt` (the word
     * "function"), and shared names like `update` or `points` matched everywhere.
     *
     * The consequence is that this can never claim a relationship the pages do not have, and a new
     * example page starts being linked the moment it uses the API.
     */
    private fun buildEvidenceIndex(repo: File, documents: List<SourceDocument>): Map<String, List<Pair<String, Int>>> {
        val docsDir = File(repo, "public/docs")
        val pages = (docsDir.listFiles { f -> f.isFile && f.extension == "md" } ?: emptyArray())
            .filter { it.name !in EVIDENCE_PAGE_BLOCKLIST }
            .sortedBy { it.name }
            .map { file ->
                val text = file.readText()
                pageTitles[file.name] = headingOf(text) ?: prettifyFileName(file.name)
                file.name to codeTextOf(text)
            }

        // A name defined by more than one documented file cannot identify any of them.
        val tokenOwners = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        documents.forEach { doc ->
            doc.evidenceTokens().forEach { tokenOwners.getOrPut(it) { mutableSetOf() } += doc.relativePath }
        }

        return documents.associate { doc ->
            val patterns = doc.evidenceTokens()
                .filter { tokenOwners[it]?.size == 1 }
                .map { (kind, name) ->
                    val escaped = Regex.escape(name)
                    if (kind == "call") Regex("$escaped\\s*\\(") else Regex("\\b$escaped\\b")
                }
            val hits = pages.mapNotNull { (name, code) ->
                val count = patterns.count { it.containsMatchIn(code) }
                if (count >= MIN_EVIDENCE_HITS) name to count else null
            }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .take(MAX_EVIDENCE_LINKS)
            doc.relativePath to hits
        }
    }

    private val FENCED_BLOCK = Regex("(?s)```.*?```")
    private val INLINE_CODE = Regex("`[^`\n]+`")

    private val FILENAME_SPAN = Regex("^`[\\w./-]+\\.(kt|md|kts|py|json|yaml|yml|glsl)`$")

    /**
     * Just the code in a markdown page: fenced blocks plus inline spans, prose discarded.
     *
     * Inline spans that are only a *filename* are dropped. Naming a source file is not using its
     * API — and since the worked-example pages now carry a back-link that names the very file this
     * index is scoring, counting them would make the evidence partly self-referential.
     */
    private fun codeTextOf(markdown: String): String {
        val fences = FENCED_BLOCK.findAll(markdown).map { it.value }.toList()
        val prose = FENCED_BLOCK.replace(markdown, " ")
        val inline = INLINE_CODE.findAll(prose).map { it.value }.filterNot { FILENAME_SPAN.matches(it) }.toList()
        return (fences + inline).joinToString("\n")
    }

    /** Docs page filename -> its own `# ` heading, so "see also" links read as the site reads. */
    private val pageTitles = mutableMapOf<String, String>()

    private fun headingOf(markdown: String): String? =
        markdown.lineSequence().firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            // A few page headings carry inline HTML for typographic effect; a link label must not.
            ?.replace(Regex("<[^>]*>"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun evidenceLinks(doc: SourceDocument, evidence: Map<String, List<Pair<String, Int>>>): String? {
        val hits = evidence[doc.relativePath].orEmpty()
        if (hits.isEmpty()) return null
        return hits.joinToString(", ") { (page, _) ->
            "[${escapeCell(pageTitles[page] ?: prettifyFileName(page))}](../$page)"
        }
    }

    // ── rendering ───────────────────────────────────────────────────────────────────────────────

    private fun renderCategoryPage(
        category: String,
        documents: List<SourceDocument>,
        typeAnchors: Map<String, String>,
        evidence: Map<String, List<Pair<String, Int>>>,
    ): String = buildString {
        appendLine(BANNER)
        appendLine("# $category")
        appendLine()
        CATEGORY_BLURBS[category]?.let { appendLine(it); appendLine() }
        appendLine(
            "${documents.size} source file(s), ${documents.sumOf { it.symbolCount() }} public symbol(s). " +
                "Everything below is the KDoc that lives beside the code — the same text the " +
                "[completion documentation panel](../completion_doc_browser.md) shows in the editor."
        )
        appendLine()
        appendLine("<div class=\"codebase-reference\" markdown>")
        appendLine()
        documents.forEach { doc -> append(renderFileSection(doc, typeAnchors, evidence)) }
        appendLine("</div>")
    }

    private fun renderFileSection(
        doc: SourceDocument,
        typeAnchors: Map<String, String>,
        evidence: Map<String, List<Pair<String, Int>>>,
    ): String = buildString {
        appendLine("## ${doc.fileName} { #${fileAnchor(doc)} }")
        appendLine()
        doc.overview?.let { appendLine(linkify(it, typeAnchors)); appendLine() }
        appendLine("`${doc.relativePath}`")
        appendLine()
        evidenceLinks(doc, evidence)?.let {
            appendLine("**Used in:** $it")
            appendLine()
        }

        if (doc.topLevelFunctions.isNotEmpty()) {
            appendLine("### Top-level functions { #${fileAnchor(doc)}-functions }")
            appendLine()
            append(renderTable(doc.topLevelFunctions, typeAnchors))
        }
        if (doc.topLevelValues.isNotEmpty()) {
            appendLine("### Top-level values { #${fileAnchor(doc)}-values }")
            appendLine()
            append(renderTable(doc.topLevelValues, typeAnchors))
        }
        doc.types.forEach { type ->
            appendLine("### ${type.symbol.simpleName} { #${typeAnchor(doc, type.symbol)} }")
            appendLine()
            appendLine("`${escapeCode(type.symbol.signature)}`")
            appendLine()
            if (type.symbol.summary.isNotBlank()) {
                appendLine(linkify(type.symbol.summary, typeAnchors))
                appendLine()
            }
            if (type.enumConstants.isNotEmpty()) {
                appendLine("Constants: ${type.enumConstants.joinToString(", ") { "`$it`" }}.")
                appendLine()
            }
            if (type.members.isNotEmpty()) append(renderTable(type.members, typeAnchors))
        }
    }

    private fun renderTable(symbols: List<Symbol>, typeAnchors: Map<String, String>): String = buildString {
        appendLine("| Declaration | Summary |")
        appendLine("| --- | --- |")
        symbols.forEach { symbol ->
            val signature = symbol.signature.ifBlank { symbol.simpleName }
            appendLine("| `${escapeCode(signature)}` | ${escapeCell(linkify(symbol.summary, typeAnchors))} |")
        }
        appendLine()
    }

    private fun renderIndexPage(
        categories: List<String>,
        documents: List<SourceDocument>,
        evidence: Map<String, List<Pair<String, Int>>>,
    ): String = buildString {
        appendLine(BANNER)
        appendLine("# What's already in the box")
        appendLine()
        appendLine(
            "Field's standard library is large and, until now, invisible: the only ways to read it " +
                "were to open the source or to already know the name you wanted. This section is a " +
                "browsable map of it — every public declaration in `afield.stdlib` and " +
                "`afield.visualization`, with the KDoc that sits next to it in the source."
        )
        appendLine()
        appendLine(
            "**${documents.sumOf { it.symbolCount() }} public symbols across ${documents.size} source files.** " +
                "Pages are grouped by the `@file:CompletionCategory` label the source itself carries, " +
                "which is the same grouping the editor's autocomplete uses."
        )
        appendLine()
        appendLine("| Category | Files | Symbols | Covers |")
        appendLine("| --- | --- | --- | --- |")
        categories.forEach { category ->
            val inCategory = documents.filter { it.category == category }
            val blurb = CATEGORY_BLURBS[category]?.substringBefore(". ")?.plus(".") ?: ""
            appendLine(
                "| [$category](${slug(category)}.md) | ${inCategory.size} | " +
                    "${inCategory.sumOf { it.symbolCount() }} | ${escapeCell(blurb)} |"
            )
        }
        appendLine()
        appendLine("## Every file, at a glance")
        appendLine()
        appendLine("<div class=\"codebase-reference\" markdown>")
        appendLine()
        appendLine("| File | Category | Symbols | What it is | Used in |")
        appendLine("| --- | --- | --- | --- | --- |")
        documents.sortedBy { it.fileName.lowercase() }.forEach { doc ->
            val link = "[${doc.fileName}](${slug(doc.category)}.md#${fileAnchor(doc)})"
            val gist = doc.overview?.let { firstSentence(it) }.orEmpty()
            val examples = evidenceLinks(doc, evidence).orEmpty()
            appendLine(
                "| $link | ${doc.category} | ${doc.symbolCount()} | ${escapeCell(stripLinks(gist))} | $examples |"
            )
        }
        appendLine()
        appendLine("</div>")
        appendLine()
        appendLine("## What is not here")
        appendLine()
        appendLine(
            "- **The generated vector algebra** (`afield.stdlib.linalg`) has its own, better page: " +
                "[Generated vector API reference](../linear_algebra_api.md), with " +
                "[Linear algebra in Field](../linear_algebra.md) for the intuition."
        )
        appendLine(
            "- **Declarations marked `Internal`** by their own `@file:CompletionCategory` are " +
                "plumbing, and are excluded on purpose."
        )
        appendLine(
            "- **Graphics, rendering and the small languages beyond their host side** live in " +
                "`afield.four`, `afield.rectsl` and `afield.stacksl`; start from " +
                "[afield.four Graphics](../afield_four_graphics.md), the " +
                "[RectSL cheatsheet](../rectsl_cheatsheet.md) and the " +
                "[StackSL cheatsheet](../stacksl_cheatsheet.md)."
        )
        appendLine()
        appendLine("## How to read a symbol you find here")
        appendLine()
        appendLine(
            "Type its name in a box and press `ctrl-space`: the " +
                "[completion documentation panel](../completion_doc_browser.md) shows the same KDoc " +
                "with every resolvable `[Symbol]` reference turned into a link you can follow, plus " +
                "a jump straight to the definition's line in the source. This reference and that " +
                "panel read the same declarations through the same parser, so anything listed here " +
                "resolves there."
        )
        appendLine()
        appendLine(
            "To search rather than browse, see [The Semantic Index](../semantic_index.md)."
        )
    }

    // ── small helpers ───────────────────────────────────────────────────────────────────────────

    private const val BANNER =
        "<!-- DO NOT EDIT — generated by afield.docs.StdlibReferenceGenerator from KDoc in src/jvmMain/kotlin. -->\n" +
            "<!-- Regenerate: ./gradlew generateCodebaseReference -->"

    /**
     * Turns KDoc `[Symbol]` / `[display][Symbol]` references into links, but **only** when the
     * target is a type this reference emits — mirroring `linkifyKDocSymbols`' rule that an
     * unresolved reference stays literal rather than becoming a dead link. Unresolved references
     * become plain inline code so the raw brackets never leak into the page.
     */
    private fun linkify(text: String, typeAnchors: Map<String, String>): String {
        if (text.isBlank()) return text
        val alternate = Regex("""\[([^\]\[]+)]\[([A-Za-z_][\w.]*)]""")
        val simple = Regex("""\[([A-Za-z_][\w.]*)]""")
        var out = alternate.replace(text) { m ->
            val target = typeAnchors[m.groupValues[2].substringAfterLast('.')]
            if (target != null) "[${m.groupValues[1]}]($target)" else "`${m.groupValues[1]}`"
        }
        out = simple.replace(out) { m ->
            val raw = m.groupValues[1]
            val target = typeAnchors[raw.substringAfterLast('.')]
            if (target != null) "[`$raw`]($target)" else "`$raw`"
        }
        return out
    }

    private fun stripLinks(text: String): String =
        Regex("""\[([^\]]*)]\([^)]*\)""").replace(text) { it.groupValues[1] }

    private fun firstSentence(text: String): String {
        val flat = text.split("\n").takeWhile { it.isNotBlank() }.joinToString(" ")
            .replace(Regex("\\s+"), " ").trim()
        val stop = Regex("""[.!?](\s|$)""").find(flat) ?: return flat
        return flat.substring(0, stop.range.first + 1)
    }

    /** Escape markdown (links, inline code preserved) for a table cell. */
    private fun escapeCell(text: String): String = text.replace("|", "\\|").replace("\n", " ")

    /** Escape a raw signature that is about to be wrapped in an inline-code span in a table cell. */
    private fun escapeCode(text: String): String =
        text.replace("|", "\\|").replace("\n", " ").replace("`", "'")

    private fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun fileAnchor(doc: SourceDocument): String = slug(doc.fileName)

    private fun typeAnchor(doc: SourceDocument, symbol: Symbol): String =
        "${slug(doc.fileName)}-${slug(symbol.simpleName)}"

    private fun prettifyFileName(page: String): String =
        page.removeSuffix(".md").split('_', '-').joinToString(" ") { part ->
            part.replaceFirstChar { c -> c.uppercaseChar() }
        }

    /** Walk up from the working directory until the repo root (the one with `public/docs`) is found. */
    private fun locateRepoRoot(): File {
        var probe: File? = File(System.getProperty("user.dir")).absoluteFile
        while (probe != null) {
            if (File(probe, "public/docs").isDirectory && File(probe, "src/jvmMain/kotlin").isDirectory) return probe
            probe = probe.parentFile
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }
}
