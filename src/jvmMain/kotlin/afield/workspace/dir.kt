package afield

import afield.logger.lprintln
import afield.ai.SemanticRetrievalSettingsStatus
import afield.ai.SemanticRetrievalService
import afield.python.PythonSettingsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

@Serializable
class Entry {
    var filename: String = ""
    var numBoxes: Int = -1
    var lastModified: Long = -1
    var boxes: MutableList<Pair<String, String>> = mutableListOf()
    var metadata: MutableList<Pair<String, List<String>>> = mutableListOf()
}

@Serializable
data class WelcomeEntry(
    val filename: String,
    val numBoxes: Int,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val lastModifiedAgoMs: Long,
    val tags: List<String> = emptyList(),
    val runningSessionCount: Int = 0,
    val runningExecutionCount: Int = 0,
    val kind: String = "document",
    // Relative paths (under the document dir) to the newest history screenshot for each surface,
    // served via /history-screenshot?file=<filename>&path=<...>. Empty when the document has none.
    val canvasThumbnail: String = "",
    val fourThumbnail: String = ""
)

@Serializable
data class ProjectEntry(
    val name: String,
    val documents: List<String>,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val lastModifiedAgoMs: Long,
    val kind: String = "project"
)

@Serializable
data class RunningDocumentEntry(
    val documentName: String,
    val sessionCount: Int,
    val ephemeral: Boolean = false,
    val openUrl: String
)

@Serializable
data class GcPauseStats(
    val monitoring: Boolean = false,
    val collectorCount: Int = 0,
    val observedCount: Long = 0,
    val sampleCount: Int = 0,
    val sampleCapacity: Int = 0,
    val medianPauseMs: Double = 0.0,
    val p95PauseMs: Double = 0.0,
    val maxPauseMs: Long = 0,
    val latestPauseMs: Long = 0
)

@Serializable
data class RunloopTimingStats(
    val running: Boolean = false,
    val frame: Long = 0,
    val totalSamples: Long = 0,
    val sampleCount: Int = 0,
    val sampleCapacity: Int = 0,
    val ticksPerSecond: Double = 0.0,
    val latestIntervalMs: Double = 0.0,
    val intervalMedianMs: Double = 0.0,
    val intervalP95Ms: Double = 0.0,
    val intervalP99Ms: Double = 0.0,
    val intervalMaxMs: Double = 0.0,
    val latestWorkMs: Double = 0.0,
    val workMedianMs: Double = 0.0,
    val workP95Ms: Double = 0.0,
    val workP99Ms: Double = 0.0,
    val workMaxMs: Double = 0.0,
    val intervalsOver1Ms: Int = 0,
    val intervalsOver4Ms: Int = 0,
    val intervalsOver8Ms: Int = 0,
    val intervalsOver16Ms: Int = 0
)

@Serializable
data class RunloopSchedulingStatus(
    val idleStrategy: String = "spin-then-park",
    val spinIterations: Int = 0,
    val parkNanos: Long = 0,
    val affinityRequested: Boolean = false,
    val affinityCore: Int = -1,
    val affinityApplied: Boolean = false,
    val affinityThreadId: Long = -1,
    val affinityMessage: String = ""
)

@Serializable
data class RuntimeAotCacheStatus(
    val state: String = "unknown",
    val summary: String = "unknown",
    val cachePath: String = "",
    val autoGenerate: Boolean = false,
    val attempted: Boolean = false,
    val detail: String = ""
)

@Serializable
data class RuntimeInfo(
    val javaVersion: String = "",
    val javaRuntimeName: String = "",
    val javaRuntimeVersion: String = "",
    val javaVmName: String = "",
    val javaVmVendor: String = "",
    val javaVmVersion: String = "",
    val javaVendor: String = "",
    val javaHome: String = "",
    val runtimeVersion: String = "",
    val osName: String = "",
    val osVersion: String = "",
    val osArch: String = "",
    val processId: Long = -1,
    val uptimeMs: Long = 0,
    val inputArguments: List<String> = emptyList(),
    val availableProcessors: Int = 0,
    val maxMemoryBytes: Long = 0,
    val totalMemoryBytes: Long = 0,
    val freeMemoryBytes: Long = 0,
    val aotCacheStatus: RuntimeAotCacheStatus = RuntimeAotCacheStatus(),
    val gcPauseStats: GcPauseStats = GcPauseStats(),
    val runloopTimingStats: RunloopTimingStats = RunloopTimingStats(),
    val runloopSchedulingStatus: RunloopSchedulingStatus = RunloopSchedulingStatus()
)

@Serializable
data class CompletionDocPolishCacheSummary(
    val cachePath: String = "",
    val exists: Boolean = false,
    val pendingDocCount: Int = 0,
    val pendingSourceFileCount: Int = 0,
    val sizeBytes: Long = 0,
    val lastModifiedEpochMs: Long = 0,
    val message: String = ""
)

@Serializable
data class CompletionDocPolishStatus(
    val cache: CompletionDocPolishCacheSummary = CompletionDocPolishCacheSummary(),
    val running: Boolean = false,
    val status: String = "idle",
    val runId: String = "",
    val taskName: String = "",
    val dryRun: Boolean = false,
    val startedAtEpochMs: Long = 0,
    val finishedAtEpochMs: Long = 0,
    val exitCode: Int? = null,
    val message: String = "",
    val logTail: String = ""
)

@Serializable
data class CompletionDocPolishRunRequest(
    val dryRun: Boolean = false
)

@Serializable
data class AiToolUseLogSummary(
    val logDir: String = "",
    val exists: Boolean = false,
    val logCount: Int = 0,
    val recentLogCount: Int = 0,
    val sizeBytes: Long = 0,
    val lastModifiedEpochMs: Long = 0,
    val reportPath: String = "",
    val dryRunPatchPath: String = "",
    val message: String = ""
)

@Serializable
data class AgentWorktreeProposal(
    val id: String,
    val label: String = "",
    val path: String = "",
    val branch: String = "",
    val baseBranch: String = "",
    val baseCommit: String = "",
    val createdAtEpochMs: Long = 0,
    val updatedAtEpochMs: Long = 0,
    val status: String = "created",
    val taskName: String = "",
    val reportPath: String = "",
    val dryRunPatchPath: String = "",
    val message: String = ""
)

@Serializable
data class AgentWorktreeActionRequest(
    val id: String
)

@Serializable
data class AgentWorktreeActionResponse(
    val success: Boolean,
    val proposal: AgentWorktreeProposal? = null,
    val message: String = ""
)

@Serializable
data class AiToolUseImprovementStatus(
    val logs: AiToolUseLogSummary = AiToolUseLogSummary(),
    val worktrees: List<AgentWorktreeProposal> = emptyList(),
    val running: Boolean = false,
    val status: String = "idle",
    val runId: String = "",
    val taskName: String = "",
    val dryRun: Boolean = true,
    val startedAtEpochMs: Long = 0,
    val finishedAtEpochMs: Long = 0,
    val exitCode: Int? = null,
    val message: String = "",
    val logTail: String = "",
    val reportMarkdown: String = "",
    val dryRunPatch: String = ""
)

@Serializable
data class AiToolUseImprovementRunRequest(
    val dryRun: Boolean = true,
    val proposalCount: Int = 1
)

@Serializable
data class AiToolUseImprovementClearRequest(
    val recentOnly: Boolean = true
)

val defaultImportClassBlacklist = listOf(
    "com.sun.*",
    "sun.*",
    "jdk.*",
    "*awt*",
    "javax.swing.*",
    "com.apple.eio.*",
    "kotlin.reflect.jvm.internal.*"
)

