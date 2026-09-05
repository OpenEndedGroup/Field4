package afield.ai

import afield.RunsAt
import afield.early
import afield.logger.lprintln
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.concurrent.thread
import kotlin.io.path.isRegularFile
import kotlin.jvm.java

data class KotlinSourceFileRecord(
    val path: String,
    val contentHash: String,
    val fileId: String?,
    val bytes: Long,
    val modifiedEpochMs: Long
)

data class KotlinDefinitionRecord(
    val name: String,
    val kind: String,
    val filePath: String,
    val line: Int,
    val fileId: String?,
    val contentHash: String,
    val kdoc: String? = null,
    val completionCategory: String? = null
)

data class KotlinSourceIndexDb(
    val version: Int = 1,
    val root: String,
    val apiKeyHash: String?,
    val updatedAtEpochMs: Long,
    val files: List<KotlinSourceFileRecord>,
    val definitions: List<KotlinDefinitionRecord>,
    val vectorStoreId: String? = null,
    val vectorStoreFileHash: String? = null
)


object KotlinSourceIndex {
    private val mapper = OpenAiFileStoreCache.defaultMapper()

    // Resolved per call, not captured: [FilestoreDir] is pinnable, and a path frozen at class-load
    // time is exactly the shape of bug that made this index machine-global in the first place.
    private val indexPath: Path get() = FilestoreDir.resolve("kotlin_source_index.json")

    @Volatile
    private var loadedFrom: Path? = null

    /**
     * The index in memory — but only while it still belongs to the directory [FilestoreDir] currently
     * resolves to.
     *
     * The guard is what makes [FilestoreDir.withDirectory] actually work. Without it, a JVM that had
     * already synced would keep answering from the old tree's index no matter where the filestore was
     * pointed, and a test seeding a fixture would silently measure whatever ran before it — which is
     * the failure mode this whole seam exists to remove, reintroduced one level up.
     *
     * The setter is `internal` rather than `private` for exactly one reason: an in-memory staleness
     * guard that only [runSync] can ever populate is a guard no test can reach, and `FilestoreDirTest`
     * has to be able to put a loaded index in place before it moves the directory out from under it.
     */
    @Volatile
    var latestIndex: KotlinSourceIndexDb? = null
        get() = if (loadedFrom == null || loadedFrom == FilestoreDir.path) field else null
        internal set(value) {
            field = value
            loadedFrom = if (value == null) null else FilestoreDir.path
        }

    fun loadIndex(): KotlinSourceIndexDb? {
        if (!Files.exists(indexPath)) return null
        return runCatching { mapper.readValue(indexPath.toFile(), KotlinSourceIndexDb::class.java) }
            .getOrNull()
    }

    fun startBackgroundSync() {
        thread(isDaemon = true, name = "KotlinSourceIndex") {
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            runSync()
        }
    }

