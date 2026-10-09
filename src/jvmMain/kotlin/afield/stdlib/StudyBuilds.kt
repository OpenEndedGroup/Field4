@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import kotlin.time.Duration

/** A build's execution state. Currency against the design is reported separately by [StudyStageState]. */
enum class StudyBuildStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, OBSOLETE, INTERRUPTED }

/** An actionable build failure, optionally naming entities and measurements in [details]. */
data class StudyBuildProblem(val code: String, val message: String,
    val suggestions: List<String> = emptyList(), val details: Map<String, Any?> = emptyMap())

/** Throw from a stage to keep its structured diagnostic in the attempt history. */
class StudyBuildFailure(val problem: StudyBuildProblem) : IllegalArgumentException(problem.message)

/** One immutable result file, relative to its attempt directory and checked by SHA-256. */
data class StudyBuildArtifact(val path: String, val sha256: String, val bytes: Long)

/** The successful build of another stage consumed by an attempt. */
data class StudyBuildDependency(val jobId: String, val fingerprint: String, val artifacts: List<StudyBuildArtifact>)

/** Files an attempt copied from the earlier attempt [jobId] with [StudyBuildContext.resumeFrom], as copied. */
data class StudyBuildResume(val jobId: String, val files: List<StudyBuildArtifact>)

/** What [StudyBuilds.prune] kept and deleted in the runner at [directory], and the bytes the deleted attempts held. */
data class StudyBuildPruneReport(val directory: File, val kept: List<String>, val deleted: List<String>, val freedBytes: Long, val dryRun: Boolean)

/**
 * Durable record of one attempt, including the exact named input values and dependency results.
 * [request] is what [StudyBuilds.start] was asked for beyond the design, and is part of [fingerprint].
 * [gates] holds, for each [StudyBuildGate] the stage consumes, the upstream results the gate judged.
 * [resumedFrom] lists what the attempt copied from earlier attempts.
 */
data class StudyBuildRecord(
    val id: String, val stage: String, val recipeVersion: String, val fingerprint: String,
    val inputs: Map<String, String>, val dependencies: Map<String, StudyBuildDependency>,
    val status: StudyBuildStatus = StudyBuildStatus.QUEUED,
    val progress: Double = 0.0, val message: String = "Queued", val cancelRequested: Boolean = false,
    val created: String = Instant.now().toString(), val updated: String = created,
    val artifacts: List<StudyBuildArtifact> = emptyList(), val facts: Map<String, Any?> = emptyMap(),
    val problem: StudyBuildProblem? = null,
    val request: Map<String, Any?> = emptyMap(),
    val gates: Map<String, Map<String, StudyBuildDependency>> = emptyMap(),
    val resumedFrom: List<StudyBuildResume> = emptyList(),
)

/** Files produced inside the attempt directory, and verification facts supplied by the recipe. */
data class StudyBuildProduct(val files: List<String>, val facts: Map<String, Any?> = emptyMap())

/** Explain who supplies a stage's missing inputs, including intentionally idle, optional stages. */
data class StudyBuildInputHelp(val label: String, val message: String) {
    init { require(label.isNotBlank() && message.isNotBlank()) { "Input help needs a label and an explanation" } }
}

/** Presentation of retained result evidence, separate from execution and dependency currency. */
enum class StudyBuildNoticeKind { INFO, WARNING, ERROR }

/** A recipe's explanation of a result and the next action; never an acceptance override. */
data class StudyBuildNotice(val message: String, val kind: StudyBuildNoticeKind = StudyBuildNoticeKind.WARNING) {
    init { require(message.isNotBlank()) { "Result feedback needs an explanation" } }
}

/**
 * A reusable build stage. [inputKeys] selects only the authored inputs this stage consumes, and may
 * name [StudyBuildGate]s; [dependencies] names other stages whose current results must exist first,
 * either in this runner or, written `dependsOn(runner, stage)`, in another runner of the same document.
 * [recipeVersion] must change when the adapter or its relevant runtime changes. The runner does not
 * infer code identity from a closure. [inputHelp] explains missing inputs without making the stage
 * runnable or changing its fingerprint; a closed gate supplies its own explanation. [resultNotes] is a
 * fast, pure presentation of a completed record's retained facts;
 * it must not inspect mutable design state or change acceptance. It is shared by UI and agent tools
 * and does not change build identity. Qualification gates belong in the build and input contracts.
 * [build] runs on a worker and must use its captured context, not mutable live input.
 */
class StudyBuildStage(val id: String, val label: String, val recipeVersion: String,
    val inputKeys: Set<String> = emptySet(), val dependencies: List<String> = emptyList(),
    val inputHelp: StudyBuildInputHelp? = null,
    val resultNotes: (StudyBuildRecord) -> List<StudyBuildNotice> = { emptyList() },
    val build: (StudyBuildContext) -> StudyBuildProduct)

/**
 * The dependency name of [stage] in the runner called [runner] in the same document, for
 * [StudyBuildStage.dependencies], [StudyBuildGate.dependencies] and [StudyBuildContext.dependencyFile].
 */
fun dependsOn(runner: String, stage: String): String {
    require(runner.matches(STUDY_BUILD_NAME) && stage.matches(STUDY_BUILD_NAME)) { "Runner and stage names are simple words" }
    return "$runner/$stage"
}