@Serializable
data class WelcomeDocsResponse(
    val documents: List<WelcomeEntry>,
    val lastOpened: String,
    val defaultDocument: String,
    val allTags: List<String> = emptyList(),
    val runningDocuments: List<RunningDocumentEntry> = emptyList(),
    val startingPoints: List<WelcomeEntry> = emptyList(),
    val templates: List<WelcomeEntry> = emptyList(),
    val currentWorkspace: String = "",
    val defaultWorkspace: String = "",
    val aiSettings: AiSettingsStatus = AiSettingsStatus(),
    val preferences: FieldPreferences = FieldPreferences(),
    val projects: List<ProjectEntry> = emptyList(),
    val runtimeInfo: RuntimeInfo = RuntimeInfo(),
    val pythonSettings: PythonSettingsStatus = PythonSettingsStatus(
        configured = false,
        source = "not loaded",
        kind = "",
        path = "",
        configPath = "",
        message = "not loaded"
    ),
    val completionDocPolish: CompletionDocPolishStatus = CompletionDocPolishStatus(),
    val aiToolUseImprovement: AiToolUseImprovementStatus = AiToolUseImprovementStatus(),
    val codeUse: CodeUseSummary = CodeUseSummary(
        enabled = false,
        databasePath = "",
        queueSize = 0,
        droppedEvents = 0,
        message = "not loaded"
    ),
    val semanticSearch: SemanticRetrievalSettingsStatus = SemanticRetrievalService.settingsStatus()
)

@Serializable
data class MavenDependencyEntry(
    val coordinate: String,
    val resolved: Boolean = false,
    val jarCount: Int = 0,
    val latestVersion: String = "",
    val status: String = "",
    val resolvedAtEpochMs: Long = 0
)

@Serializable
data class MavenClasspathStatus(
    val repositories: List<String> = emptyList(),
    val dependencies: List<MavenDependencyEntry> = emptyList(),
    val resolvedJars: List<String> = emptyList(),
    val attachedJarCount: Int = 0,
    val annotationSnippet: String = "",
    val message: String = ""
)

@Serializable
data class MavenRepositoryRequest(
    val url: String
)

@Serializable
data class MavenDependencyRequest(
    val coordinate: String
)

@Serializable
data class MavenResolveRequest(
    val coordinates: List<String> = emptyList()
)

@Serializable
data class MavenUpgradeResponse(
    val upgraded: List<String> = emptyList(),
    val status: MavenClasspathStatus
)

@Serializable
data class MavenSearchResult(
    val groupId: String,
    val artifactId: String,
    val latestVersion: String,
    val packaging: String = "",
    val versionCount: Int = 0,
    val timestampEpochMs: Long = 0,
    val coordinate: String
)

@Serializable
data class MavenSearchResponse(
    val query: String,
    val results: List<MavenSearchResult>,
    val message: String = ""
)

@Serializable
data class FieldPreferences(
    val profiling: Boolean = false,
    val framePresentationLog: Boolean = false,
    val logShadercSource: Boolean = true,
    val enableVR: Boolean = false,
    val enableHDR: Boolean = false,
    val openXRDebugInput: Boolean = true,
    val validate: Boolean = true,
    val quietMode: Boolean = false,
    val autoOpenLastDocumentInDesktop: Boolean = false,
    val editorPropertyStickiness: String = "none",
    val oscEditorEnabled: Boolean = false,
    val oscEditorPort: Int = 9000,
    val oscEditorFloatAddress: String = "/field/float",
    val oscEditorLaunchAddress: String = "/field/launch",
    val oscEditorStopAddress: String = "/field/stop",
    val oscEditorDebounceMs: Int = 200,
    val oscEditorMaxExecutionMs: Int = 750,
    val oscEditorDeltaMode: Boolean = false,
    val importClassBlacklist: List<String> = defaultImportClassBlacklist
)

@Serializable
data class FieldPreferencesUpdateRequest(
    val preferences: FieldPreferences
)

@Serializable
data class FieldPreferencesUpdateResponse(
    val preferences: FieldPreferences,
    val message: String
)

@Serializable
data class OscEditorLearnRequest(
    val port: Int,
    val timeoutMs: Long = 10_000,
    val ignoreAddresses: List<String> = emptyList()
)

@Serializable
data class OscEditorLearnResponse(
    val address: String,
    val message: String = ""
)

@Serializable
data class WorkspaceSwitchRequest(
    val path: String
)

@Serializable
data class AiSettingsStatus(
    val hasOpenAiPlatformKey: Boolean = false,
    val hasOpenRouterApiKey: Boolean = false,
    val provider: String = "openai",
    val source: String = "",
    val configPath: String = "",
    val openRouterConfigPath: String = "",
    val baseUrl: String = "",
    val flagshipModel: String = "",
    val fastModel: String = "",
    val fallbackModel: String = ""
)

@Serializable
data class AiSettingsUpdateRequest(
    val provider: String = "openai",
    val openAiPlatformKey: String = "",
    val openRouterApiKey: String = "",
    val openRouterBaseUrl: String = "",
    val flagshipModel: String = "",
    val fastModel: String = "",
    val fallbackModel: String = ""
)

@Serializable
data class AiSettingsUpdateResponse(
    val hasOpenAiPlatformKey: Boolean,
    val hasOpenRouterApiKey: Boolean,
    val provider: String,
    val source: String,
    val configPath: String,
    val openRouterConfigPath: String,
    val baseUrl: String,
    val flagshipModel: String,
    val fastModel: String,
    val fallbackModel: String,
    val message: String
)

@Serializable
data class WorkspaceSwitchResponse(
    val workspace: String,
    val current: Boolean,
    val restarting: Boolean,
    val message: String
)

@Serializable
data class WelcomeChangelogEntry(
    val hash: String,
    val summary: String,
    val committedEpochMs: Long
)

@Serializable
data class WelcomeChangelogResponse(
    val entries: List<WelcomeChangelogEntry>
)

@Serializable
data class DocumentTagsUpdateRequest(
    val filename: String,
    val tags: List<String>
)

@Serializable
data class DocumentTagsUpdateResponse(
    val filename: String,
    val tags: List<String>,
    val changed: Boolean
)

@Serializable
data class DuplicateDocumentRequest(
    val sourceFilename: String,
    val newFilename: String
)

@Serializable
data class DuplicateDocumentResponse(
    val filename: String,
    val numBoxes: Int,
    val sizeBytes: Long,
    val tags: List<String> = emptyList()
)

@Serializable
data class StartingPointOpenRequest(
    val name: String
)

@Serializable
data class RevealDocumentRequest(
    val filename: String
)

@Serializable
data class RevealDocumentResponse(
    val filename: String,
    val path: String,
    val message: String
)

@Serializable
data class ProjectCreateRequest(
    val name: String,
    val documents: List<String> = emptyList()
)

@Serializable
data class ProjectAddDocumentRequest(
    val projectName: String,
    val filename: String
)

@Serializable
data class ProjectDeleteRequest(
    val name: String
)

@Serializable
data class ProjectMutationResponse(
    val project: ProjectEntry,
    val changed: Boolean,
    val message: String = ""
)

data class TaggedDocumentsZip(
    val filename: String,
    val bytes: ByteArray,
    val documentCount: Int
)

@Serializable
data class WorkspaceSearchMatch(
    val boxName: String,
    val property: String,
    val line: Int,
    val snippet: String
)

@Serializable
data class WorkspaceSearchResult(
    val filename: String,
    val numBoxes: Int,
    val lastModifiedEpochMs: Long,
    val lastModifiedAgoMs: Long,
    val tags: List<String> = emptyList(),
    val matches: List<WorkspaceSearchMatch>,
    val kind: String = "document",
    val documents: List<String> = emptyList()
)

@Serializable
data class WorkspaceSearchResponse(
    val query: String,
    val documents: List<WorkspaceSearchResult>,
    val totalDocuments: Int,
    val totalMatches: Int,
    val truncated: Boolean
)

fun registerTemplate(dir: String) {

    val format = Json {
        prettyPrint = true
    }


    templates["dir_json"] = { template, everything ->
        val entries = simpleDir(dir)

        format.encodeToString(entries)
    }

    runAll(templatesRegistration)
}

