@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
@file:Suppress("OPT_IN_USAGE")

package afield.tools.codeuse

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.resolveSymbol
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.sourcePsiSafe
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtConstructorCalleeExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.system.exitProcess

@Serializable
data class CodeUseSemanticAnalyzeRequest(
    val code: String,
    val sourceName: String = "AgentSnippet.kt",
    val limit: Int = 5,
    val callersPerSymbol: Int = 5,
    val sourceRadiusLines: Int = 24,
    val databasePath: String = "",
    val workspaceRoot: String = "",
    val classpathHash: String = "",
    val sourceRoots: List<String> = emptyList(),
    val classpathRoots: List<String> = emptyList(),
    val imports: List<String> = emptyList(),
    val implicitReceivers: List<String> = emptyList(),
    val timeoutMs: Long = 0L
)

@Serializable
data class CodeUseSourceReferenceRow(
    val symbolName: String,
    val kind: String,
    val sourceFile: String,
    val line: Int,
    val score: Int,
    val confidence: String,
    val evidence: String = ""
)

@Serializable
data class CodeUseRelatedSourceRow(
    val symbolName: String,
    val kind: String,
    val sourceFile: String,
    val line: Int,
    val score: Int,
    val confidence: String,
    val evidence: String,
    val sourceStartLine: Int,
    val sourceEndLine: Int,
    val sourceExcerpt: String,
    val useCount: Long,
    val uniqueSnippetCount: Long,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val peerCallers: List<String> = emptyList()
)

@Serializable
data class CodeUseSemanticUseRow(
    val symbolName: String,
    val callableId: String = "",
    val kind: String = "",
    val packageName: String = "",
    val sourceFile: String = "",
    val line: Int = 0,
    val useKind: String = "",
    val receiverType: String = "",
    val snippetLine: Int = 0,
    val snippetColumn: Int = 0,
    val confidence: String = "k2-resolved",
    val evidence: String = ""
)

@Serializable
data class CodeUseSemanticUnresolvedRow(
    val text: String,
    val line: Int = 0,
    val column: Int = 0,
    val reason: String = ""
)

@Serializable
data class CodeUseSemanticAnalyzeResult(
    val success: Boolean,
    val backend: String,
    val message: String,
    val sourceName: String = "",
    val sidecarUrl: String = "",
    val resolvedUses: List<CodeUseSemanticUseRow> = emptyList(),
    val unresolvedUses: List<CodeUseSemanticUnresolvedRow> = emptyList(),
    val references: List<CodeUseSourceReferenceRow> = emptyList(),
    val relatedSources: List<CodeUseRelatedSourceRow> = emptyList()
)

@Serializable
data class HealthResult(
    val ok: Boolean,
    val backend: String,
    val workspaceRoot: String
)

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = false
}

fun main(args: Array<String>) {
    val config = SidecarConfig.fromArgs(args)
    val analyzer = K2SidecarAnalyzer(config)
    val server = HttpServer.create(InetSocketAddress(config.host, config.port), 0)
    server.executor = Executors.newFixedThreadPool(config.threads) { task ->
        Thread(task, "code-use-semantic-sidecar").apply { isDaemon = true }
    }
    server.createContext("/health") { exchange ->
        exchange.respondJson(HealthResult(true, "k2-standalone", config.workspaceRoot.absolutePathString()))
    }
    server.createContext("/analyze") { exchange ->
        if (exchange.requestMethod.uppercase() != "POST") {
            exchange.respondText(405, "POST required")
            return@createContext
        }
        runCatching {
            val body = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            val request = json.decodeFromString<CodeUseSemanticAnalyzeRequest>(body)
            analyzer.analyze(request)
        }.fold(
            onSuccess = { exchange.respondJson(it) },
            onFailure = {
                exchange.respondJson(
                    CodeUseSemanticAnalyzeResult(
                        success = false,
                        backend = "k2-standalone",
                        message = "sidecar analysis failed: ${it.message ?: it.javaClass.name}"
                    ),
                    status = 200
                )
            }
        )
    }
    server.start()
    println("code-use semantic sidecar listening on http://${config.host}:${config.port}")
}

