package afield

import afield.ai.KotlinDefinitionRecord
import afield.ai.KotlinSourceIndex
import afield.logger.lprintln
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString

@Serializable
data class CodeUseSummary(
    val enabled: Boolean,
    val databasePath: String,
    val queueSize: Int,
    val droppedEvents: Long,
    val snippets: Long = 0,
    val compileEvents: Long = 0,
    val evalEvents: Long = 0,
    val launchEvents: Long = 0,
    val sourceBlobs: Long = 0,
    val message: String = ""
)

@Serializable
data class CodeUseSnippetRow(
    val id: String,
    val createdAtEpochMs: Long,
    val document: String,
    val boxUid: String,
    val boxName: String,
    val property: String,
    val mode: String,
    val strand: String,
    val sourceName: String,
    val sourceHash: String,
    val sourcePreview: String,
    val lastCompileSuccess: Boolean? = null,
    val lastEvalSuccess: Boolean? = null,
    val lastReturnType: String = "",
    val lastRuntimeClass: String = "",
    val lastCompileDiagnostics: String = "",
    val lastEvalDiagnostics: String = "",
    val lastException: String = ""
)

@Serializable
data class CodeUseLaunchRow(
    val id: Long,
    val createdAtEpochMs: Long,
    val snippetId: String,
    val document: String,
    val boxUid: String,
    val boxName: String,
    val property: String,
    val mode: String,
    val strand: String,
    val lifecycle: String,
    val watermark: Int,
    val returnType: String = "",
    val exceptionJson: String = ""
)

@Serializable
data class CodeUseFailureRow(
    val id: String,
    val eventId: Long,
    val createdAtEpochMs: Long,
    val kind: String,
    val snippetId: String,
    val document: String,
    val boxUid: String,
    val boxName: String,
    val property: String,
    val mode: String,
    val strand: String,
    val sourcePreview: String,
    val diagnosticsJson: String = "",
    val exceptionJson: String = "",
    val lifecycle: String = "",
    val returnType: String = ""
)

@Serializable
data class CodeUseUnresolvedRow(
    val id: Long,
    val createdAtEpochMs: Long,
    val snippetId: String,
    val text: String,
    val line: Int,
    val column: Int,
    val reason: String
)

@Serializable
data class CodeUseSymbolRow(
    val id: Long,
    val fqName: String,
    val callableId: String,
    val kind: String,
    val packageName: String,
    val sourceFile: String,
    val line: Int,
    val useCount: Long,
    val uniqueSnippetCount: Long
)

@Serializable
data class CodeUseSourceTarget(
    val symbolName: String,
    val kind: String,
    val sourceFile: String,
    val line: Int,
    val confidence: String
)