fun simpleDir(base: String): List<Entry> {
    val baseDir = File(base)
    if (!baseDir.exists() || !baseDir.isDirectory) {
        return emptyList()
    }

    val entries = mutableListOf<Entry>()

    val s = SavingContext()

    baseDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
        val metadataEdnFile = File(dir, SavingContext.METADATA_EDN)
        val metadataJsonFile = File(dir, SavingContext.METADATA_JSON)
        val hasEdn = metadataEdnFile.exists()
        val hasJson = metadataJsonFile.exists()
        if (!hasEdn && !hasJson) return@forEach

        try {
            val entry = Entry()
            entry.filename = dir.name

            if (hasEdn) {
                val transfers = s.readEdnTransfers(dir.absolutePath)
                    .filter { !it.properties["_tombstone"].isTrue() }
                entry.numBoxes = transfers.size

                transfers.forEach { transfer ->
                    var contents = ""
                    var metadata = ""
                    val name =
                        s.resolveStoredValue(transfer.properties["name"], dir.absolutePath)?.toString() ?: ""
                    transfer.properties["code"]?.let {
                        contents = s.resolveStoredValue(it, dir.absolutePath)?.toString() ?: ""
                    }
                    transfer.properties["metadata"]?.let {
                        metadata = s.resolveStoredValue(it, dir.absolutePath)?.toString() ?: ""
                    }

                    if (contents.isNotBlank()) {
                        val c = (tagsForString(metadata, "tags") ?: emptyList())
                        entry.boxes.add(name to contents)
                        entry.metadata.add(name to c)
                    }
                }

                entry.lastModified = System.currentTimeMillis() - metadataEdnFile.lastModified()
            } else {
                val metadataContent = metadataJsonFile.readText()
                val transfers = SavingContext.format.decodeFromString<Set<Box_transfer>>(metadataContent)
                    .filter { !it.properties["_tombstone"].isTrue() }
                entry.numBoxes = transfers.size

                transfers.forEach { transfer ->
                    var contents = ""
                    var metadata = ""

                    try {
                        val name =
                            s.fromTypedPair(transfer.properties.get("name")!!, dir.absolutePath).toString()
                        transfer.properties.get("code")?.let {
                            contents = s.fromTypedPair(it, dir.absolutePath, createBoxes = false)?.toString() ?: ""
                        }
                        transfer.properties.get("metadata")?.let {
                            metadata = s.fromTypedPair(it, dir.absolutePath, createBoxes = false)?.toString() ?: ""
                        }

                        if (contents.isNotBlank()) {
                            val c = (tagsForString(metadata, "tags") ?: emptyList())
                            entry.boxes.add(name to contents)
                            entry.metadata.add(name to c)
                        }
                    } catch (e: NullPointerException) {
                        // we supress this for now
                        lprintln("warning,dir", "${entry.filename} failed to get name or code for ${transfer}")
                    }
                }

                entry.lastModified = System.currentTimeMillis() - metadataJsonFile.lastModified()
            }

            entries.add(entry)
        } catch (e: Exception) {
            // Skip directories with invalid metadata
            lprintln("error,dir", "Error processing directory ${dir.name}: ${e.message}")
        }
    }

    return entries
}

fun parseRecentDocuments(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private const val PROJECTS_DIR = "_projects"
private const val PROJECT_CONTENTS = "contents.edn"

fun updateRecentDocuments(raw: String?, documentName: String, maxEntries: Int = 200): String {
    val clean = documentName.trim()
    if (clean.isBlank()) return raw ?: ""

    val updated = mutableListOf(clean)
    parseRecentDocuments(raw).forEach {
        if (it != clean && updated.size < maxEntries) updated += it
    }
    return updated.take(maxEntries).joinToString("\n")
}

/**
 * A workspace document discovered by [welcomeDirCandidates], described only by cheap `stat`
 * metadata. Turning one into a [WelcomeEntry] (box count + tags) requires parsing its metadata
 * file, which [summarizeWelcomeCandidate] does lazily.
 */
data class WelcomeDirCandidate(
    val filename: String,
    val dir: File,
    val metadataFile: File,
    val lastModifiedEpochMs: Long,
    val sizeBytes: Long
)

private data class WelcomeSummaryCacheEntry(
    val lastModifiedEpochMs: Long,
    val sizeBytes: Long,
    val summary: WorkspaceDocumentSummary
)

// Parsed per-document summaries, keyed by metadata-file path and invalidated when the file's
// size or mtime changes. Bounded by the number of documents in the workspace; each value is a
// box count plus a short tag list, so it stays small. This is what turns the welcome screen from
// "re-parse every box of every document on every refresh" into "parse once, then O(N) stats".
private val welcomeSummaryCache = ConcurrentHashMap<String, WelcomeSummaryCacheEntry>()

private fun cachedWorkspaceDocumentSummary(
    savingContext: SavingContext,
    candidate: WelcomeDirCandidate
): WorkspaceDocumentSummary {
    val path = candidate.metadataFile.absolutePath
    welcomeSummaryCache[path]?.let { cached ->
        if (cached.lastModifiedEpochMs == candidate.lastModifiedEpochMs &&
            cached.sizeBytes == candidate.sizeBytes
        ) {
            return cached.summary
        }
    }
    val summary = readWorkspaceDocumentSummary(savingContext, candidate.dir, candidate.metadataFile)
    welcomeSummaryCache[path] = WelcomeSummaryCacheEntry(
        candidate.lastModifiedEpochMs, candidate.sizeBytes, summary
    )
    return summary
}

/**
 * Cheap first pass over the workspace: `stat` every document directory and order it the way the
 * welcome screen wants (recency first, then most-recently-modified). No metadata is parsed here,
 * so this stays fast even for large workspaces and lets callers stream summaries in final order.
 */
fun welcomeDirCandidates(base: String, recents: List<String>): List<WelcomeDirCandidate> {
    val baseDir = File(base)
    if (!baseDir.exists() || !baseDir.isDirectory) {
        return emptyList()
    }

    val recencyOrder = recents.withIndex().associate { it.value to it.index }
    val candidates = mutableListOf<WelcomeDirCandidate>()

    baseDir.listFiles()?.filter { it.isDirectory && it.name != PROJECTS_DIR }?.forEach { dir ->
        val metadataEdnFile = File(dir, SavingContext.METADATA_EDN)
        val metadataJsonFile = File(dir, SavingContext.METADATA_JSON)
        val metadataFile = when {
            metadataEdnFile.exists() -> metadataEdnFile
            metadataJsonFile.exists() -> metadataJsonFile
            else -> null
        } ?: return@forEach

        candidates += WelcomeDirCandidate(
            filename = dir.name,
            dir = dir,
            metadataFile = metadataFile,
            lastModifiedEpochMs = metadataFile.lastModified(),
            sizeBytes = metadataFile.length()
        )
    }

    return candidates.sortedWith(
        compareBy<WelcomeDirCandidate> { recencyOrder[it.filename] ?: Int.MAX_VALUE }
            .thenByDescending { it.lastModifiedEpochMs }
    )
}

private val welcomeScreenshotNamePattern =
    Regex("""^v(\d+)_(\d+)\.(png|jpg|jpeg)$""", RegexOption.IGNORE_CASE)

/**
 * Newest history screenshot for the channels matching [channelMatches], as a path relative to the
 * document directory (e.g. `history_screenshots/electron-window/v01984_....jpg`). Chosen by version
 * index then timestamp from the filename, so it is a cheap name-only scan with no per-file `stat`.
 * The `/history-screenshot` route validates the file contents when it is actually served.
 */
private fun latestWelcomeScreenshot(docDir: File, channelMatches: (String) -> Boolean): String {
    val root = File(docDir, "history_screenshots")
    val channelDirs = root.listFiles { file -> file.isDirectory && channelMatches(file.name) }
        ?.takeIf { it.isNotEmpty() } ?: return ""
    var best: File? = null
    var bestVersion = -1L
    var bestTimestamp = -1L
    channelDirs.forEach { channelDir ->
        channelDir.listFiles()?.forEach { file ->
            val match = welcomeScreenshotNamePattern.matchEntire(file.name) ?: return@forEach
            val version = match.groupValues[1].toLongOrNull() ?: return@forEach
            val timestamp = match.groupValues[2].toLongOrNull() ?: 0L
            if (version > bestVersion || (version == bestVersion && timestamp > bestTimestamp)) {
                best = file
                bestVersion = version
                bestTimestamp = timestamp
            }
        }
    }
    return best?.relativeTo(docDir)?.path?.replace(File.separatorChar, '/') ?: ""
}

/**
 * Expensive per-document step: parse (or reuse a cached parse of) the document metadata to obtain
 * its box count and tags. Returns null if the document can't be read.
 */
fun summarizeWelcomeCandidate(
    candidate: WelcomeDirCandidate,
    now: Long = System.currentTimeMillis(),
    savingContext: SavingContext = SavingContext()
): WelcomeEntry? {
    return try {
        val summary = cachedWorkspaceDocumentSummary(savingContext, candidate)
        WelcomeEntry(
            filename = candidate.filename,
            numBoxes = summary.numBoxes,
            sizeBytes = candidate.sizeBytes,
            lastModifiedEpochMs = candidate.lastModifiedEpochMs,
            lastModifiedAgoMs = now - candidate.lastModifiedEpochMs,
            tags = summary.tags,
            canvasThumbnail = latestWelcomeScreenshot(candidate.dir) { it == "electron-window" },
            fourThumbnail = latestWelcomeScreenshot(candidate.dir) {
                it == "four-window" || it.startsWith("four-window-")
            }
        )
    } catch (e: Exception) {
        lprintln("warning,welcome", "Skipping ${candidate.filename}: ${e.message}")
        null
    }
}

fun welcomeDir(base: String, recents: List<String>): List<WelcomeEntry> {
    val now = System.currentTimeMillis()
    val savingContext = SavingContext()
    return welcomeDirCandidates(base, recents)
        .mapNotNull { summarizeWelcomeCandidate(it, now, savingContext) }
}

/** Repo-relative location of the materialized agent-transcript starting points (see
 * [afield.examples.AgentTranscriptMaterializer]). One document folder per transcript. */
private const val STARTING_POINTS_REL = "public/docs/assets/agent_transcripts"

/** Repo-relative location of the bundled document templates (`remote`, `note`, …) that are also
 * offered on the welcome screen as a distinct "Templates" group of starting points. */
private const val TEMPLATES_REL = "templates"

/**
 * Locate the bundled "starting points" workspace — the materialized agent transcripts. These are
 * curated, CI-verified example documents that ship with Field (not in the user's workspace), so the
 * welcome screen and its search can offer them as ready-to-open scenes. Resolution:
 * `-Dafield.startingPoints.dir` override, else the repo-relative dir walked up from the working dir.
 * Returns null when not found (e.g. a packaged build without the docs assets) — callers degrade to
 * simply not offering starting points.
 */
fun startingPointsDir(): File? {
    System.getProperty("afield.startingPoints.dir")?.takeIf { it.isNotBlank() }?.let {
        return File(it).takeIf { d -> d.isDirectory }
    }
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val candidate = File(dir, STARTING_POINTS_REL)
        if (candidate.isDirectory) return candidate
        dir = dir.parentFile
    }
    return null
}