    private fun runSync() {
        val root = Paths.get(System.getProperty("user.dir")).normalize()
        val sourceRoots = listOf(
            root.resolve("./src/jvmMain/kotlin"),
            root.resolve("./src/personal/kotlin"),
            root.resolve("./src/generatedLinalg/kotlin"),
        ).filter { Files.exists(it) }

        if (sourceRoots.isEmpty()) {
            lprintln("warning,sourceindex", "no source roots found under ${root.toAbsolutePath()}")
            return
        }

        val previous = loadIndex()
        val files = mutableListOf<KotlinSourceFileRecord>()
        val definitions = mutableListOf<KotlinDefinitionRecord>()

        val ktFiles = collectKotlinFiles(sourceRoots)
        ktFiles.forEach { file ->
            val rel = root.relativize(file).toString().replace("\\", "/")
            val bytes = runCatching { Files.readAllBytes(file) }.getOrElse { return@forEach }
            val rawText = runCatching { bytes.toString(StandardCharsets.UTF_8) }.getOrDefault("")
            val isKotlinFile = rel.endsWith(".kt", ignoreCase = true) || rel.endsWith(".kts", ignoreCase = true)
            val indexedText = if (isKotlinFile) {
                buildString {
                    appendLine("// @language=kotlin")
                    appendLine("// @file $rel")
                    appendLine()
                    append(rawText)
                }
            } else {
                rawText
            }
            val indexedBytes = indexedText.toByteArray(StandardCharsets.UTF_8)
            val contentHash = Sha256.hex(indexedBytes)
            val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
            files += KotlinSourceFileRecord(
                path = rel,
                contentHash = contentHash,
                fileId = null,
                bytes = indexedBytes.size.toLong(),
                modifiedEpochMs = modified
            )

            parseTopLevelDefinitions(rawText).forEach { def ->
                definitions += KotlinDefinitionRecord(
                    name = def.name,
                    kind = def.kind,
                    filePath = rel,
                    line = def.line,
                    fileId = null,
                    contentHash = contentHash,
                    kdoc = def.kdoc,
                    completionCategory = def.completionCategory
                )
            }
        }

        val index = KotlinSourceIndexDb(
            root = ".",
            apiKeyHash = null,
            updatedAtEpochMs = System.currentTimeMillis(),
            files = files,
            definitions = definitions,
            vectorStoreId = null,
            vectorStoreFileHash = null
        )

        if (previous != null && previous.materiallySameAs(index)) {
            latestIndex = previous
            lprintln("info,sourceindex", "index unchanged: ${files.size} files, ${definitions.size} definitions")
        } else {
            runCatching {
                Files.createDirectories(indexPath.parent)
                mapper.writerWithDefaultPrettyPrinter().writeValue(indexPath.toFile(), index)
            }.onFailure {
                lprintln("error,sourceindex", "failed to persist index: ${it.message}")
            }

            latestIndex = index
            lprintln("info,sourceindex", "indexed ${files.size} files, ${definitions.size} definitions")
        }
    }

    private fun KotlinSourceIndexDb.materiallySameAs(other: KotlinSourceIndexDb): Boolean =
        root == other.root &&
            apiKeyHash == other.apiKeyHash &&
            vectorStoreId == other.vectorStoreId &&
            vectorStoreFileHash == other.vectorStoreFileHash &&
            files.map { it.copy(modifiedEpochMs = 0L) } == other.files.map { it.copy(modifiedEpochMs = 0L) } &&
            definitions == other.definitions

