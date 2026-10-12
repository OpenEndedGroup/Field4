package afield.ai.agent

import afield.ReadOnlyDocuments
import afield.SessionInformation
import afield.ai.core.SummarisesForToolLog
import afield.ai.core.AwaitedBuild
import afield.ai.core.SuspendsTurn
import afield.ai.core.TurnSuspension
import afield.ai.core.AgentToolMeta
import afield.ai.core.ToolExposure
import afield.ai.core.ToolEffect
import afield.ai.core.ToolRegistry
import afield.ai.core.LlmProgressUpdate
import afield.stdlib.BoxStudyBuildBinding
import afield.stdlib.StudyBuildArtifact
import afield.stdlib.StudyBuildRecord
import afield.stdlib.StudyBuilds
import afield.stdlib.StudyBuildStatus
import afield.stdlib.StudyStageState
import afield.stdlib.StudyBuildFinding
import afield.stdlib.StudyBuildNotice
import afield.stdlib.StudyBuildNoticeKind
import afield.stdlib.studyBuildGeometryView
import afield.stdlib.studyBuildUsedEarlierInputs
import afield.ai.core.aiToolMapper
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.module.kotlin.convertValue

@JsonClassDescription("List the live derived-stage build runners in this document. Returns success plus, per runner, its source box id, name, dependency currency, current results and latest attempt. Use it when you need a runner's boxId and name and the task does not give them. A document with no runners returns an empty list, not an error. e.g. a result entry has boxId = `a1b2`, name = `mesh`.")
@AgentToolMeta("builds", ToolExposure.DIRECT, effects = [ToolEffect.READ_DOCUMENT, ToolEffect.FILESYSTEM_READ])
data class ListStudyBuildsRequest(val unused: Int = 0)

@JsonClassDescription("Inspect a document build runner's stages and paged durable attempt history, or one jobId. Returns each stage with its fingerprint and currency, what a waiting stage waits for (inputHelp, such as a closed gate's measurement), the attempts with their requests, structured failure suggestions and retained artifact identities. Running, succeeded and current are different: an old success can be stale after edits. Use it before StartStudyBuildRequest, which needs the stage fingerprint from here. This does not start or retry work. An unknown boxId/name fails with success=false and says to list runners. e.g. boxId = `a1b2`, name = `mesh`, limit = 5.")
@AgentToolMeta("builds", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT, ToolEffect.FILESYSTEM_READ])
data class InspectStudyBuildsRequest(val boxId: String, val name: String, val jobId: String = "", val offset: Int = 0, val limit: Int = 10)

@JsonClassDescription("Wait up to 900 seconds for an existing build job to finish, streaming real progress into chat without additional model turns. Use it when a StartStudyBuildRequest observation timed out, or to follow a job started elsewhere. For a long build call this directly with waitSeconds=600 and leave afterUpdated empty; inside a tool program the wait is clamped to the program's remaining deadline. Return long-job handles from programs and observe them separately. Do not spend model turns repeatedly polling unchanged work. Returns immediately on completion. An optional afterUpdated timestamp returns early when progress changes, for interactive observation. timedOut means observation ended, not build failure. The result includes live stage currency, qualification facts and artifact references; check these before consuming its result. Cancellation of this wait does not cancel the build. An unknown job, or waitSeconds outside 0..900, fails with success=false. e.g. jobId = `job-3`, waitSeconds = 600.")
@AgentToolMeta("builds", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT, ToolEffect.FILESYSTEM_READ])
data class WaitStudyBuildRequest(val boxId: String, val name: String, val jobId: String,
    val waitSeconds: Double = 600.0, val afterUpdated: String = "")