/** Welcome cards for the bundled starting points, tagged `kind = "starting-point"`. Empty when none. */
fun startingPoints(): List<WelcomeEntry> {
    val dir = startingPointsDir() ?: return emptyList()
    val now = System.currentTimeMillis()
    val savingContext = SavingContext()
    return welcomeDirCandidates(dir.absolutePath, emptyList())
        .mapNotNull { summarizeWelcomeCandidate(it, now, savingContext) }
        .map { it.copy(kind = "starting-point") }
}

/**
 * Locate the bundled document-template directory (`templates/`), whose self-contained document
 * subfolders (each carrying a `metadata.edn`/`metadata.json`) are surfaced on the welcome screen as
 * a distinct "Templates" group of starting points. Resolution mirrors [startingPointsDir]:
 * `-Dafield.templates.dir` override, else the repo-relative dir walked up from the working dir.
 * Returns null when not found (e.g. a packaged build without the bundled templates).
 */
fun templatesStartingPointsDir(): File? {
    System.getProperty("afield.templates.dir")?.takeIf { it.isNotBlank() }?.let {
        return File(it).takeIf { d -> d.isDirectory }
    }
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val candidate = File(dir, TEMPLATES_REL)
        if (candidate.isDirectory) return candidate
        dir = dir.parentFile
    }
    return null
}

/** Welcome cards for the bundled document templates, tagged `kind = "template"`. Empty when none. */
fun templateStartingPoints(): List<WelcomeEntry> {
    val dir = templatesStartingPointsDir() ?: return emptyList()
    val now = System.currentTimeMillis()
    val savingContext = SavingContext()
    return welcomeDirCandidates(dir.absolutePath, emptyList())
        .mapNotNull { summarizeWelcomeCandidate(it, now, savingContext) }
        .map { it.copy(kind = "template") }
}

/** Text-search the bundled document templates, tagging every result `kind = "template"`. */
fun searchTemplateStartingPoints(
    rawQuery: String,
    maxDocuments: Int = 50,
    maxMatchesPerDocument: Int = 5,
    maxScanMillis: Long = 3_000L,
    cancellationCheck: () -> Unit = {}
): WorkspaceSearchResponse {
    val dir = templatesStartingPointsDir()
        ?: return WorkspaceSearchResponse(rawQuery.trim(), emptyList(), 0, 0, false)
    val response = searchWorkspace(
        base = dir.absolutePath,
        rawQuery = rawQuery,
        maxDocuments = maxDocuments,
        maxMatchesPerDocument = maxMatchesPerDocument,
        maxScanMillis = maxScanMillis,
        cancellationCheck = cancellationCheck
    )
    return response.copy(documents = response.documents.map { it.copy(kind = "template") })
}

/** Text-search the bundled starting points, tagging every result `kind = "starting-point"`. */
fun searchStartingPoints(
    rawQuery: String,
    maxDocuments: Int = 50,
    maxMatchesPerDocument: Int = 5,
    maxScanMillis: Long = 3_000L,
    cancellationCheck: () -> Unit = {}
): WorkspaceSearchResponse {
    val dir = startingPointsDir()
        ?: return WorkspaceSearchResponse(rawQuery.trim(), emptyList(), 0, 0, false)
    val response = searchWorkspace(
        base = dir.absolutePath,
        rawQuery = rawQuery,
        maxDocuments = maxDocuments,
        maxMatchesPerDocument = maxMatchesPerDocument,
        maxScanMillis = maxScanMillis,
        cancellationCheck = cancellationCheck
    )
    return response.copy(documents = response.documents.map { it.copy(kind = "starting-point") })
}

fun projectsDir(base: String): File {
    val baseDir = File(base).canonicalFile
    val dir = File(baseDir, PROJECTS_DIR).canonicalFile
    require(dir.toPath().startsWith(baseDir.toPath())) { "projects directory is outside workspace" }
    return dir
}

private fun validateWorkspaceProjectName(name: String): String {
    val clean = name.trim()
    require(clean.isNotBlank()) { "missing project name" }
    require(!clean.contains('/') && !clean.contains('\\') && !clean.contains('\u0000')) {
        "invalid project name"
    }
    require(!clean.contains("..") && !clean.startsWith(".") && !clean.endsWith(" ")) {
        "invalid project name"
    }
    require(clean != PROJECTS_DIR) { "reserved project name" }

    val reservedNames = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    )
    require(clean.lowercase() !in reservedNames) { "reserved project name" }
    return clean
}

