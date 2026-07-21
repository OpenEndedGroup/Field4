package afield

import afield.ai.KotlinDefinitionRecord
import afield.ai.KotlinSourceIndex
import afield.logger.PinnedConsole
import afield.logger.lprintln
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
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
    val quietMode: Boolean = false,
    val analyzerQueueSize: Int = 0,
    val droppedAnalysisJobs: Long = 0,
    val analyzerCompletedJobs: Long = 0,
    val analyzerJobsPerMinute: Double = 0.0,
    val catchupBacklog: Long = 0,
    val catchupQueuedJobs: Long = 0,
    val analyzerBackend: String = "source-index",
    val analyzerMessage: String = "",
    val semanticBackend: String = "source-index-fallback",
    val semanticMessage: String = "",
    val semanticSidecarUrl: String = "",
    val snippets: Long = 0,
    val compileEvents: Long = 0,
    val evalEvents: Long = 0,
    val launchEvents: Long = 0,
    val sourceBlobs: Long = 0,
    val symbolUseEdges: Long = 0,
    val k2ResolvedUseEdges: Long = 0,
    val sourceIndexUseEdges: Long = 0,
    val textualUseEdges: Long = 0,
    val unknownUseEdges: Long = 0,
    val unresolvedUseEdges: Long = 0,
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
private data class CodeUseSemanticSidecarHealthResult(
    val ok: Boolean = false,
    val backend: String = "",
    val workspaceRoot: String = ""
)

/**
 * Asynchronous, best-effort history of code compiled and run by Field.
 *
 * Compile/evaluation/launch hooks enqueue immutable records and never wait for SQLite or source
 * analysis. One worker persists content-addressed source plus event/context rows; a separate
 * low-priority worker resolves likely source symbols. Queue overflow, database failure, and
 * analyzer failure are reported in [CodeUseSummary] but must not break user-code execution.
 *
 * The built-in analyzer uses [KotlinSourceIndex] and records only unambiguous unqualified or
 * receiver-qualified matches. [semanticAnalyzeCode] may ask the configured out-of-process K2
 * sidecar for stronger `k2-resolved` edges, then enriches those edges with local SQLite evidence;
 * timeout or failure returns the local `source-index-fallback` shape. Neither analyzer touches the
 * live REPL compilation state.
 *
 * Storage defaults to `~/.afield/code-use/field-code-use.sqlite` and can be overridden by the
 * `afield.codeUse.path` property or `AFIELD_CODE_USE_PATH`. The public HTTP routes and AI tool
 * adapters are installed in `Server.kt` and `Ai4Installer.kt`; keep their user-facing inventories
 * in `dev_docs/agents/CODE_USE_TELEMETRY.md` rather than duplicating query schemas here.
 */
object CodeUseTelemetryService {
    private const val QUEUE_CAPACITY = 4096
    private const val ANALYSIS_QUEUE_CAPACITY = 2048
    private const val MAX_SOURCE_CHARS = 1_000_000
    private const val SUMMARY_CACHE_TTL_MS = 5_000L
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val queue = ArrayBlockingQueue<CodeUseEvent>(QUEUE_CAPACITY)
    private val analysisQueue = ArrayBlockingQueue<CodeUseAnalysisJob>(ANALYSIS_QUEUE_CAPACITY)
    private val droppedEvents = AtomicLong(0)
    private val droppedAnalysisJobs = AtomicLong(0)
    private val analyzerCompletedJobs = AtomicLong(0)
    private val catchupQueuedJobs = AtomicLong(0)

    // --- background catch-up progress, surfaced as a single pinned console row (see reportCatchupProgress) ---
    private const val CATCHUP_CHANNEL = "catchup"
    private const val CATCHUP_REPORT_MIN_NANOS = 500_000_000L // coalesce the pinned row to ≤ 2 repaints/sec
    private val catchupTotal = AtomicLong(0)     // snippets enqueued for catch-up this run
    private val catchupDone = AtomicLong(0)      // catch-up snippets the analyzer has finished
    @Volatile private var catchupActive = false  // a catch-up pass is in flight (its row is pinned)
    @Volatile private var lastCatchupReportNanos = 0L

    private val analysisRateLock = Any()
    private val analysisCompletionTimestamps = ArrayDeque<Long>()
    private val started = AtomicLong(0)
    private val analysisStarted = AtomicLong(0)
    private val disabledMessage = AtomicLong(0)
    private val schemaLock = Any()

    @Volatile
    private var enabled = true

    @Volatile
    private var quietMode = runCatching { currentFieldPreferences().quietMode }.getOrDefault(false)

    @Volatile
    private var schemaReady = false

    // The welcome screen polls summary() repeatedly; the counts it needs come from several full
    // COUNT(*) scans over the telemetry SQLite tables. Serve a short-lived cached copy so bursts
    // of polls don't each re-scan the database.
    @Volatile
    private var summaryCache: Pair<Long, CodeUseSummary>? = null

    // Single-flight guard for background summary recomputes kicked off by summarySnapshot().
    private val summaryRefreshLock = Any()
    private var summaryRefreshing = false

    @Volatile
    private var fallbackDefinitionsCache: Pair<Long, List<KotlinDefinitionRecord>>? = null

    private val databasePath: Path = Paths.get(
        System.getProperty("afield.codeUse.path")
            ?: System.getenv("AFIELD_CODE_USE_PATH")
            ?: Paths.get(System.getProperty("user.home"), ".afield", "code-use", "field-code-use.sqlite").toString()
    ).toAbsolutePath().normalize()

