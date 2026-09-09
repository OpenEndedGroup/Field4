package afield.ai.core.completion

import afield.ai.FilestoreDir
import afield.ai.KotlinDefinitionRecord
import afield.ai.KotlinSourceIndex
import afield.ai.KotlinSourceIndexDb
import afield.ai.OpenAiFileStoreCache
import afield.ai.Sha256
import afield.ai.core.Ai4ModelDefaults
import afield.ai.core.LlmBridge
import afield.ai.core.LlmReasoningEffort
import afield.ai.core.LlmThreadState
import afield.ai.core.SilentLog
import afield.ai.core.aiProviderConfigRevision
import afield.ai.core.createLlmBridge
import afield.logger.lprintln
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipFile
import kotlin.concurrent.read
import kotlin.concurrent.write

private const val COMPLETION_DOC_PROMPT_VERSION = "completion-doc-param-notes-v2"

/**
 * The generated-completion-doc cache's file name, spelled once.
 *
 * [Ai4CompletionDoc] writes it, `Ai4CompletionDocPolishCli` polishes it, and
 * `CompletionDocPolishMonitor` reports on it. All three used to spell it themselves, which is how
 * `~/.afield/filestore` came to be spelled four times before [FilestoreDir] existed.
 */
const val GENERATED_COMPLETION_DOCS_FILE = "generated_completion_docs.json"

/**
 * How many distinct indexed source files [Ai4CompletionDoc.dropStaleGeneratedDocs] probes before it
 * concludes it is looking at the wrong tree. Definitions come out of the index grouped by file, so a
 * couple of hundred distinct paths is a broad sample of the checkout and a few hundred `exists` calls.
 */
private const val INDEX_TREE_PROBE_FILES = 200

object Ai4CompletionDoc {
    private fun model(): String = Ai4ModelDefaults.envOrDefault(
        "AFIELD_COMPLETION_DOC_MODEL",
        Ai4ModelDefaults.FAST_MODEL
    )
    @Volatile private var bridgeRevision = -1L
    @Volatile private var activeBridge: LlmBridge? = null
    private val threadByKey = ConcurrentHashMap<String, LlmThreadState>()

    @Synchronized
    private fun bridge(): LlmBridge {
        val revision = aiProviderConfigRevision()
        activeBridge?.takeIf { bridgeRevision == revision }?.let { return it }
        return createLlmBridge(
            model = model(),
            log = SilentLog,
            reasoningEffort = LlmReasoningEffort.LOW
        ).also {
            threadByKey.clear()
            activeBridge = it
            bridgeRevision = revision
        }
    }
    private val cache by lazy { GeneratedCompletionDocCache() }
    private val generationLocks = ConcurrentHashMap<String, Any>()
    private val swept = AtomicBoolean(false)

    /** Separates the jar from the entry in the `jar:file:...!/path/File.kt` paths the index stores. */
    private const val JAR_ENTRY_MARKER = "!/"

    fun resolveDoc(
        fileKey: String,
        label: String,
        displayText: String,
        insertText: String,
        detail: String,
        existingDoc: String,
        allCode: String,
        line: Int,
        column: Int,
        documentationSourceName: String = "",
        documentationSourcePath: String = "",
        documentationSourceLine: Int = -1
    ): String {
        val symbol = extractSymbolName(displayText, insertText, label)
        val definition = findDefinition(
            symbol = symbol,
            sourceName = documentationSourceName,
            sourcePath = documentationSourcePath,
            sourceLine = documentationSourceLine
        ) ?: return ""
        if (!definition.kdoc.isNullOrBlank()) return ""

        val definitionSnippet = loadDefinitionSnippet(definition) ?: return ""
        val lockKey = "${definition.name}:${definition.filePath}:${definitionSnippet.sourceHash}"
        val generationLock = generationLocks.computeIfAbsent(lockKey) { Any() }

        synchronized(generationLock) {
            sweepStaleOnce()
            cache.lookup(
                sourceName = definition.name,
                sourcePath = definition.filePath,
                sourceLine = definition.line,
                sourceHash = definitionSnippet.sourceHash
            )?.let { return generatedDocumentationMarkdown(it.documentation, cached = true) }

            val generated = generateDoc(
                fileKey = fileKey,
                symbol = symbol,
                label = label,
                displayText = displayText,
                insertText = insertText,
                detail = detail,
                existingDoc = existingDoc,
                allCode = allCode,
                line = line,
                column = column,
                definition = definition,
                definitionSnippet = definitionSnippet
            )
            if (generated.isBlank()) return ""

            cache.put(
                sourceName = definition.name,
                sourcePath = definition.filePath,
                sourceLine = definition.line,
                sourceHash = definitionSnippet.sourceHash,
                documentation = generated,
                model = model()
            )
            return generatedDocumentationMarkdown(generated, cached = false)
        }
    }