@JsonClassDescription("Start one derived stage from its current frozen inputs. Returns success, the job handle and, when it waited, the outcome. Use it when a stage is not current and the task needs its result. When called directly, waitSeconds=600 starts and observes completion in one call with streamed progress; zero returns immediately. Inside a tool program, start long work with waitSeconds=0 and return the job handle, then observe it separately: a nested wait cannot extend the program's deadline. Supply the stage fingerprint from the latest inspect. Missing/stale dependency results, closed gates and competing builds are refused. If observation times out, use WaitStudyBuildRequest on the same returned job; do not equate accepted/queued with successful or current. Continue editing while it runs: outdated results cannot become current. e.g. stageId = `bake`, expectedFingerprint from InspectStudyBuildsRequest, waitSeconds = 600.")
@AgentToolMeta("builds", ToolExposure.DEFERRED, effects = [ToolEffect.MUTATE_DOCUMENT, ToolEffect.EXECUTE_CODE, ToolEffect.CONTROL_RUNTIME, ToolEffect.FILESYSTEM_WRITE])
data class StartStudyBuildRequest(val boxId: String, val name: String, val stageId: String, val expectedFingerprint: String, val waitSeconds: Double = 0.0,
    // A JSON string rather than a Map: openai-java's strict schema refuses an object with no named properties.
    @JsonPropertyDescription("Optional parameters for this one attempt that the stage documents, as a JSON object in a string, such as a job to resume, e.g. `{\"resume\": \"<jobId>\"}` or `{\"budgetSeconds\": 60}`. Empty for none. Recorded in the attempt and part of its fingerprint; a later start does not reuse it, so pass it again when it still applies. Anything but a JSON object fails with success=false.")
    val request: String = "")

@JsonClassDescription("Request cancellation of a specific build. Returns success=true once the request is recorded; an unknown runner or job fails with success=false. Use it when a running build is no longer wanted, e.g. jobId = `job-3` after its inputs changed. Publication is revoked; a running native operation may need time to finish. Poll the same job until terminal instead of treating a cancellation request as proof it stopped. Previous successful results and attempt history are retained.")
@AgentToolMeta("builds", ToolExposure.DEFERRED, effects = [ToolEffect.MUTATE_DOCUMENT, ToolEffect.CONTROL_RUNTIME, ToolEffect.FILESYSTEM_WRITE])
data class CancelStudyBuildRequest(val boxId: String, val name: String, val jobId: String)

@JsonClassDescription("End this turn and let Field wait for build jobs without the model, then resume this conversation with their outcomes. Use it after StartStudyBuildRequest (waitSeconds=0) when a build will take more than a few minutes, instead of repeated WaitStudyBuildRequest calls: every wait costs a model round, and this costs none. Returns at once with suspended=true and the jobs it waits for; call no other tool after it in this turn. When every job is terminal, or maxHours have passed, Field sends you a message with each job's status, currency, facts and result notes, and you carry on from there. If every job has already finished it returns their outcomes with suspended=false and the turn goes on. An unknown runner or job, more than 20 jobs, or maxHours outside 0..48 fails with success=false. e.g. boxId = `a1b2`, name = `mesh`, jobIds = [`3f2a1c9e-...`], maxHours = 4.")
@AgentToolMeta("builds", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT, ToolEffect.FILESYSTEM_READ, ToolEffect.AGENT_STATE])
data class AwaitStudyBuildsRequest(val boxId: String, val name: String, val jobIds: List<String>, val maxHours: Double = 4.0)

@JsonClassDescription("Delete build attempts this document no longer needs, across all of its build runners, to free disk space. Returns, per runner, how many attempts were kept and deleted and the bytes freed, plus any pins that name no attempt. Kept: each stage's current result, latest attempt and latest success, running builds, pinned jobIds, and every attempt those depend on, were gated on or resumed from. Use it when attempt history has grown large after many rebuilds: call with apply=false to see what would go, then apply=true to delete it. A deleted attempt cannot be inspected or resumed from again. A read-only document refuses it. e.g. pins = [`<jobId to keep>`], apply = false.")
@AgentToolMeta("builds", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT, ToolEffect.FILESYSTEM_READ, ToolEffect.MUTATE_DOCUMENT, ToolEffect.FILESYSTEM_WRITE])
data class PruneStudyBuildsRequest(
    @JsonPropertyDescription("Job IDs to keep even when nothing else needs them, such as a result to compare against later.")
    val pins: List<String> = emptyList(),
    @JsonPropertyDescription("false (the default) reports what would be deleted and deletes nothing; true deletes it.")
    val apply: Boolean = false)