    private val semanticK2Status: SemanticK2AnalyzerStatus = SemanticK2AnalyzerStatus.detect()
    private val semanticSidecarUrl: String = (
        System.getProperty("afield.codeUse.semanticSidecar.url")
            ?: System.getenv("AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL")
            ?: ""
        ).trim()
    private val semanticSidecarTimeoutMs: Long = (
        System.getProperty("afield.codeUse.semanticSidecar.timeoutMs")
            ?: System.getenv("AFIELD_CODE_USE_SEMANTIC_SIDECAR_TIMEOUT_MS")
            ?: "8000"
        ).toLongOrNull()?.coerceIn(100L, 30_000L) ?: 8000L
    private val semanticSidecarHttpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(semanticSidecarTimeoutMs))
        .build()

    @Volatile
    private var semanticSidecarHealthCache: Pair<Long, SemanticSidecarHealthStatus>? = null

    private data class SemanticSidecarHealthStatus(
        val backend: String,
        val message: String,
        val url: String
    )

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
        refreshQuietMode()
        start()
        summaryCache?.let { if (now() - it.first < SUMMARY_CACHE_TTL_MS) return it.second }
        val computed = computeSummary()
        summaryCache = now() to computed
        return computed
    }

    /**
     * Non-blocking counterpart to [summary] for hot polling paths such as the welcome screen.
     * Returns the most recent computed summary immediately (even if a little stale), or a cheap
     * "still gathering" placeholder if none has been computed yet, and kicks off a background
     * recompute when the cache is stale. It never runs the full-table SQLite scans on the caller's
     * thread, so it can't stall Netty request or workspace-search dispatcher threads — the cold
     * first computeSummary() (which can take many seconds on a large database) no longer blocks the
     * welcome response or, by dispatcher starvation, a document the user clicks right after.
     */
    fun summarySnapshot(): CodeUseSummary {
        refreshQuietMode()
        start()
        val cached = summaryCache
        if (cached == null || now() - cached.first >= SUMMARY_CACHE_TTL_MS) {
            triggerSummaryRefresh()
        }
        return cached?.second ?: computingSummaryPlaceholder()
    }

    private fun triggerSummaryRefresh() {
        synchronized(summaryRefreshLock) {
            if (summaryRefreshing) return
            summaryRefreshing = true
        }
        thread(name = "code-use-telemetry-summary", isDaemon = true) {
            try {
                val computed = computeSummary()
                summaryCache = now() to computed
            } catch (_: Throwable) {
                // Leave the previous snapshot in place; the next poll retries.
            } finally {
                synchronized(summaryRefreshLock) { summaryRefreshing = false }
            }
        }
    }

    private fun computingSummaryPlaceholder(): CodeUseSummary {
        val health = semanticSidecarHealthCache?.second
        val message = if (enabled) "Gathering code-use statistics…" else "Code-use telemetry is disabled."
        return CodeUseSummary(
            enabled = enabled,
            databasePath = databasePath.absolutePathString(),
            queueSize = queue.size,
            droppedEvents = droppedEvents.get(),
            quietMode = quietMode,
            analyzerQueueSize = analysisQueue.size,
            droppedAnalysisJobs = droppedAnalysisJobs.get(),
            analyzerCompletedJobs = analyzerCompletedJobs.get(),
            analyzerJobsPerMinute = analyzerJobsPerMinute(),
            catchupQueuedJobs = catchupQueuedJobs.get(),
            analyzerBackend = health?.let { storeAnalyzerBackend(it) } ?: "source-index",
            analyzerMessage = message,
            semanticBackend = health?.backend ?: "source-index-fallback",
            semanticMessage = health?.message.orEmpty(),
            semanticSidecarUrl = health?.url ?: semanticSidecarUrl,
            message = message
        )
    }

    private fun computeSummary(): CodeUseSummary {
        val semanticHealth = semanticSidecarHealthStatus()
        if (!enabled) {
            return CodeUseSummary(
                enabled = false,
                databasePath = databasePath.absolutePathString(),
                queueSize = queue.size,
                droppedEvents = droppedEvents.get(),
                quietMode = quietMode,
                analyzerQueueSize = analysisQueue.size,
                droppedAnalysisJobs = droppedAnalysisJobs.get(),
                analyzerCompletedJobs = analyzerCompletedJobs.get(),
                analyzerJobsPerMinute = analyzerJobsPerMinute(),
                catchupQueuedJobs = catchupQueuedJobs.get(),
                analyzerBackend = storeAnalyzerBackend(semanticHealth),
                analyzerMessage = storeAnalyzerMessage(semanticHealth),
                semanticBackend = semanticHealth.backend,
                semanticMessage = semanticHealth.message,
                semanticSidecarUrl = semanticHealth.url,
                message = "Code-use telemetry is disabled; SQLite initialization failed."
            )
        }
        return runCatching {
            withConnection { conn ->
                val confidence = confidenceSummary(conn)
                val catchupBacklog = recentK2CatchupBacklog(conn)
                CodeUseSummary(
                    enabled = true,
                    databasePath = databasePath.absolutePathString(),
                    queueSize = queue.size,
                    droppedEvents = droppedEvents.get(),
                    quietMode = quietMode,
                    analyzerQueueSize = analysisQueue.size,
                    droppedAnalysisJobs = droppedAnalysisJobs.get(),
                    analyzerCompletedJobs = analyzerCompletedJobs.get(),
                    analyzerJobsPerMinute = analyzerJobsPerMinute(),
                    catchupBacklog = catchupBacklog,
                    catchupQueuedJobs = catchupQueuedJobs.get(),
                    analyzerBackend = storeAnalyzerBackend(semanticHealth),
                    analyzerMessage = storeAnalyzerMessage(semanticHealth),
                    semanticBackend = semanticHealth.backend,
                    semanticMessage = semanticHealth.message,
                    semanticSidecarUrl = semanticHealth.url,
                    snippets = count(conn, "snippets"),
                    compileEvents = count(conn, "compile_events"),
                    evalEvents = count(conn, "eval_events"),
                    launchEvents = count(conn, "launch_events"),
                    sourceBlobs = count(conn, "source_blobs"),
                    symbolUseEdges = confidence.symbolUseEdges,
                    k2ResolvedUseEdges = confidence.k2ResolvedUseEdges,
                    sourceIndexUseEdges = confidence.sourceIndexUseEdges,
                    textualUseEdges = confidence.textualUseEdges,
                    unknownUseEdges = confidence.unknownUseEdges,
                    unresolvedUseEdges = confidence.unresolvedUseEdges,
                    message = if (quietMode) "Quiet mode is on; background semantic catch-up is paused." else ""
                )
            }
        }.getOrElse {
            CodeUseSummary(
                enabled = false,
                databasePath = databasePath.absolutePathString(),
                queueSize = queue.size,
                droppedEvents = droppedEvents.get(),
                quietMode = quietMode,
                analyzerQueueSize = analysisQueue.size,
                droppedAnalysisJobs = droppedAnalysisJobs.get(),
                analyzerCompletedJobs = analyzerCompletedJobs.get(),
                analyzerJobsPerMinute = analyzerJobsPerMinute(),
                catchupQueuedJobs = catchupQueuedJobs.get(),
                analyzerBackend = storeAnalyzerBackend(semanticHealth),
                analyzerMessage = storeAnalyzerMessage(semanticHealth),
                semanticBackend = semanticHealth.backend,
                semanticMessage = semanticHealth.message,
                semanticSidecarUrl = semanticHealth.url,
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
                    AND COALESCE(su.confidence, '') <> 'source-index-match'
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
        if (resolved.isNotEmpty()) {
            return target.copy(confidence = combineConfidences(resolved.map { it.confidence })) to resolved
        }

        return target to textualSnippetUses(names, limit = limit, sinceEpochMs = 0L)
    }

    fun referencesForCode(code: String, limit: Int = 5): List<CodeUseSourceReferenceRow> {
        val text = code.take(80_000)
        val definitions = sourceDefinitions()
        if (definitions.isEmpty()) return emptyList()
        val defsBySimpleName = definitions.groupBy { it.name.substringAfterLast('.') }
        return sourceIndexMatchesForSource(text, defsBySimpleName)
            .groupBy { "${it.definition.filePath}:${it.definition.line}:${it.definition.name}" }
            .values
            .map { group ->
                val first = group.first()
                val def = first.definition
                CodeUseSourceReferenceRow(
                    symbolName = def.name,
                    kind = def.kind,
                    sourceFile = def.filePath,
                    line = def.line,
                    score = group.sumOf { sourceIndexReferenceScore(it.confidence) },
                    confidence = combineConfidences(group.map { it.confidence }),
                    evidence = group.map { it.evidence }.filter { it.isNotBlank() }.distinct().joinToString(",")
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
        relatedSourceForReferences(
            referencesForCode(code, symbolLimit.coerceIn(1, 20)),
            callersPerSymbol = callersPerSymbol,
            sourceRadiusLines = sourceRadiusLines
        )

    fun semanticAnalyzeCode(request: CodeUseSemanticAnalyzeRequest): CodeUseSemanticAnalyzeResult {
        val safeRequest = enrichedSemanticRequest(request)
        if (safeRequest.code.isBlank()) {
            return CodeUseSemanticAnalyzeResult(
                success = false,
                backend = "none",
                message = "No code was provided for semantic analysis.",
                sourceName = safeRequest.sourceName
            )
        }

        val sidecar = semanticSidecarResult(safeRequest)
        if (sidecar != null) {
            return enrichSemanticResult(safeRequest, sidecar)
        }

        return fallbackSemanticResult(
            request = safeRequest,
            message = if (semanticSidecarUrl.isBlank()) {
                "No semantic K2 sidecar is configured; used local source-index graph fallback."
            } else {
                "Semantic K2 sidecar was unavailable; used local source-index graph fallback."
            }
        )
    }

    fun semanticAnalyzeCode(
        code: String,
        sourceName: String = "AgentSnippet.kt",
        limit: Int = 5,
        callersPerSymbol: Int = 5,
        sourceRadiusLines: Int = 24
    ): CodeUseSemanticAnalyzeResult =
        semanticAnalyzeCode(
            CodeUseSemanticAnalyzeRequest(
                code = code,
                sourceName = sourceName,
                limit = limit,
                callersPerSymbol = callersPerSymbol,
                sourceRadiusLines = sourceRadiusLines
            )
        )

    private fun relatedSourceForReferences(
        references: List<CodeUseSourceReferenceRow>,
        callersPerSymbol: Int = 5,
        sourceRadiusLines: Int = 24
    ): List<CodeUseRelatedSourceRow> =
        references.map { ref ->
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

    private fun enrichedSemanticRequest(request: CodeUseSemanticAnalyzeRequest): CodeUseSemanticAnalyzeRequest {
        val root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize().absolutePathString()
        return request.copy(
            code = request.code.take(80_000),
            sourceName = request.sourceName.ifBlank { "AgentSnippet.kt" },
            limit = request.limit.coerceIn(1, 20),
            callersPerSymbol = request.callersPerSymbol.coerceIn(0, 20),
            sourceRadiusLines = request.sourceRadiusLines.coerceIn(4, 80),
            databasePath = request.databasePath.ifBlank { databasePath.absolutePathString() },
            workspaceRoot = request.workspaceRoot.ifBlank { root },
            sourceRoots = request.sourceRoots.ifEmpty { defaultSemanticSourceRoots(root) },
            classpathRoots = request.classpathRoots,
            timeoutMs = request.timeoutMs.takeIf { it > 0 }?.coerceIn(100L, 30_000L) ?: semanticSidecarTimeoutMs
        )
    }

    private fun storeAnalyzerBackend(semanticHealth: SemanticSidecarHealthStatus): String =
        if (semanticHealth.backend != "source-index-fallback" && semanticHealth.url.isNotBlank()) {
            "k2-sidecar"
        } else {
            "source-index"
        }

    private fun storeAnalyzerMessage(semanticHealth: SemanticSidecarHealthStatus): String =
        if (semanticHealth.backend != "source-index-fallback" && semanticHealth.url.isNotBlank()) {
            "Background event-store analyzer uses the semantic K2 sidecar for new snippets, with source-index fallback when the sidecar is unavailable."
        } else {
            "Background event-store analyzer is using the source index; live semantic queries use the sidecar when available."
        }

    private fun semanticSidecarHealthStatus(): SemanticSidecarHealthStatus {
        val timestamp = now()
        val cached = semanticSidecarHealthCache
        if (cached != null && timestamp - cached.first < 2_000L) return cached.second
        val status = computeSemanticSidecarHealthStatus()
        semanticSidecarHealthCache = timestamp to status
        return status
    }

    private fun computeSemanticSidecarHealthStatus(): SemanticSidecarHealthStatus {
        val base = semanticSidecarBaseEndpoint()
            ?: return SemanticSidecarHealthStatus(
                backend = "source-index-fallback",
                message = "No semantic K2 sidecar is configured; live semantic queries use source-index fallback.",
                url = ""
            )
        val healthEndpoint = base.resolve("/health")
        return runCatching {
            val request = HttpRequest.newBuilder(healthEndpoint)
                .timeout(Duration.ofMillis(semanticSidecarTimeoutMs.coerceAtMost(800L)))
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = semanticSidecarHttpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() !in 200..299) {
                return SemanticSidecarHealthStatus(
                    backend = "source-index-fallback",
                    message = "Semantic K2 sidecar health check returned HTTP ${response.statusCode()}; live semantic queries use source-index fallback.",
                    url = base.toString()
                )
            }
            val health = json.decodeFromString<CodeUseSemanticSidecarHealthResult>(response.body())
            if (!health.ok) {
                return SemanticSidecarHealthStatus(
                    backend = "source-index-fallback",
                    message = "Semantic K2 sidecar at $base reported unhealthy; live semantic queries use source-index fallback.",
                    url = base.toString()
                )
            }
            SemanticSidecarHealthStatus(
                backend = health.backend.ifBlank { "k2-sidecar" },
                message = "Semantic K2 sidecar is available at $base for ${health.workspaceRoot.ifBlank { "this checkout" }}.",
                url = base.toString()
            )
        }.getOrElse {
            SemanticSidecarHealthStatus(
                backend = "source-index-fallback",
                message = "Semantic K2 sidecar is configured at $base but is unavailable (${it.message ?: it.javaClass.simpleName}); live semantic queries use source-index fallback.",
                url = base.toString()
            )
        }
    }

    private fun defaultSemanticSourceRoots(workspaceRoot: String): List<String> {
        val root = Paths.get(workspaceRoot)
        return listOf("src/jvmMain/kotlin", "src/commonMain/kotlin", "src/personal/kotlin")
            .map { root.resolve(it).toAbsolutePath().normalize() }
            .filter { Files.exists(it) }
            .map { it.absolutePathString() }
    }

    private fun semanticSidecarResult(request: CodeUseSemanticAnalyzeRequest): CodeUseSemanticAnalyzeResult? {
        val endpoint = semanticSidecarEndpoint() ?: return null
        return runCatching {
            val body = json.encodeToString(request)
            val httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(request.timeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()
            val response = semanticSidecarHttpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() !in 200..299) {
                lprintln("warning,code-use", "semantic sidecar returned HTTP ${response.statusCode()} from $endpoint")
                return null
            }
            json.decodeFromString<CodeUseSemanticAnalyzeResult>(response.body())
        }.onFailure {
            lprintln("warning,code-use", "semantic sidecar unavailable at $endpoint: ${it.message ?: it.javaClass.name}")
        }.getOrNull()
    }

    private fun semanticSidecarEndpoint(): URI? {
        val base = semanticSidecarBaseEndpoint() ?: return null
        return if (base.path.endsWith("/analyze")) base else base.resolve("/analyze")
    }

    private fun semanticSidecarBaseEndpoint(): URI? =
        runCatching {
            if (semanticSidecarUrl.isBlank()) return null
            val trimmed = semanticSidecarUrl.trimEnd('/')
            val withoutAnalyze = if (trimmed.endsWith("/analyze")) trimmed.removeSuffix("/analyze") else trimmed
            URI.create(withoutAnalyze)
        }.onFailure {
            lprintln("warning,code-use", "invalid semantic sidecar URL '$semanticSidecarUrl': ${it.message ?: it.javaClass.name}")
        }.getOrNull()

    private fun enrichSemanticResult(
        request: CodeUseSemanticAnalyzeRequest,
        sidecar: CodeUseSemanticAnalyzeResult
    ): CodeUseSemanticAnalyzeResult {
        val references = sidecar.references.ifEmpty {
            semanticUsesToReferences(sidecar.resolvedUses).ifEmpty {
                referencesForCode(request.code, request.limit)
            }
        }
        val related = sidecar.relatedSources.ifEmpty {
            relatedSourceForReferences(
                references.take(request.limit),
                callersPerSymbol = request.callersPerSymbol,
                sourceRadiusLines = request.sourceRadiusLines
            )
        }
        val message = buildString {
            append(sidecar.message.ifBlank { "Semantic sidecar returned ${sidecar.resolvedUses.size} resolved use(s)." })
            if (sidecar.references.isEmpty() || sidecar.relatedSources.isEmpty()) {
                append(" Field enriched the result from the local code-use graph.")
            }
        }
        return sidecar.copy(
            success = sidecar.success,
            backend = sidecar.backend.ifBlank { "semantic-sidecar" },
            message = message,
            sourceName = sidecar.sourceName.ifBlank { request.sourceName },
            sidecarUrl = sidecar.sidecarUrl.ifBlank { semanticSidecarUrl },
            references = references.take(request.limit),
            relatedSources = related.take(request.limit)
        )
    }

    private fun fallbackSemanticResult(
        request: CodeUseSemanticAnalyzeRequest,
        message: String
    ): CodeUseSemanticAnalyzeResult {
        val references = referencesForCode(request.code, request.limit)
        val related = relatedSourceForReferences(
            references,
            callersPerSymbol = request.callersPerSymbol,
            sourceRadiusLines = request.sourceRadiusLines
        )
        val unresolved = if (references.isEmpty()) {
            extractCandidateUses(request.code).take(20).map {
                CodeUseSemanticUnresolvedRow(
                    text = it.text,
                    line = it.line,
                    column = it.column,
                    reason = "source-index-fallback-no-match"
                )
            }
        } else {
            emptyList()
        }
        return CodeUseSemanticAnalyzeResult(
            success = true,
            backend = "source-index-fallback",
            message = message,
            sourceName = request.sourceName,
            sidecarUrl = semanticSidecarUrl,
            unresolvedUses = unresolved,
            references = references,
            relatedSources = related
        )
    }

    private fun semanticUsesToReferences(uses: List<CodeUseSemanticUseRow>): List<CodeUseSourceReferenceRow> =
        uses
            .filter { it.sourceFile.isNotBlank() && it.line > 0 && it.symbolName.isNotBlank() }
            .groupBy { "${normalizeSourcePath(it.sourceFile)}:${it.line}:${it.symbolName}" }
            .values
            .map { group ->
                val first = group.first()
                CodeUseSourceReferenceRow(
                    symbolName = first.symbolName,
                    kind = first.kind,
                    sourceFile = normalizeSourcePath(first.sourceFile),
                    line = first.line,
                    score = 100 + group.size,
                    confidence = first.confidence.ifBlank { "k2-resolved" },
                    evidence = group.map { it.evidence.ifBlank { it.useKind } }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(",")
                )
            }
            .sortedWith(
                compareByDescending<CodeUseSourceReferenceRow> { it.score }
                    .thenBy { it.sourceFile }
                    .thenBy { it.line }
            )

    fun hotSourceSymbols(window: String = "all", limit: Int = 20): List<CodeUseHotSourceSymbolRow> {
        val since = windowStartEpochMs(window)
        val resolved = resolvedHotSourceSymbols(since, limit)
        if (resolved.isNotEmpty()) return resolved
        return textualHotSourceSymbols(since, limit)
    }

    fun earlyDocumentSymbols(document: String = "", earlyWindowMs: Long = 10 * 60 * 1000L, limit: Int = 20): List<CodeUseEarlySourceSymbolRow> {
        val resolved = resolvedEarlyDocumentSymbols(document, earlyWindowMs, limit)
        if (resolved.isNotEmpty()) return resolved
        return textualEarlyDocumentSymbols(document, earlyWindowMs, limit)
    }

    private fun resolvedEarlyDocumentSymbols(document: String, earlyWindowMs: Long, limit: Int): List<CodeUseEarlySourceSymbolRow> =
        queryRows(limit.coerceIn(1, 500)) { conn, safeLimit ->
            val documentFilter = document.trim()
            val earlyWindow = earlyWindowMs.coerceAtLeast(1_000L)
            conn.prepareStatement(
                """
                WITH document_starts AS (
                    SELECT document, MIN(created_at) AS start_at
                    FROM snippets
                    WHERE document <> ''
                      AND (? = '' OR document LIKE ? ESCAPE '\' COLLATE NOCASE)
                    GROUP BY document
                ),
                site_uses AS (
                    SELECT
                        s.document,
                        sym.fq_name,
                        sym.kind,
                        sym.source_file,
                        sym.line,
                        COALESCE(s.document, '') || '|' || COALESCE(s.box_uid, '') || '|' || COALESCE(s.property, '') AS site_id,
                        MIN(su.created_at) AS first_use_at,
                        MAX(su.created_at) AS last_use_at,
                        GROUP_CONCAT(DISTINCT COALESCE(NULLIF(su.confidence, ''), 'unknown')) AS confidences
                    FROM symbol_uses su
                    JOIN symbols sym ON sym.id = su.symbol_id
                    JOIN snippets s ON s.id = su.snippet_id
                    WHERE s.document <> ''
                      AND COALESCE(su.confidence, '') <> 'source-index-match'
                      AND (? = '' OR s.document LIKE ? ESCAPE '\' COLLATE NOCASE)
                    GROUP BY s.document, sym.id, site_id
                )
                SELECT
                    su.document,
                    su.fq_name,
                    su.kind,
                    su.source_file,
                    su.line,
                    SUM(CASE WHEN su.first_use_at <= ds.start_at + ? THEN 1 ELSE 0 END) AS early_sites,
                    SUM(CASE WHEN su.first_use_at > ds.start_at + ? THEN 1 ELSE 0 END) AS later_sites,
                    MIN(su.first_use_at) AS first_seen,
                    MAX(su.last_use_at) AS last_seen,
                    GROUP_CONCAT(DISTINCT su.confidences) AS confidences
                FROM site_uses su
                JOIN document_starts ds ON ds.document = su.document
                GROUP BY su.document, su.fq_name, su.kind, su.source_file, su.line
                HAVING early_sites > 0 AND later_sites <= 1
                ORDER BY (early_sites - later_sites) DESC, later_sites ASC, first_seen ASC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                val like = "%${escapeLike(documentFilter)}%"
                ps.setString(1, documentFilter)
                ps.setString(2, like)
                ps.setString(3, documentFilter)
                ps.setString(4, like)
                ps.setLong(5, earlyWindow)
                ps.setLong(6, earlyWindow)
                ps.setInt(7, safeLimit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                CodeUseEarlySourceSymbolRow(
                                    document = rs.getString("document").orEmpty(),
                                    symbolName = rs.getString("fq_name").orEmpty(),
                                    kind = rs.getString("kind").orEmpty(),
                                    sourceFile = rs.getString("source_file").orEmpty(),
                                    line = rs.getInt("line"),
                                    earlyUseCount = rs.getLong("early_sites"),
                                    laterUseCount = rs.getLong("later_sites"),
                                    firstSeenEpochMs = rs.getLong("first_seen"),
                                    lastSeenEpochMs = rs.getLong("last_seen"),
                                    confidence = combineConfidences(rs.getString("confidences").orEmpty().split(","))
                                )
                            )
                        }
                    }
                }
            }
        }

    private fun textualEarlyDocumentSymbols(document: String = "", earlyWindowMs: Long = 10 * 60 * 1000L, limit: Int = 20): List<CodeUseEarlySourceSymbolRow> {
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
            val matches = sourceIndexMatchesForSource(row.sourceText, defsBySimpleName)
            matches.forEach { match ->
                val def = match.definition
                val key = "${row.document}|${def.filePath}:${def.line}:${def.name}"
                val stat = stats.getOrPut(key) { EarlyStat(row.document, def) }
                if (early) stat.earlyUseCount++ else stat.laterUseCount++
                stat.confidences += match.confidence
                if (stat.firstSeenEpochMs == 0L || row.createdAtEpochMs < stat.firstSeenEpochMs) stat.firstSeenEpochMs = row.createdAtEpochMs
                if (row.createdAtEpochMs > stat.lastSeenEpochMs) stat.lastSeenEpochMs = row.createdAtEpochMs
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
                    confidence = combineConfidences(it.confidences)
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
        var lastSeenEpochMs: Long = 0,
        val confidences: MutableSet<String> = linkedSetOf()
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
                    confidence = "source-index-target"
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
                  AND COALESCE(su.confidence, '') <> 'source-index-match'
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
            val whereParts = mutableListOf("COALESCE(su.confidence, '') <> 'source-index-match'")
            if (sinceEpochMs > 0L) whereParts += "su.created_at >= ?"
            val whereClause = "WHERE ${whereParts.joinToString(" AND ")}"
            conn.prepareStatement(
                """
                SELECT sym.fq_name, sym.kind, sym.source_file, sym.line,
                       COUNT(DISTINCT COALESCE(s.document, '') || '|' || COALESCE(s.box_uid, '') || '|' || COALESCE(s.property, '')) AS use_count,
                       COUNT(DISTINCT su.snippet_id) AS unique_snippet_count,
                       MIN(su.created_at) AS first_seen,
                       MAX(su.created_at) AS last_seen,
                       GROUP_CONCAT(DISTINCT COALESCE(NULLIF(su.confidence, ''), 'unknown')) AS confidences
                FROM symbol_uses su
                JOIN symbols sym ON sym.id = su.symbol_id
                JOIN snippets s ON s.id = su.snippet_id
                $whereClause
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
                                    confidence = combineConfidences(rs.getString("confidences").orEmpty().split(","))
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
            val siteId = observedCodeSiteId(row)
            sourceIndexMatchesForSource(row.sourceText, defsBySimpleName)
                .groupBy { "${it.definition.filePath}:${it.definition.line}:${it.definition.name}" }
                .values
                .forEach { group ->
                    val match = group.first()
                    val def = match.definition
                    val key = "${def.filePath}:${def.line}:${def.name}"
                    val stat = stats.getOrPut(key) { HotStat(def) }
                    stat.siteIds += siteId
                    stat.snippetIds += row.id
                    stat.confidences += group.map { it.confidence }
                    if (stat.firstSeenEpochMs == 0L || row.createdAtEpochMs < stat.firstSeenEpochMs) stat.firstSeenEpochMs = row.createdAtEpochMs
                    if (row.createdAtEpochMs > stat.lastSeenEpochMs) stat.lastSeenEpochMs = row.createdAtEpochMs
                }
        }
        return stats.values
            .sortedWith(
                compareByDescending<HotStat> { it.siteIds.size }
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
                    useCount = it.siteIds.size.toLong(),
                    uniqueSnippetCount = it.snippetIds.size.toLong(),
                    firstSeenEpochMs = it.firstSeenEpochMs,
                    lastSeenEpochMs = it.lastSeenEpochMs,
                    confidence = combineConfidences(it.confidences)
                )
            }
    }

    private fun observedCodeSiteId(row: SnippetTextRow): String =
        "${row.document}|${row.boxUid}|${row.property}"

    private fun sourceIndexMatchesForSource(
        source: String,
        defsBySimpleName: Map<String, List<KotlinDefinitionRecord>>
    ): List<SourceIndexStaticMatch> =
        extractCandidateUses(source).flatMap { candidate ->
            val matches = defsBySimpleName[candidate.text].orEmpty()
                .filter { candidateMatchesDefinition(candidate, it) }
            val selected = selectSourceIndexMatches(candidate, matches)
            val evidence = if (candidate.receiverHint.isBlank()) {
                "${candidate.useKind}:${candidate.text}"
            } else {
                "${candidate.useKind}:${candidate.receiverHint}.${candidate.text}"
            }
            selected.definitions.map { SourceIndexStaticMatch(it, selected.confidence, evidence) }
        }

    private fun sourceIndexReferenceScore(confidence: String): Int =
        when {
            confidence.contains("qualified") -> 100
            confidence.contains("unique") -> 70
            else -> 1
        }

    private fun combineConfidences(confidences: Iterable<String>): String {
        val clean = confidences
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(
                compareBy<String> {
                    when {
                        it.contains("k2") -> 0
                        it.contains("source-index") -> 1
                        it.contains("textual") -> 2
                        else -> 3
                    }
                }.thenBy { it }
            )
        return when (clean.size) {
            0 -> "unknown"
            1 -> clean.first()
            else -> clean.joinToString("+")
        }
    }

    private data class HotStat(
        val definition: KotlinDefinitionRecord,
        val siteIds: MutableSet<String> = linkedSetOf(),
        val snippetIds: MutableSet<String> = linkedSetOf(),
        var firstSeenEpochMs: Long = 0,
        var lastSeenEpochMs: Long = 0,
        val confidences: MutableSet<String> = linkedSetOf()
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
        if (analysisStarted.compareAndSet(0, 1)) {
            thread(name = "code-use-telemetry-analyzer", isDaemon = true) {
                analysisLoop()
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
                    // A summary computed while this batch was still queued must not remain cached
                    // for the full TTL after the rows become visible. Besides making status polls
                    // truthful sooner, this closes a race in callers that wait for persistence by
                    // polling summary().
                    summaryCache = null
                }
            }
        } catch (t: Throwable) {
            enabled = false
            if (disabledMessage.compareAndSet(0, 1)) {
                lprintln("warning,code-use", "code-use telemetry disabled: ${t.message ?: t.javaClass.name}")
            }
        }
    }

    private fun enqueueAnalysis(
        snippetId: String,
        source: String,
        sourceName: String,
        createdAtEpochMs: Long,
        mode: String = "",
        catchup: Boolean = false
    ): Boolean {
        refreshQuietMode()
        if (quietMode) return false
        if (!isSignalBearingSource(source, mode)) return false
        val job = CodeUseAnalysisJob(
            snippetId = snippetId,
            source = source.take(MAX_SOURCE_CHARS),
            sourceName = sourceName,
            createdAtEpochMs = createdAtEpochMs,
            catchup = catchup
        )
        if (analysisQueue.offer(job)) return true
        droppedAnalysisJobs.incrementAndGet()
        return false
    }

    private fun analysisLoop() {
        Thread.currentThread().priority = Thread.MIN_PRIORITY
        while (true) {
            val first = analysisQueue.poll(1, TimeUnit.SECONDS)
            if (first == null) {
                // Idle tick: refresh the pinned catch-up row so it stays alive (and visibly *stalled*
                // if the analyzer is blocked) instead of silently expiring after 20s.
                if (catchupActive) reportCatchupProgress()
                continue
            }
            refreshQuietMode()
            if (quietMode) {
                analysisQueue.clear()
                cancelCatchupReport()
                continue
            }
            val latestBySnippet = LinkedHashMap<String, CodeUseAnalysisJob>()
            latestBySnippet[first.snippetId] = first
            var drained = 0
            while (drained < 128) {
                val next = analysisQueue.poll() ?: break
                // Keep the newest job, but never lose a catch-up snippet's flag to a coinciding live edit —
                // otherwise catchupDone would fall one short of catchupTotal and wedge the progress row.
                val merged = if (latestBySnippet[next.snippetId]?.catchup == true) next.copy(catchup = true) else next
                latestBySnippet[next.snippetId] = merged
                drained++
            }
            latestBySnippet.values.forEach { job ->
                runCatching { analyzeAndStore(job) }
                    .onFailure { lprintln("warning,code-use", "code-use analyzer skipped ${job.snippetId}: ${it.message ?: it.javaClass.name}") }
                recordAnalysisJobCompleted()
                if (job.catchup) catchupDone.incrementAndGet()
            }
            reportCatchupProgress()
        }
    }

    /**
     * Repaint the single pinned "semantic catch-up" row (channel [CATCHUP_CHANNEL], pinned at the top of
     * the console by [enqueueRecentSidecarCatchup]). No-op unless a catch-up pass is active; coalesced to
     * [CATCHUP_REPORT_MIN_NANOS] so a fast-draining queue doesn't flood repaints. When the backlog empties
     * it hands off to [finishCatchupReport]. The row shows done/total, live queue depth, throughput, and a
     * rough ETA — so a flat count is an at-a-glance signal that the sidecar analyzer is wedged.
     */
    private fun reportCatchupProgress(force: Boolean = false) {
        if (!catchupActive) return
        val total = catchupTotal.get()
        if (total <= 0L) return
        val done = catchupDone.get().coerceAtMost(total)
        if (done >= total) {
            finishCatchupReport()
            return
        }
        val nowNanos = System.nanoTime()
        if (!force && nowNanos - lastCatchupReportNanos < CATCHUP_REPORT_MIN_NANOS) return
        lastCatchupReportNanos = nowNanos
        val pct = done * 100L / total
        val rate = analyzerJobsPerMinute()
        val eta = if (rate > 0.0) {
            val remainingSec = ((total - done).toDouble() / (rate / 60.0)).toLong()
            " · ~${formatCatchupEta(remainingSec)} left"
        } else ""
        lprintln(
            "info,$CATCHUP_CHANNEL",
            "semantic catch-up $done/$total ($pct%) · ${analysisQueue.size} queued · ${"%.0f".format(rate)}/min$eta"
        )
    }

    /** Close out a finished catch-up pass: log a scrolling summary line and drop the pinned row. */
    private fun finishCatchupReport() {
        if (!catchupActive) return
        catchupActive = false
        val total = catchupTotal.get()
        lprintln("info,code-use", "semantic sidecar catch-up complete · $total snippet(s) analyzed")
        runCatching { PinnedConsole.unpin(CATCHUP_CHANNEL) }
    }

    /** Abandon the pinned catch-up row without a completion summary (e.g. quiet mode cleared the queue). */
    private fun cancelCatchupReport() {
        if (!catchupActive) return
        catchupActive = false
        runCatching { PinnedConsole.unpin(CATCHUP_CHANNEL) }
    }

    private fun formatCatchupEta(seconds: Long): String = when {
        seconds < 60L -> "${seconds}s"
        seconds < 3600L -> "${seconds / 60L}m ${seconds % 60L}s"
        else -> "${seconds / 3600L}h ${(seconds % 3600L) / 60L}m"
    }

    private fun analyzeAndStore(job: CodeUseAnalysisJob) {
        val analyzed = analyzeSource(job.source, job.sourceName)
        withConnection { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM symbol_uses WHERE snippet_id = ?").use { ps ->
                    ps.setString(1, job.snippetId)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM unresolved_uses WHERE snippet_id = ?").use { ps ->
                    ps.setString(1, job.snippetId)
                    ps.executeUpdate()
                }

                val seenUses = linkedSetOf<String>()
                analyzed.resolved.forEach { use ->
                    val symbolId = upsertSymbol(conn, use.definition, use.callableId, use.packageName)
                    val key = "$symbolId|${use.useKind}|${use.line}|${use.column}"
                    if (!seenUses.add(key)) return@forEach
                    conn.prepareStatement(
                        """
                        INSERT INTO symbol_uses(snippet_id, symbol_id, use_kind, receiver_type, line, column, confidence, created_at)
                        VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { ps ->
                        ps.setString(1, job.snippetId)
                        ps.setLong(2, symbolId)
                        ps.setString(3, use.useKind)
                        ps.setString(4, use.receiverType)
                        ps.setInt(5, use.line)
                        ps.setInt(6, use.column)
                        ps.setString(7, use.confidence)
                        ps.setLong(8, job.createdAtEpochMs)
                        ps.executeUpdate()
                    }
                }

                analyzed.unresolved.take(80).forEach { unresolved ->
                    conn.prepareStatement(
                        """
                        INSERT INTO unresolved_uses(snippet_id, text, line, column, reason, created_at)
                        VALUES(?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { ps ->
                        ps.setString(1, job.snippetId)
                        ps.setString(2, unresolved.text)
                        ps.setInt(3, unresolved.line)
                        ps.setInt(4, unresolved.column)
                        ps.setString(5, unresolved.reason)
                        ps.setLong(6, job.createdAtEpochMs)
                        ps.executeUpdate()
                    }
                }
                conn.commit()
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun upsertSymbol(
        conn: Connection,
        definition: KotlinDefinitionRecord,
        callableId: String = "",
        packageNameOverride: String = ""
    ): Long {
        val packageName = packageNameOverride.ifBlank { sourcePackage(definition.filePath) }
        val callable = callableId.ifBlank { definition.name }
        conn.prepareStatement(
            """
            SELECT id FROM symbols
            WHERE fq_name = ? AND callable_id = ? AND kind = ? AND source_file = ? AND line = ?
            ORDER BY id ASC LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, definition.name)
            ps.setString(2, callable)
            ps.setString(3, definition.kind)
            ps.setString(4, definition.filePath)
            ps.setInt(5, definition.line)
            ps.executeQuery().use { rs ->
                if (rs.next()) return rs.getLong("id")
            }
        }
        conn.prepareStatement(
            """
            INSERT INTO symbols(fq_name, callable_id, kind, package, source_file, line)
            VALUES(?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            java.sql.Statement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setString(1, definition.name)
            ps.setString(2, callable)
            ps.setString(3, definition.kind)
            ps.setString(4, packageName)
            ps.setString(5, definition.filePath)
            ps.setInt(6, definition.line)
            ps.executeUpdate()
            ps.generatedKeys.use { rs ->
                if (rs.next()) return rs.getLong(1)
            }
        }
        error("Could not insert symbol ${definition.name}")
    }

    private fun sourcePackage(sourceFile: String): String {
        val root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val path = runCatching {
            val raw = Paths.get(sourceFile)
            if (raw.isAbsolute) raw else root.resolve(raw)
        }.getOrNull() ?: return ""
        if (!Files.exists(path)) return ""
        return runCatching {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).useLines { lines ->
                lines.firstNotNullOfOrNull { line ->
                    Regex("""^\s*package\s+([A-Za-z0-9_.]+)""").find(line)?.groupValues?.getOrNull(1)
                }.orEmpty()
            }
        }.getOrDefault("")
    }

    private fun analyzeSource(source: String, sourceName: String = ""): AnalyzedSource {
        if (!isSignalBearingSource(source)) {
            return AnalyzedSource(resolved = emptyList(), unresolved = emptyList())
        }
        analyzeSourceWithSidecar(source, sourceName)?.let { return it }
        val definitions = sourceDefinitions()
        if (definitions.isEmpty()) {
            return AnalyzedSource(
                resolved = emptyList(),
                unresolved = extractCandidateUses(source).take(80).map {
                    CodeUseAnalyzerUnresolved(it.text, it.line, it.column, "source-index-empty")
                }
            )
        }
        val defsBySimple = definitions.groupBy { it.name.substringAfterLast('.') }
        val resolved = mutableListOf<CodeUseAnalyzerUse>()
        val unresolved = mutableListOf<CodeUseAnalyzerUnresolved>()
        extractCandidateUses(source).forEach { candidate ->
            val matches = defsBySimple[candidate.text].orEmpty()
                .filter { candidateMatchesDefinition(candidate, it) }
            val selected = selectSourceIndexMatches(candidate, matches)
            when {
                matches.isEmpty() -> unresolved += CodeUseAnalyzerUnresolved(
                    text = candidate.text,
                    line = candidate.line,
                    column = candidate.column,
                    reason = "no-source-index-match"
                )
                selected.definitions.isEmpty() -> unresolved += CodeUseAnalyzerUnresolved(
                    text = candidate.text,
                    line = candidate.line,
                    column = candidate.column,
                    reason = "ambiguous-source-index-match:${selected.ambiguousCount}"
                )
                else -> selected.definitions.forEach { definition ->
                    resolved += CodeUseAnalyzerUse(
                        definition = definition,
                        useKind = candidate.useKind,
                        receiverType = candidate.receiverHint,
                        line = candidate.line,
                        column = candidate.column,
                        confidence = selected.confidence
                    )
                }
            }
        }
        return AnalyzedSource(resolved = resolved, unresolved = unresolved)
    }

    private fun analyzeSourceWithSidecar(source: String, sourceName: String): AnalyzedSource? {
        val request = enrichedSemanticRequest(
            CodeUseSemanticAnalyzeRequest(
                code = source,
                sourceName = sourceName.ifBlank { "FieldSnippet.kt" },
                limit = 80,
                timeoutMs = semanticSidecarTimeoutMs
            )
        ).copy(limit = 80)
        val result = semanticSidecarResult(request) ?: return null
        if (!result.success) return null
        val resolved = result.resolvedUses
            .filter { it.sourceFile.isNotBlank() && it.line > 0 && it.symbolName.isNotBlank() }
            .map {
                CodeUseAnalyzerUse(
                    definition = KotlinDefinitionRecord(
                        name = it.symbolName,
                        kind = it.kind.ifBlank { "symbol" },
                        filePath = normalizeSourcePath(it.sourceFile),
                        line = it.line,
                        fileId = null,
                        contentHash = ""
                    ),
                    callableId = it.callableId.ifBlank { it.symbolName },
                    packageName = it.packageName,
                    useKind = it.useKind.ifBlank { "reference" },
                    receiverType = it.receiverType,
                    line = it.snippetLine,
                    column = it.snippetColumn,
                    confidence = it.confidence.ifBlank { "k2-resolved" }
                )
            }
        val unresolved = result.unresolvedUses.map {
            CodeUseAnalyzerUnresolved(
                text = it.text,
                line = it.line,
                column = it.column,
                reason = it.reason.ifBlank { "k2-unresolved" }
            )
        }
        if (resolved.isEmpty() && unresolved.isEmpty()) return null
        return AnalyzedSource(resolved = resolved, unresolved = unresolved)
    }

    private fun isSignalBearingSource(source: String, mode: String = ""): Boolean {
        if (source.isBlank()) return false
        val normalizedMode = mode.trim().lowercase()
        if (
            normalizedMode.contains("field-startup-imports") ||
            normalizedMode.contains("imports-or-preamble") ||
            normalizedMode == "compile:blank"
        ) {
            return false
        }
        return source.lineSequence().any { rawLine ->
            val trimmed = rawLine.substringBefore("//").trim()
            trimmed.isNotBlank() &&
                !trimmed.startsWith("package ") &&
                !trimmed.startsWith("import ") &&
                !trimmed.startsWith("@file:")
        }
    }

    private fun candidateMatchesDefinition(candidate: CodeUseCandidate, definition: KotlinDefinitionRecord): Boolean =
        when (candidate.useKind) {
            "constructor" -> definition.kind in setOf("class", "interface", "object")
            "call" -> definition.kind in setOf("fun", "class", "object")
            "property" -> definition.kind in setOf("val", "var", "fun")
            else -> true
        }

    private fun selectSourceIndexMatches(
        candidate: CodeUseCandidate,
        matches: List<KotlinDefinitionRecord>
    ): SourceIndexMatchSelection {
        if (matches.isEmpty()) return SourceIndexMatchSelection(emptyList(), "source-index-empty", 0)
        val receiver = candidate.receiverHint.trim()
        val narrowed = if (receiver.isBlank()) {
            matches
        } else {
            matches.filter { definitionOwnerMatches(receiver, it) }
        }
        if (narrowed.size == 1) {
            return SourceIndexMatchSelection(
                definitions = narrowed,
                confidence = if (receiver.isBlank()) "source-index-unique" else "source-index-qualified",
                ambiguousCount = 1
            )
        }
        return SourceIndexMatchSelection(
            definitions = emptyList(),
            confidence = "source-index-ambiguous",
            ambiguousCount = if (narrowed.isEmpty()) matches.size else narrowed.size
        )
    }

    private fun definitionOwnerMatches(receiver: String, definition: KotlinDefinitionRecord): Boolean {
        val localOwner = definition.name.substringBeforeLast('.', missingDelimiterValue = "")
        if (localOwner == receiver || localOwner.substringAfterLast('.') == receiver) return true
        val fqOwner = definitionFqName(definition).substringBeforeLast('.', missingDelimiterValue = "")
        return fqOwner == receiver || fqOwner.endsWith(".$receiver")
    }

    private fun definitionFqName(definition: KotlinDefinitionRecord): String {
        val name = definition.name.trim()
        if (name.isBlank()) return ""
        val pkg = sourcePackage(definition.filePath)
        return if (pkg.isBlank() || name.startsWith("$pkg.")) name else "$pkg.$name"
    }

    private fun extractCandidateUses(source: String): List<CodeUseCandidate> {
        val noise = analyzerNoiseTokens
        val out = mutableListOf<CodeUseCandidate>()
        source.lineSequence().forEachIndexed { lineIndex, rawLine ->
            val lineNumber = lineIndex + 1
            val line = rawLine.substringBefore("//")
            val trimmed = line.trimStart()
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) return@forEachIndexed
            val declarationNames = declarationNamesInLine(line)
            Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(line).forEach { match ->
                val text = match.value
                if (text in noise || text in declarationNames) return@forEach
                val before = line.take(match.range.first).trimEnd()
                val after = line.drop(match.range.last + 1).trimStart()
                val prevIdentifier = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*\.?\s*$""")
                    .find(before)
                    ?.groupValues
                    ?.getOrNull(1)
                    .orEmpty()
                val useKind = when {
                    after.startsWith("(") && text.first().isUpperCase() -> "constructor"
                    after.startsWith("(") -> "call"
                    before.endsWith(".") -> "property"
                    else -> "reference"
                }
                if (useKind == "reference" && text.first().isLowerCase()) return@forEach
                out += CodeUseCandidate(
                    text = text,
                    useKind = useKind,
                    receiverHint = if (before.endsWith(".")) prevIdentifier else "",
                    line = lineNumber,
                    column = match.range.first + 1
                )
            }
        }
        return out
    }

    private fun declarationNamesInLine(line: String): Set<String> {
        val out = linkedSetOf<String>()
        Regex("""\b(?:fun|val|var|class|interface|object|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .findAll(line)
            .forEach { out += it.groupValues[1] }
        return out
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
                enqueueAnalysis(event.snippetId, event.source, event.sourceName, event.createdAtEpochMs, event.context.mode)
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
                if (event.source.isNotBlank()) {
                    enqueueAnalysis(event.snippetId, event.source, "", event.createdAtEpochMs, event.context.mode)
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
                pruneNonSignalAnalysisRows(conn)
                pruneLegacySourceIndexMatchRows(conn)
                enqueueRecentSidecarCatchup(conn)
            }
            schemaReady = true
        }
    }

    private fun enqueueRecentSidecarCatchup(conn: Connection) {
        refreshQuietMode()
        if (quietMode) return
        val health = semanticSidecarHealthStatus()
        if (storeAnalyzerBackend(health) != "k2-sidecar") return
        var queued = 0
        val since = now() - 7L * 24L * 60L * 60L * 1000L
        conn.prepareStatement(
            """
            SELECT s.id, s.source_name, s.created_at, s.mode, b.text
            FROM snippets s
            JOIN source_blobs b ON b.hash = s.source_hash
            WHERE s.updated_at >= ?
              AND NOT EXISTS (
                  SELECT 1
                  FROM symbol_uses su
                  WHERE su.snippet_id = s.id
                    AND su.confidence = 'k2-resolved'
              )
            ORDER BY s.updated_at DESC
            LIMIT 1000
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, since)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val enqueued = enqueueAnalysis(
                        snippetId = rs.getString("id").orEmpty(),
                        source = rs.getString("text").orEmpty(),
                        sourceName = rs.getString("source_name").orEmpty(),
                        createdAtEpochMs = rs.getLong("created_at"),
                        mode = rs.getString("mode").orEmpty(),
                        catchup = true
                    )
                    if (enqueued) queued++
                }
            }
        }
        if (queued > 0) {
            catchupQueuedJobs.addAndGet(queued.toLong())
            // Pin a live progress row at the top of the console so the catch-up pass reports itself
            // instead of going quiet after this one line (see reportCatchupProgress / analysisLoop).
            catchupTotal.addAndGet(queued.toLong())
            catchupActive = true
            runCatching { PinnedConsole.pin(CATCHUP_CHANNEL, heatmap = false) }
            lprintln("info,code-use", "queued $queued recent snippet(s) for semantic sidecar catch-up analysis")
            reportCatchupProgress(force = true)
        }
    }

    private fun recentK2CatchupBacklog(conn: Connection): Long {
        val since = now() - 7L * 24L * 60L * 60L * 1000L
        var count = 0L
        conn.prepareStatement(
            """
            SELECT s.mode, b.text
            FROM snippets s
            JOIN source_blobs b ON b.hash = s.source_hash
            WHERE s.updated_at >= ?
              AND NOT EXISTS (
                  SELECT 1
                  FROM symbol_uses su
                  WHERE su.snippet_id = s.id
                    AND su.confidence = 'k2-resolved'
              )
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, since)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    if (isSignalBearingSource(rs.getString("text").orEmpty(), rs.getString("mode").orEmpty())) {
                        count++
                    }
                }
            }
        }
        return count
    }

    private fun recordAnalysisJobCompleted() {
        analyzerCompletedJobs.incrementAndGet()
        val timestamp = now()
        synchronized(analysisRateLock) {
            analysisCompletionTimestamps.addLast(timestamp)
            trimAnalysisCompletionTimestamps(timestamp)
        }
    }

    private fun analyzerJobsPerMinute(): Double {
        val timestamp = now()
        synchronized(analysisRateLock) {
            trimAnalysisCompletionTimestamps(timestamp)
            val first = analysisCompletionTimestamps.peekFirst() ?: return 0.0
            val elapsedMs = (timestamp - first).coerceAtLeast(1L)
            val scale = if (elapsedMs < 60_000L) 60_000.0 / elapsedMs.toDouble() else 1.0
            return analysisCompletionTimestamps.size.toDouble() * scale
        }
    }

    private fun trimAnalysisCompletionTimestamps(nowMs: Long) {
        val cutoff = nowMs - 60_000L
        while (analysisCompletionTimestamps.peekFirst()?.let { it < cutoff } == true) {
            analysisCompletionTimestamps.removeFirst()
        }
    }

    fun configureQuietMode(enabled: Boolean) {
        quietMode = enabled
        if (enabled) {
            analysisQueue.clear()
        }
    }

    private fun refreshQuietMode(): Boolean {
        quietMode = runCatching { currentFieldPreferences().quietMode }.getOrDefault(quietMode)
        return quietMode
    }

    private fun pruneNonSignalAnalysisRows(conn: Connection) {
        val snippetIds = mutableListOf<String>()
        conn.prepareStatement(
            """
            SELECT s.id, s.mode, b.text
            FROM snippets s
            LEFT JOIN source_blobs b ON b.hash = s.source_hash
            WHERE s.id IN (SELECT snippet_id FROM symbol_uses UNION SELECT snippet_id FROM unresolved_uses)
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val source = rs.getString("text").orEmpty()
                    val mode = rs.getString("mode").orEmpty()
                    if (!isSignalBearingSource(source, mode)) snippetIds += rs.getString("id").orEmpty()
                }
            }
        }
        if (snippetIds.isEmpty()) return
        conn.autoCommit = false
        try {
            conn.prepareStatement("DELETE FROM symbol_uses WHERE snippet_id = ?").use { ps ->
                snippetIds.forEach {
                    ps.setString(1, it)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.prepareStatement("DELETE FROM unresolved_uses WHERE snippet_id = ?").use { ps ->
                snippetIds.forEach {
                    ps.setString(1, it)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
            lprintln("info,code-use", "pruned analyzer rows for ${snippetIds.size} import/preamble-only snippet(s)")
        } catch (t: Throwable) {
            runCatching { conn.rollback() }
            lprintln("warning,code-use", "could not prune import/preamble-only analyzer rows: ${t.message ?: t.javaClass.name}")
        } finally {
            conn.autoCommit = true
        }
    }

    private fun pruneLegacySourceIndexMatchRows(conn: Connection) {
        var deleted = 0
        conn.autoCommit = false
        try {
            conn.prepareStatement("DELETE FROM symbol_uses WHERE confidence = 'source-index-match'").use { ps ->
                deleted = ps.executeUpdate()
            }
            conn.commit()
            if (deleted > 0) {
                lprintln(
                    "info,code-use",
                    "pruned $deleted legacy source-index-match analyzer row(s); future source-index rows require unique or qualified matches"
                )
            }
        } catch (t: Throwable) {
            runCatching { conn.rollback() }
            lprintln("warning,code-use", "could not prune legacy source-index-match rows: ${t.message ?: t.javaClass.name}")
        } finally {
            conn.autoCommit = true
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

    private fun confidenceSummary(conn: Connection): ConfidenceSummary {
        conn.prepareStatement(
            """
            SELECT
              COUNT(*) AS total,
              SUM(CASE WHEN confidence = 'k2-resolved' THEN 1 ELSE 0 END) AS k2,
              SUM(CASE WHEN confidence LIKE 'source-index-%' AND confidence <> 'source-index-match' THEN 1 ELSE 0 END) AS source_index,
              SUM(CASE WHEN confidence LIKE 'textual%' THEN 1 ELSE 0 END) AS textual,
              SUM(CASE WHEN COALESCE(confidence, '') = '' OR confidence = 'unknown' THEN 1 ELSE 0 END) AS unknown
            FROM symbol_uses
            WHERE COALESCE(confidence, '') <> 'source-index-match'
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    return ConfidenceSummary(
                        symbolUseEdges = rs.getLong("total"),
                        k2ResolvedUseEdges = rs.getLong("k2"),
                        sourceIndexUseEdges = rs.getLong("source_index"),
                        textualUseEdges = rs.getLong("textual"),
                        unknownUseEdges = rs.getLong("unknown"),
                        unresolvedUseEdges = count(conn, "unresolved_uses")
                    )
                }
            }
        }
        return ConfidenceSummary(unresolvedUseEdges = count(conn, "unresolved_uses"))
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

    private data class CodeUseAnalysisJob(
        val snippetId: String,
        val source: String,
        val sourceName: String,
        val createdAtEpochMs: Long,
        val catchup: Boolean = false
    )

    private data class CodeUseCandidate(
        val text: String,
        val useKind: String,
        val receiverHint: String,
        val line: Int,
        val column: Int
    )

    private data class CodeUseAnalyzerUse(
        val definition: KotlinDefinitionRecord,
        val callableId: String = "",
        val packageName: String = "",
        val useKind: String,
        val receiverType: String,
        val line: Int,
        val column: Int,
        val confidence: String
    )

    private data class CodeUseAnalyzerUnresolved(
        val text: String,
        val line: Int,
        val column: Int,
        val reason: String
    )

    private data class SourceIndexMatchSelection(
        val definitions: List<KotlinDefinitionRecord>,
        val confidence: String,
        val ambiguousCount: Int
    )

    private data class SourceIndexStaticMatch(
        val definition: KotlinDefinitionRecord,
        val confidence: String,
        val evidence: String = ""
    )

    private data class ConfidenceSummary(
        val symbolUseEdges: Long = 0,
        val k2ResolvedUseEdges: Long = 0,
        val sourceIndexUseEdges: Long = 0,
        val textualUseEdges: Long = 0,
        val unknownUseEdges: Long = 0,
        val unresolvedUseEdges: Long = 0
    )

    private data class AnalyzedSource(
        val resolved: List<CodeUseAnalyzerUse>,
        val unresolved: List<CodeUseAnalyzerUnresolved>
    )

    private data class SemanticK2AnalyzerStatus(
        val publicApiAvailable: Boolean,
        val standaloneRuntimeAvailable: Boolean,
        val message: String
    ) {
        companion object {
            fun detect(): SemanticK2AnalyzerStatus {
                val publicApiClasses = listOf(
                    "org.jetbrains.kotlin.analysis.api.AnalyzeKt",
                    "org.jetbrains.kotlin.analysis.api.session.KaSessionProvider"
                )
                val standaloneRuntimeClasses = listOf(
                    "org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISessionBuilderKt",
                    "org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.StandaloneProjectFactory",
                    "org.jetbrains.kotlin.analysis.api.fir.KaFirSessionProvider"
                )
                val publicResourcesAvailable = publicApiClasses.all(::hasClassResource)
                val publicLoadResults = publicApiClasses.map { it to loadClassError(it) }
                val publicApiAvailable = publicLoadResults.all { it.second == null }
                val standaloneRuntimeAvailable = standaloneRuntimeClasses.any { loadClassError(it) == null }
                val firstPublicLoadError = publicLoadResults.firstOrNull { it.second != null }
                val message = when {
                    publicApiAvailable && standaloneRuntimeAvailable ->
                        "K2 Analysis API classes and a standalone/session runtime are visible; semantic backend can be enabled once wired."
                    publicApiAvailable ->
                        "K2 Analysis API public classes are visible, but no standalone/session provider runtime is on the classpath; using source-index analyzer."
                    publicResourcesAvailable && firstPublicLoadError != null ->
                        "K2 Analysis API jar resources are visible, but ${firstPublicLoadError.first} is not loadable (${firstPublicLoadError.second}); using source-index analyzer."
                    else ->
                        "K2 Analysis API classes are not visible; using source-index analyzer."
                }
                return SemanticK2AnalyzerStatus(publicApiAvailable, standaloneRuntimeAvailable, message)
            }

            private fun hasClassResource(name: String): Boolean {
                val resourceName = name.replace('.', '/') + ".class"
                return CodeUseTelemetryService::class.java.classLoader.getResource(resourceName) != null
            }

            private fun loadClassError(name: String): String? =
                runCatching { Class.forName(name, false, CodeUseTelemetryService::class.java.classLoader) }
                    .fold(
                        onSuccess = { null },
                        onFailure = { "${it.javaClass.simpleName}: ${it.message ?: name}" }
                    )
        }
    }

    private val analyzerNoiseTokens = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "for", "while", "if", "else",
        "when", "return", "true", "false", "null", "this", "super", "is", "in", "as", "break", "continue",
        "try", "catch", "finally", "throw", "new", "by", "where", "do", "constructor", "init", "override",
        "private", "public", "protected", "internal", "open", "final", "abstract", "sealed", "data", "enum",
        "inline", "suspend", "operator", "infix", "tailrec", "reified", "crossinline", "noinline", "get", "set"
    )
}

private fun sha256(text: String): String = CodeUseTelemetryService.sha256(text)