    /**
     * Drop cache records the generation path can no longer reach, and report what went.
     *
     * The cache is keyed by declaration identity — prompt version, name, source path, source line, and
     * a hash of the declaration's own source — so [resolveDoc] never *reads* a record whose declaration
     * has moved or changed: the recomputed key simply misses, and a fresh doc is generated under a new
     * key. But that guard only guards the read. Nothing ever removed the record it missed, so the file
     * was append-only, and every move, every signature edit, and every bump of
     * [COMPLETION_DOC_PROMPT_VERSION] left its predecessor behind for good.
     *
     * Unreachable records are not harmless, because the cache has a second consumer that is not keyed:
     * `polishCompletionDocs` reads the whole file and integrates the prose into real KDoc. A real run on
     * 2026-09-04 was handed two records pointing at `afield/four/synth/GraphQuery.kt` — moved to
     * `afield/graphquery/` long before — and a `generateSmoothIsoContours` record describing
     * `pointWeights`/`edgeWeights` parameters the declaration no longer has. It was being asked to
     * document today's source from drafts written against source that is gone.
     *
     * So this restores the obvious invariant: the file holds only what [resolveDoc] could still hit. A
     * record goes when its declaration source is gone, or when the source now at its recorded location
     * hashes to something else. Nothing is kept on the grounds that its prose still reads well — a draft
     * is one `FAST_MODEL` call to remake, and wrong KDoc in source is not.
     *
     * Deliberately not a wipe when the tree cannot be seen. Snippet bounds come from the
     * [KotlinSourceIndex], and paths are resolved against `user.dir`, so a missing index or the wrong
     * working directory would make every record look stale and take the whole cache with it. Those cases
     * skip and say why. See [cannotSeeTheTree].
     *
     * @param cachePath the cache to sweep. Defaults to the one [resolveDoc] reads and writes; the polish
     *   CLI passes its own `--cache-file`, which is a different process and so a different instance.
     */
    fun dropStaleGeneratedDocs(cachePath: Path? = null): StaleSweep {
        val index = KotlinSourceIndex.latestIndex
            ?: KotlinSourceIndex.loadIndex()
            ?: return StaleSweep(skipped = "no Kotlin source index")
        cannotSeeTheTree(index)?.let { return StaleSweep(skipped = it) }
        val indexedRoots = sourceRootsIn(index)
        val target = cachePath?.let { GeneratedCompletionDocCache(it) } ?: cache
        return target.dropUnreachable { probeDeclarationSource(it, indexedRoots) }
    }

    /** What [dropStaleGeneratedDocs] did. [dropped] carries one readable line per removed record. */
    data class StaleSweep(
        val considered: Int = 0,
        val kept: Int = 0,
        /** Left alone because the declaration's source is not something this checkout can see at all. */
        val unverifiable: Int = 0,
        val dropped: List<String> = emptyList(),
        /** Why nothing was examined at all, when the tree could not be seen. */
        val skipped: String? = null
    ) {
        fun describe(): String {
            if (skipped != null) return "skipped — $skipped"
            if (considered == 0) return "no records"
            val tail = if (unverifiable > 0) ", $unverifiable unverifiable" else ""
            return "dropped ${dropped.size} stale of $considered, kept $kept$tail"
        }
    }

