package afield.ai.core

import afield.RunsAt
import afield.ai.CanonicalBundle
import afield.ai.CanonicalDoc
import afield.ai.OpenAiKnowledgeStore
import afield.ai.Sha256
import afield.early
import afield.logger.lprintln
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

@JsonIgnoreProperties(ignoreUnknown = true)
data class GlslIncludeSymbolRecord(
    val name: String,
    val kind: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GlslIncludeFileRecord(
    val includePath: String,
    val localPath: String,
    val contentHash: String,
    val bytes: Long,
    val symbols: List<GlslIncludeSymbolRecord> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GlslIncludeLibraryRecord(
    val name: String,
    val includePrefix: String,
    val root: String,
    val source: String,
    val gitUrl: String? = null,
    val gitRef: String? = null,
    val revision: String? = null,
    val updatedAtEpochMs: Long,
    val files: List<GlslIncludeFileRecord> = emptyList(),
    val vectorStoreId: String? = null,
    val bundleVersion: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GlslIncludeIndexDb(
    val version: Int = 1,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val libraries: List<GlslIncludeLibraryRecord> = emptyList()
)

private data class GlslIncludeLibrarySpec(
    val name: String,
    val includePrefix: String,
    val root: Path,
    val source: String,
    val gitUrl: String? = null,
    val gitRef: String = "main",
    val managed: Boolean = false
)

/**
 * Indexes local or managed directories of GLSL include files for AI grounding.
 *
 * Lygia is just the default managed instance of this generic mechanism. Users can
 * add more include roots with AFIELD_GLSL_INCLUDE_DIRS.
 */
object GlslIncludeLibraryIndex {
    private const val LYGIA_URL = "https://github.com/patriciogonzalezvivo/lygia.git"

    private val mapper = OpenAiKnowledgeStore(
        Paths.get(System.getProperty("user.home"), ".afield", "filestore", "glsl_include_knowledge_store.json")
    )
    private val cacheDir: Path = Paths.get(System.getProperty("user.home"), ".afield", "filestore")
    private val vendorDir: Path = Paths.get(System.getProperty("user.home"), ".afield", "vendor")
    private val indexPath: Path = cacheDir.resolve("glsl_include_index.json")
    private val syncLock = Any()

    @Volatile
    var latestIndex: GlslIncludeIndexDb? = null
        private set

    fun loadIndex(): GlslIncludeIndexDb? {
        if (!Files.exists(indexPath)) return null
        return runCatching { OpenAiFileStoreCacheMapper.read(indexPath) }.getOrNull()
    }

    fun startBackgroundSync() {
        if (!enabled()) {
            lprintln("info,glsl.includes", "GLSL include indexing disabled")
            return
        }
        thread(isDaemon = true, name = "GlslIncludeLibraryIndex") {
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            synchronized(syncLock) {
                runSync()
            }
        }
    }

    fun currentVectorStoreIdsFor(property: String, prompt: String): List<String> {
        if (!shouldUseFor(property, prompt)) return emptyList()
        val index = latestIndex ?: loadIndex() ?: return emptyList()
        return index.libraries.mapNotNull { it.vectorStoreId }.distinct()
    }

    fun currentVectorStoreIdsForQuery(query: String): List<String> {
        if (!mentionsIncludes(query)) return emptyList()
        val index = latestIndex ?: loadIndex() ?: return emptyList()
        return index.libraries.mapNotNull { it.vectorStoreId }.distinct()
    }

    fun contextAttachmentFor(prompt: String, limit: Int = 24): ContextAttachment? {
        val manifest = manifestFor(prompt, limit)
        if (manifest.isBlank()) return null
        return ContextAttachment(
            fileName = "afield://glsl-includes/manifest",
            language = "Markdown",
            code = manifest
        )
    }

    fun manifestFor(prompt: String, limit: Int = 24): String {
        val index = latestIndex ?: loadIndex() ?: return ""
        if (index.libraries.isEmpty()) return ""
        val tokens = queryTokens(prompt)
        val ranked = index.libraries.flatMap { lib ->
            lib.files.map { file -> lib to file }
        }.map { (lib, file) ->
            Triple(scoreFile(tokens, file), lib, file)
        }.filter { it.first > 0 || tokens.isEmpty() }
            .sortedWith(
                compareByDescending<Triple<Int, GlslIncludeLibraryRecord, GlslIncludeFileRecord>> { it.first }
                    .thenBy { it.third.includePath }
            )
            .take(limit.coerceAtLeast(1))

        return buildString {
            appendLine("GLSL include libraries are available to AI assistance.")
            appendLine()
            appendLine("Rules:")
            appendLine("- Prefer targeted include directives over copying library code.")
            appendLine("- Use include paths exactly as shown, for example: `#include \"lygia/generative/fbm.glsl\"`.")
            appendLine("- If exact implementation details are needed, request the include path via AttachmentRequest.")
            appendLine()
            appendLine("Libraries:")
            index.libraries.forEach { lib ->
                val source = lib.gitUrl ?: lib.root
                appendLine("- ${lib.name}: include prefix `${lib.includePrefix}/`, files=${lib.files.size}, source=$source")
            }
            if (ranked.isNotEmpty()) {
                appendLine()
                appendLine("Relevant include files:")
                ranked.forEach { (_, _, file) ->
                    val symbols = file.symbols.take(8).joinToString(", ") { it.name }
                    if (symbols.isBlank()) {
                        appendLine("- `${file.includePath}`")
                    } else {
                        appendLine("- `${file.includePath}`: $symbols")
                    }
                }
            }
        }.trimEnd()
    }

    fun resolveAttachment(raw: String): AttachmentResponse? {
        val requested = normalizeIncludeRequest(raw) ?: return null
        val index = latestIndex ?: loadIndex() ?: return null
        for (lib in index.libraries) {
            val file = lib.files.firstOrNull { it.includePath == requested } ?: continue
            val path = Paths.get(file.localPath)
            if (!Files.exists(path) || !path.isRegularFile()) continue
            val content = runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull() ?: continue
            return AttachmentResponse(
                file = file.includePath,
                language = "GLSL",
                content = buildString {
                    appendLine("// GLSL include: ${file.includePath}")
                    append(content)
                }
            )
        }
        return null
    }

    private fun runSync() {
        val specs = configuredLibraries()
        if (specs.isEmpty()) {
            lprintln("info,glsl.includes", "no GLSL include libraries configured")
            return
        }

        val apiKey = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
        val records = mutableListOf<GlslIncludeLibraryRecord>()

        specs.forEach { spec ->
            val root = prepareRoot(spec) ?: return@forEach
            val revision = revisionFor(root)
            val files = collectIncludeFiles(root, spec.includePrefix)
            if (files.isEmpty()) {
                lprintln("warning,glsl.includes", "no GLSL include files found for ${spec.name} at $root")
                return@forEach
            }

            val bundleVersion = Sha256.hexUtf8(
                buildString {
                    appendLine(spec.name)
                    appendLine(spec.includePrefix)
                    appendLine(revision ?: "")
                    files.forEach { appendLine("${it.includePath}:${it.contentHash}") }
                }
            )

            val vectorStoreId = if (apiKey != null) {
                runCatching {
                    mapper.ensureCanonicalBundleReady(
                        apiKey = apiKey,
                        bundle = CanonicalBundle(
                            name = "glsl-includes-${spec.name}",
                            version = bundleVersion,
                            docs = canonicalDocsFor(spec, files)
                        ),
                        purposeForDocs = "assistants"
                    )
                }.onFailure {
                    lprintln("error,glsl.includes", "vector store sync failed for ${spec.name}: ${it.message}")
                }.getOrNull()
            } else {
                null
            }

            records += GlslIncludeLibraryRecord(
                name = spec.name,
                includePrefix = spec.includePrefix,
                root = root.toAbsolutePath().normalize().toString(),
                source = spec.source,
                gitUrl = spec.gitUrl,
                gitRef = spec.gitRef,
                revision = revision,
                updatedAtEpochMs = System.currentTimeMillis(),
                files = files,
                vectorStoreId = vectorStoreId,
                bundleVersion = bundleVersion
            )
        }

        val db = GlslIncludeIndexDb(
            updatedAtEpochMs = System.currentTimeMillis(),
            libraries = records
        )
        writeIndex(db)
        latestIndex = db
        lprintln(
            "info,glsl.includes",
            "indexed ${records.sumOf { it.files.size }} GLSL include files across ${records.size} libraries"
        )
    }

    private fun enabled(): Boolean =
        !System.getenv("AFIELD_GLSL_INCLUDE_INDEX").equals("off", ignoreCase = true)

    private fun shouldUseFor(property: String, prompt: String): Boolean =
        glslSpecForProperty(property) != null || mentionsIncludes(prompt)

    private fun mentionsIncludes(text: String): Boolean {
        val lower = text.lowercase()
        return listOf(
            "lygia", "#include", "glsl include", "shader include", "sdf", "fbm", "noise",
            "voronoi", "palette", "tonemap", "easing", "latticeboltzmann"
        ).any { it in lower }
    }

    private fun configuredLibraries(): List<GlslIncludeLibrarySpec> {
        val specs = mutableListOf<GlslIncludeLibrarySpec>()
        if (!System.getenv("AFIELD_GLSL_INCLUDE_DEFAULTS").equals("off", ignoreCase = true)) {
            val overridePath = System.getenv("AFIELD_LYGIA_PATH")?.takeIf { it.isNotBlank() }?.let(::expandPath)
            specs += GlslIncludeLibrarySpec(
                name = "lygia",
                includePrefix = "lygia",
                root = overridePath ?: vendorDir.resolve("lygia"),
                source = if (overridePath != null) "local" else "managed-git",
                gitUrl = if (overridePath == null) LYGIA_URL else null,
                gitRef = System.getenv("AFIELD_LYGIA_REF")?.takeIf { it.isNotBlank() } ?: "main",
                managed = overridePath == null
            )
        }

        parseLocalDirs().forEach { specs += it }
        parseManagedRepos().forEach { specs += it }

        return specs.distinctBy { it.name to it.root.toAbsolutePath().normalize().toString() }
    }

    private fun parseLocalDirs(): List<GlslIncludeLibrarySpec> {
        val raw = System.getenv("AFIELD_GLSL_INCLUDE_DIRS")?.takeIf { it.isNotBlank() } ?: return emptyList()
        return raw.split(File.pathSeparatorChar)
            .mapNotNull { entry ->
                val trimmed = entry.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                val parts = trimmed.split("=", limit = 2)
                val label = if (parts.size == 2) parts[0].trim() else ""
                val path = expandPath(if (parts.size == 2) parts[1].trim() else trimmed)
                val nameAndPrefix = label.takeIf { it.isNotBlank() } ?: path.fileName?.toString().orEmpty()
                val (name, prefix) = parseNamePrefix(nameAndPrefix, path.fileName?.toString().orEmpty())
                GlslIncludeLibrarySpec(
                    name = name,
                    includePrefix = prefix,
                    root = path,
                    source = "local",
                    managed = false
                )
            }
    }

    private fun parseManagedRepos(): List<GlslIncludeLibrarySpec> {
        val raw = System.getenv("AFIELD_GLSL_INCLUDE_REPOS")?.takeIf { it.isNotBlank() } ?: return emptyList()
        return raw.split(';')
            .mapNotNull { entry ->
                val trimmed = entry.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                val parts = trimmed.split("=", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val (name, prefix) = parseNamePrefix(parts[0].trim(), "glsl")
                val urlAndRef = parts[1].trim()
                val url = urlAndRef.substringBefore("#")
                val ref = urlAndRef.substringAfter("#", "main").ifBlank { "main" }
                GlslIncludeLibrarySpec(
                    name = name,
                    includePrefix = prefix,
                    root = vendorDir.resolve(name),
                    source = "managed-git",
                    gitUrl = url,
                    gitRef = ref,
                    managed = true
                )
            }
    }

    private fun parseNamePrefix(label: String, fallback: String): Pair<String, String> {
        val cleaned = label.trim().ifBlank { fallback }.ifBlank { "glsl" }
        val parts = cleaned.split(":", limit = 2)
        val name = sanitizeName(parts[0])
        val prefix = sanitizeIncludePrefix(parts.getOrElse(1) { parts[0] })
        return name to prefix
    }

    private fun prepareRoot(spec: GlslIncludeLibrarySpec): Path? {
        val root = spec.root.toAbsolutePath().normalize()
        if (!spec.managed) {
            return if (Files.exists(root) && root.isDirectory()) root else null
        }

        val gitUrl = spec.gitUrl ?: return null
        return try {
            Files.createDirectories(root.parent)
            if (!Files.exists(root.resolve(".git"))) {
                lprintln("info,glsl.includes", "cloning ${spec.name} from $gitUrl to $root")
                runGitCommand(null, "clone", "--branch", spec.gitRef, gitUrl, root.toString())
                touchRefreshStamp(root)
            } else if (shouldRefresh(root)) {
                lprintln("info,glsl.includes", "refreshing managed GLSL include repo ${spec.name}")
                runCatching { runGitCommand(root, "fetch", "origin") }
                runCatching { runGitCommand(root, "pull", "--ff-only") }
                touchRefreshStamp(root)
            }
            root
        } catch (t: Throwable) {
            lprintln("error,glsl.includes", "failed to prepare ${spec.name}: ${t.message}")
            if (Files.exists(root) && root.isDirectory()) root else null
        }
    }

    private fun shouldRefresh(root: Path): Boolean {
        val stamp = root.resolve(".afield-last-refresh")
        val last = runCatching { Files.getLastModifiedTime(stamp).toMillis() }.getOrDefault(0L)
        val intervalMs = (System.getenv("AFIELD_GLSL_INCLUDE_REFRESH_HOURS")?.toLongOrNull() ?: 168L) * 60L * 60L * 1000L
        return System.currentTimeMillis() - last > intervalMs
    }

    private fun touchRefreshStamp(root: Path) {
        runCatching {
            Files.writeString(root.resolve(".afield-last-refresh"), Instant.now().toString(), StandardCharsets.UTF_8)
        }
    }

    private fun revisionFor(root: Path): String? =
        runCatching {
            if (!Files.exists(root.resolve(".git"))) return@runCatching null
            runGitCommand(root, "rev-parse", "HEAD").trim().takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun runGitCommand(cwd: Path?, vararg args: String): String {
        val command = listOf("git") + args
        val process = ProcessBuilder(command)
            .apply {
                cwd?.let { directory(it.toFile()) }
                redirectErrorStream(true)
            }
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw IllegalStateException("git command timed out: ${command.joinToString(" ")}")
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException(
                "git command failed (${process.exitValue()}): ${command.joinToString(" ")}\n$output"
            )
        }
        return output
    }

    private fun collectIncludeFiles(root: Path, includePrefix: String): List<GlslIncludeFileRecord> {
        if (!Files.exists(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() }
                .filter { path ->
                    val rel = root.relativize(path).invariantSeparatorsPathString
                    !rel.startsWith(".git/") &&
                        (rel.endsWith(".glsl", ignoreCase = true) ||
                            rel.equals("README.md", ignoreCase = true) ||
                            rel.equals("README_GLSL.md", ignoreCase = true) ||
                            rel.equals("EXAMPLES.md", ignoreCase = true) ||
                            rel.equals("DESIGN.md", ignoreCase = true))
                }
                .map { path ->
                    val rel = root.relativize(path).invariantSeparatorsPathString
                    val includePath = if (rel.endsWith(".glsl", ignoreCase = true)) {
                        "$includePrefix/$rel"
                    } else {
                        "$includePrefix/$rel"
                    }
                    val bytes = runCatching { Files.readAllBytes(path) }.getOrDefault(ByteArray(0))
                    val text = runCatching { bytes.toString(StandardCharsets.UTF_8) }.getOrDefault("")
                    GlslIncludeFileRecord(
                        includePath = includePath,
                        localPath = path.toAbsolutePath().normalize().toString(),
                        contentHash = Sha256.hex(bytes),
                        bytes = bytes.size.toLong(),
                        symbols = if (rel.endsWith(".glsl", ignoreCase = true)) extractSymbols(text) else emptyList()
                    )
                }
                .sorted(Comparator.comparing<GlslIncludeFileRecord, String> { it.includePath })
                .toList()
        }
    }

    private fun canonicalDocsFor(
        spec: GlslIncludeLibrarySpec,
        files: List<GlslIncludeFileRecord>
    ): List<CanonicalDoc> {
        val docs = mutableListOf<CanonicalDoc>()
        val current = StringBuilder()
        var chunk = 1
        val maxChunkChars = 180_000

        fun flush() {
            if (current.isBlank()) return
            docs += CanonicalDoc(
                fileName = "glsl-includes__${spec.name}__chunk-${chunk.toString().padStart(3, '0')}.txt",
                content = current.toString().trimEnd(),
                contentType = "text/plain; charset=utf-8"
            )
            current.clear()
            chunk++
        }

        files.forEach { file ->
            val path = Paths.get(file.localPath)
            val content = runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull() ?: return@forEach
            val entry = buildString {
                appendLine("----- BEGIN GLSL INCLUDE -----")
                appendLine("GLSL include library: ${spec.name}")
                appendLine("Include path: ${file.includePath}")
                appendLine("Use in Field shader code as: #include \"${file.includePath}\"")
                if (file.symbols.isNotEmpty()) {
                    appendLine("Symbols: ${file.symbols.joinToString(", ") { it.name }}")
                }
                appendLine()
                appendLine(content.trimEnd())
                appendLine("----- END GLSL INCLUDE -----")
                appendLine()
            }
            if (current.isNotEmpty() && current.length + entry.length > maxChunkChars) flush()
            current.append(entry)
        }
        flush()
        return docs
    }

    private fun extractSymbols(text: String): List<GlslIncludeSymbolRecord> {
        val symbols = linkedMapOf<String, GlslIncludeSymbolRecord>()
        val functionRegex = Regex(
            """(?m)^\s*(?:[A-Za-z_][A-Za-z0-9_]*\s+)*[A-Za-z_][A-Za-z0-9_<>]*\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("""
        )
        functionRegex.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            if (name !in glslControlWords) symbols[name] = GlslIncludeSymbolRecord(name, "function")
        }
        val macroRegex = Regex("""(?m)^\s*#\s*define\s+([A-Za-z_][A-Za-z0-9_]*)""")
        macroRegex.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            symbols.putIfAbsent(name, GlslIncludeSymbolRecord(name, "macro"))
        }
        return symbols.values.take(40)
    }

    private fun queryTokens(raw: String): Set<String> =
        Regex("[A-Za-z_][A-Za-z0-9_]*")
            .findAll(raw)
            .map { it.value.lowercase() }
            .filter { it.length >= 3 && it !in queryNoise }
            .toSet()

    private fun scoreFile(tokens: Set<String>, file: GlslIncludeFileRecord): Int {
        if (tokens.isEmpty()) return 1
        val path = file.includePath.lowercase()
        val symbolNames = file.symbols.map { it.name.lowercase() }
        var score = 0
        tokens.forEach { token ->
            if (path.contains(token)) score += 18
            symbolNames.forEach { symbol ->
                if (symbol == token) score += 45
                else if (symbol.contains(token)) score += 25
            }
        }
        return score
    }

    private fun normalizeIncludeRequest(raw: String): String? {
        var s = raw.trim().trim('"', '\'', '`')
        s = s.removePrefix("#include").trim().trim('<', '>', '"', '\'', '`')
        if (s.startsWith("afield://glsl-includes/")) s = s.removePrefix("afield://glsl-includes/")
        if (!s.endsWith(".glsl", ignoreCase = true) && !s.endsWith(".md", ignoreCase = true)) return null
        return s.replace('\\', '/').removePrefix("/")
    }

    private fun writeIndex(db: GlslIncludeIndexDb) {
        runCatching {
            Files.createDirectories(indexPath.parent)
            val tmp = indexPath.resolveSibling(indexPath.fileName.toString() + ".tmp")
            Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { writer ->
                OpenAiFileStoreCacheMapper.mapper.writerWithDefaultPrettyPrinter().writeValue(writer, db)
            }
            Files.move(tmp, indexPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            lprintln("error,glsl.includes", "failed to write index: ${it.message}")
        }
    }

    private fun expandPath(raw: String): Path {
        val home = System.getProperty("user.home")
        return when {
            raw == "~" -> Paths.get(home)
            raw.startsWith("~/") || raw.startsWith("~\\") -> Paths.get(home).resolve(raw.drop(2))
            else -> Paths.get(raw)
        }.normalize()
    }

    private fun sanitizeName(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9_.-]+"), "-").trim('-').ifBlank { "glsl" }

    private fun sanitizeIncludePrefix(raw: String): String =
        raw.replace('\\', '/').trim('/').ifBlank { sanitizeName(raw) }

    private val glslControlWords = setOf(
        "if", "for", "while", "switch", "return", "main", "defined"
    )

    private val queryNoise = setOf(
        "glsl", "shader", "include", "includes", "field", "fragment", "vertex", "compute",
        "mesh", "task", "void", "float", "vec2", "vec3", "vec4", "mat3", "mat4"
    )
}

private object OpenAiFileStoreCacheMapper {
    val mapper = afield.ai.OpenAiFileStoreCache.defaultMapper()

    fun read(path: Path): GlslIncludeIndexDb =
        mapper.readValue(path.toFile(), GlslIncludeIndexDb::class.java)
}

@RunsAt(early, priority = 110)
fun syncGlslIncludeLibraryIndex() {
    GlslIncludeLibraryIndex.startBackgroundSync()
}