/**
 * An input called [name] that exists only while [check] finds nothing wrong. A stage that lists [name]
 * in its `inputKeys` waits until then, and its card and agent inspection say what it waits for.
 *
 * [check] judges the gate's own [inputKeys] and the current results of its [dependencies] (stages of
 * this runner, or `dependsOn(runner, stage)`), and returns null to open the gate or a sentence saying
 * what is still wrong. While the gate is open its value is a hash of that evidence, so a rebuilt
 * upstream result makes the waiting stage's results stale like any other input change. A queued build
 * checks its gates again when it starts, and fails before its recipe runs if one has closed.
 * [check] runs whenever the stages are read: keep it fast and pure, and read only its evidence.
 */
class StudyBuildGate(val name: String, val inputKeys: Set<String> = emptySet(), val dependencies: List<String> = emptyList(),
    val check: (StudyBuildGateEvidence) -> String?)

/** What a [StudyBuildGate] judges: its named inputs and the current results of its dependencies. */
class StudyBuildGateEvidence internal constructor(val inputs: Map<String, String>,
    private val results: Map<String, StudyBuildRecord>, private val read: (String, StudyBuildRecord, StudyBuildArtifact) -> File) {
    /** The current result of [dependency], which must be one of the gate's dependencies. */
    fun result(dependency: String): StudyBuildRecord = results[dependency] ?: error("'$dependency' is not a dependency of this gate")
    /** The retained facts of [dependency]'s current result. */
    fun facts(dependency: String): Map<String, Any?> = result(dependency).facts
    /** A result file of [dependency], checked against its recorded hash. */
    fun file(dependency: String, path: String): File {
        val record = result(dependency)
        return read(dependency, record, record.artifacts.firstOrNull { it.path == path } ?: error("No '$path' in the result of '$dependency'"))
    }
}

/** A stage's current availability, its latest attempt, and its last published result when still valid. */
data class StudyStageState(val id: String, val label: String, val fingerprint: String?,
    val current: StudyBuildRecord?, val latest: StudyBuildRecord?, val reason: String?, val blockedBy: List<String> = emptyList(),
    val inputHelp: StudyBuildInputHelp? = null, val resultNotes: List<StudyBuildNotice> = emptyList())

/**
 * The frozen inputs a worker can read and its own output directory. [checkCancelled] cooperatively
 * stops work; cancellation never claims that an uncooperative native operation has already stopped.
 */
class StudyBuildContext internal constructor(private val store: StudyBuilds, val attempt: StudyBuildRecord) {
    val inputs: Map<String, String> get() = attempt.inputs
    /** What this attempt was started with beyond its inputs; empty unless [StudyBuilds.start] was given one. */
    val request: Map<String, Any?> get() = attempt.request
    val directory: File get() = store.attemptDirectory(attempt.id)
    fun file(path: String): File = store.artifactFile(attempt.id, path)
    fun checkCancelled() = store.checkCancelled(attempt.id)
    fun progress(fraction: Double, message: String) = store.progress(attempt.id, fraction, message)

    /**
     * Read a frozen dependency file; a changed file refuses instead of becoming silently current.
     * [stage] is the name in the stage's dependencies, including `dependsOn(runner, stage)`.
     */
    fun dependencyFile(stage: String, path: String): File {
        val dependency = attempt.dependencies[stage] ?: error("Stage '$stage' is not a dependency")
        val artifact = dependency.artifacts.firstOrNull { it.path == path } ?: error("No '$path' in dependency '$stage'")
        return store.resultFile(stage, dependency.jobId, artifact)
    }

    /**
     * Copy [paths] (files, or directories with everything under them) from the finished attempt [jobId]
     * of this runner into this attempt, at the same paths, and return the copies. Each copy is checked
     * against its source; a file that was a result of that attempt must also still match its recorded
     * hash. What was copied, with hashes, is recorded in [StudyBuildRecord.resumedFrom].
     * With [sameDesign], the default, the earlier attempt must be of this stage and recipe and have
     * built the same inputs and dependency results, so a checkpoint of a replaced design is refused.
     */
    fun resumeFrom(jobId: String, paths: List<String>, sameDesign: Boolean = true): List<File> =
        store.resume(attempt, jobId, paths, sameDesign)

    /**
     * Run an external program in this attempt's directory and wait for it, keeping its combined output
     * in [log]. [progressFile], a file the program rewrites with `{"fraction": 0.4, "message": "..."}`,
     * becomes this attempt's progress, scaled into [progressSpan]. The program is stopped, with every
     * process it started, when the build is cancelled, when [deadline] passes, or when [stall] passes
     * with no change to the progress file (or, without one, no new output). A stop or, with [check],
     * a non-zero exit fails the attempt with a [StudyBuildFailure] naming the log.
     */
    fun runProcess(command: List<String>, progressFile: String? = null, stall: Duration? = null, deadline: Duration? = null,
        log: String = "process.log", environment: Map<String, String> = emptyMap(),
        progressSpan: ClosedFloatingPointRange<Double> = 0.0..1.0, check: Boolean = true): StudyBuildProcessResult =
        store.runProcess(attempt, command, progressFile, stall, deadline, log, environment, progressSpan, check)
}