    /**
     * Sweep once per JVM, on the first cache read, so a live Field heals the file it is writing to.
     *
     * On the read path rather than at cache load because that is where the sweep's precondition already
     * holds: [resolveDoc] has been through `findDefinition`, so the index is in memory by now.
     */
    private fun sweepStaleOnce() {
        if (!swept.compareAndSet(false, true)) return
        val report = runCatching { dropStaleGeneratedDocs() }.getOrElse { error ->
            lprintln("warning,completion-docs", "stale generated-doc sweep failed: ${error.message}")
            return
        }
        if (report.dropped.isNotEmpty() || report.skipped != null) {
            lprintln("info,completion-docs", "generated completion docs: ${report.describe()}")
            report.dropped.forEach { lprintln("info,completion-docs", "  dropped $it") }
        }
    }

    /**
     * Null when [index] plausibly describes the tree under `user.dir`, otherwise why it does not.
     *
     * Both failures look identical from inside the sweep — every record stale — and both would be acted
     * on by deleting the entire cache, so they are separated here instead. One indexed file that
     * resolves is enough: it says the relative paths in the index and in the cache mean the same thing
     * here as they did when they were written.
     */
    private fun cannotSeeTheTree(index: KotlinSourceIndexDb): String? {
        if (index.definitions.isEmpty()) return "the Kotlin source index has no definitions"
        val anyIndexedFileOnDisk = index.definitions.asSequence()
            .map { it.filePath }
            .filterNot { it.startsWith("jar:") }
            .distinct()
            .take(INDEX_TREE_PROBE_FILES)
            .any { Files.exists(Paths.get(it)) }
        if (!anyIndexedFileOnDisk) {
            return "no indexed source file resolves from ${Paths.get("").toAbsolutePath()}"
        }
        return null
    }

    /**
     * What the tree says now about the declaration a cache record was drafted from.
     *
     * [indexedRoots] is what separates a declaration that went away from one this checkout cannot see:
     * see [sourceRootsIn].
     */
    private fun probeDeclarationSource(
        record: GeneratedCompletionDocRecord,
        indexedRoots: Set<String>
    ): DeclarationSource {
        if (record.sourcePath.isBlank() || record.sourceLine <= 0) return DeclarationSource.Gone
        if (record.sourcePath.startsWith("jar:") && jarPathOf(record.sourcePath)?.let(Files::exists) != true) {
            // The sources jar has been pruned out of the Gradle cache, or this is another machine. The
            // declaration is no more likely to have changed than the record is to be wrong, and a
            // dependency's sources come back with the next resolve, so say nothing about it.
            return DeclarationSource.Unverifiable
        }
        val snippet = loadDefinitionSnippet(
            KotlinDefinitionRecord(
                name = record.sourceName,
                kind = "",
                filePath = record.sourcePath,
                line = record.sourceLine,
                fileId = null,
                contentHash = "",
                kdoc = null
            )
        ) ?: return absentSource(record.sourcePath, indexedRoots)
        return DeclarationSource.Found(snippet.sourceHash)
    }

    /**
     * A record whose declaration source could not be read: did it go away, or was it never here?
     *
     * Missing from a source root the index covers is a declaration that went away. Missing from a root
     * the index has never heard of is a source set this checkout does not have — an uninitialised
     * `src/personal`, or a jar entry — and nothing this checkout is entitled to an opinion about. A path
     * that will not even parse is junk, and goes.
     */
    private fun absentSource(sourcePath: String, indexedRoots: Set<String>): DeclarationSource {
        val root = sourceRootOf(sourcePath)
        return when {
            root == null -> DeclarationSource.Gone
            root in indexedRoots -> DeclarationSource.Gone
            else -> DeclarationSource.Unverifiable
        }
    }