@Serializable
data class CodeUseSnippetUseRow(
    val snippetId: String,
    val createdAtEpochMs: Long,
    val document: String,
    val boxUid: String,
    val boxName: String,
    val property: String,
    val mode: String,
    val strand: String,
    val sourcePreview: String,
    val lastCompileSuccess: Boolean? = null,
    val lastEvalSuccess: Boolean? = null,
    val lastReturnType: String = "",
    val lastRuntimeClass: String = "",
    val recentLaunches: String = "",
    val confidence: String = "textual",
    val evidence: String = ""
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
data class CodeUseHotSourceSymbolRow(
    val symbolName: String,
    val kind: String,
    val sourceFile: String,
    val line: Int,
    val useCount: Long,
    val uniqueSnippetCount: Long,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val confidence: String
)

@Serializable
data class CodeUseEarlySourceSymbolRow(
    val document: String,
    val symbolName: String,
    val kind: String,
    val sourceFile: String,
    val line: Int,
    val earlyUseCount: Long,
    val laterUseCount: Long,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val confidence: String
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
    val peerCallers: List<CodeUseSnippetUseRow> = emptyList()
)

object CodeUseTelemetryService {
    private const val QUEUE_CAPACITY = 4096
    private const val MAX_SOURCE_CHARS = 1_000_000
    private val json = Json { encodeDefaults = true }
    private val queue = ArrayBlockingQueue<CodeUseEvent>(QUEUE_CAPACITY)
    private val droppedEvents = AtomicLong(0)
    private val started = AtomicLong(0)
    private val disabledMessage = AtomicLong(0)
    private val schemaLock = Any()

    @Volatile
    private var enabled = true

    @Volatile
    private var schemaReady = false

    @Volatile
    private var fallbackDefinitionsCache: Pair<Long, List<KotlinDefinitionRecord>>? = null

    private val databasePath: Path = Paths.get(
        System.getProperty("afield.codeUse.path")
            ?: System.getenv("AFIELD_CODE_USE_PATH")
            ?: Paths.get(System.getProperty("user.home"), ".afield", "code-use", "field-code-use.sqlite").toString()
    ).toAbsolutePath().normalize()

    fun newSnippetKey(): String = UUID.randomUUID().toString()

    fun captureBoxContext(box: Box?, property: String = "", mode: String = "", strand: String = ""): CodeUseContext {
        val info = runCatching { box?.takeIf { it.isInsideInitialized() }?.inside }.getOrNull()
        return CodeUseContext(
            document = info?.documentName ?: "",
            documentPath = info?.filename ?: "",
            boxUid = box?.id ?: "",
            boxName = runCatching { box?.name.orEmpty() }.getOrDefault(""),
            property = property,
            mode = mode,
            strand = strand,
            parentIds = runCatching { box?.parents?.map { it.id }?.sorted().orEmpty() }.getOrDefault(emptyList()),
            childIds = runCatching { box?.children?.map { it.id }?.sorted().orEmpty() }.getOrDefault(emptyList()),
            preambleHash = runCatching { box?.preamble?.takeIf { it.isNotBlank() }?.let(::sha256).orEmpty() }.getOrDefault("")
        )
    }

    fun recordCompileResult(
        snippetId: String,
        source: String,
        sourceName: String,
        context: CodeUseContext,
        success: Boolean,
        diagnostics: List<String>,
        elapsedMs: Double,
        classpathHash: String = ""
    ) {
        offer(
            CodeUseEvent.CompileResult(
                snippetId = snippetId,
                source = source.take(MAX_SOURCE_CHARS),
                sourceName = sourceName,
                context = context,
                success = success,
                diagnostics = diagnostics.take(80),
                elapsedMs = elapsedMs,
                classpathHash = classpathHash,
                createdAtEpochMs = now()
            )
        )
    }

    fun recordEvaluationResult(
        snippetId: String,
        context: CodeUseContext,
        success: Boolean,
        runtimeClass: String = "",
        returnType: String = "",
        exception: Throwable? = null,
        diagnostics: List<String> = emptyList(),
        elapsedMs: Double
    ) {
        offer(
            CodeUseEvent.EvaluationResult(
                snippetId = snippetId,
                context = context,
                success = success,
                runtimeClass = runtimeClass,
                returnType = returnType,
                exceptionJson = exception?.let { exceptionSummary(it) } ?: "",
                diagnostics = diagnostics.take(80),
                elapsedMs = elapsedMs,
                createdAtEpochMs = now()
            )
        )
    }

    fun recordLaunchLifecycle(
        snippetId: String,
        source: String,
        context: CodeUseContext,
        lifecycle: String,
        watermark: Int = -1,
        returnType: String = "",
        exception: Throwable? = null
    ) {
        offer(
            CodeUseEvent.LaunchLifecycle(
                snippetId = snippetId,
                source = source.take(MAX_SOURCE_CHARS),
                context = context,
                lifecycle = lifecycle,
                watermark = watermark,
                returnType = returnType,
                exceptionJson = exception?.let { exceptionSummary(it) } ?: "",
                createdAtEpochMs = now()
            )
        )
    }

    fun summary(): CodeUseSummary {
        start()
        if (!enabled) {
            return CodeUseSummary(
                enabled = false,
                databasePath = databasePath.absolutePathString(),
                queueSize = queue.size,
                droppedEvents = droppedEvents.get(),
                message = "Code-use telemetry is disabled; SQLite initialization failed."
            )
        }
        return runCatching {
            withConnection { conn ->
                CodeUseSummary(
                    enabled = true,
                    databasePath = databasePath.absolutePathString(),
                    queueSize = queue.size,
                    droppedEvents = droppedEvents.get(),
                    snippets = count(conn, "snippets"),
                    compileEvents = count(conn, "compile_events"),
                    evalEvents = count(conn, "eval_events"),
                    launchEvents = count(conn, "launch_events"),
                    sourceBlobs = count(conn, "source_blobs")
                )
            }
        }.getOrElse {
            CodeUseSummary(
                enabled = false,
                databasePath = databasePath.absolutePathString(),
                queueSize = queue.size,
                droppedEvents = droppedEvents.get(),
                message = it.message ?: it.javaClass.name
            )
        }
    }

    fun recentSnippets(limit: Int = 100): List<CodeUseSnippetRow> =
        queryRows(limit.coerceIn(1, 500)) { conn, safeLimit ->
            conn.prepareStatement(
                """
                SELECT s.id, s.created_at, s.document, s.box_uid, s.box_name, s.property, s.mode, s.strand,
                       s.source_name, s.source_hash, substr(b.text, 1, 260) AS source_preview,
                       ce.success AS compile_success,
                       ce.diagnostics_json AS compile_diagnostics,
                       ee.success AS eval_success,
                       ee.return_type,
                       ee.runtime_class,
                       ee.diagnostics_json AS eval_diagnostics,
                       ee.exception_json
                FROM snippets s
                LEFT JOIN source_blobs b ON b.hash = s.source_hash
                LEFT JOIN compile_events ce ON ce.id = (
                    SELECT id FROM compile_events WHERE snippet_id = s.id ORDER BY created_at DESC, id DESC LIMIT 1
                )
                LEFT JOIN eval_events ee ON ee.id = (
                    SELECT id FROM eval_events WHERE snippet_id = s.id ORDER BY created_at DESC, id DESC LIMIT 1
                )
                ORDER BY s.created_at DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, safeLimit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                CodeUseSnippetRow(
                                    id = rs.getString("id"),
                                    createdAtEpochMs = rs.getLong("created_at"),
                                    document = rs.getString("document").orEmpty(),
                                    boxUid = rs.getString("box_uid").orEmpty(),
                                    boxName = rs.getString("box_name").orEmpty(),
                                    property = rs.getString("property").orEmpty(),
                                    mode = rs.getString("mode").orEmpty(),
                                    strand = rs.getString("strand").orEmpty(),
                                    sourceName = rs.getString("source_name").orEmpty(),
                                    sourceHash = rs.getString("source_hash").orEmpty(),
                                    sourcePreview = rs.getString("source_preview").orEmpty(),
                                    lastCompileSuccess = rs.nullableBoolean("compile_success"),
                                    lastEvalSuccess = rs.nullableBoolean("eval_success"),
                                    lastReturnType = rs.getString("return_type").orEmpty(),
                                    lastRuntimeClass = rs.getString("runtime_class").orEmpty(),
                                    lastCompileDiagnostics = rs.getString("compile_diagnostics").orEmpty(),
                                    lastEvalDiagnostics = rs.getString("eval_diagnostics").orEmpty(),
                                    lastException = rs.getString("exception_json").orEmpty()
                                )
                            )
                        }
                    }
                }
            }
        }

    fun recentLaunches(limit: Int = 100): List<CodeUseLaunchRow> =
        queryRows(limit.coerceIn(1, 500)) { conn, safeLimit ->
            conn.prepareStatement(
                """
                SELECT l.id, l.created_at, l.snippet_id, s.document, s.box_uid, s.box_name, s.property,
                       s.mode, s.strand, l.lifecycle, l.watermark, l.return_type, l.exception_json
                FROM launch_events l
                LEFT JOIN snippets s ON s.id = l.snippet_id
                ORDER BY l.created_at DESC, l.id DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, safeLimit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                CodeUseLaunchRow(
                                    id = rs.getLong("id"),
                                    createdAtEpochMs = rs.getLong("created_at"),
                                    snippetId = rs.getString("snippet_id").orEmpty(),
                                    document = rs.getString("document").orEmpty(),
                                    boxUid = rs.getString("box_uid").orEmpty(),
                                    boxName = rs.getString("box_name").orEmpty(),
                                    property = rs.getString("property").orEmpty(),
                                    mode = rs.getString("mode").orEmpty(),
                                    strand = rs.getString("strand").orEmpty(),
                                    lifecycle = rs.getString("lifecycle").orEmpty(),
                                    watermark = rs.getInt("watermark"),
                                    returnType = rs.getString("return_type").orEmpty(),
                                    exceptionJson = rs.getString("exception_json").orEmpty()
                                )
                            )
                        }
                    }
                }
            }
        }

    fun failures(limit: Int = 100): List<CodeUseFailureRow> =
        queryRows(limit.coerceIn(1, 500)) { conn, safeLimit ->
            conn.prepareStatement(
                """
                SELECT * FROM (
                    SELECT 'compile' AS kind, ce.id AS event_id, ce.created_at, ce.snippet_id,
                           s.document, s.box_uid, s.box_name, s.property, s.mode, s.strand,
                           substr(b.text, 1, 360) AS source_preview,
                           ce.diagnostics_json AS diagnostics_json,
                           '' AS exception_json,
                           '' AS lifecycle,
                           '' AS return_type
                    FROM compile_events ce
                    LEFT JOIN snippets s ON s.id = ce.snippet_id
                    LEFT JOIN source_blobs b ON b.hash = s.source_hash
                    WHERE ce.success = 0

                    UNION ALL

                    SELECT 'eval' AS kind, ee.id AS event_id, ee.created_at, ee.snippet_id,
                           s.document, s.box_uid, s.box_name, s.property, s.mode, s.strand,
                           substr(b.text, 1, 360) AS source_preview,
                           ee.diagnostics_json AS diagnostics_json,
                           ee.exception_json AS exception_json,
                           '' AS lifecycle,
                           ee.return_type AS return_type
                    FROM eval_events ee
                    LEFT JOIN snippets s ON s.id = ee.snippet_id
                    LEFT JOIN source_blobs b ON b.hash = s.source_hash
                    WHERE ee.success = 0 OR COALESCE(ee.exception_json, '') <> ''

                    UNION ALL

                    SELECT 'lifecycle' AS kind, le.id AS event_id, le.created_at, le.snippet_id,
                           s.document, s.box_uid, s.box_name, s.property, s.mode, s.strand,
                           substr(b.text, 1, 360) AS source_preview,
                           '' AS diagnostics_json,
                           le.exception_json AS exception_json,
                           le.lifecycle AS lifecycle,
                           le.return_type AS return_type
                    FROM launch_events le
                    LEFT JOIN snippets s ON s.id = le.snippet_id
                    LEFT JOIN source_blobs b ON b.hash = s.source_hash
                    WHERE le.lifecycle LIKE '%error%'
                       OR le.lifecycle LIKE '%exception%'
                       OR COALESCE(le.exception_json, '') <> ''
                )
                ORDER BY created_at DESC, event_id DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, safeLimit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val kind = rs.getString("kind").orEmpty()
                            val eventId = rs.getLong("event_id")
                            add(
                                CodeUseFailureRow(
                                    id = "$kind:$eventId",
                                    eventId = eventId,
                                    createdAtEpochMs = rs.getLong("created_at"),
                                    kind = kind,
                                    snippetId = rs.getString("snippet_id").orEmpty(),
                                    document = rs.getString("document").orEmpty(),
                                    boxUid = rs.getString("box_uid").orEmpty(),
                                    boxName = rs.getString("box_name").orEmpty(),
                                    property = rs.getString("property").orEmpty(),
                                    mode = rs.getString("mode").orEmpty(),
                                    strand = rs.getString("strand").orEmpty(),
                                    sourcePreview = rs.getString("source_preview").orEmpty(),
                                    diagnosticsJson = rs.getString("diagnostics_json").orEmpty(),
                                    exceptionJson = rs.getString("exception_json").orEmpty(),
                                    lifecycle = rs.getString("lifecycle").orEmpty(),
                                    returnType = rs.getString("return_type").orEmpty()
                                )
                            )
                        }
                    }
                }
            }
        }

    fun unresolved(limit: Int = 100): List<CodeUseUnresolvedRow> =
        queryRows(limit.coerceIn(1, 500)) { conn, safeLimit ->
            conn.prepareStatement(
                """
                SELECT id, created_at, snippet_id, text, line, column, reason
                FROM unresolved_uses
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, safeLimit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                CodeUseUnresolvedRow(
                                    id = rs.getLong("id"),
                                    createdAtEpochMs = rs.getLong("created_at"),
                                    snippetId = rs.getString("snippet_id").orEmpty(),
                                    text = rs.getString("text").orEmpty(),
                                    line = rs.getInt("line"),
                                    column = rs.getInt("column"),
                                    reason = rs.getString("reason").orEmpty()
                                )
                            )
                        }
                    }
                }
            }
        }

    fun symbols(limit: Int = 100): List<CodeUseSymbolRow> =
        queryRows(limit.coerceIn(1, 500)) { conn, safeLimit ->
            conn.prepareStatement(
                """
                SELECT s.id, s.fq_name, s.callable_id, s.kind, s.package, s.source_file, s.line,
                       COUNT(su.id) AS use_count,
                       COUNT(DISTINCT su.snippet_id) AS unique_snippet_count
                FROM symbols s
                LEFT JOIN symbol_uses su ON su.symbol_id = s.id
                GROUP BY s.id
                ORDER BY use_count DESC, s.fq_name ASC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, safeLimit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                CodeUseSymbolRow(
                                    id = rs.getLong("id"),
                                    fqName = rs.getString("fq_name").orEmpty(),
                                    callableId = rs.getString("callable_id").orEmpty(),
                                    kind = rs.getString("kind").orEmpty(),
                                    packageName = rs.getString("package").orEmpty(),
                                    sourceFile = rs.getString("source_file").orEmpty(),
                                    line = rs.getInt("line"),
                                    useCount = rs.getLong("use_count"),
                                    uniqueSnippetCount = rs.getLong("unique_snippet_count")
                                )
                            )
                        }
                    }
                }
            }
        }

    fun callersForSourceLine(sourcePath: String, line: Int, limit: Int = 20): Pair<CodeUseSourceTarget?, List<CodeUseSnippetUseRow>> {
        val safeLine = line.coerceAtLeast(1)
        val target = sourceTargetForLine(sourcePath, safeLine)
        val names = target?.let { symbolSearchNames(it.symbolName) }.orEmpty()
        if (names.isEmpty()) return target to emptyList()

        val resolved = resolvedSnippetUsesForTarget(target!!, limit)
        if (resolved.isNotEmpty()) return target.copy(confidence = "resolved") to resolved

        return target to textualSnippetUses(names, limit = limit, sinceEpochMs = 0L)
    }

    fun referencesForCode(code: String, limit: Int = 5): List<CodeUseSourceReferenceRow> {
        val text = code.take(80_000)
        val tokens = extractCodeTokens(text)
        if (tokens.isEmpty()) return emptyList()
        val loweredText = text.lowercase()
        val definitions = sourceDefinitions()
        if (definitions.isEmpty()) return emptyList()

        return definitions.mapNotNull { def ->
            val simple = def.name.substringAfterLast('.')
            val lowerSimple = simple.lowercase()
            val lowerFull = def.name.lowercase()
            var score = 0
            val evidence = mutableListOf<String>()
            if (lowerFull.length > lowerSimple.length && lowerFull in loweredText) {
                score += 70
                evidence += "qualified-name"
            }
            if (lowerSimple in tokens) {
                score += 35
                evidence += "identifier"
            }
            if (Regex("""\b${Regex.escape(simple)}\s*\(""").containsMatchIn(text)) {
                score += 25
                evidence += "call-shape"
            }
            if (Regex("""\.\s*${Regex.escape(simple)}\b""").containsMatchIn(text)) {
                score += 15
                evidence += "member-access"
            }
            if (score <= 0) return@mapNotNull null
            CodeUseSourceReferenceRow(
                symbolName = def.name,
                kind = def.kind,
                sourceFile = def.filePath,
                line = def.line,
                score = score,
                confidence = "source-index-match",
                evidence = evidence.distinct().joinToString(",")
            )
        }
            .sortedWith(
                compareByDescending<CodeUseSourceReferenceRow> { it.score }
                    .thenBy { it.sourceFile }
                    .thenBy { it.line }
            )
            .take(limit.coerceIn(1, 50))
    }

    fun relatedSourceForCode(
        code: String,
        symbolLimit: Int = 5,
        callersPerSymbol: Int = 5,
        sourceRadiusLines: Int = 24
    ): List<CodeUseRelatedSourceRow> =
        referencesForCode(code, symbolLimit.coerceIn(1, 20)).map { ref ->
            val stats = sourceUsageStats(ref.symbolName)
            val excerpt = sourceExcerpt(ref.sourceFile, ref.line, sourceRadiusLines.coerceIn(4, 80))
            val (_, callers) = callersForSourceLine(ref.sourceFile, ref.line, callersPerSymbol.coerceIn(0, 20))
            CodeUseRelatedSourceRow(
                symbolName = ref.symbolName,
                kind = ref.kind,
                sourceFile = ref.sourceFile,
                line = ref.line,
                score = ref.score,
                confidence = ref.confidence,
                evidence = ref.evidence,
                sourceStartLine = excerpt.startLine,
                sourceEndLine = excerpt.endLine,
                sourceExcerpt = excerpt.text,
                useCount = stats.useCount,
                uniqueSnippetCount = stats.uniqueSnippetCount,
                firstSeenEpochMs = stats.firstSeenEpochMs,
                lastSeenEpochMs = stats.lastSeenEpochMs,
                peerCallers = callers
            )
        }

    fun hotSourceSymbols(window: String = "all", limit: Int = 20): List<CodeUseHotSourceSymbolRow> {
        val since = windowStartEpochMs(window)
        val resolved = resolvedHotSourceSymbols(since, limit)
        if (resolved.isNotEmpty()) return resolved
        return textualHotSourceSymbols(since, limit)
    }

    fun earlyDocumentSymbols(document: String = "", earlyWindowMs: Long = 10 * 60 * 1000L, limit: Int = 20): List<CodeUseEarlySourceSymbolRow> {
        val definitions = sourceDefinitions()
        if (definitions.isEmpty()) return emptyList()
        val defsBySimpleName = definitions.groupBy { it.name.substringAfterLast('.') }
        val rows = snippetTexts(sinceEpochMs = 0L, limit = 5000)
            .filter { row -> document.isBlank() || row.document.contains(document, ignoreCase = true) }
            .filter { it.document.isNotBlank() }
            .sortedBy { it.createdAtEpochMs }
        if (rows.isEmpty()) return emptyList()

        val documentStart = rows.groupBy { it.document }
            .mapValues { (_, docRows) -> docRows.minOf { it.createdAtEpochMs } }
        val stats = LinkedHashMap<String, EarlyStat>()
        rows.forEach { row ->
            val start = documentStart[row.document] ?: row.createdAtEpochMs
            val early = row.createdAtEpochMs <= start + earlyWindowMs.coerceAtLeast(1_000L)
            val tokens = extractCodeTokens(row.sourceText)
            tokens.forEach { token ->
                val defs = defsBySimpleName[token].orEmpty()
                if (defs.size > 8) return@forEach
                defs.forEach { def ->
                    val key = "${row.document}|${def.filePath}:${def.line}:${def.name}"
                    val stat = stats.getOrPut(key) { EarlyStat(row.document, def) }
                    if (early) stat.earlyUseCount++ else stat.laterUseCount++
                    if (stat.firstSeenEpochMs == 0L || row.createdAtEpochMs < stat.firstSeenEpochMs) stat.firstSeenEpochMs = row.createdAtEpochMs
                    if (row.createdAtEpochMs > stat.lastSeenEpochMs) stat.lastSeenEpochMs = row.createdAtEpochMs
                }
            }
        }

        return stats.values
            .filter { it.earlyUseCount > 0 && it.laterUseCount <= 1 }
            .sortedWith(
                compareByDescending<EarlyStat> { it.earlyUseCount - it.laterUseCount }
                    .thenBy { it.laterUseCount }
                    .thenBy { it.firstSeenEpochMs }
            )
            .take(limit.coerceIn(1, 50))
            .map {
                CodeUseEarlySourceSymbolRow(
                    document = it.document,
                    symbolName = it.definition.name,
                    kind = it.definition.kind,
                    sourceFile = it.definition.filePath,
                    line = it.definition.line,
                    earlyUseCount = it.earlyUseCount,
                    laterUseCount = it.laterUseCount,
                    firstSeenEpochMs = it.firstSeenEpochMs,
                    lastSeenEpochMs = it.lastSeenEpochMs,
                    confidence = "textual"
                )
            }
    }

    private data class SnippetTextRow(
        val id: String,
        val createdAtEpochMs: Long,
        val document: String,
        val boxUid: String,
        val boxName: String,
        val property: String,
        val mode: String,
        val strand: String,
        val sourceText: String,
        val lastCompileSuccess: Boolean?,
        val lastEvalSuccess: Boolean?,
        val lastReturnType: String,
        val lastRuntimeClass: String,
        val recentLaunches: String
    )

    private data class EarlyStat(
        val document: String,
        val definition: KotlinDefinitionRecord,
        var earlyUseCount: Long = 0,
        var laterUseCount: Long = 0,
        var firstSeenEpochMs: Long = 0,
        var lastSeenEpochMs: Long = 0
    )

    private data class SourceUsageStats(
        val useCount: Long = 0,
        val uniqueSnippetCount: Long = 0,
        val firstSeenEpochMs: Long = 0,
        val lastSeenEpochMs: Long = 0
    )

    private data class SourceExcerpt(
        val startLine: Int = 0,
        val endLine: Int = 0,
        val text: String = ""
    )

    private fun sourceDefinitions(): List<KotlinDefinitionRecord> =
        KotlinSourceIndex.latestIndex?.definitions
            ?: KotlinSourceIndex.loadIndex()?.definitions
            ?: scanSourceDefinitions()

    private fun scanSourceDefinitions(): List<KotlinDefinitionRecord> {
        val cached = fallbackDefinitionsCache
        if (cached != null && now() - cached.first < 60_000L) return cached.second
        val root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val sourceRoots = listOf(root.resolve("src/jvmMain/kotlin"), root.resolve("src/personal/kotlin"))
            .filter { Files.exists(it) }
        if (sourceRoots.isEmpty()) return emptyList()
        val out = mutableListOf<KotlinDefinitionRecord>()
        sourceRoots.forEach { sourceRoot ->
            runCatching {
                Files.walk(sourceRoot).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                        .forEach { path ->
                            val rel = root.relativize(path).toString().replace("\\", "/")
                            val lines = runCatching { Files.readAllLines(path, StandardCharsets.UTF_8) }
                                .getOrDefault(emptyList())
                            lines.forEachIndexed { index, line ->
                                val parsed = parseDeclarationLine(line) ?: return@forEachIndexed
                                out += KotlinDefinitionRecord(
                                    name = parsed.second,
                                    kind = parsed.first,
                                    filePath = rel,
                                    line = index + 1,
                                    fileId = null,
                                    contentHash = ""
                                )
                            }
                        }
                }
            }
        }
        val definitions = out.toList()
        fallbackDefinitionsCache = now() to definitions
        return definitions
    }

    private fun sourceTargetForLine(sourcePath: String, line: Int): CodeUseSourceTarget? {
        val normalized = normalizeSourcePath(sourcePath)
        val definitions = sourceDefinitions()
        definitions
            .filter { normalizeSourcePath(it.filePath) == normalized && it.line <= line }
            .maxByOrNull { it.line }
            ?.let {
                return CodeUseSourceTarget(
                    symbolName = it.name,
                    kind = it.kind,
                    sourceFile = it.filePath,
                    line = it.line,
                    confidence = "source-index-match"
                )
            }

        return inferSourceTargetFromFile(normalized, line)
    }

    private fun inferSourceTargetFromFile(sourcePath: String, line: Int): CodeUseSourceTarget? {
        val root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val path = runCatching {
            val direct = Paths.get(sourcePath)
            if (direct.isAbsolute) direct else root.resolve(sourcePath)
        }.getOrNull() ?: return null
        if (!Files.exists(path)) return null
        val lines = runCatching { Files.readAllLines(path, StandardCharsets.UTF_8) }.getOrDefault(emptyList())
        if (lines.isEmpty()) return null
        val start = (line - 1).coerceIn(0, lines.lastIndex)
        for (idx in start downTo 0) {
            val parsed = parseDeclarationLine(lines[idx]) ?: continue
            return CodeUseSourceTarget(
                symbolName = parsed.second,
                kind = parsed.first,
                sourceFile = normalizeSourcePath(sourcePath),
                line = idx + 1,
                confidence = "textual"
            )
        }
        return null
    }

    private fun parseDeclarationLine(rawLine: String): Pair<String, String>? {
        val line = rawLine.trimStart()
        if (line.startsWith("//") || line.startsWith("*")) return null
        val match = Regex("""\b(fun|val|var|class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)?)""")
            .find(line)
            ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun normalizeSourcePath(raw: String): String {
        val trimmed = raw.trim().removePrefix("./").replace("\\", "/")
        if (trimmed.isBlank()) return ""
        val root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return runCatching {
            val path = Paths.get(trimmed)
            if (path.isAbsolute) {
                root.relativize(path.toAbsolutePath().normalize()).toString().replace("\\", "/")
            } else {
                trimmed
            }
        }.getOrDefault(trimmed)
    }

    private fun symbolSearchNames(symbolName: String): List<String> {
        val clean = symbolName.trim().trim('`')
        if (clean.isBlank()) return emptyList()
        val simple = clean.substringAfterLast('.')
        return listOf(clean, simple).filter { it.isNotBlank() }.distinct()
    }

    private fun sourceUsageStats(symbolName: String): SourceUsageStats {
        val names = symbolSearchNames(symbolName)
        val primary = names.firstOrNull()?.takeIf { it.isNotBlank() } ?: return SourceUsageStats()
        val rows = snippetTextsContaining(primary, sinceEpochMs = 0L, limit = 5000)
            .filter { row ->
                names.any { name ->
                    containsIdentifier(row.sourceText, name.substringAfterLast('.')) || row.sourceText.contains(name)
                }
            }
        if (rows.isEmpty()) return SourceUsageStats()
        return SourceUsageStats(
            useCount = rows.size.toLong(),
            uniqueSnippetCount = rows.map { it.id }.distinct().size.toLong(),
            firstSeenEpochMs = rows.minOf { it.createdAtEpochMs },
            lastSeenEpochMs = rows.maxOf { it.createdAtEpochMs }
        )
    }

    private fun sourceExcerpt(sourcePath: String, line: Int, radiusLines: Int): SourceExcerpt {
        val normalized = normalizeSourcePath(sourcePath)
        val root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val path = runCatching {
            val raw = Paths.get(normalized)
            if (raw.isAbsolute) raw else root.resolve(raw)
        }.getOrNull() ?: return SourceExcerpt()
        val lines = runCatching { Files.readAllLines(path, StandardCharsets.UTF_8) }.getOrDefault(emptyList())
        if (lines.isEmpty()) return SourceExcerpt()
        val center = (line - 1).coerceIn(0, lines.lastIndex)
        val start = (center - radiusLines).coerceAtLeast(0)
        val end = (center + radiusLines).coerceAtMost(lines.lastIndex)
        val text = (start..end).joinToString("\n") { idx ->
            "${idx + 1}: ${lines[idx]}"
        }
        return SourceExcerpt(startLine = start + 1, endLine = end + 1, text = text)
    }

    private fun resolvedSnippetUsesForTarget(target: CodeUseSourceTarget, limit: Int): List<CodeUseSnippetUseRow> =
        queryRows(limit.coerceIn(1, 500)) { conn, safeLimit ->
            conn.prepareStatement(
                """
                SELECT su.snippet_id, su.created_at AS use_created_at, su.confidence AS use_confidence,
                       s.created_at, s.document, s.box_uid, s.box_name, s.property, s.mode, s.strand,
                       substr(b.text, 1, 320) AS source_preview,
                       ce.success AS compile_success,
                       ee.success AS eval_success,
                       ee.return_type,
                       ee.runtime_class,
                       (
                         SELECT group_concat(lifecycle || COALESCE(':' || NULLIF(return_type, ''), ''), ', ')
                         FROM (
                           SELECT lifecycle, return_type
                           FROM launch_events le
                           WHERE le.snippet_id = s.id
                           ORDER BY created_at DESC, id DESC
                           LIMIT 4
                         )
                       ) AS recent_launches
                FROM symbol_uses su
                JOIN symbols sym ON sym.id = su.symbol_id
                JOIN snippets s ON s.id = su.snippet_id
                LEFT JOIN source_blobs b ON b.hash = s.source_hash
                LEFT JOIN compile_events ce ON ce.id = (
                    SELECT id FROM compile_events WHERE snippet_id = s.id ORDER BY created_at DESC, id DESC LIMIT 1
                )
                LEFT JOIN eval_events ee ON ee.id = (
                    SELECT id FROM eval_events WHERE snippet_id = s.id ORDER BY created_at DESC, id DESC LIMIT 1
                )
                WHERE sym.source_file = ?
                  AND sym.line = ?
                ORDER BY su.created_at DESC, su.id DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, target.sourceFile)
                ps.setInt(2, target.line)
                ps.setInt(3, safeLimit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                CodeUseSnippetUseRow(
                                    snippetId = rs.getString("snippet_id").orEmpty(),
                                    createdAtEpochMs = rs.getLong("created_at"),
                                    document = rs.getString("document").orEmpty(),
                                    boxUid = rs.getString("box_uid").orEmpty(),
                                    boxName = rs.getString("box_name").orEmpty(),
                                    property = rs.getString("property").orEmpty(),
                                    mode = rs.getString("mode").orEmpty(),
                                    strand = rs.getString("strand").orEmpty(),
                                    sourcePreview = rs.getString("source_preview").orEmpty(),
                                    lastCompileSuccess = rs.nullableBoolean("compile_success"),
                                    lastEvalSuccess = rs.nullableBoolean("eval_success"),
                                    lastReturnType = rs.getString("return_type").orEmpty(),
                                    lastRuntimeClass = rs.getString("runtime_class").orEmpty(),
                                    recentLaunches = rs.getString("recent_launches").orEmpty(),
                                    confidence = rs.getString("use_confidence").orEmpty().ifBlank { "resolved" },
                                    evidence = "symbol_uses"
                                )
                            )
                        }
                    }
                }
            }
        }

    private fun textualSnippetUses(names: List<String>, limit: Int, sinceEpochMs: Long): List<CodeUseSnippetUseRow> {
        val primary = names.firstOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return snippetTextsContaining(primary, sinceEpochMs, (limit * 10).coerceIn(limit, 500))
            .asSequence()
            .mapNotNull { row ->
                val matched = names.firstOrNull { containsIdentifier(row.sourceText, it.substringAfterLast('.')) || row.sourceText.contains(it) }
                    ?: return@mapNotNull null
                CodeUseSnippetUseRow(
                    snippetId = row.id,
                    createdAtEpochMs = row.createdAtEpochMs,
                    document = row.document,
                    boxUid = row.boxUid,
                    boxName = row.boxName,
                    property = row.property,
                    mode = row.mode,
                    strand = row.strand,
                    sourcePreview = excerptAround(row.sourceText, matched),
                    lastCompileSuccess = row.lastCompileSuccess,
                    lastEvalSuccess = row.lastEvalSuccess,
                    lastReturnType = row.lastReturnType,
                    lastRuntimeClass = row.lastRuntimeClass,
                    recentLaunches = row.recentLaunches,
                    confidence = "textual",
                    evidence = matched
                )
            }
            .take(limit.coerceIn(1, 50))
            .toList()
    }

    private fun resolvedHotSourceSymbols(sinceEpochMs: Long, limit: Int): List<CodeUseHotSourceSymbolRow> =
        queryRows(limit.coerceIn(1, 500)) { conn, safeLimit ->
            val sinceClause = if (sinceEpochMs > 0L) "WHERE su.created_at >= ?" else ""
            conn.prepareStatement(
                """
                SELECT sym.fq_name, sym.kind, sym.source_file, sym.line,
                       COUNT(su.id) AS use_count,
                       COUNT(DISTINCT su.snippet_id) AS unique_snippet_count,
                       MIN(su.created_at) AS first_seen,
                       MAX(su.created_at) AS last_seen
                FROM symbol_uses su
                JOIN symbols sym ON sym.id = su.symbol_id
                $sinceClause
                GROUP BY sym.id
                ORDER BY use_count DESC, unique_snippet_count DESC, last_seen DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                var idx = 1
                if (sinceEpochMs > 0L) ps.setLong(idx++, sinceEpochMs)
                ps.setInt(idx, safeLimit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                CodeUseHotSourceSymbolRow(
                                    symbolName = rs.getString("fq_name").orEmpty(),
                                    kind = rs.getString("kind").orEmpty(),
                                    sourceFile = rs.getString("source_file").orEmpty(),
                                    line = rs.getInt("line"),
                                    useCount = rs.getLong("use_count"),
                                    uniqueSnippetCount = rs.getLong("unique_snippet_count"),
                                    firstSeenEpochMs = rs.getLong("first_seen"),
                                    lastSeenEpochMs = rs.getLong("last_seen"),
                                    confidence = "resolved"
                                )
                            )
                        }
                    }
                }
            }
        }

    private fun textualHotSourceSymbols(sinceEpochMs: Long, limit: Int): List<CodeUseHotSourceSymbolRow> {
        val definitions = sourceDefinitions()
        if (definitions.isEmpty()) return emptyList()
        val defsBySimpleName = definitions.groupBy { it.name.substringAfterLast('.') }
        val stats = LinkedHashMap<String, HotStat>()
        snippetTexts(sinceEpochMs = sinceEpochMs, limit = 5000).forEach { row ->
            val tokens = extractCodeTokens(row.sourceText)
            tokens.forEach { token ->
                val defs = defsBySimpleName[token].orEmpty()
                if (defs.size > 8) return@forEach
                defs.forEach { def ->
                    val key = "${def.filePath}:${def.line}:${def.name}"
                    val stat = stats.getOrPut(key) { HotStat(def) }
                    stat.useCount++
                    stat.snippetIds += row.id
                    if (stat.firstSeenEpochMs == 0L || row.createdAtEpochMs < stat.firstSeenEpochMs) stat.firstSeenEpochMs = row.createdAtEpochMs
                    if (row.createdAtEpochMs > stat.lastSeenEpochMs) stat.lastSeenEpochMs = row.createdAtEpochMs
                }
            }
        }
        return stats.values
            .sortedWith(
                compareByDescending<HotStat> { it.useCount }
                    .thenByDescending { it.snippetIds.size }
                    .thenByDescending { it.lastSeenEpochMs }
            )
            .take(limit.coerceIn(1, 50))
            .map {
                CodeUseHotSourceSymbolRow(
                    symbolName = it.definition.name,
                    kind = it.definition.kind,
                    sourceFile = it.definition.filePath,
                    line = it.definition.line,
                    useCount = it.useCount,
                    uniqueSnippetCount = it.snippetIds.size.toLong(),
                    firstSeenEpochMs = it.firstSeenEpochMs,
                    lastSeenEpochMs = it.lastSeenEpochMs,
                    confidence = "textual"
                )
            }
    }

    private data class HotStat(
        val definition: KotlinDefinitionRecord,
        var useCount: Long = 0,
        val snippetIds: MutableSet<String> = linkedSetOf(),
        var firstSeenEpochMs: Long = 0,
        var lastSeenEpochMs: Long = 0
    )

    private fun snippetTextsContaining(term: String, sinceEpochMs: Long, limit: Int): List<SnippetTextRow> =
        queryRows(limit.coerceIn(1, 5000)) { conn, safeLimit ->
            val sinceClause = if (sinceEpochMs > 0L) "AND s.created_at >= ?" else ""
            conn.prepareStatement(
                """
                SELECT s.id, s.created_at, s.document, s.box_uid, s.box_name, s.property, s.mode, s.strand,
                       b.text,
                       ce.success AS compile_success,
                       ee.success AS eval_success,
                       ee.return_type,
                       ee.runtime_class,
                       (
                         SELECT group_concat(lifecycle || COALESCE(':' || NULLIF(return_type, ''), ''), ', ')
                         FROM (
                           SELECT lifecycle, return_type
                           FROM launch_events le
                           WHERE le.snippet_id = s.id
                           ORDER BY created_at DESC, id DESC
                           LIMIT 4
                         )
                       ) AS recent_launches
                FROM snippets s
                JOIN source_blobs b ON b.hash = s.source_hash
                LEFT JOIN compile_events ce ON ce.id = (
                    SELECT id FROM compile_events WHERE snippet_id = s.id ORDER BY created_at DESC, id DESC LIMIT 1
                )
                LEFT JOIN eval_events ee ON ee.id = (
                    SELECT id FROM eval_events WHERE snippet_id = s.id ORDER BY created_at DESC, id DESC LIMIT 1
                )
                WHERE b.text LIKE ? ESCAPE '\'
                $sinceClause
                ORDER BY s.created_at DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                var idx = 1
                ps.setString(idx++, "%${escapeLike(term)}%")
                if (sinceEpochMs > 0L) ps.setLong(idx++, sinceEpochMs)
                ps.setInt(idx, safeLimit)
                ps.executeQuery().use { rs -> readSnippetTextRows(rs) }
            }
        }

    private fun snippetTexts(sinceEpochMs: Long, limit: Int): List<SnippetTextRow> =
        queryRows(limit.coerceIn(1, 5000)) { conn, safeLimit ->
            val sinceClause = if (sinceEpochMs > 0L) "WHERE s.created_at >= ?" else ""
            conn.prepareStatement(
                """
                SELECT s.id, s.created_at, s.document, s.box_uid, s.box_name, s.property, s.mode, s.strand,
                       b.text,
                       ce.success AS compile_success,
                       ee.success AS eval_success,
                       ee.return_type,
                       ee.runtime_class,
                       (
                         SELECT group_concat(lifecycle || COALESCE(':' || NULLIF(return_type, ''), ''), ', ')
                         FROM (
                           SELECT lifecycle, return_type
                           FROM launch_events le
                           WHERE le.snippet_id = s.id
                           ORDER BY created_at DESC, id DESC
                           LIMIT 4
                         )
                       ) AS recent_launches
                FROM snippets s
                JOIN source_blobs b ON b.hash = s.source_hash
                LEFT JOIN compile_events ce ON ce.id = (
                    SELECT id FROM compile_events WHERE snippet_id = s.id ORDER BY created_at DESC, id DESC LIMIT 1
                )
                LEFT JOIN eval_events ee ON ee.id = (
                    SELECT id FROM eval_events WHERE snippet_id = s.id ORDER BY created_at DESC, id DESC LIMIT 1
                )
                $sinceClause
                ORDER BY s.created_at DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                var idx = 1
                if (sinceEpochMs > 0L) ps.setLong(idx++, sinceEpochMs)
                ps.setInt(idx, safeLimit)
                ps.executeQuery().use { rs -> readSnippetTextRows(rs) }
            }
        }

    private fun readSnippetTextRows(rs: ResultSet): List<SnippetTextRow> =
        buildList {
            while (rs.next()) {
                add(
                    SnippetTextRow(
                        id = rs.getString("id").orEmpty(),
                        createdAtEpochMs = rs.getLong("created_at"),
                        document = rs.getString("document").orEmpty(),
                        boxUid = rs.getString("box_uid").orEmpty(),
                        boxName = rs.getString("box_name").orEmpty(),
                        property = rs.getString("property").orEmpty(),
                        mode = rs.getString("mode").orEmpty(),
                        strand = rs.getString("strand").orEmpty(),
                        sourceText = rs.getString("text").orEmpty(),
                        lastCompileSuccess = rs.nullableBoolean("compile_success"),
                        lastEvalSuccess = rs.nullableBoolean("eval_success"),
                        lastReturnType = rs.getString("return_type").orEmpty(),
                        lastRuntimeClass = rs.getString("runtime_class").orEmpty(),
                        recentLaunches = rs.getString("recent_launches").orEmpty()
                    )
                )
            }
        }

    private fun extractCodeTokens(code: String): Set<String> {
        val noise = setOf(
            "package", "import", "class", "interface", "object", "fun", "val", "var", "for", "while", "if", "else",
            "when", "return", "true", "false", "null", "this", "super", "is", "in", "as", "break", "continue",
            "try", "catch", "finally", "throw", "new", "by", "where", "do", "constructor", "init"
        )
        return Regex("""[A-Za-z_][A-Za-z0-9_]*""")
            .findAll(code)
            .map { it.value }
            .filter { it.length >= 2 && it !in noise }
            .toSet()
    }

    private fun containsIdentifier(text: String, name: String): Boolean {
        if (name.isBlank()) return false
        return Regex("""(^|[^A-Za-z0-9_])${Regex.escape(name)}([^A-Za-z0-9_]|$)""").containsMatchIn(text)
    }

    private fun excerptAround(text: String, term: String, radius: Int = 160): String {
        val idx = text.indexOf(term).takeIf { it >= 0 }
            ?: text.indexOf(term.substringAfterLast('.')).takeIf { it >= 0 }
            ?: 0
        val start = (idx - radius).coerceAtLeast(0)
        val end = (idx + term.length + radius).coerceAtMost(text.length)
        return text.substring(start, end).trim()
    }

    private fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun windowStartEpochMs(window: String): Long {
        val now = now()
        return when (window.trim().lowercase()) {
            "day", "today", "24h" -> now - 24L * 60L * 60L * 1000L
            "week", "this-week", "7d" -> now - 7L * 24L * 60L * 60L * 1000L
            "month", "30d" -> now - 30L * 24L * 60L * 60L * 1000L
            else -> 0L
        }
    }

    private fun offer(event: CodeUseEvent) {
        start()
        if (!enabled) return
        if (!queue.offer(event)) {
            droppedEvents.incrementAndGet()
        }
    }

    private fun start() {
        if (started.compareAndSet(0, 1)) {
            thread(name = "code-use-telemetry-writer", isDaemon = true) {
                writerLoop()
            }
        }
    }

    private fun writerLoop() {
        try {
            Class.forName("org.sqlite.JDBC")
            ensureSchema()
            openConnection().use { conn ->
                conn.autoCommit = false
                while (true) {
                    val first = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    writeEvent(conn, first)
                    var drained = 0
                    while (drained < 256) {
                        val next = queue.poll() ?: break
                        writeEvent(conn, next)
                        drained++
                    }
                    conn.commit()
                }
            }
        } catch (t: Throwable) {
            enabled = false
            if (disabledMessage.compareAndSet(0, 1)) {
                lprintln("warning,code-use", "code-use telemetry disabled: ${t.message ?: t.javaClass.name}")
            }
        }
    }

    private fun initializeSchema(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute("PRAGMA busy_timeout=5000")
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS source_blobs(
                  hash TEXT PRIMARY KEY,
                  text TEXT NOT NULL,
                  size INTEGER NOT NULL,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS snippets(
                  id TEXT PRIMARY KEY,
                  source_hash TEXT NOT NULL,
                  document TEXT,
                  document_path TEXT,
                  box_uid TEXT,
                  box_name TEXT,
                  property TEXT,
                  mode TEXT,
                  strand TEXT,
                  source_name TEXT,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS compile_events(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  snippet_id TEXT NOT NULL,
                  success INTEGER NOT NULL,
                  diagnostics_json TEXT,
                  elapsed_ms REAL,
                  classpath_hash TEXT,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS eval_events(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  snippet_id TEXT NOT NULL,
                  success INTEGER NOT NULL,
                  runtime_class TEXT,
                  return_type TEXT,
                  exception_json TEXT,
                  diagnostics_json TEXT,
                  elapsed_ms REAL,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS launch_events(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  snippet_id TEXT NOT NULL,
                  lifecycle TEXT NOT NULL,
                  watermark INTEGER,
                  return_type TEXT,
                  exception_json TEXT,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            runCatching { st.execute("ALTER TABLE launch_events ADD COLUMN exception_json TEXT") }
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS symbols(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  fq_name TEXT NOT NULL,
                  callable_id TEXT,
                  kind TEXT,
                  package TEXT,
                  source_file TEXT,
                  line INTEGER
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS symbol_uses(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  snippet_id TEXT NOT NULL,
                  symbol_id INTEGER NOT NULL,
                  use_kind TEXT,
                  receiver_type TEXT,
                  line INTEGER,
                  column INTEGER,
                  confidence TEXT,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS unresolved_uses(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  snippet_id TEXT NOT NULL,
                  text TEXT,
                  line INTEGER,
                  column INTEGER,
                  reason TEXT,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS contexts(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  snippet_id TEXT NOT NULL,
                  imports_json TEXT,
                  implicit_receivers_json TEXT,
                  parents_json TEXT,
                  children_json TEXT,
                  preamble_hash TEXT,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS classpath_entries(
                  hash TEXT,
                  path TEXT,
                  mtime INTEGER,
                  size INTEGER,
                  PRIMARY KEY(hash, path)
                )
                """.trimIndent()
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_snippets_created ON snippets(created_at DESC)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_compile_snippet ON compile_events(snippet_id, created_at DESC)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_eval_snippet ON eval_events(snippet_id, created_at DESC)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_launch_snippet ON launch_events(snippet_id, created_at DESC)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_symbol_uses_symbol ON symbol_uses(symbol_id, snippet_id)")
        }
    }

    private fun writeEvent(conn: Connection, event: CodeUseEvent) {
        when (event) {
            is CodeUseEvent.CompileResult -> {
                upsertSnippet(conn, event.snippetId, event.source, event.sourceName, event.context, event.createdAtEpochMs)
                writeContext(conn, event.snippetId, event.context, event.createdAtEpochMs)
                conn.prepareStatement(
                    """
                    INSERT INTO compile_events(snippet_id, success, diagnostics_json, elapsed_ms, classpath_hash, created_at)
                    VALUES(?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, event.snippetId)
                    ps.setInt(2, if (event.success) 1 else 0)
                    ps.setString(3, json.encodeToString(event.diagnostics))
                    ps.setDouble(4, event.elapsedMs)
                    ps.setString(5, event.classpathHash)
                    ps.setLong(6, event.createdAtEpochMs)
                    ps.executeUpdate()
                }
            }

            is CodeUseEvent.EvaluationResult -> {
                touchSnippetContext(conn, event.snippetId, event.context, event.createdAtEpochMs)
                conn.prepareStatement(
                    """
                    INSERT INTO eval_events(snippet_id, success, runtime_class, return_type, exception_json, diagnostics_json, elapsed_ms, created_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, event.snippetId)
                    ps.setInt(2, if (event.success) 1 else 0)
                    ps.setString(3, event.runtimeClass)
                    ps.setString(4, event.returnType)
                    ps.setString(5, event.exceptionJson)
                    ps.setString(6, json.encodeToString(event.diagnostics))
                    ps.setDouble(7, event.elapsedMs)
                    ps.setLong(8, event.createdAtEpochMs)
                    ps.executeUpdate()
                }
            }

            is CodeUseEvent.LaunchLifecycle -> {
                if (event.source.isNotBlank()) {
                    upsertSnippet(conn, event.snippetId, event.source, "", event.context, event.createdAtEpochMs)
                    writeContext(conn, event.snippetId, event.context, event.createdAtEpochMs)
                } else {
                    touchSnippetContext(conn, event.snippetId, event.context, event.createdAtEpochMs)
                }
                conn.prepareStatement(
                    """
                    INSERT INTO launch_events(snippet_id, lifecycle, watermark, return_type, exception_json, created_at)
                    VALUES(?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, event.snippetId)
                    ps.setString(2, event.lifecycle)
                    ps.setInt(3, event.watermark)
                    ps.setString(4, event.returnType)
                    ps.setString(5, event.exceptionJson)
                    ps.setLong(6, event.createdAtEpochMs)
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun upsertSnippet(
        conn: Connection,
        snippetId: String,
        source: String,
        sourceName: String,
        context: CodeUseContext,
        createdAt: Long
    ) {
        val hash = sha256(source)
        conn.prepareStatement(
            """
            INSERT OR IGNORE INTO source_blobs(hash, text, size, created_at)
            VALUES(?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, hash)
            ps.setString(2, source)
            ps.setInt(3, source.length)
            ps.setLong(4, createdAt)
            ps.executeUpdate()
        }
        conn.prepareStatement(
            """
            INSERT INTO snippets(id, source_hash, document, document_path, box_uid, box_name, property, mode, strand, source_name, created_at, updated_at)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              source_hash=excluded.source_hash,
              document=excluded.document,
              document_path=excluded.document_path,
              box_uid=excluded.box_uid,
              box_name=excluded.box_name,
              property=excluded.property,
              mode=excluded.mode,
              strand=excluded.strand,
              source_name=COALESCE(NULLIF(excluded.source_name, ''), snippets.source_name),
              updated_at=excluded.updated_at
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, snippetId)
            ps.setString(2, hash)
            ps.setString(3, context.document)
            ps.setString(4, context.documentPath)
            ps.setString(5, context.boxUid)
            ps.setString(6, context.boxName)
            ps.setString(7, context.property)
            ps.setString(8, context.mode)
            ps.setString(9, context.strand)
            ps.setString(10, sourceName)
            ps.setLong(11, createdAt)
            ps.setLong(12, createdAt)
            ps.executeUpdate()
        }
    }

    private fun touchSnippetContext(conn: Connection, snippetId: String, context: CodeUseContext, updatedAt: Long) {
        conn.prepareStatement(
            """
            UPDATE snippets
            SET document=COALESCE(NULLIF(?, ''), document),
                document_path=COALESCE(NULLIF(?, ''), document_path),
                box_uid=COALESCE(NULLIF(?, ''), box_uid),
                box_name=COALESCE(NULLIF(?, ''), box_name),
                property=COALESCE(NULLIF(?, ''), property),
                mode=COALESCE(NULLIF(?, ''), mode),
                strand=COALESCE(NULLIF(?, ''), strand),
                updated_at=?
            WHERE id=?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, context.document)
            ps.setString(2, context.documentPath)
            ps.setString(3, context.boxUid)
            ps.setString(4, context.boxName)
            ps.setString(5, context.property)
            ps.setString(6, context.mode)
            ps.setString(7, context.strand)
            ps.setLong(8, updatedAt)
            ps.setString(9, snippetId)
            ps.executeUpdate()
        }
    }

    private fun writeContext(conn: Connection, snippetId: String, context: CodeUseContext, createdAt: Long) {
        conn.prepareStatement(
            """
            INSERT INTO contexts(snippet_id, imports_json, implicit_receivers_json, parents_json, children_json, preamble_hash, created_at)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, snippetId)
            ps.setString(2, json.encodeToString(context.imports))
            ps.setString(3, json.encodeToString(context.implicitReceivers))
            ps.setString(4, json.encodeToString(context.parentIds))
            ps.setString(5, json.encodeToString(context.childIds))
            ps.setString(6, context.preambleHash)
            ps.setLong(7, createdAt)
            ps.executeUpdate()
        }
    }

    private fun <T> queryRows(limit: Int, block: (Connection, Int) -> List<T>): List<T> {
        start()
        if (!enabled) return emptyList()
        return runCatching { withConnection { conn -> block(conn, limit) } }.getOrDefault(emptyList())
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        ensureSchema()
        openConnection().use { conn -> return block(conn) }
    }

    private fun ensureSchema() {
        if (schemaReady) return
        synchronized(schemaLock) {
            if (schemaReady) return
            Files.createDirectories(databasePath.parent)
            DriverManager.getConnection("jdbc:sqlite:${databasePath.absolutePathString()}").use { conn ->
                conn.autoCommit = true
                initializeSchema(conn)
            }
            schemaReady = true
        }
    }

    private fun openConnection(): Connection {
        Files.createDirectories(databasePath.parent)
        return DriverManager.getConnection("jdbc:sqlite:${databasePath.absolutePathString()}").also { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout=5000")
            }
        }
    }

    private fun count(conn: Connection, table: String): Long =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }

    private fun ResultSet.nullableBoolean(column: String): Boolean? {
        val value = getObject(column) ?: return null
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value.toString() == "1" || value.toString().equals("true", ignoreCase = true)
        }
    }

    private fun exceptionSummary(t: Throwable): String =
        json.encodeToString(
            mapOf(
                "type" to t.javaClass.name,
                "message" to (t.message ?: ""),
                "stack" to t.stackTraceToString().take(12_000)
            )
        )

    private fun now(): Long = Instant.now().toEpochMilli()

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Serializable
    data class CodeUseContext(
        val document: String = "",
        val documentPath: String = "",
        val boxUid: String = "",
        val boxName: String = "",
        val property: String = "",
        val mode: String = "",
        val strand: String = "",
        val imports: List<String> = emptyList(),
        val implicitReceivers: List<String> = emptyList(),
        val parentIds: List<String> = emptyList(),
        val childIds: List<String> = emptyList(),
        val preambleHash: String = ""
    )

    private sealed class CodeUseEvent {
        data class CompileResult(
            val snippetId: String,
            val source: String,
            val sourceName: String,
            val context: CodeUseContext,
            val success: Boolean,
            val diagnostics: List<String>,
            val elapsedMs: Double,
            val classpathHash: String,
            val createdAtEpochMs: Long
        ) : CodeUseEvent()

        data class EvaluationResult(
            val snippetId: String,
            val context: CodeUseContext,
            val success: Boolean,
            val runtimeClass: String,
            val returnType: String,
            val exceptionJson: String,
            val diagnostics: List<String>,
            val elapsedMs: Double,
            val createdAtEpochMs: Long
        ) : CodeUseEvent()

        data class LaunchLifecycle(
            val snippetId: String,
            val source: String,
            val context: CodeUseContext,
            val lifecycle: String,
            val watermark: Int,
            val returnType: String,
            val exceptionJson: String,
            val createdAtEpochMs: Long
        ) : CodeUseEvent()
    }
}

private fun sha256(text: String): String = CodeUseTelemetryService.sha256(text)