/**
 * Durable, asynchronous derived stages over a study's authoritative input snapshot.
 *
 * [readInputs] returns an atomic snapshot of named immutable strings (often JSON) from the existing
 * design authority, such as [SourceStudy]. It is called outside the store lock and must not mutate
 * the design. No second editable design is stored here. Every attempt captures the selected values,
 * recipe version and exact dependency builds before work starts. Publication checks them again;
 * a late result is retained as obsolete, and never replaces the current result.
 *
 * [gates] add inputs that exist only while a check of other results passes. [upstream] finds another
 * runner by name for a `dependsOn(runner, stage)` dependency; [Box.studyBuilds] supplies the runners
 * of the same document. It is called outside the store lock, and a cycle across runners is refused.
 * With [exclusive], the runner builds one stage at a time and refuses a start while any build runs.
 *
 * Reading [stages] recomputes currency, so later source edits invalidate downstream results even
 * without an explicit notification. A failed retry preserves a previous result if it still matches.
 * Result files and the manifest must remain together; missing or changed files invalidate currency.
 *
 * One instance owns a directory through an OS file lock. After a crash, queued/running records become
 * interrupted on reopen, with an explicit retry instruction. Reopening never resubmits side effects.
 * [close] revokes publication and signals cancellation; it does not wait for uncooperative workers.
 * This runner establishes provenance and lifecycle, not the correctness of a recipe's geometry.
 */