    /**
     * The source roots the index covers, as `src/jvmMain`-shaped prefixes.
     *
     * `src/personal` is an optional submodule, so a worktree that has not initialised it has no such
     * directory and every record under it reads as a deleted declaration — 12 of them, on the run that
     * prompted this. The index is synced from the same tree the sweep is looking at, so the source sets
     * it covers are what tell the two absences apart: a file missing from a root the index knows is a
     * file that went away, and a file missing from a root the index has never heard of is a source set
     * that is not checked out here. The second keeps its records, and its drafts stay valid for the
     * checkout that does have the submodule (the cache is per-checkout — see [FilestoreDir]).
     */
    private fun sourceRootsIn(index: KotlinSourceIndexDb): Set<String> =
        index.definitions.asSequence()
            .map { it.filePath }
            .filterNot { it.startsWith("jar:") }
            .mapNotNull(::sourceRootOf)
            .toSet()

    /** The first two path segments of [path] — `src/jvmMain` for `src/jvmMain/kotlin/afield/Boxes.kt`. */
    private fun sourceRootOf(path: String): String? {
        val parsed = runCatching { Paths.get(path) }.getOrNull() ?: return null
        if (parsed.nameCount == 0) return null
        return parsed.subpath(0, minOf(2, parsed.nameCount)).toString()
    }

    /**
     * The hash a fresh generation would key this location's declaration by, or null if there is none.
     *
     * `internal` for one reason: a sweep test has to be able to seed a record that is genuinely current,
     * and a test that worked out the snippet bounds itself would only be testing its own arithmetic.
     */
    internal fun currentDeclarationSourceHash(sourcePath: String, sourceLine: Int): String? =
        loadDefinitionSnippet(
            KotlinDefinitionRecord(
                name = "",
                kind = "",
                filePath = sourcePath,
                line = sourceLine,
                fileId = null,
                contentHash = "",
                kdoc = null
            )
        )?.sourceHash

    private fun generateDoc(
        fileKey: String,
        symbol: String,
        label: String,
        displayText: String,
        insertText: String,
        detail: String,
        existingDoc: String,
        allCode: String,
        line: Int,
        column: Int,
        definition: KotlinDefinitionRecord,
        definitionSnippet: DefinitionSnippet
    ): String {
        val cursorContext = snippetAroundCursor(allCode, line, column)

        val instructions = """
You improve Kotlin completion documentation for declarations that currently have no human KDoc.
Use the deterministic completion documentation as the factual starting point: keep its signature, defaults, return type, and source location authoritative.
Infer behavior only from the supplied declaration source, inherited/base documentation, deterministic completion documentation, and usage context.
Return Markdown only, without /** */ delimiters and without @param, @return, or other KDoc tags.
Write a direct one-sentence summary followed by at most one short explanatory paragraph.
If parameters are non-obvious, add a short **Parameter notes** section with bullets that explain purpose, constraints, units, side effects, or important interactions.
Do not merely repeat parameter names, types, or default values from the completion signature; describe what each parameter means.
Mention side effects, lifecycle, units, or constraints only when the source supports them.
        """.trimIndent()

        val sourceThreadKey = "$fileKey#${definitionSnippet.sourceHash}"
        val bridge = bridge()
        val state = threadByKey[sourceThreadKey] ?: bridge.startThread(
            fileKey = sourceThreadKey,
            fileText = definitionSnippet.source,
            instructions = instructions
        ).also { threadByKey[sourceThreadKey] = it }

        val prompt = buildString {
            appendLine("Undocumented indexed declaration:")
            appendLine("- symbol: ${definition.name.ifBlank { symbol.ifBlank { label } }}")
            appendLine("- source: ${definition.filePath}:${definition.line}")
            appendLine("- label: $label")
            if (displayText.isNotBlank()) appendLine("- display: $displayText")
            if (detail.isNotBlank()) appendLine("- detail: $detail")
            if (insertText.isNotBlank()) {
                appendLine()
                appendLine("Insert text:")
                appendLine("```kotlin")
                appendLine(insertText.trimEnd())
                appendLine("```")
            }
            if (cursorContext.isNotBlank()) {
                appendLine()
                appendLine("Current context:")
                appendLine("```kotlin")
                appendLine(cursorContext.trimEnd())
                appendLine("```")
            }
            appendLine()
            appendLine("Declaration source:")
            appendLine("```kotlin")
            appendLine(definitionSnippet.source.take(16_000).trimEnd())
            appendLine("```")
            if (existingDoc.isNotBlank()) {
                appendLine()
                appendLine("Deterministic completion documentation (authoritative skeleton; do not repeat mechanically):")
                appendLine(existingDoc.trimEnd())
            }
            appendLine()
            appendLine("Draft the missing documentation. Prefer adding useful parameter descriptions when the source supports them.")
        }

        val result = bridge.send(
            state = state,
            userPrompt = prompt,
            maxOutputTokens = 900
        )
        return result.text.trimEnd()
    }