private data class SidecarConfig(
    val host: String,
    val port: Int,
    val workspaceRoot: Path,
    val scratchRoot: Path,
    val threads: Int
) {
    companion object {
        fun fromArgs(args: Array<String>): SidecarConfig {
            var host = "127.0.0.1"
            var port = 17841
            var workspace = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
            var scratch = workspace.resolve("build/code-use-semantic-sidecar").toAbsolutePath().normalize()
            var threads = 2
            var idx = 0
            while (idx < args.size) {
                when (args[idx]) {
                    "--host" -> host = args.valueAfter(idx++)
                    "--port" -> port = args.valueAfter(idx++).toInt()
                    "--workspace" -> workspace = Paths.get(args.valueAfter(idx++)).toAbsolutePath().normalize()
                    "--scratch" -> scratch = Paths.get(args.valueAfter(idx++)).toAbsolutePath().normalize()
                    "--threads" -> threads = args.valueAfter(idx++).toInt().coerceIn(1, 8)
                    "--help", "-h" -> {
                        println("Usage: --port 17841 --workspace /path/to/Field4 [--host 127.0.0.1] [--scratch path]")
                        exitProcess(0)
                    }
                    else -> error("Unknown argument '${args[idx]}'")
                }
                idx++
            }
            return SidecarConfig(host, port, workspace, scratch, threads)
        }

        private fun Array<String>.valueAfter(index: Int): String {
            if (index + 1 >= size) error("Missing value after ${this[index]}")
            return this[index + 1]
        }
    }
}

private class K2SidecarAnalyzer(private val config: SidecarConfig) {
    // A built standalone session indexes the whole workspace source tree (~300k lines) at build time,
    // which costs a couple of seconds. That work is identical for every request, so we build a session
    // once per (sourceRoots, classpathRoots) and keep it for the lifetime of the sidecar process. Each
    // request then analyzes its snippet as an in-memory dangling file against the pre-built module — the
    // workspace's FIR session is fetched from cache and reused, so per-request cost drops to milliseconds.
    private data class SessionKey(val sourceRoots: List<String>, val classpathRoots: List<String>)

    private class CachedSession(
        // Held only to keep the session (and its project/environment) alive for the process lifetime.
        val session: StandaloneAnalysisAPISession,
        // Any KtFile from the workspace module; used to contextualize dangling snippet files to it.
        val contextFile: KtFile,
    ) {
        // The low-level FIR caches are not designed for concurrent analysis of the same project, so
        // analysis over a shared session is serialized. Snippet turnaround is milliseconds, so this
        // does not meaningfully limit throughput at the sidecar's small thread count.
        val lock = ReentrantLock()
    }

    private val sessions = ConcurrentHashMap<SessionKey, CachedSession>()

    fun analyze(rawRequest: CodeUseSemanticAnalyzeRequest): CodeUseSemanticAnalyzeResult {
        val request = normalizeRequest(rawRequest)
        if (request.code.isBlank()) {
            return CodeUseSemanticAnalyzeResult(false, "k2-standalone", "No code was provided.", request.sourceName)
        }
        if (!request.hasSignalBearingCode()) {
            return CodeUseSemanticAnalyzeResult(
                success = true,
                backend = "k2-standalone",
                message = "No signal-bearing code was provided; import/package/preamble lines were treated as context.",
                sourceName = request.sourceName
            )
        }
        val sourceRoots = sourceRoots(request)
        val classpathRoots = classpathRoots(request)
        val cached = cachedSession(sourceRoots, classpathRoots)
            ?: return CodeUseSemanticAnalyzeResult(
                false,
                "k2-standalone",
                "No Kotlin source files were found under the configured source roots; cannot build an analysis session.",
                request.sourceName
            )
        return cached.lock.withLock {
            // Non-physical (eventSystemEnabled = false) dangling file: the platform lazily builds a
            // KaDanglingFileModule whose contextModule is the cached workspace module, and drops this
            // throwaway session on the next PSI change, so snippets never pollute the cached module.
            val snippetFile = KtPsiFactory
                .contextual(cached.contextFile, markGenerated = false, eventSystemEnabled = false)
                .createFile(safeSnippetName(request.sourceName), syntheticSource(request))
            analyzeKtFile(request, snippetFile, config.workspaceRoot)
        }
    }

