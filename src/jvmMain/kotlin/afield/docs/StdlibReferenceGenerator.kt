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

    /**
     * Repo-relative home of the KDoc worklist.
     *
     * `dev_docs/` and not `public/docs/`: this is a ranked list of what is *missing*, which is a
     * standing engineering task rather than something a reader of the site wants. The published
     * pages already carry the honest coverage numbers.
     */
    private const val WORKLIST_REL = "dev_docs/kdoc_worklist.md"

    /** How many individual gaps the worklist names before it stops and says how many it dropped. */
    private const val WORKLIST_ENTRIES = 120

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
        "Polytopes" to "Polyhedra as topology first and coordinates second. An abstract incidence " +
            "complex you can build and validate; the Conway operator algebra — `dual`, `ambo`, " +
            "`kis`, `truncate`, `gyro` and the composites written over them — that rewrites it, " +
            "with per-element provenance and lineage carried through every step; and three " +
            "realization verbs that hand the result coordinates: `realize` for a first embedding, " +
            "`relax` for one that satisfies constraints you declare, and `canonicalize` for the " +
            "classical edge-tangent form you would recognise as a solid. Everything is immutable " +
            "and CPU-only, so a Conway expression evaluates in a box, a test or a headless docs " +
            "capture equally well; `FieldAdapters.kt` is where a polytope becomes `FLine`s or a " +
            "`TriMesh`. 4-polytopes, their Schlegel and slice projections, and an OFF importer " +
            "live here too. Files are listed alphabetically below; a reader new to the package " +
            "wants `Topology.kt`, then `Transforms.kt`, then `Conway.kt`, then `Canonical.kt`, " +
            "then `FieldAdapters.kt` — and [Polytopes: topology first, coordinates later]" +
            "(../polytope_examples.md) for the same ground with pictures.",
        "Solvers" to "Nonlinear least squares, aimed at geometry. A constraint vocabulary — " +
            "planarity, colinearity, fixed distances, perpendicularity, dihedral angles, panel " +
            "areas, positions held hard or held softly — assembled into a program, compiled once " +
            "and solved by Ceres across a foreign-function bridge; the architectural layer above " +
            "it for gridshells and panel families; and the reuse-oriented half that re-solves the " +
            "same program every frame while a user drags. Residuals you cannot express in the " +
            "vocabulary can be written in Kotlin and differentiated exactly by Ceres through the " +
            "expression VM. These entry points need the native Ceres library present, and nothing " +
            "here draws — a solve returns points, and drawing them is your business. Start from " +
            "`CeresGeometry.kt`, which is the vocabulary everything else is written in; " +
            "`ArchitecturalGeometry.kt` is the layer you will actually call.",
        "Imaging" to "Rasters and computer vision: convolution and the filter family, gradients " +
            "and edges, corners, blobs and morphology, multiscale pyramids, warps and optical " +
            "flow. Dependency-free CPU float math built on one value type, `Img`, with the " +
            "separable-convolution core vectorized to roughly OpenCV's single-threaded speed. " +
            "Every detector hands back geometry, so results go straight to `lines()`.",
        "ALife" to "Spatial artificial life: grid cellular automata (life-like, cyclic and " +
            "Wolfram's elementary rules), continuous systems (reaction-diffusion, Turing patterns, " +
            "Lenia), agent populations (boids, Physarum, diffusion-limited aggregation) and " +
            "structural growth (L-systems, space colonization) — plus the measurement layer every " +
            "one of them makes its claims through, because \"it produced pixels\" is not evidence.",
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
        pages["index.md"] = renderIndexPage(categories, documents, typeAnchors, evidence)

        pages.forEach { (name, text) -> File(outRoot, name).writeText(text) }

        // The worklist is committed outside the reference dir, so it needs its own destination:
        // beside the pages when they are going to a scratch directory (so a probe run is
        // self-contained), and to its real home only when the committed pages are being rewritten.
        val worklist = renderKdocWorklist(documents, evidence)
        val writingCommitted = explicitOut != null &&
            outRoot.canonicalFile == File(repo, REFERENCE_REL).canonicalFile
        val worklistOut =
            if (writingCommitted) File(repo, WORKLIST_REL) else File(outRoot, "kdoc_worklist.md")
        worklistOut.parentFile?.mkdirs()
        worklistOut.writeText(worklist)
        println("StdlibReferenceGenerator: KDoc worklist -> ${worklistOut.absolutePath}")

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
                val committedWorklist = File(repo, WORKLIST_REL)
                when {
                    !committedWorklist.isFile -> failures += "$WORKLIST_REL: missing"
                    committedWorklist.readText() != worklist ->
                        failures += "$WORKLIST_REL: committed worklist is out of date"
                }
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
        fun allSymbols(): List<Symbol> =
            types.map { it.symbol } + types.flatMap { it.members } + topLevelFunctions + topLevelValues

        fun symbolCount(): Int = allSymbols().size

        /** How many of this file's public symbols actually carry a KDoc summary. */
        fun documentedCount(): Int = allSymbols().count { it.summary.isNotBlank() }

        /**
         * Search patterns that, if found in a docs page's *code*, mean the page really uses this
         * file's API.
         *
         * Only entry points count — top-level functions, types and values, never members. A member
         * name like `transform`, `origin` or `previous` is an ordinary English word that turns up in
         * unrelated example code; a top-level call `stft(` or a type `Spectrogram` does not.
         *
         * [GENERIC_CALL_NAMES] is excluded on top of that, because "exactly one file defines it" is
         * not the same as "seeing it means that file".
         */
        fun evidenceTokens(): Set<Pair<String, String>> = buildSet {
            topLevelFunctions.forEach {
                if (it.simpleName.length >= 3 && it.simpleName !in GENERIC_CALL_NAMES) add("call" to it.simpleName)
            }
            types.forEach { if (it.symbol.simpleName.length >= 4) add("type" to it.symbol.simpleName) }
            topLevelValues.forEach { if (it.simpleName.length >= 5) add("name" to it.simpleName) }
        }
    }

    /**
     * Call names that are never evidence of anything, however uniquely a file owns them.
     *
     * The evidence index counts a name only when exactly one documented file declares it — which
     * looked like enough until `CeresExpression.kt` was published. That file declares top-level
     * `exp`, `cos`, `tan`, `min`, `max`, `abs`, `sqrt`, `pow` and `atan2` as expression builders, and
     * it is the only file in the corpus that declares them at top level, so it "uniquely owned" the
     * whole `kotlin.math` vocabulary. Every docs page with arithmetic in a code fence matched two or
     * more, and `functions_examples.md` — which has nothing whatsoever to do with the Ceres
     * expression VM — was published as a worked example of it.
     *
     * Uniqueness is a claim about the corpus; this set is a claim about the *reader*. Domain verbs
     * that happen to be short stay in (`kis`, `dual`, `stft`): seeing one really does mean the page
     * is about that file.
     */
    private val GENERIC_CALL_NAMES = setOf(
        "abs", "acos", "asin", "atan", "atan2", "ceil", "cos", "cosh", "exp", "floor", "hypot",
        "ln", "log", "log10", "log2", "max", "min", "pow", "round", "sign", "sin", "sinh", "sqrt",
        "tan", "tanh",
        // Not kotlin.math, but words an unrelated code fence uses about anything.
        "constant", "square", "reciprocal",
    )

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
        val cleaned = dropBlockTags(stripKDocMarkers(body))
        if (cleaned.isBlank()) return null
        // The KDocExtractor attaches this same block to the next declaration; remember which one so
        // the declaration's own row stays empty rather than echoing the file overview.
        val attachedTo = (probe + 1).takeIf { probe < lines.size }
        return cleaned to attachedTo
    }

    /**
     * [text] with its trailing KDoc block tags (`@see`, `@param`, `@throws`, …) removed.
     *
     * A symbol's summary is only ever its first paragraph, so tags never reach the page that way; a
     * file overview is printed whole, and `imaging_simd.kt`'s `@see` lines were rendering literally as
     * body text. Only tags at the *end* of the block are dropped, which is where KDoc puts them —
     * anything else would silently swallow prose.
     */
    private fun dropBlockTags(text: String): String {
        val lines = text.split("\n").toMutableList()
        var cut = lines.size
        for (i in lines.indices.reversed()) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty()) continue
            if (BLOCK_TAG.containsMatchIn(trimmed)) { cut = i; continue }
            break
        }
        return lines.subList(0, cut).joinToString("\n").trimEnd()
    }

    private val BLOCK_TAG = Regex("""^@(param|return|throws|exception|see|since|sample|author|constructor|receiver|property|suppress)\b""")

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
        // `val x: Int get() = …` stops at the `=`, leaving a dangling accessor: it is not part of
        // the declaration a caller writes.
        s = s.removeSuffix("get()").trimEnd()
        s = s.trimEnd().trimEnd(':').trimEnd()
        // A trailing comma survives when a parameter list is cut short by the line budget.
        if (s.endsWith(",")) s = s.dropLast(1)
        return declegate(s.trim())
    }

    /** Property delegates whose single type argument *is* the property's type. */
    private val PROPERTY_DELEGATES = setOf("Delegation", "Inheritance", "InheritanceFromContext")

    /**
     * Rewrites `var FLine.color by Delegation<Vector4f> { … }` as `var FLine.color: Vector4f`.
     *
     * Which delegate stores a box property is an implementation detail; the caller writes
     * `line.color = …` and needs the *type*. For the delegates in [PROPERTY_DELEGATES] the single
     * type argument is exactly that type, so this loses nothing and drops a lot of noise. A property
     * that already states its type, or that uses any other delegate, is left alone.
     */
    private fun declegate(signature: String): String {
        val byIndex = signature.indexOf(" by ")
        if (byIndex < 0) return signature
        val head = signature.substring(0, byIndex)
        if (':' in head) return signature
        val delegate = signature.substring(byIndex + 4).trimStart()
        val open = delegate.indexOf('<')
        if (open <= 0) return signature
        if (delegate.substring(0, open) !in PROPERTY_DELEGATES) return signature
        var depth = 0
        for (i in open until delegate.length) {
            when (delegate[i]) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) {
                        val type = delegate.substring(open + 1, i).trim()
                        return if (type.isEmpty()) signature else "$head: $type"
                    }
                }
            }
        }
        return signature
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

    // ── overload coalescing ─────────────────────────────────────────────────────────────────────

    /**
     * One table row: a canonical declaration, plus the sibling overloads it stands in for.
     *
     * @param alsoAccepts alternative parameter types the collapsed overloads accept, first seen first
     * @param extraOverloads how many further overloads this row speaks for (0 when it is alone)
     */
    private data class Row(
        val symbol: Symbol,
        val summary: String,
        val alsoAccepts: List<String> = emptyList(),
        val extraOverloads: Int = 0,
    )

    /**
     * Collapses an overload family that only exists to walk Kotlin's numeric tower into one row.
     *
     * Field's signal API is written three times over — once taking `Fn`, once `Number`, once `Any` —
     * and again for `Domain` vs `ClosedRange<*>`. Six near-identical 20-parameter signatures for
     * `pulseTrain` tell the reader nothing the first one did not, and they were the tallest rows on
     * the page.
     *
     * Two declarations join a family only when **everything except the parameter types matches**:
     * same name, same parameter *names* in the same order, same return type. That is deliberately
     * strict — real overloads differ in arity or in what they name their parameters, so they stay
     * separate rows.
     *
     * The family is **not** collapsed when its members carry two different KDoc summaries: that means
     * someone documented them apart, so they are not interchangeable after all.
     *
     * The canonical member is the one with the most defaulted parameters, then the longest signature,
     * then source order — a total order, so the output is stable for the drift guard.
     *
     * Most-defaults rather than most-general-types, which is the tempting rule: `List<Fn>.sequence`
     * takes `overlap: Number = 0.0` in one overload and a mandatory `overlap: Fn` in the other, and
     * showing the `Fn` one would tell the reader that `overlap` is required when it is not. A wrong
     * default is a worse error than a narrow type, and the badge names the other types anyway. Where
     * the general overload *is* also the fullest — `pulseTrain`, and most of this corpus — this picks
     * it regardless.
     */
    private fun coalesce(symbols: List<Symbol>): List<Row> {
        val groups = LinkedHashMap<String, MutableList<Symbol>>()
        symbols.forEachIndexed { index, symbol ->
            // Only functions overload; anything unparseable gets a unique key and stays alone.
            val key = if (symbol.kind == "fun") overloadKey(symbol.signature) else null
            groups.getOrPut(key?.let { "$it@${symbol.owner}" } ?: "unique#$index") { mutableListOf() } += symbol
        }
        return groups.values.map { family ->
            val summaries = family.map { it.summary }.filter { it.isNotBlank() }.distinct()
            if (family.size == 1 || summaries.size > 1) {
                return@map family.map { Row(it, it.summary) }
            }
            val canonical = family.withIndex().maxWithOrNull(
                compareBy({ defaultedParameterCount(it.value.signature) }, { it.value.signature.length }, { -it.index })
            )!!.value
            val canonicalTypes = parameterTypes(canonical.signature)
            val alternatives = LinkedHashSet<String>()
            family.filter { it !== canonical }.forEach { other ->
                parameterTypes(other.signature).forEachIndexed { position, type ->
                    if (type.isNotBlank() && canonicalTypes.getOrNull(position) != type) alternatives += type
                }
            }
            listOf(
                Row(
                    symbol = canonical,
                    summary = summaries.firstOrNull().orEmpty(),
                    alsoAccepts = alternatives.toList(),
                    extraOverloads = family.size - 1,
                )
            )
        }.flatten()
    }

    /** Identity of an overload family: name, parameter names in order, return type. Types erased. */
    private fun overloadKey(signature: String): String? {
        val (head, params, tail) = splitSignature(signature) ?: return null
        val names = params.map { it.substringBefore(':').substringBefore('=').trim() }
        if (names.any { it.isEmpty() }) return null
        return "${head.trim()}(${names.joinToString(",")})$tail"
    }

    private fun defaultedParameterCount(signature: String): Int =
        splitSignature(signature)?.second?.count { '=' in it } ?: 0

    private fun parameterTypes(signature: String): List<String> =
        splitSignature(signature)?.second?.map {
            if (':' in it) it.substringAfter(':').substringBefore('=').trim() else ""
        } ?: emptyList()

    /**
     * A signature split into everything before the parameter list, the top-level parameters, and
     * everything after — respecting nesting, so `(Fn) -> Double` and `Map<String, Int>` survive.
     */
    private fun splitSignature(signature: String): Triple<String, List<String>, String>? {
        val open = signature.indexOf('(')
        if (open < 0) return null
        val params = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (i in open until signature.length) {
            val ch = signature[i]
            when (ch) {
                '(', '[', '<' -> { depth++; if (depth > 1) current.append(ch) }
                ')', ']', '>' -> {
                    depth--
                    if (depth == 0) {
                        if (current.isNotBlank()) params += current.toString()
                        return Triple(signature.substring(0, open), params, signature.substring(i + 1))
                    }
                    current.append(ch)
                }
                ',' -> if (depth == 1) { params += current.toString(); current.setLength(0) } else current.append(ch)
                else -> current.append(ch)
            }
        }
        return null
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
            "${documents.size} source file(s), ${documents.sumOf { it.symbolCount() }} public symbol(s), " +
                "${documents.sumOf { it.documentedCount() }} of them carrying KDoc. Everything below is the " +
                "documentation that lives beside the code — the same text the " +
                "[completion documentation panel](../completion_doc_browser.md) shows in the editor. A blank " +
                "summary means the declaration has no KDoc yet, not that this page dropped it."
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
        // demoteHeadings before fencesToDiffstatic: the latter rewrites an opening fence to
        // "```diffstatic {data-lang=…}", which FENCE_OPENER no longer matches, so a demotion run
        // afterwards mistakes the *closing* fence for an opening one and stops demoting from there on.
        doc.overview?.let { appendLine(fencesToDiffstatic(demoteHeadings(linkify(it, typeAnchors)))); appendLine() }
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
            appendLine(highlightSignature(type.symbol.signature))
            appendLine()
            if (type.symbol.summary.isNotBlank()) {
                appendLine(fencesToDiffstatic(linkify(type.symbol.summary, typeAnchors)))
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
        coalesce(symbols).forEach { row ->
            val signature = row.symbol.signature.ifBlank { row.symbol.simpleName }
            appendLine(
                "| ${highlightSignature(signature)}${overloadBadge(row)} | " +
                    "${escapeCell(linkify(row.summary, typeAnchors))} |"
            )
        }
        appendLine()
    }

    private fun renderIndexPage(
        categories: List<String>,
        documents: List<SourceDocument>,
        typeAnchors: Map<String, String>,
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
        val total = documents.sumOf { it.symbolCount() }
        val documented = documents.sumOf { it.documentedCount() }
        appendLine(
            "**$total public symbols across ${documents.size} source files**, $documented of them " +
                "(${documented * 100 / total}%) carrying a KDoc summary. Pages are grouped by the " +
                "`@file:CompletionCategory` label the source itself carries, which is the same grouping " +
                "the editor's autocomplete uses."
        )
        appendLine()
        appendLine(
            "The \"documented\" column is the honest one: it is a coverage report on Field's own KDoc, " +
                "not a claim about this page. Where a summary is blank the declaration has none yet."
        )
        appendLine()
        appendLine("| Category | Files | Symbols | Documented | Covers |")
        appendLine("| --- | --- | --- | --- | --- |")
        categories.forEach { category ->
            val inCategory = documents.filter { it.category == category }
            val blurb = CATEGORY_BLURBS[category]?.let { firstSentence(it) } ?: ""
            val symbols = inCategory.sumOf { it.symbolCount() }
            val documentedHere = inCategory.sumOf { it.documentedCount() }
            appendLine(
                "| [$category](${slug(category)}.md) | ${inCategory.size} | $symbols | " +
                    "$documentedHere (${if (symbols == 0) 0 else documentedHere * 100 / symbols}%) | " +
                    "${escapeCell(blurb)} |"
            )
        }
        appendLine()
        appendLine("## Every file, at a glance")
        appendLine()
        appendLine("<div class=\"codebase-reference\" markdown>")
        appendLine()
        appendLine("| File | Category | Symbols | Documented | What it is | Used in |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        documents.sortedBy { it.fileName.lowercase() }.forEach { doc ->
            val link = "[${doc.fileName}](${slug(doc.category)}.md#${fileAnchor(doc)})"
            // linkify before stripLinks: `linkify` is what turns a KDoc `[Symbol]` into either a link
            // or inline code, and without it the raw brackets reach the page. `stripLinks` then flattens
            // the resolved ones, because this column is a one-line gist sitting between two columns
            // that are already all links.
            val gist = doc.overview
                ?.let { firstSentence(it) }
                ?.let { stripLinks(linkify(it, typeAnchors)) }
                .orEmpty()
            val examples = evidenceLinks(doc, evidence).orEmpty()
            appendLine(
                "| $link | ${doc.category} | ${doc.symbolCount()} | ${doc.documentedCount()} | " +
                    "${escapeCell(gist)} | $examples |"
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

    // ── signature highlighting ──────────────────────────────────────────────────────────────────

    /**
     * Kotlin keywords a signature can contain. Deliberately not the whole language — a signature has
     * no statements — so anything unrecognised falls through to the name rules below.
     */
    private val SIGNATURE_KEYWORDS = setOf(
        "fun", "val", "var", "class", "interface", "object", "typealias", "enum", "data", "sealed",
        "annotation", "value", "companion", "inner", "abstract", "open", "final", "override",
        "inline", "noinline", "crossinline", "suspend", "tailrec", "operator", "infix", "external",
        "const", "lateinit", "vararg", "reified", "out", "in", "where", "by", "init", "constructor",
        "public", "private", "internal", "protected", "expect", "actual",
    )

    /** Literal keywords Pygments colours as constants rather than as keywords. */
    private val SIGNATURE_CONSTANTS = setOf("true", "false", "null", "this", "super", "it")

    /**
     * Renders a signature as pre-highlighted HTML using **Pygments' own class names**, wrapped in a
     * `.highlight` container.
     *
     * The docs theme already ships a Pygments stylesheet mapping `k`/`nf`/`nc`/`n`/`o`/`p`/`mf`/`s`
     * to its `--md-code-hl-*-color` palette, so borrowing those class names colours these signatures
     * with the same palette as every fenced code block on the site and needs no stylesheet of its
     * own. Doing it here rather than with a fenced block is what keeps a signature inside a table
     * cell, where a fence cannot go.
     *
     * Hand-written rather than delegated to a real lexer because the output has to be **byte-stable**
     * for the drift guard, and because a signature is a small enough grammar to tokenise exactly:
     * keyword, declaration name, type (capitalised), parameter name, literal, operator, punctuation.
     */
    private fun highlightSignature(signature: String): String {
        val out = StringBuilder("<code class=\"highlight\">")
        var i = 0
        var sawDeclarationName = false
        var depth = 0
        while (i < signature.length) {
            val ch = signature[i]
            when {
                ch.isWhitespace() -> { out.append(' '); i++ }

                ch == '`' -> {
                    val end = signature.indexOf('`', i + 1)
                    val stop = if (end < 0) signature.length - 1 else end
                    out.span("n", signature.substring(i, stop + 1))
                    i = stop + 1
                }

                ch == '"' -> {
                    var j = i + 1
                    while (j < signature.length && (signature[j] != '"' || signature[j - 1] == '\\')) j++
                    val stop = minOf(j, signature.length - 1)
                    out.span("s", signature.substring(i, stop + 1))
                    i = stop + 1
                }

                ch.isDigit() -> {
                    var j = i
                    while (j < signature.length && (signature[j].isLetterOrDigit() || signature[j] == '.')) j++
                    val text = signature.substring(i, j)
                    out.span(if ('.' in text) "mf" else "mi", text)
                    i = j
                }

                ch.isLetter() || ch == '_' -> {
                    var j = i
                    while (j < signature.length && (signature[j].isLetterOrDigit() || signature[j] == '_')) j++
                    val word = signature.substring(i, j)
                    val followedByCall = signature.getOrNull(j) == '('
                    val cls = when {
                        word in SIGNATURE_KEYWORDS -> "k"
                        word in SIGNATURE_CONSTANTS -> "kc"
                        // The declaration's own name: the first call-shaped identifier at top level.
                        !sawDeclarationName && depth == 0 && followedByCall -> { sawDeclarationName = true; "nf" }
                        word.first().isUpperCase() -> "nc"
                        followedByCall -> "nf"
                        else -> "n"
                    }
                    out.span(cls, word)
                    i = j
                }

                else -> {
                    if (ch == '(' || ch == '[') depth++
                    if (ch == ')' || ch == ']') depth--
                    // `->`, `?`, `*`, `=` read as operators; brackets and separators as punctuation.
                    val isArrow = signature.startsWith("->", i)
                    when {
                        isArrow -> { out.span("o", "->"); i += 2 }
                        ch == '=' || ch == '?' || ch == '*' || ch == '!' || ch == '&' || ch == '|' ->
                            { out.span("o", ch.toString()); i++ }
                        else -> { out.span("p", ch.toString()); i++ }
                    }
                }
            }
        }
        out.append("</code>")
        return out.toString()
    }

    private fun StringBuilder.span(cls: String, text: String) {
        append("<span class=\"").append(cls).append("\">").append(htmlInTableCell(text)).append("</span>")
    }

    /**
     * Escapes text for raw HTML that sits inside a markdown table cell.
     *
     * Both layers bite: `<`/`>`/`&` are HTML, and `|` would end the cell while `*`, `_`, `` ` ``,
     * `[` and `]` are markdown inline syntax that the parser still applies around inline HTML —
     * `Map<*, *>` would otherwise come out italicised. Numeric entities render identically and dodge
     * both.
     */
    private fun htmlInTableCell(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '|' -> out.append("&#124;")
                '*' -> out.append("&#42;")
                '_' -> out.append("&#95;")
                '`' -> out.append("&#96;")
                '[' -> out.append("&#91;")
                ']' -> out.append("&#93;")
                '\\' -> out.append("&#92;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /** The `+N overloads · also …` badge that stands in for a coalesced family. */
    private fun overloadBadge(row: Row): String {
        if (row.extraOverloads == 0) return ""
        val plural = if (row.extraOverloads == 1) "overload" else "overloads"
        val also = row.alsoAccepts
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { htmlInTableCell(it) }
            ?.let { " · also $it" }
            .orEmpty()
        return "<span class=\"sig-overloads\">+${row.extraOverloads} $plural$also</span>"
    }

    // ── KDoc worklist ───────────────────────────────────────────────────────────────────────────

    /**
     * Members Kotlin generates for a `data class`, or that every type has anyway.
     *
     * A worklist that tells someone to go and write KDoc for `component1` is a worklist nobody
     * finishes. These are excluded from the gap counts entirely — they are not documentation debt.
     */
    private val BOILERPLATE_MEMBERS = setOf(
        "toString", "equals", "hashCode", "copy", "compareTo",
    )

    private fun isBoilerplate(symbol: Symbol): Boolean =
        symbol.owner != null &&
            (symbol.simpleName in BOILERPLATE_MEMBERS || symbol.simpleName.matches(Regex("component\\d+")))

    /**
     * The undocumented declarations of one file that are worth someone's afternoon, highest first.
     *
     * "Entry point" is the same notion [SourceDocument.evidenceTokens] uses: a type, or a top-level
     * function or value. A member is only reachable once you already have the thing it hangs off, so
     * the type's own KDoc is what orients a reader and is counted first. Overload families are
     * counted once — [coalesce] has already merged them, so documenting `pulseTrain` closes one gap,
     * not six.
     */
    private fun undocumentedEntryPoints(doc: SourceDocument): List<Symbol> {
        val types = doc.types.map { it.symbol }
        val topLevel = coalesce(doc.topLevelFunctions).map { it.symbol } + doc.topLevelValues
        return (types + topLevel).filter { it.summary.isBlank() && !isBoilerplate(it) }
    }

    private fun undocumentedMembers(doc: SourceDocument): List<Symbol> =
        doc.types.flatMap { type -> coalesce(type.members).map { it.symbol } }
            .filter { it.summary.isBlank() && !isBoilerplate(it) }

    /**
     * A ranked, actionable list of the KDoc that is missing — the other half of the coverage column.
     *
     * The reference pages report that 10% of this surface carries KDoc; that number is only useful if
     * it comes with a next action. Ranking is by payoff, and every signal is one this generator
     * already computes:
     *
     * 1. **Files a worked example already demonstrates** come first. [buildEvidenceIndex] knows which
     *    docs pages call which file's API, so these are the entry points readers demonstrably reach
     *    for — an undocumented symbol here costs the most.
     * 2. **Types before functions before values, entry points before members.** A type's KDoc orients
     *    everything inside it, so it is the single highest-leverage thing to write.
     * 3. **Bigger gaps before smaller ones**, so an hour spent lands where the surface is widest.
     *
     * Regenerate with the pages: `./gradlew generateCodebaseReference`.
     */
    private fun renderKdocWorklist(
        documents: List<SourceDocument>,
        evidence: Map<String, List<Pair<String, Int>>>,
    ): String = buildString {
        fun demonstrated(doc: SourceDocument) = evidence[doc.relativePath].orEmpty().isNotEmpty()

        val totalSymbols = documents.sumOf { it.symbolCount() }
        val totalDocumented = documents.sumOf { it.documentedCount() }
        val gaps = documents.associateWith { undocumentedEntryPoints(it) }
        val memberGaps = documents.associateWith { undocumentedMembers(it) }
        val totalEntryGaps = gaps.values.sumOf { it.size }
        val totalMemberGaps = memberGaps.values.sumOf { it.size }

        appendLine(
            "<!-- DO NOT EDIT — generated by afield.docs.StdlibReferenceGenerator alongside " +
                "public/docs/reference/. -->"
        )
        appendLine("<!-- Regenerate: ./gradlew generateCodebaseReference -->")
        appendLine("# KDoc worklist")
        appendLine()
        appendLine(
            "The other half of the coverage column on " +
                "[the codebase reference](../public/docs/reference/index.md): $totalDocumented of " +
                "$totalSymbols public symbols in `afield.stdlib` and `afield.visualization` carry a " +
                "KDoc summary, and this is what is missing, most valuable first."
        )
        appendLine()
        appendLine(
            "**$totalEntryGaps undocumented entry points** (types, top-level functions and values) " +
                "and $totalMemberGaps undocumented members. Overload families count once — " +
                "documenting `pulseTrain` closes one gap, not six — and the members Kotlin generates " +
                "for a `data class` are not counted at all."
        )
        appendLine()
        appendLine(
            "Ranking is by payoff, from signals the generator already has: files that a worked " +
                "example page demonstrably calls come first, then types before functions before " +
                "values, then the widest gaps. Write the KDoc next to the declaration, then run " +
                "`./gradlew generateCodebaseReference` — the reference pages and this list both " +
                "follow from the source."
        )
        appendLine()

        appendLine("## Where the debt is, by file")
        appendLine()
        appendLine("| File | Category | Used by examples | Undocumented entry points | Undocumented members | Coverage |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        documents
            .sortedWith(
                compareByDescending<SourceDocument> { demonstrated(it) }
                    .thenByDescending { gaps.getValue(it).size }
                    .thenByDescending { memberGaps.getValue(it).size }
                    .thenBy { it.relativePath }
            )
            .filter { gaps.getValue(it).isNotEmpty() || memberGaps.getValue(it).isNotEmpty() }
            .forEach { doc ->
                val pages = evidence[doc.relativePath].orEmpty()
                val used = if (pages.isEmpty()) "—" else pages.joinToString(", ") { it.first.removeSuffix(".md") }
                val coverage =
                    if (doc.symbolCount() == 0) 0 else doc.documentedCount() * 100 / doc.symbolCount()
                appendLine(
                    "| [${doc.fileName}](../${doc.relativePath}) | ${doc.category} | " +
                        "${escapeCell(used)} | ${gaps.getValue(doc).size} | " +
                        "${memberGaps.getValue(doc).size} | $coverage% |"
                )
            }
        appendLine()

        appendLine("## The highest-value individual gaps")
        appendLine()
        val ranked = documents.flatMap { doc -> gaps.getValue(doc).map { doc to it } }
            .sortedWith(
                compareByDescending<Pair<SourceDocument, Symbol>> { demonstrated(it.first) }
                    .thenBy { kindRank(it.second) }
                    .thenBy { it.first.relativePath }
                    .thenBy { it.second.line }
            )
        if (ranked.isEmpty()) {
            appendLine("Nothing — every entry point in this surface carries a KDoc summary.")
        } else {
            // The name and where it lives, not the signature: a 400-character `data class` header is
            // unscannable in a worklist, and the reference page already carries it in full.
            appendLine("| Declaration | Kind | Where |")
            appendLine("| --- | --- | --- |")
            ranked.take(WORKLIST_ENTRIES).forEach { (doc, symbol) ->
                appendLine(
                    "| `${escapeCode(symbol.qualifiedName)}` | ${symbol.kind} | " +
                        "`${doc.relativePath}:${symbol.line}` |"
                )
            }
            appendLine()
            val dropped = ranked.size - minOf(ranked.size, WORKLIST_ENTRIES)
            if (dropped > 0) {
                appendLine(
                    "…and **$dropped more** undocumented entry points not listed here. This section " +
                        "is capped at $WORKLIST_ENTRIES so it stays a worklist rather than a dump; " +
                        "the per-file table above is complete."
                )
            } else {
                appendLine("That is all of them.")
            }
        }
    }

    /** Types first, then top-level functions, then values — the order KDoc pays off in. */
    private fun kindRank(symbol: Symbol): Int = when (symbol.kind) {
        "interface" -> 0
        "class" -> 0
        "object" -> 1
        "typealias" -> 1
        "fun" -> 2
        else -> 3
    }

    // ── small helpers ───────────────────────────────────────────────────────────────────────────

    private const val BANNER =
        "<!-- DO NOT EDIT — generated by afield.docs.StdlibReferenceGenerator from KDoc in src/jvmMain/kotlin. -->\n" +
            "<!-- Regenerate: ./gradlew generateCodebaseReference -->"

    /**
     * Rewrites the markdown code fences in KDoc-derived prose to the docs site's `diffstatic` fence.
     *
     * KDoc is written with ordinary triple-backtick `kotlin` fences, which is what IDEs and Field's own
     * in-app documentation panel expect, so the sources stay exactly as they are. The published
     * reference, though, is a Zensical page where every other code block on the site is a `diffstatic`
     * fence rendered by Field's own highlighter — a passed-through `kotlin` fence gets the site's
     * default highlighter instead and reads as a foreign element (different type colours, a line-number
     * gutter, a copy button). Normalising at emission rather than in the KDoc keeps one convention in
     * the source and one on the page, and covers every overview written after this without asking
     * anyone to remember.
     *
     * A fence with no language is treated as Kotlin, which is what those blocks have always been in
     * practice. Closing fences are left alone, and an already-`diffstatic` fence is passed through.
     */
    /**
     * Re-levels the headings in a file overview so its shallowest one becomes `###` — a child of the
     * `## SomeFile.kt` section the prose is printed inside — with deeper headings keeping their
     * relative depth.
     *
     * A file overview is written as free-standing prose and its author reasonably starts at `#` or
     * `##`. Printed verbatim, a `#` competed with the page's own title and a `##` became a *sibling*
     * of the file heading: `reference/polytopes.md` rendered 45 `h2`s of which only 17 were files, and
     * the page's table of contents read as a flat list alternating "Canonical.kt" with "Two
     * iterations, and why there are two". Normalising to `###` puts them at the same level as the
     * `### TypeName` sections that follow in the same file, which is where they belong, and means an
     * overview reads the same whichever convention its author used.
     *
     * Lines inside a fenced block are left alone — `#` is a comment in several of the languages the
     * overviews quote. Levels are clamped at `######`, markdown's deepest.
     */
    private fun demoteHeadings(text: String): String {
        if ('#' !in text) return text
        val lines = text.split("\n")
        var fenced = false
        val headingLevels = mutableListOf<Int>()
        lines.forEach { line ->
            if (FENCE_OPENER.matchEntire(line) != null) { fenced = !fenced; return@forEach }
            if (fenced) return@forEach
            HEADING.matchEntire(line)?.let { headingLevels += it.groupValues[1].length }
        }
        val shallowest = headingLevels.minOrNull() ?: return text
        val shift = 3 - shallowest
        if (shift == 0) return text
        fenced = false
        return lines.joinToString("\n") { line ->
            if (FENCE_OPENER.matchEntire(line) != null) { fenced = !fenced; return@joinToString line }
            if (fenced) return@joinToString line
            val match = HEADING.matchEntire(line) ?: return@joinToString line
            val level = (match.groupValues[1].length + shift).coerceIn(1, 6)
            "${"#".repeat(level)} ${match.groupValues[2]}"
        }
    }

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")

    private fun fencesToDiffstatic(text: String): String {
        if (!text.contains("```")) return text
        var open = false
        return text.split("\n").joinToString("\n") { line ->
            val match = FENCE_OPENER.matchEntire(line) ?: return@joinToString line
            if (open) {
                open = false
                line
            } else {
                open = true
                val lang = match.groupValues[2].ifBlank { "kotlin" }
                if (lang.startsWith("diff")) line else "${match.groupValues[1]}```diffstatic {data-lang=$lang}"
            }
        }
    }

    /** A whole-line triple-backtick fence: leading indent, then an optional bare language word. */
    private val FENCE_OPENER = Regex("""^(\s*)```\s*([A-Za-z0-9_+-]*)\s*$""")

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