    private data class DefinitionSnippet(
        val source: String,
        val sourceHash: String
    )

    private fun generatedDocumentationMarkdown(documentation: String, cached: Boolean): String =
        buildString {
            append("**AI-generated draft documentation")
            if (cached) append(" (cached)")
            append("**\n\n")
            append(documentation.trim())
        }

    private fun extractSymbolName(displayText: String, insertText: String, label: String): String {
        val candidates = listOf(displayText, insertText, label)
        for (raw in candidates) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            parseSymbolFromDeclaration(text)?.let { return it }
            parseQualifiedName(text)?.let { return it }
        }
        return label.trim()
    }

    private fun unwrapIdentifier(raw: String): String =
        if (raw.startsWith("`") && raw.endsWith("`") && raw.length > 1) {
            raw.removePrefix("`").removeSuffix("`")
        } else {
            raw
        }

    private fun parseSymbolFromDeclaration(text: String): String? {
        val match = Regex("^(fun|class|interface|object|val|var|typealias)\\s+(.+)$")
            .find(text)
            ?: return null
        val kind = match.groupValues[1]
        val remainder = match.groupValues[2].trimStart()
        return when (kind) {
            "fun" -> {
                val head = remainder.substringBefore("(").trim()
                parseQualifiedName(head)
            }
            "val", "var" -> {
                val head = remainder.substringBefore(":").substringBefore("=").trim()
                parseQualifiedName(head)
            }
            "class", "interface", "object", "typealias" -> {
                Regex("^(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)")
                    .find(remainder)?.groupValues?.get(1)?.let { unwrapIdentifier(it) }
            }
            else -> null
        }
    }

    private fun parseQualifiedName(raw: String): String? {
        if (raw.isBlank()) return null
        val noGenerics = stripGenericArgs(raw)
        val compact = removeWhitespaceOutsideBackticks(noGenerics).replace("?", "")
        if (compact.isBlank()) return null
        val regex = Regex("^(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)(\\.`[^`]+`|\\.[A-Za-z_][A-Za-z0-9_]*)*$")
        val match = regex.find(compact) ?: return null
        return match.value.split(".").joinToString(".") { unwrapIdentifier(it) }
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

    private fun findDefinition(
        symbol: String,
        sourceName: String,
        sourcePath: String,
        sourceLine: Int
    ): KotlinDefinitionRecord? {
        if (sourcePath.startsWith("jar:") && sourceLine > 0) {
            return KotlinDefinitionRecord(
                name = sourceName.ifBlank { symbol },
                kind = "fun",
                filePath = sourcePath,
                line = sourceLine,
                fileId = null,
                contentHash = "",
                kdoc = null
            )
        }

        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
        val definitions = index?.definitions.orEmpty()
        if (sourcePath.isNotBlank() && sourceLine > 0) {
            definitions.firstOrNull {
                it.filePath == sourcePath &&
                    it.line == sourceLine &&
                    (
                        sourceName.isBlank() ||
                            it.name == sourceName ||
                            it.name.substringAfterLast('.') == sourceName.substringAfterLast('.')
                        )
            }?.let { return it }
        }
        if (sourcePath.isNotBlank() && sourceName.isNotBlank()) {
            definitions.firstOrNull {
                it.filePath == sourcePath && it.name == sourceName
            }?.let { return it }
        }
        if (sourceName.isNotBlank()) {
            definitions.firstOrNull { it.name == sourceName }?.let { return it }
        }
        if (symbol.isBlank()) return null
        definitions.firstOrNull { it.name == symbol }?.let { return it }
        definitions.firstOrNull { it.name.equals(symbol, ignoreCase = true) }?.let { return it }
        val dotIdx = symbol.lastIndexOf('.')
        if (dotIdx > 0 && dotIdx < symbol.length - 1) {
            val short = symbol.substring(dotIdx + 1)
            definitions.firstOrNull { it.name == short }?.let { return it }
            return definitions.firstOrNull { it.name.equals(short, ignoreCase = true) }
        }
        return null
    }

    private fun loadDefinitionSnippet(def: KotlinDefinitionRecord): DefinitionSnippet? {
        if (def.filePath.startsWith("jar:")) {
            return loadJarDefinitionSnippet(def)
        }

        val path = Paths.get(def.filePath)
        if (!Files.exists(path)) return null
        val text = runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull() ?: return null
        val lineOffsets = mutableListOf(0)
        text.forEachIndexed { index, ch ->
            if (ch == '\n' && index + 1 < text.length) lineOffsets += index + 1
        }
        val start = lineOffsets.getOrNull((def.line - 1).coerceAtLeast(0)) ?: return null
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
        val nextLine = index?.definitions
            .orEmpty()
            .asSequence()
            .filter { it.filePath == def.filePath && it.line > def.line }
            .minOfOrNull { it.line }
        val end = nextLine
            ?.let { lineOffsets.getOrNull((it - 1).coerceAtLeast(0)) }
            ?: text.length
        val source = text.substring(start, end).trimEnd()
        if (source.isBlank()) return null
        return DefinitionSnippet(source = source, sourceHash = Sha256.hexUtf8(source))
    }

    /** The jar behind a `jar:file:...!/entry` source path, or null if that is not what this is. */
    private fun jarPathOf(filePath: String): Path? {
        val split = filePath.indexOf(JAR_ENTRY_MARKER)
        if (!filePath.startsWith("jar:") || split == -1) return null
        val jarUri = filePath.substring("jar:".length, split)
        return runCatching { Paths.get(java.net.URI.create(jarUri)) }.getOrNull()
    }

    private fun loadJarDefinitionSnippet(def: KotlinDefinitionRecord): DefinitionSnippet? {
        val split = def.filePath.indexOf(JAR_ENTRY_MARKER)
        if (split == -1) return null
        val entryName = def.filePath.substring(split + JAR_ENTRY_MARKER.length)
        val jarPath = jarPathOf(def.filePath) ?: return null
        if (!Files.exists(jarPath)) return null
        val text = runCatching {
            ZipFile(jarPath.toFile()).use { zip ->
                val entry = zip.getEntry(entryName) ?: return null
                zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
        }.getOrNull() ?: return null

        val lineOffsets = mutableListOf(0)
        text.forEachIndexed { index, ch ->
            if (ch == '\n' && index + 1 < text.length) lineOffsets += index + 1
        }
        val start = lineOffsets.getOrNull((def.line - 1).coerceAtLeast(0)) ?: return null
        val end = sourceSnippetEnd(text, start)
        val source = text.substring(start, end).trimEnd()
        if (source.isBlank()) return null
        return DefinitionSnippet(source = source, sourceHash = Sha256.hexUtf8(source))
    }

    private fun sourceSnippetEnd(text: String, start: Int): Int {
        val firstBrace = text.indexOf('{', start)
        val firstSemicolon = text.indexOf(';', start).takeIf { it >= 0 } ?: Int.MAX_VALUE
        if (firstBrace == -1 || firstSemicolon < firstBrace) {
            return (if (firstSemicolon == Int.MAX_VALUE) text.indexOf('\n', start) else firstSemicolon + 1)
                .takeIf { it > start }
                ?: text.length
        }

        var depth = 0
        var inString = false
        var stringChar = '"'
        var i = firstBrace
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (ch == '\\' && i + 1 < text.length) {
                    i += 2
                    continue
                }
                if (ch == stringChar) inString = false
                i++
                continue
            }
            if (ch == '"' || ch == '\'') {
                inString = true
                stringChar = ch
                i++
                continue
            }
            when (ch) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth <= 0) return (i + 1).coerceAtMost(text.length)
                }
            }
            i++
        }
        return text.length
    }

    private fun snippetAroundCursor(text: String, line: Int, column: Int): String {
        if (text.isBlank()) return ""
        val lines = text.split("\n")
        val center = (line - 1).coerceIn(0, lines.lastIndex)
        val start = (center - 12).coerceAtLeast(0)
        val end = (center + 12).coerceAtMost(lines.lastIndex)
        val snippet = lines.subList(start, end + 1).toMutableList()
        val lineIdx = center - start
        if (lineIdx in snippet.indices) {
            val raw = snippet[lineIdx]
            val col = (column - 1).coerceIn(0, raw.length)
            snippet[lineIdx] = raw.substring(0, col) + "<cursor>" + raw.substring(col)
        }
        return snippet.joinToString("\n")
    }
}