    /**
     * Get KDoc for a symbol from the index.
     *
     * Looks up the KDoc comment (`/** ... */`) that [parseTopLevelDefinitions] extracted and
     * stored on each [KotlinDefinitionRecord]. The index is built by scanning the source roots
     * (src/jvmMain/kotlin, src/personal/kotlin, src/generatedLinalg/kotlin) once at server startup
     * (see [syncKotlinSourceIndex]); it is not live-updated, so a restart is needed to pick up edits.
     *
     * Both top-level and nested/member symbols are supported: [parseTopLevelDefinitions] tracks a
     * class stack and emits qualified names like `Outer.member` for declarations inside a class body,
     * and extension functions are indexed under `ReceiverType.methodName`. Callers therefore pass a
     * qualified name (e.g. a receiver-qualified `"Vector3f.add"` for dot-completion) to get an exact,
     * unambiguous match. A plain simple name first tries an exact match, then falls back to matching
     * the last segment of qualified names; a name that already contains '.' never falls back to that
     * partial match (so `"Vector3f.add"` will not accidentally resolve to `"LittleHistory.add"`).
     */
    fun getKDocForSymbol(symbolName: String): String? {
        val index = latestIndex ?: loadIndex() ?: return null
        
        // First try exact match
        val exactMatch = index.definitions.firstOrNull { it.name == symbolName }
        if (exactMatch?.kdoc != null) {
            return exactMatch.kdoc
        }

        // If the input is already qualified (contains '.'), don't fall through to partial matching —
        // that would match unrelated symbols with the same simple name (e.g. "Vector3f.add" → "LittleHistory.add")
        if ('.' in symbolName) return null
        
        // Try matching the last part of qualified names as a fallback for simple names only
        val partialMatch = index.definitions.firstOrNull { 
            it.name == symbolName || it.name.endsWith(".$symbolName") 
        }
        return partialMatch?.kdoc
    }

    /**
     * The completion category for one candidate.
     *
     * [declaredIn] is what makes this answerable: a bare member name is not a question this index can
     * answer, because the index is keyed by name and a common verb collides hard — in this tree `scale`
     * is declared in 42 categorised files, `close` in 21, `copy` in 16. Given the *declaring* container
     * the answer is exact, because [parseTopLevelDefinitions] already stores members qualified
     * (`"$owner.$name"`), so `FLine.moveTo` is a key this index holds.
     *
     * `declaredIn` arrives from the AA sidecar per candidate ([AaCompletionClient.DECLARED_IN]) and is
     * either a class FQN for a member (`afield.graphics.FLine`) or a package for a top-level extension
     * (`afield.four.synth`). Both are handled below, and both beat voting on a name.
     *
     * Returns null rather than guessing when the evidence does not single one declaration out. That is
     * deliberate and it is a fix, not a regression: the caller falls through to `declarationCategory` and
     * then to `inferCompletionCategory`, which knows how to read a path and a qualified name. A wrong
     * category is much worse than none, because the popup GROUPS by category — a misfiled member is
     * rendered in a foreign section, below every row of its own type, where no ranking term can reach it.
     * That is what filed `FLine.moveTo` under **Math** (its only categorised namesake being
     * `stdlib/Functions.kt`) and `close`/`rot`/`scale`/`copy` under Solvers/Patterns/Synthesis/Audio,
     * while `lineTo` and `cubicTo` were correct all along purely because they collide with nothing.
     */
    fun getCompletionCategoryForSymbol(
        symbolName: String,
        sourceName: String? = null,
        sourcePath: String? = null,
        declaredIn: String? = null
    ): String? {
        val index = latestIndex ?: loadIndex() ?: return null
        val simpleName = symbolName.substringAfterLast('.').trim()
        if (simpleName.isBlank()) return null

        fun KotlinDefinitionRecord.categoryOrNull(): String? =
            completionCategory?.trim()?.takeIf { it.isNotEmpty() }

        if (!sourceName.isNullOrBlank()) {
            index.definitions.firstOrNull { it.name == sourceName }?.categoryOrNull()?.let { return it }
        }

        val matches = index.definitions.filter {
            it.name.substringAfterLast('.') == simpleName ||
                (!sourceName.isNullOrBlank() && it.name == sourceName)
        }

        if (!sourcePath.isNullOrBlank()) {
            matches.firstOrNull { it.filePath == sourcePath }?.categoryOrNull()?.let { return it }
        }

        val container = declaredIn?.trim()?.takeIf { it.isNotEmpty() }
        if (container != null) {
            // A member: ask for the exact declaration, `Owner.member`. If it is in the index, its answer is
            // the only correct one — including "no category", which must NOT fall through to the name vote.
            val ownerSimple = container.substringAfterLast('.')
            val exact = matches.filter { it.name == "$ownerSimple.$simpleName" }
            if (exact.isNotEmpty()) return exact.firstNotNullOfOrNull { it.categoryOrNull() }

            // A top-level extension: `declaredIn` is a package, which scopes the vote to the right file
            // instead of choosing between 42 of them. A package can still contain several categorised
            // files, so answer only when those files agree rather than inheriting filesystem walk order.
            val packagePath = container.replace('.', '/')
            val inPackage = matches.filter { it.filePath.contains("/$packagePath/") }
            if (inPackage.isNotEmpty()) {
                return inPackage.mapNotNull { it.categoryOrNull() }.distinct().singleOrNull()
            }

            // `declaredIn` names something outside this tree — `java.lang.Math`, a jar. This tree's
            // namesakes say nothing about it, so decline instead of borrowing their category.
            return null
        }

        // No declaring container to go on: answer only if the tree is unanimous about the name.
        return matches.mapNotNull { it.categoryOrNull() }.distinct().singleOrNull()
    }

    /**
     * The definitions [roots] declare — the deterministic half of [runSync], with no network or cache.
     *
     * A function of the source tree and nothing else, which is exactly what a test needs to seed a
     * fixture index into [FilestoreDir] instead of reading whichever Field last wrote the machine's
     * one. `fileId` and `contentHash` are left empty because the local retrieval consumers do not
     * require hosted IDs.
     *
     * @param relativeTo the path each `filePath` is recorded relative to — `user.dir` in [runSync].
     */
    fun scanDefinitions(roots: List<Path>, relativeTo: Path): List<KotlinDefinitionRecord> =
        collectKotlinFiles(roots.filter { Files.exists(it) }).sorted().flatMap { file ->
            val rel = runCatching { relativeTo.relativize(file).toString() }
                .getOrElse { file.toString() }.replace("\\", "/")
            val text = runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrNull()
                ?: return@flatMap emptyList()
            parseTopLevelDefinitions(text).map { def ->
                KotlinDefinitionRecord(
                    name = def.name,
                    kind = def.kind,
                    filePath = rel,
                    line = def.line,
                    fileId = null,
                    contentHash = "",
                    kdoc = def.kdoc,
                    completionCategory = def.completionCategory
                )
            }
        }

    private fun collectKotlinFiles(roots: List<Path>): List<Path> {
        val out = mutableListOf<Path>()
        roots.forEach { root ->
            runCatching {
                Files.walk(root).use { stream ->
                    stream.filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                        .forEach { out.add(it) }
                }
            }
        }
        return out
    }

    /**
     * One declaration as [parseTopLevelDefinitions] saw it.
     *
     * [visibility] and [owner] are *not* persisted into [KotlinDefinitionRecord] — the on-disk index
     * schema is unchanged — they exist for in-process consumers that need more than the completion
     * resolver does. `afield.docs.StdlibReferenceGenerator` uses them to emit only the public API and
     * to group members under their declaring type.
     */
    internal data class ParsedDefinition(
        val kind: String,
        val name: String,
        val line: Int,
        val kdoc: String? = null,
        val completionCategory: String? = null,
        /** `public`, `private`, `internal` or `protected`; `public` when no modifier was written. */
        val visibility: String = "public",
        /** Qualified name of the enclosing class/object/interface, or null for a top-level declaration. */
        val owner: String? = null
    )
    private data class ParsedLineDefinition(
        val kind: String,
        val name: String,
        val completionCategory: String? = null,
        val visibility: String = "public"
    )
    private data class ClassContext(val name: String, val depth: Int)
    private data class PendingClass(
        val name: String,
        val lineNumber: Int,
        var headerParenDepth: Int = 0
    )

    private val modifierTokens = setOf(
        "public", "private", "internal", "protected",
        "open", "final", "abstract", "sealed", "data", "enum", "annotation", "value",
        "inline", "suspend", "tailrec", "operator", "infix", "external", "override",
        "const", "lateinit", "companion", "inner"
    )

    private val visibilityTokens = setOf("public", "private", "internal", "protected")

    /** `fun` followed by the `interface` keyword — a SAM declaration, not a function. */
    private val FUN_INTERFACE = Regex("""^interface\b""")

    /**
     * The name part of a property declaration's text, with any delegate or initialiser removed.
     *
     * `by` is cut *before* `:` because a delegated property need have no type annotation at all
     * (`var FLine.color by Delegation<Vector4f> { vec(1, 1, 1, 1) }`), in which case there is no `:`
     * to stop at and the whole delegate expression would otherwise be taken for the name — which
     * fails to parse and drops the declaration from the index entirely.
     */
    private fun propertyNameHead(remainder: String): String =
        remainder.substringBefore(" by ").substringBefore(":").substringBefore("=").trim()

    /**
     * Line-based scan of one Kotlin source file for its top-level and one-level-nested declarations.
     *
     * Deliberately not a real parser: a [KotlinParseState] brace/string/comment state machine keeps
     * track of nesting so only declarations at file scope, or directly inside a class/object/interface
     * body, are emitted (never locals inside a function body). Members are emitted qualified as
     * `Owner.member`.
     *
     * `internal` rather than private because this is the single place that decides "what counts as a
     * declaration" for Field: the completion doc browser and the generated codebase reference
     * (`afield.docs.StdlibReferenceGenerator`) must agree by construction rather than by two parsers
     * happening to behave the same way.
     */
    internal fun parseTopLevelDefinitions(text: String): List<ParsedDefinition> {
        val out = mutableListOf<ParsedDefinition>()

        // Extract KDocs using the KDocExtractor
        val kdocs = KDocExtractor.extractKDocs(text)
        val kdocByLine = kdocs.associateBy { it.declarationLine }
        val fileCategory = fileCompletionCategory(text)

        val state = KotlinParseState()
        val classStack = ArrayDeque<ClassContext>()
        var pendingClass: PendingClass? = null
        var pendingCategory: String? = null

        val lines = text.split("\n")
        lines.forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val depthAtStart = state.depth
            val currentClass = classStack.lastOrNull()
            val canParse = !state.inBlockComment &&
                !state.inRawString &&
                !state.inString &&
                (depthAtStart == 0 || (currentClass != null && depthAtStart == currentClass.depth))

            if (canParse) {
                val def = parseDefinitionFromLine(rawLine)
                if (def == null && rawLine.trimStart().startsWith("@")) {
                    completionCategoryFromAnnotation(rawLine)?.let { pendingCategory = it }
                }
                if (pendingClass != null &&
                    pendingClass.lineNumber != lineNumber &&
                    pendingClass.headerParenDepth == 0 &&
                    def != null
                ) {
                    // A new declaration appeared before the pending class body opened.
                    pendingClass = null
                }
                def?.let { parsed ->
                    val kind = parsed.kind
                    val name = parsed.name
                    val kdoc = kdocByLine[lineNumber]?.text
                    val category = parsed.completionCategory ?: pendingCategory ?: fileCategory
                    pendingCategory = null
                    when (kind) {
                        "class", "interface", "object" -> {
                            val qualifiedName = if (currentClass != null) {
                                "${currentClass.name}.$name"
                            } else {
                                name
                            }
                            out += ParsedDefinition(
                                kind, qualifiedName, lineNumber, kdoc, category,
                                parsed.visibility, currentClass?.name
                            )
                            pendingClass = PendingClass(qualifiedName, lineNumber)
                        }
                        "fun", "val", "var", "typealias" -> {
                            // A `val`/`var` on a continuation line of a class's primary constructor
                            // is that class's property, not a top-level declaration. Without this the
                            // index gained bogus bare entries (`re`, `im`, `sampleRate`, …) that any
                            // simple-name KDoc lookup could match by accident.
                            val constructorOwner = pendingClass
                                ?.takeIf { (kind == "val" || kind == "var") && it.headerParenDepth > 0 }
                                ?.name
                            val owner = constructorOwner
                                ?: currentClass?.takeIf { depthAtStart == it.depth }?.name
                            val qualifiedName = if (owner != null) "$owner.$name" else name
                            out += ParsedDefinition(
                                kind, qualifiedName, lineNumber, kdoc, category,
                                parsed.visibility, owner
                            )
                        }
                    }
                }
            }

            var openedClassThisLine = false
            state.consumeLine(rawLine) { ch, _, _, after ->
                val pending = pendingClass
                if (pending != null && !openedClassThisLine) {
                    when (ch) {
                        '(' -> pending.headerParenDepth++
                        ')' -> if (pending.headerParenDepth > 0) pending.headerParenDepth--
                        '{' -> if (pending.headerParenDepth == 0) {
                            classStack.addLast(ClassContext(pending.name, after))
                            pendingClass = null
                            openedClassThisLine = true
                        }
                    }
                }
            }

            while (classStack.isNotEmpty() && state.depth < classStack.last().depth) {
                classStack.removeLast()
            }
        }
        return out
    }

    private fun parseDefinitionFromLine(line: String): ParsedLineDefinition? {
        var s = line.trimStart()
        if (s.isEmpty()) return null
        if (s.startsWith("package ") || s.startsWith("import ")) return null
        if (s.startsWith("//")) return null

        val annotationRegex = Regex("^@\\w[\\w\\.]*\\s*(\\([^)]*\\))?\\s*")
        var category: String? = null
        while (true) {
            val match = annotationRegex.find(s)
            if (match != null && match.range.first == 0) {
                completionCategoryFromAnnotation(match.value)?.let { category = it }
                s = s.substring(match.range.last + 1).trimStart()
            } else {
                break
            }
        }

        val tokens = s.split(Regex("\\s+"))
        var idx = 0
        var sawCompanion = false
        var visibility = "public"
        while (idx < tokens.size && modifierTokens.contains(tokens[idx])) {
            if (tokens[idx] == "companion") sawCompanion = true
            if (tokens[idx] in visibilityTokens) visibility = tokens[idx]
            idx++
        }
        if (idx >= tokens.size) return null

        var keyword = tokens[idx]
        var remainder = s.substringAfter(keyword, "").trimStart()

        // `fun interface Foo` declares a SAM interface, not a function. Skipping it hid a whole
        // class of public API (FnMetric, TuioListener, QuantileFunction…) from the index.
        if (keyword == "fun" && FUN_INTERFACE.containsMatchIn(remainder)) {
            keyword = "interface"
            remainder = remainder.removePrefix("interface").trimStart()
        }

        val rawName = when (keyword) {
            "fun" -> {
                val head = remainder.substringBefore("(").trim()
                parseQualifiedName(head)
            }
            "class", "interface", "object" -> {
                Regex("^(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)")
                    .find(remainder)?.groupValues?.get(1)?.let { unwrapIdentifier(it) }
            }
            "val", "var" -> {
                val head = propertyNameHead(remainder)
                parseQualifiedName(head)
            }
            "typealias" -> {
                val head = remainder.substringBefore("=").trim()
                parseQualifiedName(head)
            }
            else -> null
        }
        val effectiveName = when {
            keyword == "object" && (rawName == null || rawName.isBlank()) && sawCompanion -> "Companion"
            rawName == null -> return null
            else -> rawName
        }
        val name = effectiveName
        if (name.isBlank()) return null
        if (keyword == "class" || keyword == "interface" || keyword == "object" || keyword == "fun" || keyword == "val" || keyword == "var" || keyword == "typealias") {
            return ParsedLineDefinition(keyword, name, category, visibility)
        }
        return null
    }

    private fun fileCompletionCategory(text: String): String? =
        Regex("""(?m)^\s*@file:(?:[\w.]+\.)?CompletionCategory\s*\(\s*"([^"]+)"""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun completionCategoryFromAnnotation(text: String): String? =
        Regex("""@(?:[\w.]+\.)?CompletionCategory\s*\(\s*"([^"]+)"""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun parseQualifiedName(raw: String): String? {
        if (raw.isBlank()) return null
        val noGenerics = stripGenericArgs(raw)
        val compact = removeWhitespaceOutsideBackticks(noGenerics).replace("?", "")
        if (compact.isBlank()) return null
        val regex = Regex("^(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)(\\.`[^`]+`|\\.[A-Za-z_][A-Za-z0-9_]*)*$")
        val match = regex.find(compact) ?: return null
        return match.value.split(".").joinToString(".") { unwrapIdentifier(it) }
    }

    private fun unwrapIdentifier(raw: String): String =
        if (raw.startsWith("`") && raw.endsWith("`") && raw.length > 1) {
            raw.removePrefix("`").removeSuffix("`")
        } else {
            raw
        }

    private fun stripGenericArgs(raw: String): String {
        val sb = StringBuilder(raw.length)
        var depth = 0
        for (ch in raw) {
            when (ch) {
                '<' -> depth++
                '>' -> if (depth > 0) depth--
                else -> if (depth == 0) sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun removeWhitespaceOutsideBackticks(raw: String): String {
        val sb = StringBuilder(raw.length)
        var inBackticks = false
        for (ch in raw) {
            if (ch == '`') {
                inBackticks = !inBackticks
                sb.append(ch)
                continue
            }
            if (!inBackticks && ch.isWhitespace()) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * A line-at-a-time lexical tracker for "how deeply nested in braces is this line, in *code*".
     *
     * Everything downstream — which declarations [parseTopLevelDefinitions] is willing to emit, and
     * therefore what the completion panel offers and what the generated reference publishes — hangs
     * off [depth] being right. A depth that drifts *low* is the expensive direction: the scan decides
     * a function body has ended while still inside it and starts indexing the locals that follow as
     * though they were public API. Every one of the five shapes below was found that way, by
     * cross-checking this against [afield.docs.KotlinSourcePartition] over the whole repository.
     *
     * The five, each with the smallest source that shows it:
     *
     * 1. **Backtick-quoted identifiers.** `` fun `a builder's groups are named`() { `` — the
     *    apostrophe inside the backticks is not a character literal, but a scanner that does not know
     *    about backticks reads one, and then swallows every brace up to the next apostrophe anywhere
     *    in the file. This is the big one in test sources, where possessives in test names are
     *    idiomatic.
     * 2. **Nested block comments.** Kotlin's block comments nest, so a KDoc that *mentions* a comment
     *    marker — `RewriteDsl.kt` explains a "stack mark" and writes the marker in backticks — stays
     *    open across it. Treating the inner close as the outer close ends the comment mid-prose and
     *    hands the rest of the KDoc to the code path.
     * 3. **String templates.** `"…${line?.let { " x=$it" } ?: ""} …"` — the expression inside `${…}`
     *    is code, and can contain strings of its own. Skipping from the opening quote to the next
     *    quote lands inside the template, after which its braces are counted and its text is lexed
     *    as code.
     * 4. **A quote run closing a raw string.** `""" data-label="$x""""` is a raw string whose content
     *    ends in a quote: of the four closing quotes the *last three* close it. Taking the first
     *    three leaves a stray quote that opens a string nothing ever closes.
     * 5. **A string that never closes on its line.** A single-quoted or double-quoted literal cannot
     *    contain a newline in Kotlin, so if one is still open at the end of a line the scan has
     *    mis-lexed something. Dropping it there bounds the damage to one line instead of letting it
     *    run to the end of the file. (A `${…}` inside a string *may* span lines, and is kept.)
     *
     * Deliberately still not a real parser: it is a lexer's worth of state, no more, because the
     * declaration recogniser above it is line-based and could not use a parse tree if it had one.
     */
    private class KotlinParseState {
        /** Brace nesting depth in code: braces inside strings, comments and templates do not count. */
        var depth: Int = 0
            private set

        /** Nesting count for block comments, which nest in Kotlin. Zero means "not in a comment". */
        private var blockCommentDepth: Int = 0

        private enum class Frame { CODE, STRING, RAW_STRING }

        /**
         * The lexical context stack, innermost last, with a `CODE` frame at the bottom that is never
         * popped. A string pushes `STRING`/`RAW_STRING`; a `${` inside one pushes a `CODE` frame back
         * on top, which is what makes `"${f("x")}"` come out right.
         */
        private val frames = ArrayDeque<Frame>().apply { addLast(Frame.CODE) }

        /** The opening quote of each `STRING` frame, so a `'` literal is not closed by a `"`. */
        private val quotes = ArrayDeque<Char>()

        /** Open brace count *within* the innermost `CODE` frame, so a template knows its own `}`. */
        private val braces = ArrayDeque<Int>().apply { addLast(0) }

        val inBlockComment: Boolean get() = blockCommentDepth > 0

        /** True anywhere lexically inside a raw string, including inside one of its templates. */
        val inRawString: Boolean get() = frames.contains(Frame.RAW_STRING)

        /** True anywhere lexically inside a normal string or character literal. */
        val inString: Boolean get() = frames.contains(Frame.STRING)

        private fun pushCode() {
            frames.addLast(Frame.CODE)
            braces.addLast(0)
        }

        private fun popFrame() {
            when (frames.removeLast()) {
                Frame.CODE -> braces.removeLast()
                Frame.STRING -> quotes.removeLast()
                Frame.RAW_STRING -> Unit
            }
        }

        fun consumeLine(line: String, onToken: ((ch: Char, index: Int, before: Int, after: Int) -> Unit)? = null) {
            var i = 0
            while (i < line.length) {
                if (blockCommentDepth > 0) {
                    when {
                        line.startsWith("*/", i) -> { blockCommentDepth--; i += 2 }
                        line.startsWith("/*", i) -> { blockCommentDepth++; i += 2 }
                        else -> i++
                    }
                    continue
                }
                when (frames.last()) {
                    Frame.RAW_STRING -> {
                        when {
                            line.startsWith("\${", i) -> { pushCode(); i += 2 }
                            line.startsWith("\"\"\"", i) -> {
                                // A run of N ≥ 3 quotes closes with its last three; the rest are content.
                                var run = 0
                                while (i + run < line.length && line[i + run] == '"') run++
                                popFrame()
                                i += run
                            }
                            else -> i++
                        }
                        continue
                    }
                    Frame.STRING -> {
                        when {
                            line[i] == '\\' -> i += 2
                            line.startsWith("\${", i) -> { pushCode(); i += 2 }
                            line[i] == quotes.last() -> { popFrame(); i++ }
                            else -> i++
                        }
                        continue
                    }
                    Frame.CODE -> Unit
                }

                if (line.startsWith("//", i)) break
                if (line.startsWith("/*", i)) {
                    blockCommentDepth = 1
                    i += 2
                    continue
                }
                if (line.startsWith("\"\"\"", i)) {
                    frames.addLast(Frame.RAW_STRING)
                    i += 3
                    continue
                }
                val ch = line[i]
                if (ch == '`') {
                    // A backtick-quoted identifier. It cannot span a line, so an unclosed one is a
                    // lexical error; treat that backtick as an ordinary character rather than eating
                    // the rest of the line.
                    val close = line.indexOf('`', i + 1)
                    i = if (close < 0) i + 1 else close + 1
                    continue
                }
                if (ch == '"' || ch == '\'') {
                    frames.addLast(Frame.STRING)
                    quotes.addLast(ch)
                    i++
                    continue
                }
                // Tokens are only reported from the outermost code frame: a brace inside a string
                // template is a lambda in an interpolation, never a class body.
                val outermost = frames.size == 1
                if (ch == '{') {
                    val before = depth
                    depth++
                    braces.addLast(braces.removeLast() + 1)
                    if (outermost) onToken?.invoke(ch, i, before, depth)
                } else if (ch == '}') {
                    if (braces.last() == 0 && frames.size > 1) {
                        // The `}` that ends a `${…}`; the string it interrupted resumes.
                        popFrame()
                        i++
                        continue
                    }
                    val before = depth
                    depth = (depth - 1).coerceAtLeast(0)
                    braces.addLast((braces.removeLast() - 1).coerceAtLeast(0))
                    if (outermost) onToken?.invoke(ch, i, before, depth)
                } else if (ch == '(' || ch == ')') {
                    if (outermost) onToken?.invoke(ch, i, depth, depth)
                }
                i++
            }
            // A normal string or character literal cannot contain a newline. One still open here is
            // a mis-lex; end it, so the damage stops at this line. A `${…}` left open is not dropped:
            // a template expression legitimately spans lines.
            while (frames.size > 1 && frames.last() == Frame.STRING) popFrame()
        }
    }
}

@RunsAt(early, priority = 100)
fun syncKotlinSourceIndex() {
    KotlinSourceIndex.startBackgroundSync()
}