private fun workspaceProjectDir(base: String, name: String): File {
    val cleanName = validateWorkspaceProjectName(name)
    val root = projectsDir(base)
    val projectDir = File(root, cleanName).canonicalFile
    require(projectDir.toPath().startsWith(root.toPath())) { "project is outside workspace" }
    return projectDir
}

private fun normalizeProjectDocuments(base: String, documents: List<String>, requireExisting: Boolean = true): List<String> {
    val seen = linkedSetOf<String>()
    documents.forEach { raw ->
        val name = validateWorkspaceDocumentName(raw)
        if (requireExisting) {
            val dir = workspaceDocumentDir(base, name)
            require(dir.isDirectory) { "document '$name' not found" }
            require(File(dir, SavingContext.METADATA_EDN).isFile || File(dir, SavingContext.METADATA_JSON).isFile) {
                "document '$name' has no metadata"
            }
        }
        seen += name
    }
    return seen.toList().sortedWith(String.CASE_INSENSITIVE_ORDER)
}

private fun readProjectDocuments(contents: File): List<String> {
    if (!contents.isFile) return emptyList()
    val root = Edn.read(contents.readText())
    @Suppress("UNCHECKED_CAST")
    val map = root as? Map<Any?, Any?> ?: return emptyList()
    val rawDocuments = map[":documents"] ?: map["documents"] ?: return emptyList()
    @Suppress("UNCHECKED_CAST")
    return (rawDocuments as? List<Any?>)
        ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
        .orEmpty()
}

private fun writeProjectDocuments(contents: File, documents: List<String>) {
    contents.parentFile?.mkdirs()
    contents.writeText("{:documents ${Edn.writeList(documents)}}\n")
}

private fun projectEntry(base: String, projectDir: File, now: Long = System.currentTimeMillis()): ProjectEntry {
    val contents = File(projectDir, PROJECT_CONTENTS)
    val rawDocuments = readProjectDocuments(contents)
    val documents = normalizeProjectDocuments(base, rawDocuments, requireExisting = false)
    if (documents != rawDocuments) {
        writeProjectDocuments(contents, documents)
    }
    val modifiedAt = contents.takeIf { it.isFile }?.lastModified() ?: projectDir.lastModified()
    return ProjectEntry(
        name = projectDir.name,
        documents = documents,
        sizeBytes = contents.takeIf { it.isFile }?.length() ?: 0L,
        lastModifiedEpochMs = modifiedAt,
        lastModifiedAgoMs = now - modifiedAt
    )
}

fun listWorkspaceProjects(base: String): List<ProjectEntry> {
    val root = projectsDir(base)
    if (!root.exists()) return emptyList()
    if (!root.isDirectory) return emptyList()
    val now = System.currentTimeMillis()
    return root.listFiles()
        ?.filter { it.isDirectory && File(it, PROJECT_CONTENTS).isFile }
        ?.mapNotNull { dir ->
            runCatching { projectEntry(base, dir, now) }
                .onFailure { lprintln("warning,welcome", "Skipping project ${dir.name}: ${it.message}") }
                .getOrNull()
        }
        ?.sortedWith(compareByDescending<ProjectEntry> { it.lastModifiedEpochMs }.thenBy { it.name.lowercase() })
        .orEmpty()
}

fun createWorkspaceProject(base: String, name: String, documents: List<String> = emptyList()): ProjectMutationResponse {
    val cleanName = validateWorkspaceProjectName(name)
    val dir = workspaceProjectDir(base, cleanName)
    require(!dir.exists()) { "project already exists" }
    require(dir.mkdirs()) { "could not create project" }
    val normalizedDocuments = normalizeProjectDocuments(base, documents)
    val contents = File(dir, PROJECT_CONTENTS)
    writeProjectDocuments(contents, normalizedDocuments)
    return ProjectMutationResponse(
        project = projectEntry(base, dir),
        changed = true,
        message = "Project created."
    )
}

fun addDocumentToWorkspaceProject(base: String, projectName: String, filename: String): ProjectMutationResponse {
    val dir = workspaceProjectDir(base, projectName)
    require(dir.isDirectory) { "project not found" }
    val contents = File(dir, PROJECT_CONTENTS)
    val existing = readProjectDocuments(contents)
    val normalized = normalizeProjectDocuments(base, existing + filename)
    val changed = normalized != normalizeProjectDocuments(base, existing)
    if (changed) writeProjectDocuments(contents, normalized)
    return ProjectMutationResponse(
        project = projectEntry(base, dir),
        changed = changed,
        message = if (changed) "Document added to project." else "Document already in project."
    )
}

fun deleteWorkspaceProject(base: String, name: String): ProjectMutationResponse {
    val dir = workspaceProjectDir(base, name)
    require(dir.isDirectory) { "project not found" }
    val before = projectEntry(base, dir)
    require(dir.deleteRecursively()) { "could not delete project" }
    return ProjectMutationResponse(
        project = before,
        changed = true,
        message = "Project deleted."
    )
}

private data class SearchOptions(
    val maxDocuments: Int = 50,
    val maxMatchesPerDocument: Int = 5,
    val maxScanMillis: Long = 3_000L
)

private class WorkspaceSearchBudgetExceeded : RuntimeException()

private data class WorkspaceDocumentScan(
    val summary: WorkspaceDocumentSummary,
    val matches: List<WorkspaceSearchMatch>
)

private val workspaceSearchProperties = linkedSetOf(
    "name",
    "code",
    "fragment",
    "vertex",
    "vertex_fragment",
    "compute",
    "task",
    "mesh",
    "raygen",
    "miss",
    "closesthit",
    "anyhit",
    "intersection",
    "callable",
    "fragment_s",
    "vertex_s",
    "compute_s",
    "task_s",
    "mesh_s",
    "raygen_s",
    "miss_s",
    "closesthit_s",
    "anyhit_s",
    "intersection_s",
    "callable_s",
    "customization",
    "remote_js",
    "metadata"
)

private fun workspaceSearchSidecarSuffixes(): List<String> {
    return workspaceSearchProperties.mapNotNull { property ->
        Box.ioSeparateProperties[property]?.let { extension -> "_${property}_.${extension}" }
    }
}

private fun workspaceSearchCandidateFiles(dir: File, metadataFile: File): Sequence<File> = sequence {
    yield(metadataFile)
    val suffixes = workspaceSearchSidecarSuffixes()
    dir.listFiles()?.forEach { file ->
        if (!file.isFile) return@forEach
        if (file == metadataFile) return@forEach
        if (suffixes.any { suffix -> file.name.endsWith(suffix) }) {
            yield(file)
        }
    }
}

private fun documentMayContainQuery(
    dir: File,
    metadataFile: File,
    query: String,
    checkSearchActive: () -> Unit
): Boolean {
    checkSearchActive()
    if (dir.name.lowercase().contains(query)) return true

    workspaceSearchCandidateFiles(dir, metadataFile).forEach { file ->
        checkSearchActive()
        if (fileContainsQuery(file, query, checkSearchActive)) return true
    }
    return false
}

private fun fileContainsQuery(file: File, query: String, checkSearchActive: () -> Unit): Boolean {
    if (!file.isFile) return false

    return try {
        var found = false
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            var lineNumber = 0
            for (line in lines) {
                if (lineNumber++ % 128 == 0) checkSearchActive()
                if (line.lowercase().contains(query)) {
                    found = true
                    break
                }
            }
        }
        found
    } catch (_: Exception) {
        false
    }
}