data class StudyBuildToolResult(val success: Boolean, val message: String, val details: Map<String, Any?> = emptyMap()) : SummarisesForToolLog {
    override fun toolLogSummary() = "success=$success $message"
}

/** [AwaitStudyBuildsRequest]'s result. A set [suspension] ends the turn; the model reads only the rest. */
data class StudyBuildAwaitResult(val success: Boolean, val message: String, val details: Map<String, Any?> = emptyMap(),
    @get:JsonIgnore override val suspension: TurnSuspension? = null) : SummarisesForToolLog, SuspendsTurn {
    override fun toolLogSummary() = "success=$success suspended=${suspension != null} $message"
}

/**
 * Where an awaited job stands, read without the model: its record from the live runner, or from the runner's
 * directory when the runner is not running ([runnerLive] false). [record] is null for a job neither knows.
 */
internal data class AwaitedJobState(val record: StudyBuildRecord?, val runnerLive: Boolean)

/** An awaited job's final outcome for the continuation: [current] and [notes] need the live runner. */
internal data class AwaitedJobOutcome(val job: AwaitedBuild, val record: StudyBuildRecord?, val runnerLive: Boolean,
    val current: Boolean? = null, val notes: List<StudyBuildNotice> = emptyList())

/** Resolve on the owner thread, then read/queue off it. Input adapters may themselves marshal reads. */
internal class StudyBuildTools(private val info: SessionInformation, private val bindings: () -> List<BoxStudyBuildBinding>,
    private val findings: (Set<String>) -> List<StudyBuildFinding> = { emptyList() }) {
    private fun guarded(action: () -> StudyBuildToolResult): StudyBuildToolResult = try { action() }
    catch (e: Exception) { StudyBuildToolResult(false, e.message ?: "Build operation failed") }

    private fun runner(boxId: String, name: String): BoxStudyBuildBinding = bindings().firstOrNull { it.owner.id == boxId && it.name == name }
        ?: error("No live build runner '$name' on box '$boxId' in this document. List runners, or run its source box again.")

    fun list(): StudyBuildToolResult = guarded {
        val entries = bindings().map { mapOf("boxId" to it.owner.id, "boxName" to it.owner.name, "name" to it.name, "stages" to it.builds.stages().map(::stageSummary)) }
        StudyBuildToolResult(true, "${entries.size} build runners", mapOf("runners" to entries))
    }

    fun inspect(q: InspectStudyBuildsRequest): StudyBuildToolResult = guarded {
        val binding = runner(q.boxId, q.name)
        val details = if (q.jobId.isNotBlank()) jobDetails(binding, binding.builds.job(q.jobId)) else {
            val stages = binding.builds.stages()
            // A retained attempt of a stage the recipe no longer defines cannot match any current inputs.
            mapOf("stages" to stages.map(::stageSummary), "history" to binding.builds.history(q.offset, q.limit)
                .map { job -> jobSummary(job, stages.firstOrNull { it.id == job.stage }) })
        }
        StudyBuildToolResult(true, "Build state inspected", details + mapOf("boxId" to q.boxId, "name" to q.name))
    }

    fun start(q: StartStudyBuildRequest, reportProgress: ((LlmProgressUpdate) -> Unit)? = null): StudyBuildToolResult = guarded {
        check(!info.readOnly && !ReadOnlyDocuments.global) { "This document is read-only" }
        require(q.waitSeconds.isFinite() && q.waitSeconds in 0.0..900.0) { "waitSeconds must be finite and between 0 and 900" }
        val binding = runner(q.boxId, q.name)
        val job = binding.builds.start(q.stageId, q.expectedFingerprint, decodeBuildRequest(q.request))
        reportBuildProgress(reportProgress, binding.builds.stages().first { it.id == job.stage }.label, job)
        if (q.waitSeconds > 0) wait(WaitStudyBuildRequest(q.boxId, q.name, job.id, q.waitSeconds), reportProgress)
        else StudyBuildToolResult(true, "Build accepted; wait on this job for its final outcome.", jobDetails(binding, job))
    }

    fun wait(q: WaitStudyBuildRequest, reportProgress: ((LlmProgressUpdate) -> Unit)? = null): StudyBuildToolResult = guarded {
        require(q.waitSeconds.isFinite() && q.waitSeconds in 0.0..900.0) { "waitSeconds must be finite and between 0 and 900" }
        val binding = runner(q.boxId, q.name)
        val deadline = System.nanoTime() + (q.waitSeconds * 1_000_000_000).toLong()
        var job = binding.builds.job(q.jobId)
        val label = binding.builds.stages().first { it.id == job.stage }.label
        var lastPublished = System.nanoTime()
        var published = job
        fun publish() = reportBuildProgress(reportProgress, label, job, binding.builds.resultNotes(job.id), failureView(binding, job))
        publish()
        fun terminal() = job.status !in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)
        fun changed() = q.afterUpdated.isNotEmpty() && job.updated != q.afterUpdated
        while (!terminal() && !changed() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(minOf(100L, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1L)))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Build observation interrupted; the job was not cancelled or restarted.", e)
            }
            job = binding.builds.job(q.jobId)
            val now = System.nanoTime()
            if (terminal() || (job.updated != published.updated && now - lastPublished >= 1_000_000_000) ||
                now - lastPublished >= 10_000_000_000) {
                publish()
                published = job; lastPublished = now
            }
        }
        if (job.updated != published.updated) publish()
        StudyBuildToolResult(true, if (terminal()) "Build reached ${job.status}; check the returned currency, facts and result notes."
            else if (changed()) "Build progress changed." else "Still running; wait again on this same job.",
            jobDetails(binding, job) + mapOf("terminal" to terminal(), "timedOut" to (!terminal() && !changed())))
    }

    /**
     * Suspend the turn until [AwaitStudyBuildsRequest.jobIds] are terminal; see its description. Jobs that have
     * all finished already are answered at once, without a suspension.
     */
    fun await(q: AwaitStudyBuildsRequest, now: Long = System.currentTimeMillis()): StudyBuildAwaitResult = try {
        require(q.maxHours.isFinite() && q.maxHours > 0.0 && q.maxHours <= 48.0) { "maxHours must be more than 0 and at most 48" }
        val ids = q.jobIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(ids.isNotEmpty() && ids.size <= 20) { "Name between 1 and 20 jobIds, as StartStudyBuildRequest returned them" }
        val binding = runner(q.boxId, q.name)
        val jobs = ids.map { binding.builds.job(it) }
        val stages = binding.builds.stages()
        val summaries = jobs.map { job -> jobSummary(job, stages.firstOrNull { it.id == job.stage }) }
        if (jobs.none { it.status in ACTIVE_BUILD }) {
            StudyBuildAwaitResult(true, "Every job has already finished; nothing to wait for. Check each job's currency before using it.",
                mapOf("suspended" to false, "jobs" to jobs.map { jobDetails(binding, it) }))
        } else {
            val deadline = now + (q.maxHours * 3_600_000).toLong()
            val awaited = jobs.map { job ->
                AwaitedBuild(binding.owner.id, binding.name, job.id, job.stage,
                    stages.firstOrNull { it.id == job.stage }?.label.orEmpty(), binding.builds.directory.absolutePath)
            }
            StudyBuildAwaitResult(true, "Your turn ends here. Field waits for these jobs without the model and resumes this " +
                "conversation with their outcomes when every one has finished, or at resumesBy. Call no other tool in this turn.",
                mapOf("suspended" to true, "jobs" to summaries, "resumesBy" to java.time.Instant.ofEpochMilli(deadline).toString()),
                TurnSuspension(awaited, deadline))
        }
    } catch (e: Exception) { StudyBuildAwaitResult(false, e.message ?: "Could not wait for these builds") }

    /** One poll of an awaited job, cheap enough to repeat: no stage currency, no result notes. */
    internal fun awaitedState(job: AwaitedBuild): AwaitedJobState {
        val binding = bindings().firstOrNull { it.owner.id == job.boxId && it.name == job.name }
        if (binding != null) return AwaitedJobState(runCatching { binding.builds.job(job.jobId) }.getOrNull(), true)
        return AwaitedJobState(job.directory.takeIf { it.isNotBlank() }?.let { StudyBuilds.retainedRecord(java.io.File(it), job.jobId) }, false)
    }

    /** The outcome reported when the wait ends, with currency and result notes when the runner is live. */
    internal fun awaitedOutcome(job: AwaitedBuild): AwaitedJobOutcome {
        val binding = bindings().firstOrNull { it.owner.id == job.boxId && it.name == job.name }
            ?: return AwaitedJobOutcome(job, awaitedState(job).record, false)
        val record = runCatching { binding.builds.job(job.jobId) }.getOrNull() ?: return AwaitedJobOutcome(job, null, true)
        val stage = runCatching { binding.builds.stages().firstOrNull { it.id == record.stage } }.getOrNull()
        return AwaitedJobOutcome(job, record, true, stage?.let { it.current?.id == record.id },
            runCatching { binding.builds.resultNotes(record.id) }.getOrDefault(emptyList()))
    }

    // The card's "Show where it fails" link, as a chat box link: only a failure against the current inputs has one.
    private fun failureView(binding: BoxStudyBuildBinding, job: StudyBuildRecord): String? {
        if (job.status != StudyBuildStatus.FAILED) return null
        val view = job.problem?.let(::studyBuildGeometryView) ?: return null
        val stage = binding.builds.stages().firstOrNull { it.id == job.stage }
        if (stage == null || studyBuildUsedEarlierInputs(stage, job)) return null
        val box = info.boxes[view.box]?.let { found ->
            val name = found.name.replace("[", "").replace("]", "").ifBlank { view.box }
            "[$name](field://box/${java.net.URLEncoder.encode(view.box, Charsets.UTF_8).replace("+", "%20")})"
        } ?: "“${view.box}”"
        return "The failing geometry is shown in red in the output of $box${view.caption?.let { ": $it" }.orEmpty()}."
    }

    /**
     * For each of [runIds] that names a build attempt in this document, whether that attempt ran against
     * earlier inputs ([studyBuildUsedEarlierInputs], as on the card). Other ids are absent, as are attempts
     * of a runner whose inputs cannot be read now.
     */
    fun runsFromEarlierInputs(runIds: Set<String>): Map<String, Boolean> {
        if (runIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Boolean>()
        for (binding in bindings()) {
            val jobs = (runIds - result.keys).mapNotNull { id -> runCatching { binding.builds.job(id) }.getOrNull() }
            if (jobs.isEmpty()) continue
            val stages = try { binding.builds.stages() } catch (e: Exception) { continue }
            for (job in jobs) result[job.id] = stages.firstOrNull { it.id == job.stage }.let { it == null || studyBuildUsedEarlierInputs(it, job) }
        }
        return result
    }

    private fun reportBuildProgress(report: ((LlmProgressUpdate) -> Unit)?, label: String, job: StudyBuildRecord,
        notes: List<StudyBuildNotice> = emptyList(), failureView: String? = null) {
        if (report == null) return
        val active = job.status in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)
        val state = when (job.status) {
            StudyBuildStatus.QUEUED -> "Queued"
            StudyBuildStatus.RUNNING -> "${(job.progress * 100).toInt()}%"
            StudyBuildStatus.SUCCEEDED -> if (notes.any { it.kind != StudyBuildNoticeKind.INFO }) "Build complete; needs review" else "Build complete; checking currency"
            StudyBuildStatus.FAILED -> "Check failed"
            else -> job.status.name.lowercase().replaceFirstChar { it.uppercase() }
        }
        val body = buildString {
            append(job.problem?.message ?: job.message)
            job.problem?.suggestions?.forEach { append("\n"); append(it) }
            failureView?.let { append("\n"); append(it) }
            notes.forEach { append("\n"); append(it.message) }
            if (active) {
                val elapsed = runCatching { java.time.Duration.between(java.time.Instant.parse(job.created), java.time.Instant.now()).seconds }.getOrDefault(0).coerceAtLeast(0)
                append("\nElapsed ${elapsed / 60}m ${elapsed % 60}s. This same attempt is still running.")
            }
            append("\nAttempt ${job.id}")
        }
        // A disconnected presentation callback must not restart or fail the real build.
        runCatching { report(LlmProgressUpdate("$label · $state", body, phase = if (active) "build" else "build_result")) }
    }

    fun cancel(q: CancelStudyBuildRequest): StudyBuildToolResult = guarded {
        check(!info.readOnly && !ReadOnlyDocuments.global) { "This document is read-only" }
        val binding = runner(q.boxId, q.name)
        val job = binding.builds.cancel(q.jobId)
        StudyBuildToolResult(true, job.message, jobDetails(binding, job))
    }

    fun prune(q: PruneStudyBuildsRequest): StudyBuildToolResult = guarded {
        if (q.apply) check(!info.readOnly && !ReadOnlyDocuments.global) { "This document is read-only" }
        val runners = bindings().distinctBy { it.builds }
        val reports = StudyBuilds.prune(runners.map { it.builds }, q.pins.toSet(), dryRun = !q.apply)
        val entries = runners.zip(reports).map { (binding, report) -> mapOf("boxId" to binding.owner.id, "name" to binding.name,
            "kept" to report.kept.size, "deleted" to report.deleted.size, "freedBytes" to report.freedBytes) }
        val count = reports.sumOf { it.deleted.size }
        val size = "%.1f MB".format(java.util.Locale.ROOT, reports.sumOf { it.freedBytes } / 1e6)
        StudyBuildToolResult(true, if (q.apply) "Deleted $count attempts, freeing $size."
            else "Would delete $count attempts, freeing $size; call again with apply=true to delete them.",
            mapOf("runners" to entries, "unknownPins" to q.pins.filter { pin -> reports.none { pin in it.kept } }))
    }

    // Frozen source remains in input.json. Ordinary orchestration must not resend it to the model.
    private fun jobDetails(binding: BoxStudyBuildBinding, job: StudyBuildRecord): Map<String, Any?> {
        val stage = binding.builds.stages().first { it.id == job.stage }
        return mapOf("boxId" to binding.owner.id, "name" to binding.name, "job" to jobSummary(job, stage),
            "directory" to binding.builds.attemptDirectory(job.id).absolutePath,
            "facts" to job.facts, "artifacts" to job.artifacts,
            "resultNotes" to binding.builds.resultNotes(job.id),
            "currency" to mapOf("isCurrent" to (stage.current?.id == job.id),
                "currentJobId" to stage.current?.id, "fingerprint" to stage.fingerprint,
                "reason" to stage.reason, "blockedBy" to stage.blockedBy))
    }

    // What the card shows: an attempt against earlier inputs has no open findings and no "Show where it fails" link.
    private fun jobSummary(job: StudyBuildRecord, stage: StudyStageState?): Map<String, Any?> {
        val earlier = stage == null || studyBuildUsedEarlierInputs(stage, job)
        val reviews = findings(setOf(job.id))
        val view = job.problem?.takeIf { !earlier }?.let(::studyBuildGeometryView)
        return mapOf("id" to job.id, "stage" to job.stage,
            "status" to job.status, "fingerprint" to job.fingerprint, "fromEarlierInputs" to earlier, "progress" to job.progress,
            "message" to job.message, "cancelRequested" to job.cancelRequested, "updated" to job.updated,
            "artifactCount" to job.artifacts.size, "problem" to job.problem, "request" to job.request,
            "resumedFrom" to job.resumedFrom.map { mapOf("jobId" to it.jobId, "files" to it.files.map(StudyBuildArtifact::path)) },
            "openFindings" to if (earlier) 0 else reviews.count { it.status != "resolved" },
            "reviewFindings" to reviews.map { aiToolMapper.convertValue<Map<String, Any?>>(it) + ("fromEarlierInputs" to earlier) }) +
            view?.let { mapOf("geometryView" to mapOf("box" to it.box, "boxName" to info.boxes[it.box]?.name,
                "caption" to it.caption, "viewId" to it.viewId)) }.orEmpty()
    }

    private fun stageSummary(stage: StudyStageState): Map<String, Any?> = mapOf("id" to stage.id, "label" to stage.label,
        "fingerprint" to stage.fingerprint, "current" to stage.current?.let { jobSummary(it, stage) },
        "latest" to stage.latest?.let { jobSummary(it, stage) }, "reason" to stage.reason, "blockedBy" to stage.blockedBy,
        "inputHelp" to stage.inputHelp, "resultNotes" to stage.resultNotes)
}