/**
 * What the tree says now about a cached record's declaration.
 *
 * Three states rather than two because "I cannot tell" and "it is gone" must not act the same: the
 * first keeps the record, the second removes it.
 */
internal sealed interface DeclarationSource {
    /** The declaration is still there, and this is the hash of the source it now spans. */
    data class Found(val sourceHash: String) : DeclarationSource

    /** No declaration source at the recorded path and line: the file moved, shrank, or went away. */
    data object Gone : DeclarationSource

    /** The source is in a jar that is not on this machine right now. */
    data object Unverifiable : DeclarationSource
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class GeneratedCompletionDocRecord(
    val sourceName: String = "",
    val sourcePath: String = "",
    val sourceLine: Int = -1,
    val sourceHash: String = "",
    val documentation: String = "",
    val model: String = "",
    val createdAtEpochMs: Long = Instant.now().toEpochMilli()
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class GeneratedCompletionDocCacheDb(
    val version: Int = 1,
    val records: MutableMap<String, GeneratedCompletionDocRecord> = mutableMapOf()
)

/**
 * `internal` rather than private to the file so a sweep test can seed real records through [put]
 * instead of hand-writing the JSON — a test that spells the key itself would pass just as happily
 * against a broken [cacheKey].
 */
internal class GeneratedCompletionDocCache(
    private val dbPath: Path = FilestoreDir.resolve(GENERATED_COMPLETION_DOCS_FILE)
) {
    private val mapper = OpenAiFileStoreCache.defaultMapper()
    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var db = loadOrCreate()

    fun lookup(
        sourceName: String,
        sourcePath: String,
        sourceLine: Int,
        sourceHash: String
    ): GeneratedCompletionDocRecord? {
        val key = cacheKey(sourceName, sourcePath, sourceLine, sourceHash)
        return lock.read { db.records[key] }
    }

    fun put(
        sourceName: String,
        sourcePath: String,
        sourceLine: Int,
        sourceHash: String,
        documentation: String,
        model: String
    ) {
        val key = cacheKey(sourceName, sourcePath, sourceLine, sourceHash)
        val record = GeneratedCompletionDocRecord(
            sourceName = sourceName,
            sourcePath = sourcePath,
            sourceLine = sourceLine,
            sourceHash = sourceHash,
            documentation = documentation,
            model = model
        )
        lock.write {
            db.records[key] = record
            persistLocked()
        }
    }

    /**
     * A record's identity: the prompt it was written to, the declaration, and the source it saw.
     *
     * Takes the hash rather than either-or, because a caller that passed source text where a hash was
     * expected used to be rescued by a guess ("64 hex characters is probably already a hash") that no
     * amount of source text is safe from. Both call sites have the hash in hand — [DefinitionSnippet]
     * carries it — and so does [dropUnreachable], which is why the three agree on the key by
     * construction.
     */
    private fun cacheKey(
        sourceName: String,
        sourcePath: String,
        sourceLine: Int,
        sourceHash: String
    ): String =
        Sha256.hexUtf8("$COMPLETION_DOC_PROMPT_VERSION\n$sourceName\n$sourcePath\n$sourceLine\n$sourceHash")

    /**
     * Remove every record whose key cannot be recomputed from the tree as it stands, and persist if any
     * went. See [Ai4CompletionDoc.dropStaleGeneratedDocs] for why this is needed at all.
     *
     * Probing happens off-lock, against a snapshot, because it reads a file per record; the removal then
     * re-checks each doomed record against the live map so a doc generated meanwhile — the same key,
     * legitimately re-added — is not deleted on the way out.
     */
    fun dropUnreachable(
        probe: (GeneratedCompletionDocRecord) -> DeclarationSource
    ): Ai4CompletionDoc.StaleSweep {
        val snapshot = lock.read { db.records.toMap() }
        if (snapshot.isEmpty()) return Ai4CompletionDoc.StaleSweep()

        var unverifiable = 0
        val doomed = LinkedHashMap<String, String>()
        for ((key, record) in snapshot) {
            when (val source = probe(record)) {
                DeclarationSource.Unverifiable -> unverifiable++
                DeclarationSource.Gone -> doomed[key] = "declaration source is gone"
                is DeclarationSource.Found -> {
                    val current = cacheKey(record.sourceName, record.sourcePath, record.sourceLine, source.sourceHash)
                    if (current != key) {
                        doomed[key] = if (source.sourceHash == record.sourceHash) {
                            // Same source, different key: the only other ingredient is the prompt, so
                            // the draft was written to instructions this build no longer uses.
                            "prompt version changed"
                        } else {
                            "declaration source changed"
                        }
                    }
                }
            }
        }

        val removed = if (doomed.isEmpty()) {
            emptyList()
        } else {
            lock.write {
                val gone = doomed.keys.filter { db.records[it] == snapshot[it] }
                gone.forEach { db.records.remove(it) }
                if (gone.isNotEmpty()) persistLocked()
                gone
            }
        }

        return Ai4CompletionDoc.StaleSweep(
            considered = snapshot.size,
            kept = snapshot.size - removed.size,
            unverifiable = unverifiable,
            dropped = removed.map { key ->
                val record = snapshot.getValue(key)
                "${record.sourceName} (${record.sourcePath}:${record.sourceLine}) — ${doomed.getValue(key)}"
            }
        )
    }

    /** How many records are on disk right now — for a sweep test's before and after. */
    fun recordCount(): Int = lock.read { db.records.size }

    private fun loadOrCreate(): GeneratedCompletionDocCacheDb {
        if (!Files.exists(dbPath)) return GeneratedCompletionDocCacheDb()
        return runCatching {
            mapper.readValue(dbPath.toFile(), GeneratedCompletionDocCacheDb::class.java)
        }.getOrElse {
            val backup = dbPath.resolveSibling(dbPath.fileName.toString() + ".corrupt")
            runCatching { Files.copy(dbPath, backup, StandardCopyOption.REPLACE_EXISTING) }
            GeneratedCompletionDocCacheDb()
        }
    }

    private fun persistLocked() {
        Files.createDirectories(dbPath.parent)
        val tmp = dbPath.resolveSibling(dbPath.fileName.toString() + ".tmp")
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), db)
        runCatching {
            Files.move(tmp, dbPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(tmp, dbPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