fun searchWorkspace(
    base: String,
    rawQuery: String,
    maxDocuments: Int = 50,
    maxMatchesPerDocument: Int = 5,
    maxScanMillis: Long = 3_000L,
    cancellationCheck: () -> Unit = {}
): WorkspaceSearchResponse {
    val query = rawQuery.trim()
    if (query.isBlank()) {
        return WorkspaceSearchResponse(
            query = "",
            documents = emptyList(),
            totalDocuments = 0,
            totalMatches = 0,
            truncated = false
        )
    }

    val baseDir = File(base)
    if (!baseDir.exists() || !baseDir.isDirectory) {
        return WorkspaceSearchResponse(
            query = query,
            documents = emptyList(),
            totalDocuments = 0,
            totalMatches = 0,
            truncated = false
        )
    }

    val options = SearchOptions(
        maxDocuments = maxDocuments.coerceIn(1, 200),
        maxMatchesPerDocument = maxMatchesPerDocument.coerceIn(1, 20),
        maxScanMillis = maxScanMillis.coerceIn(250L, 15_000L)
    )
    val now = System.currentTimeMillis()
    val startedAt = System.nanoTime()
    val searchTerm = query.lowercase()
    val savingContext = SavingContext()
    val results = mutableListOf<WorkspaceSearchResult>()
    var totalMatches = 0
    var truncated = false

    fun checkSearchActive() {
        cancellationCheck()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
        if (elapsedMillis > options.maxScanMillis) {
            throw WorkspaceSearchBudgetExceeded()
        }
    }

    val directories = baseDir.listFiles()?.filter { it.isDirectory && it.name != PROJECTS_DIR }?.sortedByDescending {
        max(
            File(it, SavingContext.METADATA_EDN).lastModified(),
            File(it, SavingContext.METADATA_JSON).lastModified()
        )
    }.orEmpty()

    for (dir in directories) {
        checkSearchActive()
        if (results.size >= options.maxDocuments) {
            truncated = true
            break
        }

        val metadataEdnFile = File(dir, SavingContext.METADATA_EDN)
        val metadataJsonFile = File(dir, SavingContext.METADATA_JSON)
        val metadataFile = when {
            metadataEdnFile.exists() -> metadataEdnFile
            metadataJsonFile.exists() -> metadataJsonFile
            else -> null
        }
        if (metadataFile == null) continue

        try {
            if (!documentMayContainQuery(dir, metadataFile, searchTerm, ::checkSearchActive)) {
                continue
            }

            val scan = if (metadataFile.name == SavingContext.METADATA_EDN) {
                scanEdnDocument(savingContext, dir.absolutePath, searchTerm, options, ::checkSearchActive)
            } else {
                scanJsonDocument(savingContext, metadataJsonFile, dir.absolutePath, searchTerm, options, ::checkSearchActive)
            }
            checkSearchActive()

            val filenameMatches = buildMatchesForText(
                boxName = dir.name,
                property = "document",
                text = dir.name,
                query = searchTerm,
                maxMatches = options.maxMatchesPerDocument,
                checkSearchActive = ::checkSearchActive
            )

            val tagMatches = buildMatchesForText(
                boxName = dir.name,
                property = "tags",
                text = scan.summary.tags.joinToString(" "),
                query = searchTerm,
                maxMatches = options.maxMatchesPerDocument,
                checkSearchActive = ::checkSearchActive
            )
            val combined = (filenameMatches + tagMatches + scan.matches).take(options.maxMatchesPerDocument)
            if (combined.isEmpty()) continue

            totalMatches += combined.size
            val modifiedAt = metadataFile.lastModified()
            results += WorkspaceSearchResult(
                filename = dir.name,
                numBoxes = scan.summary.numBoxes,
                lastModifiedEpochMs = modifiedAt,
                lastModifiedAgoMs = now - modifiedAt,
                tags = scan.summary.tags,
                matches = combined
            )
        } catch (e: WorkspaceSearchBudgetExceeded) {
            truncated = true
            break
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lprintln("warning,workspace-search", "Skipping ${dir.name}: ${e.message}")
        }
    }

    for (project in listWorkspaceProjects(base)) {
        checkSearchActive()
        if (results.size >= options.maxDocuments) {
            truncated = true
            break
        }

        val filenameMatches = buildMatchesForText(
            boxName = project.name,
            property = "project",
            text = project.name,
            query = searchTerm,
            maxMatches = options.maxMatchesPerDocument,
            checkSearchActive = ::checkSearchActive
        )
        val documentMatches = buildMatchesForText(
            boxName = project.name,
            property = "documents",
            text = project.documents.joinToString(" "),
            query = searchTerm,
            maxMatches = options.maxMatchesPerDocument,
            checkSearchActive = ::checkSearchActive
        )
        val combined = (filenameMatches + documentMatches).take(options.maxMatchesPerDocument)
        if (combined.isEmpty()) continue

        totalMatches += combined.size
        results += WorkspaceSearchResult(
            filename = project.name,
            numBoxes = project.documents.size,
            lastModifiedEpochMs = project.lastModifiedEpochMs,
            lastModifiedAgoMs = project.lastModifiedAgoMs,
            tags = emptyList(),
            matches = combined,
            kind = "project",
            documents = project.documents
        )
    }

    return WorkspaceSearchResponse(
        query = query,
        documents = results,
        totalDocuments = results.size,
        totalMatches = totalMatches,
        truncated = truncated
    )
}

private data class WorkspaceDocumentSummary(
    val numBoxes: Int,
    val tags: List<String>
)

private fun readWorkspaceDocumentSummary(
    savingContext: SavingContext,
    dir: File,
    metadataFile: File
): WorkspaceDocumentSummary {
    return if (metadataFile.name == SavingContext.METADATA_EDN) {
        val transfers = savingContext.readEdnTransfers(dir.absolutePath)
            .filter { !it.properties["_tombstone"].isTrue() }
        WorkspaceDocumentSummary(
            numBoxes = transfers.size,
            tags = collectDocumentTagsFromEdn(transfers) { value ->
                savingContext.resolveStoredValue(value, dir.absolutePath)?.toString()
            }
        )
    } else {
        val metadataContent = metadataFile.readText()
        val transfers = SavingContext.format.decodeFromString<Set<Box_transfer>>(metadataContent)
            .filter { !it.properties["_tombstone"].isTrue() }
        WorkspaceDocumentSummary(
            numBoxes = transfers.size,
            tags = collectDocumentTagsFromJson(transfers) { value ->
                @Suppress("UNCHECKED_CAST")
                val typed = value as? Pair<String, JsonElement> ?: return@collectDocumentTagsFromJson null
                savingContext.fromTypedPair(typed, dir.absolutePath, createBoxes = false)?.toString()
            }
        )
    }
}

private fun collectDocumentTagsFromEdn(
    transfers: List<BoxTransferEdn>,
    resolve: (Any?) -> String?
): List<String> {
    val rootTags = transfers
        .firstOrNull { it.id == "root" }
        ?.properties
        ?.get("metadata")
        ?.let(resolve)
        ?.let { tagsForString(it, "tags") }
        .orEmpty()

    val aggregateTags = linkedSetOf<String>()
    transfers.forEach { transfer ->
        val metadata = resolve(transfer.properties["metadata"]) ?: return@forEach
        tagsForString(metadata, "tags")?.forEach { aggregateTags += it }
    }

    return normalizeDocumentTags(rootTags.ifEmpty { aggregateTags.toList() })
}

private fun collectDocumentTagsFromJson(
    transfers: List<Box_transfer>,
    resolve: (Any?) -> String?
): List<String> {
    val rootTags = transfers
        .firstOrNull { it.id == "root" }
        ?.properties
        ?.get("metadata")
        ?.let(resolve)
        ?.let { tagsForString(it, "tags") }
        .orEmpty()

    val aggregateTags = linkedSetOf<String>()
    transfers.forEach { transfer ->
        val metadata = resolve(transfer.properties["metadata"]) ?: return@forEach
        tagsForString(metadata, "tags")?.forEach { aggregateTags += it }
    }

    return normalizeDocumentTags(rootTags.ifEmpty { aggregateTags.toList() })
}