/** [StartStudyBuildRequest.request]: blank is no request, otherwise a JSON object whose values reach the worker as JSON types. */
internal fun decodeBuildRequest(json: String): Map<String, Any?> {
    if (json.isBlank()) return emptyMap()
    val node = try { aiToolMapper.readTree(json) } catch (e: com.fasterxml.jackson.core.JacksonException) {
        throw IllegalArgumentException("request must be a JSON object, e.g. {\"resume\": \"<jobId>\"}; it did not parse: ${e.originalMessage}")
    }
    require(node != null && node.isObject) { "request must be a JSON object, e.g. {\"resume\": \"<jobId>\"}, not ${node?.nodeType?.name?.lowercase() ?: "nothing"}" }
    return aiToolMapper.convertValue(node)
}

private val ACTIVE_BUILD = setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)

internal fun registerStudyBuildTools(tools: ToolRegistry) {
    tools.register(ListStudyBuildsRequest::class.java, { it.fieldGraph != null }) { ctx, _ -> ctx.fieldGraph!!.studyBuilds.list() }
    tools.register(InspectStudyBuildsRequest::class.java, { it.fieldGraph != null }) { ctx, q -> ctx.fieldGraph!!.studyBuilds.inspect(q) }
    tools.register(WaitStudyBuildRequest::class.java, { it.fieldGraph != null }) { ctx, q -> ctx.fieldGraph!!.studyBuilds.wait(q, ctx.reportProgress) }
    tools.register(StartStudyBuildRequest::class.java, { it.fieldGraph != null }) { ctx, q -> ctx.fieldGraph!!.studyBuilds.start(q, ctx.reportProgress) }
    tools.register(CancelStudyBuildRequest::class.java, { it.fieldGraph != null }) { ctx, q -> ctx.fieldGraph!!.studyBuilds.cancel(q) }
    // Only where something will resume the turn: the box chat's general turn (StudyBuildAwaits).
    tools.register(AwaitStudyBuildsRequest::class.java, { it.fieldGraph != null && it.suspendable }) { ctx, q -> ctx.fieldGraph!!.studyBuilds.await(q) }
    tools.register(PruneStudyBuildsRequest::class.java, { it.fieldGraph != null }) { ctx, q -> ctx.fieldGraph!!.studyBuilds.prune(q) }
}
