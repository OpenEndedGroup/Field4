@file:afield.CompletionCategory("Workspace")

package afield.stdlib

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

/** Durable record of one attempt, including the exact named input values and dependency results. */
data class StudyBuildRecord(
    val id: String, val stage: String, val recipeVersion: String, val fingerprint: String,
    val inputs: Map<String, String>, val dependencies: Map<String, StudyBuildDependency>,
    val status: StudyBuildStatus = StudyBuildStatus.QUEUED,
    val progress: Double = 0.0, val message: String = "Queued", val cancelRequested: Boolean = false,
    val created: String = Instant.now().toString(), val updated: String = created,
    val artifacts: List<StudyBuildArtifact> = emptyList(), val facts: Map<String, Any?> = emptyMap(),
    val problem: StudyBuildProblem? = null,
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
 * A reusable build stage. [inputKeys] selects only the authored inputs this stage consumes;
 * [dependencies] names other stages whose current results must exist first. [recipeVersion] must
 * change when the adapter or its relevant runtime changes. The runner does not infer code identity
 * from a closure. [inputHelp] explains missing inputs without making the stage runnable or changing
 * its fingerprint. [resultNotes] is a fast, pure presentation of a completed record's retained facts;
 * it must not inspect mutable design state or change acceptance. It is shared by UI and agent tools
 * and does not change build identity. Qualification gates belong in the build and input contracts.
 * [build] runs on a worker and must use its captured context, not mutable live input.
 */
class StudyBuildStage(val id: String, val label: String, val recipeVersion: String,
    val inputKeys: Set<String> = emptySet(), val dependencies: List<String> = emptyList(),
    val inputHelp: StudyBuildInputHelp? = null,
    val resultNotes: (StudyBuildRecord) -> List<StudyBuildNotice> = { emptyList() },
    val build: (StudyBuildContext) -> StudyBuildProduct)

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
    val directory: File get() = store.attemptDirectory(attempt.id)
    fun file(path: String): File = store.artifactFile(attempt.id, path)
    fun checkCancelled() = store.checkCancelled(attempt.id)
    fun progress(fraction: Double, message: String) = store.progress(attempt.id, fraction, message)

    /** Read a frozen dependency file; a changed file refuses instead of becoming silently current. */
    fun dependencyFile(stage: String, path: String): File {
        val dependency = attempt.dependencies[stage] ?: error("Stage '$stage' is not a dependency")
        val artifact = dependency.artifacts.firstOrNull { it.path == path } ?: error("No '$path' in dependency '$stage'")
        return store.verifiedFile(dependency.jobId, artifact)
    }
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
    private val readInputs: () -> Map<String, String>, workers: Int = 2) : AutoCloseable {
    private val gate = Any()
    private val mapper = jacksonObjectMapper()
    private val specs = definitions.associateBy { it.id }
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
        definitions.forEach {
            require(it.id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && it.label.isNotBlank() && it.recipeVersion.isNotBlank()) { "Each stage needs a simple ID, label and recipe version" }
            require(it.dependencies.distinct().size == it.dependencies.size && it.dependencies.all(specs::containsKey)) { "Unknown or repeated dependency of ${it.id}" }
        }
        fun visit(id: String, path: Set<String>) {
            require(id !in path) { "Build dependency cycle through $id" }
            specs.getValue(id).dependencies.forEach { visit(it, path + id) }
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
        val inputs = readInputs().toMap()
        return synchronized(gate) { ensureOpen(); states(inputs).values.toList() }
    }

    /** A retained attempt, including obsolete, failed and interrupted attempts. */
    fun job(id: String): StudyBuildRecord = synchronized(gate) { ensureOpen(); records[id] ?: error("Unknown build '$id'") }

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
    fun history(offset: Int = 0, limit: Int = 20): List<StudyBuildRecord> = synchronized(gate) {
        require(offset >= 0 && limit in 1..100)
        ensureOpen(); records.values.toList().asReversed().drop(offset).take(limit)
    }

    /** Queue a stage against the fingerprint from [stages]; a stale request changes nothing. */
    fun start(stage: String, expectedFingerprint: String): StudyBuildRecord {
        val inputs = readInputs().toMap()
        return synchronized(gate) {
            ensureOpen()
            val state = states(inputs)[stage] ?: error("Unknown stage '$stage'")
            check(state.fingerprint != null) { state.reason ?: "Stage is blocked" }
            check(state.fingerprint == expectedFingerprint) { "Build inputs changed; inspect the stages and try again." }
            check(records.values.none { it.stage == stage && it.status in ACTIVE }) { "Stage '$stage' already has a running build." }
            val spec = specs.getValue(stage)
            val allStates = states(inputs)
            val dependencies = spec.dependencies.associateWith { dep ->
                val r = checkNotNull(allStates.getValue(dep).current)
                StudyBuildDependency(r.id, r.fingerprint, r.artifacts)
            }
            val record = StudyBuildRecord(UUID.randomUUID().toString(), stage, spec.recipeVersion, state.fingerprint,
                spec.inputKeys.sorted().associateWith { inputs.getValue(it) }, dependencies)
            attemptDirectory(record.id).mkdirs()
            atomicWrite(File(attemptDirectory(record.id), "input.json"), mapper.writeValueAsBytes(record))
            records[record.id] = record
            persist()
            executor.submit { execute(record, spec) }
            record
        }
    }

    /** Signal cancellation. A running attempt remains running until its worker actually returns. */
    fun cancel(id: String): StudyBuildRecord = synchronized(gate) {
        ensureOpen()
        val record = records[id] ?: error("Unknown build '$id'")
        if (record.status !in ACTIVE) return@synchronized record
        record.copy(cancelRequested = true, message = "Cancellation requested; waiting for the worker.", updated = now()).also {
            records[id] = it; persist()
        }
    }

    internal fun checkCancelled(id: String) = synchronized(gate) {
        if (closed || records[id]?.cancelRequested != false || Thread.currentThread().isInterrupted)
            throw CancellationException("Build cancelled; previous published result retained.")
    }

    internal fun progress(id: String, fraction: Double, message: String) = synchronized(gate) {
        checkCancelled(id)
        require(fraction.isFinite() && fraction in 0.0..1.0 && message.length <= 4000) { "Progress must be 0..1 with a short message" }
        val r = records.getValue(id)
        check(r.status == StudyBuildStatus.RUNNING)
        require(fraction >= r.progress) { "Progress cannot go backwards within one attempt" }
        records[id] = r.copy(progress = fraction, message = message, updated = now()); persist()
    }

    private fun execute(initial: StudyBuildRecord, spec: StudyBuildStage) {
        try {
            synchronized(gate) {
                checkCancelled(initial.id)
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
            val inputsNow = readInputs().toMap()
            synchronized(gate) {
                checkCancelled(initial.id)
                val current = states(inputsNow).getValue(initial.stage)
                val publish = current.fingerprint == initial.fingerprint
                val completed = records.getValue(initial.id).copy(
                    status = if (publish) StudyBuildStatus.SUCCEEDED else StudyBuildStatus.OBSOLETE,
                    progress = 1.0, artifacts = artifacts, facts = facts, updated = now(),
                    message = if (publish) "Build complete" else "Inputs changed during this build; result retained for inspection.")
                records[initial.id] = completed
                if (publish) heads[initial.stage] = initial.id
                persist()
            }
        } catch (e: Throwable) {
            synchronized(gate) {
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

    private fun states(inputs: Map<String, String>): Map<String, StudyStageState> {
        val result = linkedMapOf<String, StudyStageState>()
        fun state(id: String): StudyStageState {
            result[id]?.let { return it }
            val spec = specs.getValue(id)
            val deps = spec.dependencies.associateWith { state(it) }
            val missing = spec.inputKeys.filter { it !in inputs }
            val blocked = deps.filterValues { it.current == null }.keys
            val latest = records.values.lastOrNull { it.stage == id }
            val head = heads[id]?.let(records::get)
            var reason = when {
                missing.isNotEmpty() -> "Missing input: ${missing.joinToString()}"
                blocked.isNotEmpty() -> "Build current dependencies first: ${blocked.joinToString()}"
                else -> null
            }
            val fingerprint = if (reason != null) null else SourceStudy.hash(mapper.writeValueAsString(listOf(
                id, spec.recipeVersion, spec.inputKeys.sorted().associateWith { inputs.getValue(it) },
                deps.toSortedMap().mapValues { (_, s) -> s.current!!.let { listOf(it.id, it.fingerprint, it.artifacts) } })))
            val current = if (head?.status == StudyBuildStatus.SUCCEEDED && head.fingerprint == fingerprint) {
                val corrupt = head.artifacts.firstOrNull { runCatching { verifiedFile(head.id, it) }.isFailure }
                if (corrupt != null) { reason = "Result file '${corrupt.path}' changed or is missing; rebuild this stage."; null } else head
            } else {
                if (reason == null) reason = when {
                    latest == null -> "Not built yet"
                    latest.fingerprint != fingerprint -> "Inputs or recipe changed; rebuild this stage."
                    latest.status == StudyBuildStatus.FAILED -> latest.problem?.message ?: latest.message
                    latest.status == StudyBuildStatus.CANCELLED -> "Build cancelled; no current result was published."
                    latest.status == StudyBuildStatus.INTERRUPTED -> "Build interrupted; inspect the attempt before retrying."
                    latest.status in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING) -> latest.message
                    else -> "This attempt did not publish a current result; rebuild this stage."
                }
                null
            }
            return StudyStageState(id, spec.label, fingerprint, current, latest, reason, blocked.toList(),
                spec.inputHelp.takeIf { missing.isNotEmpty() }, current?.let(::notesFor).orEmpty()).also { result[id] = it }
        }
        specs.keys.forEach(::state)
        return result
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

    override fun close() = synchronized(gate) {
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
        private val ACTIVE = setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)
        private fun now(): String = Instant.now().toString()
    }
}