fun normalizeDocumentTags(tags: List<String>): List<String> {
    val seen = linkedSetOf<String>()
    tags.forEach { raw ->
        val tag = raw.trim()
            .replace(Regex("\\s+"), "-")
            .trim('-', ',', ';')
        if (tag.isNotBlank()) seen += tag
    }
    return seen.toList().sortedWith(String.CASE_INSENSITIVE_ORDER)
}

private fun validateWorkspaceDocumentName(name: String): String {
    val clean = name.trim()
    require(clean.isNotBlank()) { "missing document filename" }
    require(!clean.contains('/') && !clean.contains('\\') && !clean.contains('\u0000')) {
        "invalid document filename"
    }
    require(!clean.contains("..") && !clean.startsWith(".") && !clean.endsWith(" ")) {
        "invalid document filename"
    }

    val reservedNames = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    )
    require(clean.lowercase() !in reservedNames) { "reserved document filename" }
    return clean
}

private fun workspaceDocumentDir(base: String, filename: String): File {
    val cleanName = validateWorkspaceDocumentName(filename)
    val baseDir = File(base).canonicalFile
    val documentDir = File(baseDir, cleanName).canonicalFile
    require(documentDir.toPath().startsWith(baseDir.toPath())) { "document is outside workspace" }
    return documentDir
}

fun resolveWorkspaceDocumentDir(base: String, filename: String): File {
    val documentDir = workspaceDocumentDir(base, filename)
    require(documentDir.isDirectory) { "document not found" }
    require(File(documentDir, SavingContext.METADATA_EDN).isFile || File(documentDir, SavingContext.METADATA_JSON).isFile) {
        "document has no metadata"
    }
    return documentDir
}

fun updateDocumentTags(base: String, filename: String, tags: List<String>): DocumentTagsUpdateResponse {
    val cleanName = validateWorkspaceDocumentName(filename)
    val documentDir = workspaceDocumentDir(base, cleanName)
    require(documentDir.isDirectory) { "document not found" }
    require(File(documentDir, SavingContext.METADATA_EDN).isFile) {
        "tag editing is only supported for EDN-backed documents"
    }

    val normalizedTags = normalizeDocumentTags(tags)
    val existingMetadata = try {
        val savingContext = SavingContext()
        val root = savingContext.readEdnTransfers(documentDir.absolutePath)
            .firstOrNull { it.id == "root" }
        root?.properties?.get("metadata")?.let {
            savingContext.resolveStoredValue(it, documentDir.absolutePath)?.toString()
        }.orEmpty()
    } catch (_: Exception) {
        ""
    }

    val updatedMetadata = replaceTagsInMetadata(existingMetadata, normalizedTags)
    val changed = SavingContext().updateRootMetadata(documentDir.absolutePath, updatedMetadata)
    return DocumentTagsUpdateResponse(cleanName, normalizedTags, changed)
}

/**
 * Copy a bundled starting point (a materialized agent transcript, see [startingPointsDir]) into the
 * user's workspace under a fresh, non-colliding name and return it, so the welcome screen can offer
 * "open" on a read-only bundled scene without ever mutating the bundle. Reuses the same recursive
 * copy [duplicateWorkspaceDocument] uses (skipping `backups`/`.git`/`versions.edn`).
 */
fun openStartingPointIntoWorkspace(base: String, name: String): DuplicateDocumentResponse {
    val cleanName = validateWorkspaceDocumentName(name)
    // A starting point can live in either bundled root: the materialized agent transcripts or the
    // document templates. Search both (transcripts first) for a matching, in-bounds document dir.
    val roots = listOfNotNull(startingPointsDir(), templatesStartingPointsDir())
    if (roots.isEmpty()) throw IllegalArgumentException("no starting points are available")
    val sourceDir = roots.asSequence()
        .map { root -> root to File(root, cleanName).canonicalFile }
        .firstOrNull { (root, dir) ->
            dir.toPath().startsWith(root.canonicalFile.toPath()) && dir.isDirectory &&
                (File(dir, SavingContext.METADATA_EDN).isFile || File(dir, SavingContext.METADATA_JSON).isFile)
        }?.second
        ?: throw IllegalArgumentException("starting point not found")

    // Pick a workspace name that does not collide (name, name-2, name-3, …).
    val baseDir = File(base).canonicalFile
    var target = cleanName
    var suffix = 2
    while (File(baseDir, target).exists()) {
        target = "$cleanName-$suffix"
        suffix++
    }
    val targetDir = File(baseDir, target).canonicalFile
    require(targetDir.toPath().startsWith(baseDir.toPath())) { "target is outside workspace" }
    require(targetDir.mkdirs()) { "could not create workspace copy" }

    try {
        sourceDir.walkTopDown()
            .onEnter { dir -> dir == sourceDir || dir.name !in STARTING_POINT_COPY_SKIP_DIRS }
            .forEach { source ->
                if (source == sourceDir) return@forEach
                if (source.isDirectory && source.name in STARTING_POINT_COPY_SKIP_DIRS) return@forEach
                if (source.name == "versions.edn" || source.name == ".DS_Store") return@forEach
                val relative = source.relativeTo(sourceDir)
                val dest = File(targetDir, relative.path)
                if (source.isDirectory) dest.mkdirs()
                else { dest.parentFile?.mkdirs(); source.copyTo(dest, overwrite = false) }
            }
    } catch (e: Exception) {
        targetDir.deleteRecursively()
        throw e
    }

    val metadataFile = when {
        File(targetDir, SavingContext.METADATA_EDN).isFile -> File(targetDir, SavingContext.METADATA_EDN)
        File(targetDir, SavingContext.METADATA_JSON).isFile -> File(targetDir, SavingContext.METADATA_JSON)
        else -> throw IllegalStateException("workspace copy was created without metadata")
    }
    val summary = readWorkspaceDocumentSummary(SavingContext(), targetDir, metadataFile)
    return DuplicateDocumentResponse(
        filename = target,
        numBoxes = summary.numBoxes,
        sizeBytes = metadataFile.length(),
        tags = summary.tags
    )
}

/**
 * Directories never copied when opening a bundled starting point (or template) into the workspace:
 * version-history/backup scaffolding and VCS metadata. `backup` (singular) matters for the bundled
 * templates — `templates/remote/backup/` holds hundreds of history files that must not be cloned.
 */
private val STARTING_POINT_COPY_SKIP_DIRS = setOf("backups", "backup", ".git")

fun duplicateWorkspaceDocument(base: String, sourceFilename: String, newFilename: String): DuplicateDocumentResponse {
    val cleanSource = validateWorkspaceDocumentName(sourceFilename)
    val cleanTarget = validateWorkspaceDocumentName(newFilename)
    val sourceDir = workspaceDocumentDir(base, cleanSource)
    val targetDir = workspaceDocumentDir(base, cleanTarget)

    require(sourceDir.isDirectory) { "source document not found" }
    require(File(sourceDir, SavingContext.METADATA_EDN).isFile || File(sourceDir, SavingContext.METADATA_JSON).isFile) {
        "source document has no metadata"
    }
    require(!targetDir.exists()) { "target document already exists" }
    require(targetDir.mkdirs()) { "could not create duplicate document" }

    try {
        sourceDir.walkTopDown()
            .onEnter { dir -> dir == sourceDir || (dir.name != "backups" && dir.name != ".git") }
            .forEach { source ->
                if (source == sourceDir) return@forEach
                if (source.isDirectory && (source.name == "backups" || source.name == ".git")) return@forEach
                if (source.name == "versions.edn") return@forEach
                val relative = source.relativeTo(sourceDir)
                val target = File(targetDir, relative.path)
                if (source.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = false)
                }
            }
    } catch (e: Exception) {
        targetDir.deleteRecursively()
        throw e
    }

    val metadataFile = when {
        File(targetDir, SavingContext.METADATA_EDN).isFile -> File(targetDir, SavingContext.METADATA_EDN)
        File(targetDir, SavingContext.METADATA_JSON).isFile -> File(targetDir, SavingContext.METADATA_JSON)
        else -> throw IllegalStateException("duplicate was created without metadata")
    }
    val summary = readWorkspaceDocumentSummary(SavingContext(), targetDir, metadataFile)
    return DuplicateDocumentResponse(
        filename = cleanTarget,
        numBoxes = summary.numBoxes,
        sizeBytes = metadataFile.length(),
        tags = summary.tags
    )
}