    private fun cachedSession(sourceRoots: List<Path>, classpathRoots: List<Path>): CachedSession? {
        val key = SessionKey(
            sourceRoots = sourceRoots.map { it.toString() }.sorted(),
            classpathRoots = classpathRoots.map { it.toString() }.sorted()
        )
        sessions[key]?.let { return it }
        // Build under a lock so a burst of first requests doesn't build several redundant sessions.
        return synchronized(sessions) {
            sessions[key] ?: buildCachedSession(sourceRoots, classpathRoots)?.also { sessions[key] = it }
        }
    }

    private fun buildCachedSession(sourceRoots: List<Path>, classpathRoots: List<Path>): CachedSession? {
        // Never disposed: the session lives for the sidecar process. Disposer.newDisposable() is not
        // registered under any parent, so nothing disposes it out from under us.
        val disposable = Disposer.newDisposable("code-use-semantic-sidecar-session")
        val session = buildStandaloneAnalysisAPISession(disposable, unitTestMode = false) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform
                val sdk = buildKtSdkModule {
                    platform = JvmPlatforms.defaultJvmPlatform
                    libraryName = "jdk"
                    addBinaryRootsFromJdkHome(Paths.get(System.getProperty("java.home")), isJre = false)
                }
                val libraries = buildKtLibraryModule {
                    platform = JvmPlatforms.defaultJvmPlatform
                    libraryName = "field-classpath"
                    addRegularDependency(sdk)
                    addBinaryRoots(classpathRoots)
                }
                addModule(buildKtSourceModule {
                    platform = JvmPlatforms.defaultJvmPlatform
                    moduleName = "field-code-use"
                    addRegularDependency(sdk)
                    addRegularDependency(libraries)
                    addSourceRoots(sourceRoots)
                })
            }
        }
        val contextFile = session.modulesWithFiles.values
            .asSequence()
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull()
        if (contextFile == null) {
            Disposer.dispose(disposable)
            return null
        }
        return CachedSession(session, contextFile)
    }

    private fun safeSnippetName(sourceName: String): String =
        sourceName.replace(Regex("""[^A-Za-z0-9_.-]"""), "_").ifBlank { "AgentSnippet.kt" }

    private fun normalizeRequest(request: CodeUseSemanticAnalyzeRequest): CodeUseSemanticAnalyzeRequest =
        request.copy(
            code = request.code.take(80_000),
            sourceName = request.sourceName.ifBlank { "AgentSnippet.kt" },
            limit = request.limit.coerceIn(1, 50),
            callersPerSymbol = request.callersPerSymbol.coerceIn(0, 50),
            sourceRadiusLines = request.sourceRadiusLines.coerceIn(4, 120)
        )

    private fun syntheticSource(request: CodeUseSemanticAnalyzeRequest): String {
        val trimmed = request.code.trimStart()
        if (trimmed.startsWith("package ")) return request.code
        val imports = syntheticImports(request).joinToString("\n") { if (it.startsWith("import ")) it else "import $it" }
        return buildString {
            appendLine(imports)
            appendLine()
            appendLine("fun __fieldCodeUseSnippet() {")
            request.code.lineSequence().forEach { line ->
                append("    ")
                appendLine(line)
            }
            appendLine("}")
        }
    }

    private fun syntheticImports(request: CodeUseSemanticAnalyzeRequest): List<String> =
        (defaultImports + request.imports)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun syntheticLineOffset(request: CodeUseSemanticAnalyzeRequest): Int =
        if (request.code.trimStart().startsWith("package ")) 0 else syntheticImports(request).size + 2

    private fun sourceRoots(request: CodeUseSemanticAnalyzeRequest): List<Path> {
        val roots = request.sourceRoots.ifEmpty {
            listOf("src/jvmMain/kotlin", "src/commonMain/kotlin", "src/personal/kotlin")
                .map { config.workspaceRoot.resolve(it).absolutePathString() }
        }
        return roots.mapNotNull(::pathIfExists).distinct()
    }

    private fun classpathRoots(request: CodeUseSemanticAnalyzeRequest): List<Path> {
        val explicit = request.classpathRoots.mapNotNull(::pathIfExists)
        return explicit.distinct()
    }

    private fun pathIfExists(raw: String): Path? =
        runCatching {
            val path = Paths.get(raw)
            val resolved = if (path.isAbsolute) path else config.workspaceRoot.resolve(path)
            resolved.toAbsolutePath().normalize().takeIf { it.exists() }
        }.getOrNull()

    private fun analyzeKtFile(
        request: CodeUseSemanticAnalyzeRequest,
        ktFile: KtFile,
        workspaceRoot: Path
    ): CodeUseSemanticAnalyzeResult {
        val resolved = linkedMapOf<String, CodeUseSemanticUseRow>()
        val unresolved = mutableListOf<CodeUseSemanticUnresolvedRow>()
        val lineOffset = syntheticLineOffset(request)
        analyze(ktFile) {
            ktFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element.isImportOrPackageContext()) {
                        super.visitElement(element)
                        return
                    }
                    when (element) {
                        is KtCallElement -> {
                            val symbol = runCatching { element.resolveSymbol() }.getOrNull()
                            if (symbol != null) {
                                resolved.putUse(element, symbol, "call", workspaceRoot, lineOffset)
                                    ?.let { reason -> unresolved += element.unresolved(reason, lineOffset) }
                            } else {
                                unresolved += element.unresolved("call-unresolved", lineOffset)
                            }
                        }
                        is KtConstructorCalleeExpression -> {
                            val symbol = runCatching { element.resolveSymbol() }.getOrNull()
                            if (symbol != null) {
                                resolved.putUse(element, symbol, "constructor", workspaceRoot, lineOffset)
                                    ?.let { reason -> unresolved += element.unresolved(reason, lineOffset) }
                            }
                        }
                    }
                    super.visitElement(element)
                }
            })
        }
        val rows = resolved.values
            .filter { it.sourceFile.isNotBlank() }
            .sortedWith(compareBy<CodeUseSemanticUseRow> { it.sourceFile }.thenBy { it.line }.thenBy { it.snippetLine })
            .take(request.limit)
        val refs = rows.mapIndexed { index, row ->
            CodeUseSourceReferenceRow(
                symbolName = row.symbolName,
                kind = row.kind,
                sourceFile = row.sourceFile,
                line = row.line,
                score = 100 - index,
                confidence = row.confidence,
                evidence = row.evidence
            )
        }
        return CodeUseSemanticAnalyzeResult(
            success = true,
            backend = "k2-standalone",
            message = "K2 sidecar resolved ${resolved.size} use(s), ${unresolved.size} unresolved.",
            sourceName = request.sourceName,
            resolvedUses = rows,
            unresolvedUses = unresolved.take(50),
            references = refs
        )
    }

    private fun MutableMap<String, CodeUseSemanticUseRow>.putUse(
        element: PsiElement,
        symbol: KaSymbol,
        useKind: String,
        workspaceRoot: Path,
        lineOffset: Int
    ): String? {
        val row = symbol.toUseRow(element, useKind, workspaceRoot, lineOffset)
            ?: return "resolved-symbol-without-field-source:${symbol.symbolKind()}:${symbol.symbolName().ifBlank { symbol::class.qualifiedName.orEmpty() }}"
        this["${row.symbolName}|${row.sourceFile}|${row.line}|${row.snippetLine}|${row.snippetColumn}|$useKind"] = row
        return null
    }

    private fun KaSymbol.toUseRow(
        element: PsiElement,
        useKind: String,
        workspaceRoot: Path,
        lineOffset: Int
    ): CodeUseSemanticUseRow? {
        val psi = runCatching { sourcePsiSafe<PsiElement>() }.getOrNull()
            ?: return null
        val sourcePath = psi.containingFile?.virtualFile?.toPathOrNull()
            ?.toAbsolutePath()
            ?.normalize()
            ?: return null
        if (!sourcePath.startsWith(workspaceRoot)) return null
        val sourceLine = psi.lineAndColumn().first
        val fqName = symbolName()
        if (fqName.isBlank()) return null
        val (snippetLine, snippetColumn) = element.lineAndColumn(lineOffset)
        return CodeUseSemanticUseRow(
            symbolName = fqName,
            callableId = callableIdString(),
            kind = symbolKind(),
            packageName = fqName.substringBeforeLast('.', ""),
            sourceFile = workspaceRoot.relativize(sourcePath).toString().replace("\\", "/"),
            line = sourceLine,
            useKind = useKind,
            receiverType = "",
            snippetLine = snippetLine,
            snippetColumn = snippetColumn,
            confidence = "k2-resolved",
            evidence = element.text.take(120)
        )
    }

    private fun KaSymbol.symbolName(): String =
        callableIdString().takeIf { it.isNotBlank() }
            ?: when (this) {
                is KaConstructorSymbol -> containingClassId?.asSingleFqName()?.asString().orEmpty()
                is KaNamedClassSymbol -> classId?.asSingleFqName()?.asString().orEmpty()
                is KaNamedSymbol -> name.asString()
                else -> ""
            }

    private fun KaSymbol.callableIdString(): String =
        when (this) {
            is KaCallableSymbol -> runCatching { callableId?.asSingleFqName()?.asString().orEmpty() }.getOrDefault(callableId.toString())
            else -> ""
        }

    private fun KaSymbol.symbolKind(): String =
        when (this) {
            is KaConstructorSymbol -> "constructor"
            is KaNamedClassSymbol -> "class"
            is KaPropertySymbol -> if (isVal) "val" else "var"
            is KaCallableSymbol -> "fun"
            else -> this::class.simpleName ?: "symbol"
        }

    private fun PsiElement.unresolved(reason: String, lineOffset: Int): CodeUseSemanticUnresolvedRow {
        val (line, column) = lineAndColumn(lineOffset)
        return CodeUseSemanticUnresolvedRow(text = text.take(120), line = line, column = column, reason = reason)
    }

    private fun CodeUseSemanticAnalyzeRequest.hasSignalBearingCode(): Boolean =
        code.lineSequence().any { rawLine ->
            val trimmed = rawLine.substringBefore("//").trim()
            trimmed.isNotBlank() &&
                !trimmed.startsWith("package ") &&
                !trimmed.startsWith("import ") &&
                !trimmed.startsWith("@file:")
        }

    private fun PsiElement.isImportOrPackageContext(): Boolean {
        var current: PsiElement? = this
        while (current != null) {
            if (current is KtImportDirective || current is KtPackageDirective) return true
            current = current.parent
        }
        return false
    }

    private fun PsiElement.lineAndColumn(lineOffset: Int = 0): Pair<Int, Int> {
        val file = containingFile ?: return 0 to 0
        // Snippets are analyzed as non-physical dangling files, which have no PSI document, so fall back
        // to counting newlines in the raw file text — otherwise every snippet position collapses to 0:0.
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: file.viewProvider.document
        val (line0, column0) = if (document != null) {
            val offset = textOffset.coerceIn(0, document.textLength)
            val line = document.getLineNumber(offset)
            line to (offset - document.getLineStartOffset(line))
        } else {
            val text = file.text
            val offset = textOffset.coerceIn(0, text.length)
            val line = text.take(offset).count { it == '\n' }
            val lineStart = text.lastIndexOf('\n', offset - 1) + 1
            line to (offset - lineStart)
        }
        return (line0 + 1 - lineOffset).coerceAtLeast(1) to column0 + 1
    }

    private val defaultImports = listOf(
        "afield.*",
        "afield.stdlib.*",
        "afield.four.*",
        "afield.four.modules.*",
        "afield.four.util.*",
        "afield.graphics.*",
        "afield.stdlib.linalg.*",
        "kotlin.math.*"
    )
}

private fun com.intellij.openapi.vfs.VirtualFile.toPathOrNull(): Path? =
    runCatching { Paths.get(path).toAbsolutePath().normalize() }.getOrNull()

private inline fun <reified T> HttpExchange.respondJson(value: T, status: Int = 200) {
    val bytes = json.encodeToString(value).toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun HttpExchange.respondText(status: Int, text: String) {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