class StudyBuilds(val directory: File, definitions: List<StudyBuildStage>,
    private val readInputs: () -> Map<String, String>, workers: Int = 2,
    gates: List<StudyBuildGate> = emptyList(), val exclusive: Boolean = false,
    private val upstream: (runner: String) -> StudyBuilds? = { null }) : AutoCloseable {
    private val monitor = Any()
    private val mapper = jacksonObjectMapper()
    private val specs = definitions.associateBy { it.id }
    private val gateSpecs = gates.associateBy { it.name }
    private val crossDependencies = (definitions.flatMap { it.dependencies } + gates.flatMap { it.dependencies }).filter { '/' in it }.toSortedSet()
    private val records = linkedMapOf<String, StudyBuildRecord>()
    private val heads = linkedMapOf<String, String>()
    private val executor = Executors.newFixedThreadPool(workers.also { require(it in 1..8) }) { task ->
        Thread(task, "study-build").apply { isDaemon = true }
    }
    private val channel: FileChannel
    private val lock: java.nio.channels.FileLock
    @Volatile private var closed = false

    private data class Manifest(val schema: Int = 1, val heads: Map<String, String> = emptyMap(), val records: List<StudyBuildRecord> = emptyList())

    init {
        require(specs.size == definitions.size && specs.isNotEmpty()) { "Stage IDs must be unique; provide at least one stage" }
        require(gateSpecs.size == gates.size) { "Gate names must be unique" }
        fun knownDependency(name: String) = name in specs || name.matches(CROSS_DEPENDENCY)
        definitions.forEach {
            require(it.id.matches(STUDY_BUILD_NAME) && it.label.isNotBlank() && it.recipeVersion.isNotBlank()) { "Each stage needs a simple ID, label and recipe version" }
            require(it.dependencies.distinct().size == it.dependencies.size && it.dependencies.all(::knownDependency)) { "Unknown or repeated dependency of ${it.id}" }
        }
        gates.forEach {
            require(it.name.isNotBlank() && it.name.length <= 80) { "Each gate needs a short name" }
            require(it.inputKeys.none(gateSpecs::containsKey)) { "Gate '${it.name}' cannot read another gate" }
            require(it.dependencies.distinct().size == it.dependencies.size && it.dependencies.all(::knownDependency)) { "Unknown or repeated dependency of gate '${it.name}'" }
        }
        fun visit(id: String, path: Set<String>) {
            require(id !in path) { "Build dependency cycle through $id" }
            val spec = specs.getValue(id)
            (spec.dependencies + spec.inputKeys.mapNotNull(gateSpecs::get).flatMap { it.dependencies })
                .filter(specs::containsKey).forEach { visit(it, path + id) }
        }
        specs.keys.forEach { visit(it, emptySet()) }
        directory.mkdirs()
        channel = FileChannel.open(File(directory, ".owner.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        lock = try { checkNotNull(channel.tryLock()) { "Another build runner owns ${directory.absolutePath}" } }
        catch (e: Exception) { channel.close(); executor.shutdownNow(); throw IllegalStateException("Another build runner owns ${directory.absolutePath}", e) }
        try {
            val saved = File(directory, "builds.json")
            if (saved.exists()) {
                val state = mapper.readValue<Manifest>(saved)
                require(state.schema == 1) { "Unsupported build manifest schema ${state.schema}" }
                state.records.forEach { require(it.id.matches(JOB_ID)); records[it.id] = it }
                heads.putAll(state.heads)
                records.replaceAll { _, r ->
                    if (r.status in ACTIVE) r.copy(status = StudyBuildStatus.INTERRUPTED,
                        message = "Build interrupted; inspect the retained attempt and explicitly retry.", updated = now(),
                        problem = StudyBuildProblem("interrupted", "The previous runner stopped before this build finished.", listOf("Retry against the current design."))) else r
                }
                persist()
            }
        } catch (e: Exception) { lock.release(); channel.close(); executor.shutdownNow(); throw e }
    }

    /** All stages against one current input snapshot, including blocked dependencies and stale outputs. */
    fun stages(): List<StudyStageState> {
        val snapshot = snapshot()
        return synchronized(monitor) { ensureOpen(); Evaluation(snapshot).all().values.toList() }
    }

    /** A retained attempt, including obsolete, failed and interrupted attempts. */
    fun job(id: String): StudyBuildRecord = synchronized(monitor) { ensureOpen(); records[id] ?: error("Unknown build '$id'") }

    /** Explain a retained result using its stage's presentation adapter, without changing its verdict. */
    fun resultNotes(id: String): List<StudyBuildNotice> = notesFor(job(id))

    private fun notesFor(record: StudyBuildRecord): List<StudyBuildNotice> {
        if (record.status !in setOf(StudyBuildStatus.SUCCEEDED, StudyBuildStatus.OBSOLETE)) return emptyList()
        return try { specs[record.stage]?.resultNotes?.invoke(record)?.toList().orEmpty() }
        catch (e: Exception) { listOf(StudyBuildNotice("Result feedback is unavailable. Inspect the retained report. ${e.message.orEmpty()}")) }
    }

    /** A verified file from the current result, refusing stale results before a viewer consumes them. */
    fun currentFile(stage: String, path: String): File {
        val current = stages().firstOrNull { it.id == stage }?.current ?: error("Stage '$stage' has no current result; rebuild it first.")
        val artifact = current.artifacts.firstOrNull { it.path == path } ?: error("Stage '$stage' has no result file '$path'")
        return verifiedFile(current.id, artifact)
    }

    /** Paged attempt history, newest first. */
    fun history(offset: Int = 0, limit: Int = 20): List<StudyBuildRecord> = synchronized(monitor) {
        require(offset >= 0 && limit in 1..100)
        ensureOpen(); records.values.toList().asReversed().drop(offset).take(limit)
    }

    /**
     * Queue a stage against the fingerprint from [stages]; a stale request changes nothing.
     *
     * [request] carries what this one attempt is asked to do beyond the design, such as a checkpoint
     * to resume or a time budget. The worker reads it as [StudyBuildContext.request]. It is recorded in
     * the attempt and folded into the attempt's fingerprint, and a later start does not inherit it.
     * Its values must survive a JSON round trip.
     */
    fun start(stage: String, expectedFingerprint: String, request: Map<String, Any?> = emptyMap()): StudyBuildRecord {
        val asked = frozenRequest(request)
        val snapshot = snapshot()
        return synchronized(monitor) {
            ensureOpen()
            check(stage in specs) { "Unknown stage '$stage'" }
            val evaluation = Evaluation(snapshot)
            val state = evaluation.stage(stage)
            check(state.fingerprint != null) { state.reason ?: "Stage is blocked" }
            check(state.fingerprint == expectedFingerprint) { "Build inputs changed; inspect the stages and try again." }
            check(records.values.none { it.stage == stage && it.status in ACTIVE }) { "Stage '$stage' already has a running build." }
            if (exclusive) records.values.firstOrNull { it.status in ACTIVE }?.let { running ->
                error("This runner builds one stage at a time, and '${specs[running.stage]?.label ?: running.stage}' is still " +
                    "${running.status.name.lowercase()}; wait for it or cancel it first.")
            }
            val spec = specs.getValue(stage)
            val dependencies = spec.dependencies.associateWith { dep ->
                val r = checkNotNull(evaluation.current(dep))
                StudyBuildDependency(r.id, r.fingerprint, r.artifacts)
            }
            val record = StudyBuildRecord(UUID.randomUUID().toString(), stage, spec.recipeVersion, studyBuildFingerprint(state.fingerprint, asked),
                spec.inputKeys.sorted().associateWith { checkNotNull(evaluation.inputValue(it)) }, dependencies,
                request = asked, gates = spec.inputKeys.filter(gateSpecs::containsKey).associateWith { evaluation.gate(it).evidence })
            attemptDirectory(record.id).mkdirs()
            atomicWrite(File(attemptDirectory(record.id), "input.json"), mapper.writeValueAsBytes(record))
            records[record.id] = record
            persist()
            executor.submit { execute(record, spec) }
            record
        }
    }

    /**
     * Block the calling thread until attempt [id] is no longer queued or running, or until
     * [timeoutSeconds] pass, and return its record; after a timeout the build keeps running.
     * For scripts and tests that run stages in sequence. A box that starts a long build should let
     * its card or an agent's wait follow it instead of blocking the thread it runs on.
     */
    fun await(id: String, timeoutSeconds: Double = 600.0): StudyBuildRecord {
        require(timeoutSeconds.isFinite() && timeoutSeconds >= 0) { "The timeout must be finite and not negative" }
        val deadline = System.nanoTime() + (timeoutSeconds * 1e9).toLong()
        while (true) {
            val record = job(id)
            if (record.status !in ACTIVE || System.nanoTime() >= deadline) return record
            Thread.sleep(minOf(20L, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1L)))
        }
    }

    /**
     * Delete the attempts nothing needs any more, with their files. Kept are each stage's current
     * result, its latest attempt and its latest success, every attempt still queued or running, every
     * attempt in [pins], and every attempt those name: their dependencies, the results their gates
     * judged, the attempts they resumed from, and job IDs among their inputs and requests. Attempt
     * directories with no record are deleted too. With [dryRun] nothing is deleted, and the report says
     * what would be. Runners that name each other's attempts are pruned together with the companion [prune].
     */
    fun prune(pins: Set<String> = emptySet(), dryRun: Boolean = false): StudyBuildPruneReport =
        prune(listOf(this), pins, dryRun).single()

    /** Records and heads as they were when a prune read this runner. */
    private class PruneView(val records: List<StudyBuildRecord>, val heads: Collection<String>)

    private fun pruneView(): PruneView = synchronized(monitor) { ensureOpen(); PruneView(records.values.toList(), heads.values.toList()) }

    private fun pruneRoots(view: PruneView): Set<String> = buildSet {
        addAll(view.heads)
        view.records.groupBy { it.stage }.values.forEach { attempts ->
            add(attempts.last().id)
            attempts.lastOrNull { it.status == StudyBuildStatus.SUCCEEDED }?.let { add(it.id) }
        }
        view.records.filter { it.status in ACTIVE }.forEach { add(it.id) }
    }

    private fun deleteUnneeded(keep: Set<String>, dryRun: Boolean): StudyBuildPruneReport = synchronized(monitor) {
        ensureOpen()
        // An attempt started since the prune read this runner stays, with what it names here.
        val kept = keep + closure(pruneRoots(PruneView(records.values.toList(), heads.values.toList()))) { records[it] }
        val unrecorded = File(directory, "attempts").listFiles().orEmpty().filter { it.isDirectory && it.name !in records }
        val deleted = records.keys.filter { it !in kept }
        val freed = deleted.sumOf { directoryBytes(attemptDirectory(it)) } + unrecorded.sumOf(::directoryBytes)
        if (!dryRun) {
            deleted.forEach { records.remove(it); attemptDirectory(it).deleteRecursively() }
            unrecorded.forEach { it.deleteRecursively() }
            persist()
        }
        StudyBuildPruneReport(directory, records.keys.filter { it in kept }, deleted, freed, dryRun)
    }

    /** Signal cancellation. A running attempt remains running until its worker actually returns. */
    fun cancel(id: String): StudyBuildRecord = synchronized(monitor) {
        ensureOpen()
        val record = records[id] ?: error("Unknown build '$id'")
        if (record.status !in ACTIVE) return@synchronized record
        record.copy(cancelRequested = true, message = "Cancellation requested; waiting for the worker.", updated = now()).also {
            records[id] = it; persist()
        }
    }

    internal fun checkCancelled(id: String) = synchronized(monitor) {
        if (closed || records[id]?.cancelRequested != false || Thread.currentThread().isInterrupted)
            throw CancellationException("Build cancelled; previous published result retained.")
    }

    internal fun progress(id: String, fraction: Double, message: String) = synchronized(monitor) {
        checkCancelled(id)
        require(fraction.isFinite() && fraction in 0.0..1.0 && message.length <= 4000) { "Progress must be 0..1 with a short message" }
        val r = records.getValue(id)
        check(r.status == StudyBuildStatus.RUNNING)
        require(fraction >= r.progress) { "Progress cannot go backwards within one attempt" }
        records[id] = r.copy(progress = fraction, message = message, updated = now()); persist()
    }

    private fun execute(initial: StudyBuildRecord, spec: StudyBuildStage) {
        try {
            val gated = spec.inputKeys.filter(gateSpecs::containsKey)
            val atStart = if (gated.isEmpty()) null else snapshot()
            synchronized(monitor) {
                checkCancelled(initial.id)
                if (atStart != null) {
                    // A queued attempt may wait while its gate's evidence changes; it does not run once the gate is closed.
                    val evaluation = Evaluation(atStart)
                    val closedGates = gated.filter { evaluation.gate(it).value == null }
                    if (closedGates.isNotEmpty()) throw StudyBuildFailure(StudyBuildProblem("gate-closed",
                        closedGates.joinToString(" ") { "'$it' closed before this build started: ${evaluation.gate(it).problem}" },
                        listOf("The stage waits until the gate opens again; build it then."), mapOf("gates" to closedGates)))
                }
                records[initial.id] = records.getValue(initial.id).copy(status = StudyBuildStatus.RUNNING, message = "Building", updated = now())
                persist()
            }
            val product = spec.build(StudyBuildContext(this, initial))
            checkCancelled(initial.id)
            require(product.files.isNotEmpty() && product.files.distinct().size == product.files.size) { "A build must return distinct result files" }
            val artifacts = product.files.map { path ->
                val file = artifactFile(initial.id, path)
                require(file.isFile) { "Missing build artifact '$path'" }
                StudyBuildArtifact(path, hashFile(file), file.length())
            }
            // Round-trip facts now: no mutable or non-serializable objects are published.
            val facts: Map<String, Any?> = mapper.readValue(mapper.writeValueAsBytes(product.facts))
            val atEnd = snapshot()
            synchronized(monitor) {
                checkCancelled(initial.id)
                val current = Evaluation(atEnd).stage(initial.stage)
                val publish = initial.ranAgainst(current.fingerprint)
                val completed = records.getValue(initial.id).copy(
                    status = if (publish) StudyBuildStatus.SUCCEEDED else StudyBuildStatus.OBSOLETE,
                    progress = 1.0, artifacts = artifacts, facts = facts, updated = now(),
                    message = if (publish) "Build complete" else "Inputs changed during this build; result retained for inspection.")
                records[initial.id] = completed
                if (publish) heads[initial.stage] = initial.id
                persist()
            }
        } catch (e: Throwable) {
            synchronized(monitor) {
                if (!closed) {
                    val r = records.getValue(initial.id)
                    val cancelled = r.cancelRequested || e is CancellationException || e is InterruptedException
                    val problem = when (e) {
                        is StudyBuildFailure -> e.problem
                        is StudyRejection -> StudyBuildProblem("rejected", e.message.orEmpty(), details = e.facts)
                        else -> StudyBuildProblem("build-failed", e.message ?: e.javaClass.simpleName)
                    }
                    records[initial.id] = r.copy(status = if (cancelled) StudyBuildStatus.CANCELLED else StudyBuildStatus.FAILED,
                        updated = now(), message = if (cancelled) "Build cancelled" else problem.message,
                        problem = if (cancelled) null else problem)
                    persist()
                }
            }
        }
    }

    /** One read of everything outside this runner: the design's inputs and other runners' results. */
    private class Snapshot(val inputs: Map<String, String>, val upstream: Map<String, Upstream>)

    /** Another runner's stage as this runner sees it: its current result, or why it has none. */
    private class Upstream(val current: StudyBuildRecord?, val reason: String)

    private class CrossRunnerCycle(message: String) : IllegalStateException(message)

    // Called outside the store lock: another runner takes its own lock, and the input adapter may marshal reads.
    private fun snapshot(): Snapshot {
        val inputs = readInputs().toMap()
        if (crossDependencies.isEmpty()) return Snapshot(inputs, emptyMap())
        val reading = READING.get()
        if (!reading.add(this)) throw CrossRunnerCycle("Build dependencies form a cycle across runners through ${directory.name}")
        try {
            val upstreamStates = crossDependencies.groupBy { it.substringBefore('/') }.flatMap { (runner, names) ->
                val states = try {
                    upstream(runner)?.stages()?.associateBy { it.id }
                } catch (e: CrossRunnerCycle) { throw e } catch (e: Exception) {
                    return@flatMap names.map { it to Upstream(null, e.message ?: "Runner '$runner' is unavailable") }
                }
                names.map { name ->
                    val stage = name.substringAfter('/')
                    val state = states?.get(stage)
                    name to when {
                        states == null -> Upstream(null, "No build runner '$runner' in this document; run the box that defines it.")
                        state == null -> Upstream(null, "Runner '$runner' has no stage '$stage'.")
                        else -> Upstream(state.current, state.reason ?: "Not built yet")
                    }
                }
            }.toMap()
            return Snapshot(inputs, upstreamStates)
        } finally { reading.remove(this) }
    }

    /** A gate as one evaluation found it: open with [value], or closed with [problem]. */
    private class GateState(val value: String?, val problem: String?, val blockedBy: List<String>, val evidence: Map<String, StudyBuildDependency>)

    /** Stages and gates against one [Snapshot], each computed once. Used under the store lock. */
    private inner class Evaluation(private val snapshot: Snapshot) {
        private val stageStates = linkedMapOf<String, StudyStageState>()
        private val gateStates = linkedMapOf<String, GateState>()

        fun all(): Map<String, StudyStageState> { specs.keys.forEach(::stage); return stageStates }

        /** The current result of a dependency in this runner or, for `runner/stage`, another one. */
        fun current(dependency: String): StudyBuildRecord? =
            if ('/' in dependency) snapshot.upstream[dependency]?.current else stage(dependency).current

        /** An authored input, or a gate's value while it is open. */
        fun inputValue(key: String): String? = if (key in gateSpecs) gate(key).value else snapshot.inputs[key]

        fun gate(name: String): GateState = gateStates[name] ?: judge(gateSpecs.getValue(name)).also { gateStates[name] = it }

        private fun judge(gate: StudyBuildGate): GateState {
            val missing = gate.inputKeys.filter { it !in snapshot.inputs }
            if (missing.isNotEmpty()) return GateState(null, "Missing input: ${missing.joinToString()}", emptyList(), emptyMap())
            val blocked = gate.dependencies.filter { current(it) == null }
            if (blocked.isNotEmpty()) return GateState(null, "Build ${blocked.joinToString()} first.", blocked, emptyMap())
            val results = gate.dependencies.associateWith { checkNotNull(current(it)) }
            val values = gate.inputKeys.sorted().associateWith { snapshot.inputs.getValue(it) }
            val evidence = results.mapValues { (_, r) -> StudyBuildDependency(r.id, r.fingerprint, r.artifacts) }
            val problem = try { gate.check(StudyBuildGateEvidence(values, results) { dependency, r, artifact -> resultFile(dependency, r.id, artifact) }) }
                catch (e: Exception) { "Its check failed: ${e.message ?: e.javaClass.simpleName}" }
            if (problem != null) return GateState(null, problem.ifBlank { "Its check did not pass." }, emptyList(), evidence)
            val value = SourceStudy.hash(mapper.writeValueAsString(listOf("gate", gate.name, values,
                evidence.toSortedMap().mapValues { (_, d) -> listOf(d.jobId, d.fingerprint, d.artifacts) })))
            return GateState(value, null, emptyList(), evidence)
        }

        fun stage(id: String): StudyStageState {
            stageStates[id]?.let { return it }
            val spec = specs.getValue(id)
            val gated = spec.inputKeys.filter(gateSpecs::containsKey)
            val missing = spec.inputKeys.filter { it !in gateSpecs && it !in snapshot.inputs }
            val blocked = (spec.dependencies.filter { current(it) == null } + gated.flatMap { gate(it).blockedBy }).distinct()
            val closedGates = gated.filter { gate(it).value == null && gate(it).blockedBy.isEmpty() }
            val latest = records.values.lastOrNull { it.stage == id }
            val head = heads[id]?.let(records::get)
            var reason = when {
                missing.isNotEmpty() -> "Missing input: ${missing.joinToString()}"
                blocked.isNotEmpty() -> "Build current dependencies first: " +
                    blocked.joinToString { dep -> snapshot.upstream[dep]?.let { "$dep (${it.reason})" } ?: dep }
                closedGates.isNotEmpty() -> closedGates.joinToString(" ") { "Waiting for $it: ${gate(it).problem}" }
                else -> null
            }
            val fingerprint = if (reason != null) null else SourceStudy.hash(mapper.writeValueAsString(listOf(
                id, spec.recipeVersion, spec.inputKeys.sorted().associateWith { checkNotNull(inputValue(it)) },
                spec.dependencies.sorted().associateWith { dep -> checkNotNull(current(dep)).let { listOf(it.id, it.fingerprint, it.artifacts) } })))
            val current = if (head?.status == StudyBuildStatus.SUCCEEDED && head.ranAgainst(fingerprint)) {
                val corrupt = head.artifacts.firstOrNull { runCatching { verifiedFile(head.id, it) }.isFailure }
                if (corrupt != null) { reason = "Result file '${corrupt.path}' changed or is missing; rebuild this stage."; null } else head
            } else {
                if (reason == null) reason = when {
                    latest == null -> "Not built yet"
                    !latest.ranAgainst(fingerprint) -> "Inputs or recipe changed; rebuild this stage."
                    latest.status == StudyBuildStatus.FAILED -> latest.problem?.message ?: latest.message
                    latest.status == StudyBuildStatus.CANCELLED -> "Build cancelled; no current result was published."
                    latest.status == StudyBuildStatus.INTERRUPTED -> "Build interrupted; inspect the attempt before retrying."
                    latest.status in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING) -> latest.message
                    else -> "This attempt did not publish a current result; rebuild this stage."
                }
                null
            }
            val help = when {
                missing.isNotEmpty() -> spec.inputHelp
                blocked.isEmpty() && closedGates.isNotEmpty() ->
                    StudyBuildInputHelp("Waiting for ${closedGates.joinToString()}", closedGates.joinToString("\n") { checkNotNull(gate(it).problem) })
                else -> null
            }
            return StudyStageState(id, spec.label, fingerprint, current, latest, reason, blocked,
                help, current?.let(::notesFor).orEmpty()).also { stageStates[id] = it }
        }
    }

    /** A verified result file of [jobId], an attempt of this runner or, for `runner/stage`, of that runner. */
    internal fun resultFile(dependency: String, jobId: String, artifact: StudyBuildArtifact): File {
        if ('/' !in dependency) return verifiedFile(jobId, artifact)
        val runner = dependency.substringBefore('/')
        val other = upstream(runner) ?: error("No build runner '$runner' in this document; run the box that defines it.")
        return other.verifiedFile(jobId, artifact)
    }

    /** [StudyBuildContext.resumeFrom]: copy files of a finished attempt into [attempt], checked and recorded. */
    internal fun resume(attempt: StudyBuildRecord, jobId: String, paths: List<String>, sameDesign: Boolean): List<File> {
        checkCancelled(attempt.id)
        require(paths.isNotEmpty()) { "Name the files to resume from" }
        val source = synchronized(monitor) { records[jobId] } ?: error("No attempt '$jobId' in this runner to resume from")
        check(source.id != attempt.id) { "An attempt cannot resume from itself" }
        check(source.status !in ACTIVE) { "Attempt '$jobId' is still ${source.status.name.lowercase()}; resume from it once it has finished" }
        if (sameDesign) check(source.stage == attempt.stage && source.recipeVersion == attempt.recipeVersion && source.inputs == attempt.inputs &&
            source.dependencies.mapValues { it.value.jobId } == attempt.dependencies.mapValues { it.value.jobId }) {
            "Attempt '$jobId' built a different design or recipe; build from the start, or pass sameDesign = false to continue from it anyway"
        }
        val root = attemptDirectory(source.id).canonicalFile
        val copied = paths.flatMap { path ->
            val origin = artifactFile(source.id, path)
            when {
                origin.isFile -> listOf(path)
                origin.isDirectory -> origin.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
                    .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
                else -> error("Attempt '$jobId' has no '$path'")
            }
        }.distinct().map { path ->
            val from = artifactFile(source.id, path)
            source.artifacts.firstOrNull { it.path == path }?.let { verifiedFile(source.id, it) }
            val sha = hashFile(from)
            val to = artifactFile(attempt.id, path)
            to.parentFile.mkdirs()
            Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            check(hashFile(to) == sha) { "The copy of '$path' from attempt '$jobId' does not match its source" }
            StudyBuildArtifact(path, sha, to.length())
        }
        synchronized(monitor) {
            checkCancelled(attempt.id)
            val r = records.getValue(attempt.id)
            records[attempt.id] = r.copy(resumedFrom = r.resumedFrom + StudyBuildResume(jobId, copied), updated = now())
            persist()
        }
        return copied.map { artifactFile(attempt.id, it.path) }
    }

    /** The progress last recorded for [id], so a caller reporting in steps never goes backwards. */
    internal fun recordedProgress(id: String): Double = synchronized(monitor) { records.getValue(id).progress }

    private fun frozenRequest(request: Map<String, Any?>): Map<String, Any?> {
        val bytes = mapper.writeValueAsBytes(request)
        require(bytes.size <= 65536) { "A build request must be under 64 KB of JSON; put larger material in the design or a retained file" }
        return mapper.readValue(bytes)
    }

    internal fun attemptDirectory(id: String): File {
        require(id.matches(JOB_ID)) { "Invalid build ID" }
        return File(File(directory, "attempts"), id)
    }

    internal fun artifactFile(id: String, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute && '\\' !in path && path.split('/').none { it in setOf("..", ".", "") }) { "Artifact path must stay inside its build directory" }
        val root = attemptDirectory(id).canonicalFile
        var cursor = root.toPath()
        for (piece in path.split('/')) {
            cursor = cursor.resolve(piece)
            require(!Files.isSymbolicLink(cursor)) { "Artifact paths cannot pass through symbolic links" }
        }
        val file = File(root, path).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file != root && path != "input.json") { "Artifact path escapes its build directory or replaces its inputs" }
        return file
    }

    internal fun verifiedFile(id: String, artifact: StudyBuildArtifact): File = artifactFile(id, artifact.path).also {
        check(it.isFile && it.length() == artifact.bytes && hashFile(it) == artifact.sha256) { "Artifact '${artifact.path}' from build '$id' changed or is missing" }
    }

    private fun persist() = atomicWrite(File(directory, "builds.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Manifest(heads = heads, records = records.values.toList())))
    private fun ensureOpen() = check(!closed) { "Build runner is closed; run its source box to reopen it." }

    override fun close() = synchronized(monitor) {
        if (!closed) {
            records.replaceAll { _, r -> if (r.status in ACTIVE) r.copy(status = StudyBuildStatus.INTERRUPTED,
                cancelRequested = true, updated = now(), message = "Runner closed; publication revoked. Retry explicitly after reopening.") else r }
            closed = true
            try { persist() } finally { executor.shutdownNow(); lock.release(); channel.close() }
        }
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val tmp = Files.createTempFile(file.parentFile.toPath(), ".build-", ".tmp")
        try {
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { out ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) out.write(buffer)
                out.force(true)
            }
            Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(tmp) }
    }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val JOB_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val CROSS_DEPENDENCY = Regex("[a-zA-Z0-9_-]{1,80}/[a-zA-Z0-9_-]{1,80}")
        private val ACTIVE = setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)
        // The runners this thread is reading, so a dependency cycle across runners is refused instead of recursing.
        private val READING = ThreadLocal.withInitial { mutableSetOf<StudyBuilds>() }
        private fun now(): String = Instant.now().toString()

        /**
         * [StudyBuilds.prune] several runners together, so an attempt one runner keeps also keeps the
         * attempts it names in another (a `dependsOn` result, a job ID in an input). One report per runner.
         */
        fun prune(runners: Collection<StudyBuilds>, pins: Set<String> = emptySet(), dryRun: Boolean = false): List<StudyBuildPruneReport> {
            val views = runners.distinct().associateWith { it.pruneView() }
            val byId = views.values.flatMap { it.records }.associateBy { it.id }
            val keep = closure(pins + views.flatMap { (runner, view) -> runner.pruneRoots(view) }, byId::get)
            return views.keys.map { it.deleteUnneeded(keep, dryRun) }
        }

        /** [roots] and every attempt they name, transitively, among the records [find] knows. */
        private fun closure(roots: Set<String>, find: (String) -> StudyBuildRecord?): Set<String> {
            val keep = roots.filterTo(mutableSetOf()) { find(it) != null }
            val frontier = ArrayDeque(keep)
            while (frontier.isNotEmpty()) {
                val record = find(frontier.removeFirst()) ?: continue
                for (id in references(record)) if (find(id) != null && keep.add(id)) frontier.add(id)
            }
            return keep
        }

        /** The attempts [record] names: dependencies, gate evidence, resumed attempts, and job IDs in its inputs or request. */
        private fun references(record: StudyBuildRecord): Set<String> = buildSet {
            record.dependencies.values.forEach { add(it.jobId) }
            record.gates.values.forEach { results -> results.values.forEach { add(it.jobId) } }
            record.resumedFrom.forEach { add(it.jobId) }
            JOB_ID.findAll(canonicalMapper.writeValueAsString(listOf(record.inputs, record.request))).forEach { add(it.value) }
            remove(record.id)
        }

        private fun directoryBytes(directory: File): Long = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

internal val STUDY_BUILD_NAME = Regex("[a-zA-Z0-9_-]{1,80}")

private val canonicalMapper = jacksonObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)

/** An attempt's fingerprint: the stage's, and when the attempt was given a request, a hash of both. */
internal fun studyBuildFingerprint(stageFingerprint: String, request: Map<String, Any?>): String =
    if (request.isEmpty()) stageFingerprint else SourceStudy.hash(canonicalMapper.writeValueAsString(listOf(stageFingerprint, request)))

/** Whether this attempt was built against the stage fingerprint [stageFingerprint], whatever it was asked. */
internal fun StudyBuildRecord.ranAgainst(stageFingerprint: String?): Boolean =
    stageFingerprint != null && fingerprint == studyBuildFingerprint(stageFingerprint, request)