fun exportTaggedDocumentsZip(base: String, rawTag: String): TaggedDocumentsZip {
    val tag = normalizeDocumentTags(listOf(rawTag)).firstOrNull()
        ?: throw IllegalArgumentException("missing tag")
    val baseDir = File(base).canonicalFile
    require(baseDir.isDirectory) { "workspace not found" }

    val documents = welcomeDir(base, emptyList())
        .filter { doc -> doc.tags.any { it.equals(tag, ignoreCase = true) } }
        .sortedBy { it.filename.lowercase() }

    require(documents.isNotEmpty()) { "no documents with tag '$tag'" }

    val output = ByteArrayOutputStream()
    var count = 0
    ZipOutputStream(output).use { zip ->
        documents.forEach { doc ->
            val dir = workspaceDocumentDir(base, doc.filename)
            val metadata = File(dir, SavingContext.METADATA_EDN)
            if (!metadata.isFile) return@forEach
            val documentName = zipSafePathSegment(doc.filename)
            zip.putNextEntry(ZipEntry("$documentName/${SavingContext.METADATA_EDN}"))
            metadata.inputStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()
            count += 1
        }
    }

    require(count > 0) { "no EDN-backed documents with tag '$tag'" }

    return TaggedDocumentsZip(
        filename = "field4-${downloadSafeName(tag)}-documents.zip",
        bytes = output.toByteArray(),
        documentCount = count
    )
}

private fun zipSafePathSegment(value: String): String {
    return value.trim()
        .replace(Regex("[\\\\/]+"), "_")
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .ifBlank { "document" }
}

private fun downloadSafeName(value: String): String {
    return value.trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-', '.')
        .ifBlank { "tagged" }
}

private fun replaceTagsInMetadata(metadata: String, tags: List<String>): String {
    val yaml = Yaml()
    val existing = try {
        @Suppress("UNCHECKED_CAST")
        yaml.load(metadata) as? Map<String, Any?>
    } catch (_: Exception) {
        null
    }

    val updated = linkedMapOf<String, Any?>()
    existing?.forEach { (key, value) ->
        if (key != "tags") updated[key] = value
    }
    if (tags.isNotEmpty()) {
        updated["tags"] = tags
    }

    if (updated.isEmpty()) return ""
    return yaml.dump(updated).trimEnd() + "\n"
}

private fun scanEdnDocument(
    savingContext: SavingContext,
    directory: String,
    query: String,
    options: SearchOptions,
    checkSearchActive: () -> Unit
): WorkspaceDocumentScan {
    val matches = mutableListOf<WorkspaceSearchMatch>()
    checkSearchActive()
    val transfers = savingContext.readEdnTransfers(directory)
        .filter { !it.properties["_tombstone"].isTrue() }
    val tags = collectDocumentTagsFromEdn(transfers) { value ->
        checkSearchActive()
        savingContext.resolveStoredValue(value, directory)?.toString()
    }

    for (transfer in transfers) {
        checkSearchActive()
        val boxName = savingContext.resolveStoredValue(transfer.properties["name"], directory)?.toString().orEmpty()
        collectPropertyMatches(
            propertyProvider = { property ->
                checkSearchActive()
                savingContext.resolveStoredValue(transfer.properties[property], directory)?.toString()
            },
            boxName = boxName,
            query = query,
            matches = matches,
            maxMatches = options.maxMatchesPerDocument,
            checkSearchActive = checkSearchActive
        )
        if (matches.size >= options.maxMatchesPerDocument) break
    }

    return WorkspaceDocumentScan(
        summary = WorkspaceDocumentSummary(transfers.size, tags),
        matches = matches
    )
}

private fun scanJsonDocument(
    savingContext: SavingContext,
    metadataJsonFile: File,
    directory: String,
    query: String,
    options: SearchOptions,
    checkSearchActive: () -> Unit
): WorkspaceDocumentScan {
    val matches = mutableListOf<WorkspaceSearchMatch>()
    checkSearchActive()
    val metadataContent = metadataJsonFile.readText()
    val transfers = SavingContext.format.decodeFromString<Set<Box_transfer>>(metadataContent)
        .filter { !it.properties["_tombstone"].isTrue() }
    val tags = collectDocumentTagsFromJson(transfers) { value ->
        checkSearchActive()
        @Suppress("UNCHECKED_CAST")
        val typed = value as? Pair<String, JsonElement> ?: return@collectDocumentTagsFromJson null
        savingContext.fromTypedPair(typed, directory, createBoxes = false)?.toString()
    }

    for (transfer in transfers) {
        checkSearchActive()
        val boxName = try {
            savingContext.fromTypedPair(transfer.properties["name"]!!, directory)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
        collectPropertyMatches(
            propertyProvider = { property ->
                checkSearchActive()
                transfer.properties[property]?.let {
                    savingContext.fromTypedPair(it, directory, createBoxes = false)?.toString()
                }
            },
            boxName = boxName,
            query = query,
            matches = matches,
            maxMatches = options.maxMatchesPerDocument,
            checkSearchActive = checkSearchActive
        )
        if (matches.size >= options.maxMatchesPerDocument) break
    }

    return WorkspaceDocumentScan(
        summary = WorkspaceDocumentSummary(transfers.size, tags),
        matches = matches
    )
}

private fun collectPropertyMatches(
    propertyProvider: (String) -> String?,
    boxName: String,
    query: String,
    matches: MutableList<WorkspaceSearchMatch>,
    maxMatches: Int,
    checkSearchActive: () -> Unit
) {
    workspaceSearchProperties.forEach { property ->
        checkSearchActive()
        if (matches.size >= maxMatches) return
        val text = propertyProvider(property) ?: return@forEach
        val remaining = maxMatches - matches.size
        matches += buildMatchesForText(boxName, property, text, query, remaining, checkSearchActive)
    }
}

private fun buildMatchesForText(
    boxName: String,
    property: String,
    text: String,
    query: String,
    maxMatches: Int,
    checkSearchActive: () -> Unit = {}
): List<WorkspaceSearchMatch> {
    if (text.isBlank() || maxMatches <= 0) return emptyList()

    val lines = text.split('\n')
    val results = mutableListOf<WorkspaceSearchMatch>()

    lines.forEachIndexed { index, rawLine ->
        if (index % 64 == 0) checkSearchActive()
        if (results.size >= maxMatches) return@forEachIndexed
        val line = rawLine.trim()
        if (!line.lowercase().contains(query)) return@forEachIndexed

        results += WorkspaceSearchMatch(
            boxName = boxName.ifBlank { "(unnamed box)" },
            property = property,
            line = index + 1,
            snippet = trimSnippet(rawLine, query)
        )
    }

    return results
}

private fun trimSnippet(line: String, query: String, maxLength: Int = 180): String {
    val trimmedLine = line.trim()
    if (trimmedLine.isBlank()) return ""

    val lowered = trimmedLine.lowercase()
    val startIndex = lowered.indexOf(query)
    if (startIndex < 0 || trimmedLine.length <= maxLength) return trimmedLine

    val context = maxLength / 2
    val sliceStart = max(0, startIndex - context)
    val sliceEnd = min(trimmedLine.length, sliceStart + maxLength)
    val prefix = if (sliceStart > 0) "…" else ""
    val suffix = if (sliceEnd < trimmedLine.length) "…" else ""
    return prefix + trimmedLine.substring(sliceStart, sliceEnd).trim() + suffix
}
