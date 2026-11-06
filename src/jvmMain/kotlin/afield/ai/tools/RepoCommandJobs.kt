package afield.ai.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * #### Background commands: start one, come back for it
 *
 * [RepoRunCommandRequest] blocks until its command ends, which suits a unit test and not a docs
 * build or a smoke run. The knight-tour session (2026-11-06) showed what a model does without
 * this: it spawned `java -cp <Field's classpath>`, `uv run …` and `./gradlew …` from inside
 * `EvaluateKotlinExpressionRequest` with `ProcessBuilder`, wrote Python wait-loops into `build/`,
 * and then polled with 44 evaluations that each slept 8 s — six minutes of tool time, none of it
 * holding the verification lock, one of them a Gradle build in the checkout the running Field
 * loads its classes from. It worked; nothing in it was safe by construction.
 *
 * A job here is one of the same named argv templates ([RepoCommandTemplates]), with the same
 * environment, lock refusal and process-tree stop as a foreground run, but:
 *
 * - [RepoStartCommandRequest] returns at once with a job id (after a short wait, so a command that
 *   fails on its first line says so in the same call);
 * - its merged output goes to a log file, all of it (up to [MAX_LOG_BYTES]), so a later call reads
 *   any part by byte offset rather than a fixed head and tail;
 * - [RepoCommandStatusRequest] can *wait* — up to [MAX_WAIT_SEC] per call, until the job ends or
 *   prints a given text — so a model watches a ten-minute build in five calls, not seventy;
 * - jobs live in one registry for this JVM, keyed by id, so the turn after the one that started a
 *   job ("great; let me know how it goes") can read it, and the person can see every job in
 *   [RepoCommandStatusRequest] with no id;
 * - a job past its wall-clock limit is stopped with its process tree, and every running job is
 *   stopped when Field exits.
 */
object RepoCommandJobs {
    const val MAX_WAIT_SEC = 120
    const val MAX_RUNNING_PER_ROOT = 3
    const val MAX_LOG_BYTES = 32L * 1024 * 1024
    const val DEFAULT_READ_BYTES = 12 * 1024
    private const val START_PEEK_MS = 1_500L

    class Job(
        val id: String,
        val root: File,
        val name: String,
        val args: List<String>,
        val argv: List<String>,
        val log: File,
        val timeoutSec: Int,
        val process: Process,
    ) {
        val startedMs: Long = System.currentTimeMillis()
        @Volatile var endedMs: Long = 0
        @Volatile var timedOut = false
        @Volatile var stopped = false
        @Volatile var bytesWritten = 0L
        @Volatile var bytesDropped = 0L
        val alive get() = process.isAlive || endedMs == 0L
        val exitCode: Int? get() = if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()
        val state: String get() = when {
            alive -> "running"
            timedOut -> "timed-out"
            stopped -> "stopped"
            else -> "exited"
        }
        fun elapsedMs() = (if (endedMs > 0) endedMs else System.currentTimeMillis()) - startedMs
        fun summary() = RepoJobSummary(id, name, args, state, exitCode, elapsedMs(), log.absolutePath)
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val counter = AtomicInteger()

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            jobs.values.filter { it.process.isAlive }.forEach { runCatching { it.stopped = true; RepoCommandTools.stopTree(it.process.toHandle(), graceMs = 1_000) } }
        }, "repo-command-jobs-shutdown"))
    }

    fun get(id: String): Job? = jobs[id.trim()]
    fun all(): List<Job> = jobs.values.sortedBy { it.startedMs }
    fun running(root: File): List<Job> = jobs.values.filter { it.alive && it.root == root }

    /** The running job whose process tree contains [pid], if any — whose job holds a lock. */
    fun owning(pid: Long): Job? = jobs.values.firstOrNull { job ->
        job.alive && (job.process.pid() == pid || job.process.toHandle().descendants().anyMatch { it.pid() == pid })
    }

    internal fun start(root: File, name: String, args: List<String>, argv: List<String>, env: Map<String, String>, timeoutSec: Int, logDir: File): Job {
        logDir.mkdirs()
        val id = "job-${java.time.LocalTime.now().withNano(0).toString().replace(":", "")}-${counter.incrementAndGet()}"
        val log = File(logDir, "$id-$name.log")
        val pb = ProcessBuilder(argv).directory(root).redirectErrorStream(true)
        pb.environment().apply { clear(); putAll(env) }
        val process = pb.start()
        process.outputStream.close()
        val job = Job(id, root, name, args, argv, log, timeoutSec, process)
        jobs[id] = job
        Thread({ copyOutput(job) }, "repo-job-output-$id").apply { isDaemon = true; start() }
        Thread({
            if (!process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)) {
                job.timedOut = true
                RepoCommandTools.stopTree(process.toHandle())
            }
        }, "repo-job-timeout-$id").apply { isDaemon = true; start() }
        return job
    }

    private fun copyOutput(job: Job) {
        try {
            job.log.outputStream().use { out ->
                job.process.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (job.bytesWritten + n <= MAX_LOG_BYTES) {
                            out.write(buf, 0, n); out.flush(); job.bytesWritten += n
                        } else job.bytesDropped += n
                    }
                }
            }
        } finally {
            runCatching { job.process.waitFor() }
            job.endedMs = System.currentTimeMillis()
        }
    }

    /** Wait briefly after a start, so a command that dies at once reports its exit and first lines. */
    internal fun peek(job: Job) {
        val deadline = System.currentTimeMillis() + START_PEEK_MS
        while (job.alive && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    /**
     * Wait up to [waitSec] for [job] to end, or for [until] to appear in its log after [from];
     * then read the log from [from] (or its tail when [from] < 0), at most [maxBytes].
     */
    internal fun read(job: Job, from: Long, waitSec: Int, until: String, maxBytes: Int): RepoJobResult {
        val start = if (from < 0) maxOf(0L, job.bytesWritten - maxBytes) else minOf(from, job.bytesWritten)
        val deadline = System.currentTimeMillis() + waitSec.coerceIn(0, MAX_WAIT_SEC) * 1000L
        var found: Boolean? = if (until.isEmpty()) null else false
        var scannedTo = start
        while (true) {
            if (until.isNotEmpty() && job.bytesWritten > scannedTo) {
                // Re-read from a little before the last scan so a match split across two reads is seen.
                val back = maxOf(start, scannedTo - until.toByteArray().size)
                val text = slice(job.log, back, job.bytesWritten - back)
                scannedTo = job.bytesWritten
                if (text.contains(until)) { found = true; break }
            }
            if (!job.alive || System.currentTimeMillis() >= deadline) break
            Thread.sleep(200)
        }
        val end = job.bytesWritten
        val available = end - start
        val (text, truncated) = if (available <= maxBytes) slice(job.log, start, available) to false
        else {
            val head = maxBytes / 4
            val tail = maxBytes - head
            (slice(job.log, start, head.toLong()) + "\n... [${available - maxBytes} bytes skipped; read them with fromByte] ...\n" +
                slice(job.log, end - tail, tail.toLong())) to true
        }
        val exit = job.exitCode
        val message = when {
            job.alive -> "running for ${job.elapsedMs() / 1000}s" + when (found) { true -> "; saw '$until'"; false -> "; '$until' not seen yet"; null -> "" }
            job.timedOut -> "stopped at its ${job.timeoutSec}s limit"
            job.stopped -> "stopped on request"
            exit == 0 -> "exit 0 after ${job.elapsedMs() / 1000}s"
            else -> "exit $exit after ${job.elapsedMs() / 1000}s"
        } + (if (job.bytesDropped > 0) "; ${job.bytesDropped} bytes past the ${MAX_LOG_BYTES / (1024 * 1024)} MB log cap were dropped" else "")
        return RepoJobResult(
            success = job.alive || exit == 0,
            message = message, jobId = job.id, command = job.argv.joinToString(" "), state = job.state, exitCode = exit,
            elapsedMs = job.elapsedMs(), output = text, fromByte = start, nextByte = end, outputBytes = end, truncated = truncated,
            found = found, logPath = job.log.absolutePath,
        )
    }

    private fun slice(file: File, from: Long, length: Long): String {
        if (length <= 0) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                val bytes = ByteArray(length.toInt().coerceAtMost(MAX_LOG_BYTES.toInt()))
                val n = raf.read(bytes)
                if (n <= 0) "" else String(bytes, 0, n, Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    internal fun stop(job: Job): Boolean {
        if (!job.process.isAlive) return false
        job.stopped = true
        RepoCommandTools.stopTree(job.process.toHandle())
        return true
    }
}

@JsonClassDescription("Start one of the allowed repository commands in the background and return at once with its jobId " +
    "(e.g. command `docs-build`, args [\"never\"]; or `field-test`, args [\"--id\", \"examples.demo-transcript.exec\"]). " +
    "Use it for anything that may run longer than a minute — a docs build, a smoke test, a Gradle check — and RepoRunCommandRequest for a quick one. " +
    "Returns the jobId, its state after a moment (a command that fails at once comes back already exited, with its output) and the log path. " +
    "Then call RepoCommandStatusRequest with the jobId, which can wait up to 120 s per call. The job keeps running after this turn, so a later turn can check it. " +
    "Refuses a command this conversation may not run, an argument its slot rejects, a run while another process holds the checkout's verification lock, " +
    "more than three running jobs, and a build in the checkout this Field runs from once its sources have changed.")
@AgentToolMeta("repo", effects = [ToolEffect.EXECUTE_CODE, ToolEffect.FILESYSTEM_WRITE])
data class RepoStartCommandRequest(
    val command: String,
    val args: List<String> = emptyList(),
    @param:JsonPropertyDescription("Wall-clock limit in seconds before the job is stopped; 0 = the command's default.")
    val timeoutSec: Int = 0,
)

@JsonClassDescription("Read a background job's state and output, waiting for it if asked, e.g. jobId `job-213045-1`, waitSeconds 120, untilText `BUILD SUCCESSFUL`. " +
    "Use it after RepoStartCommandRequest instead of polling in a loop: one call waits until the job ends, prints untilText, or waitSeconds (at most 120) pass. " +
    "Returns state (running, exited, timed-out, stopped), exitCode, elapsedMs, the output from fromByte (or its last 12 kB when fromByte is -1) and nextByte to pass next time; " +
    "found says whether untilText appeared. With no jobId it lists every job of this Field, running or finished. " +
    "Fails for an unknown jobId; a wait that runs out is not a failure, it reports the job still running.")
@AgentToolMeta("repo", effects = [ToolEffect.FILESYSTEM_READ])
data class RepoCommandStatusRequest(
    val jobId: String = "",
    @param:JsonPropertyDescription("Seconds to wait for the job to end or print untilText: 0..120.")
    val waitSeconds: Int = 0,
    @param:JsonPropertyDescription("Stop waiting as soon as this literal text appears in output after fromByte.")
    val untilText: String = "",
    @param:JsonPropertyDescription("Byte offset to read from (the previous call's nextByte); -1 = the latest output.")
    val fromByte: Long = -1,
)

@JsonClassDescription("Stop a background job and everything it started (TERM, then KILL after a grace; Gradle daemons are left alone), e.g. jobId `job-213045-1`. " +
    "Use it when a job is no longer wanted or is stuck; a job also stops by itself at its time limit. Returns the job's final state and the end of its output. " +
    "Fails for an unknown jobId; stopping a job that already ended reports that and changes nothing.")
@AgentToolMeta("repo", effects = [ToolEffect.EXECUTE_CODE])
data class RepoStopCommandRequest(val jobId: String)

data class RepoJobSummary(val jobId: String, val command: String, val args: List<String>, val state: String, val exitCode: Int?, val elapsedMs: Long, val logPath: String)

data class RepoJobResult(
    val success: Boolean,
    val message: String,
    val jobId: String = "",
    val command: String = "",
    val state: String = "",
    val exitCode: Int? = null,
    val elapsedMs: Long = 0,
    val output: String = "",
    val fromByte: Long = 0,
    val nextByte: Long = 0,
    val outputBytes: Long = 0,
    val truncated: Boolean = false,
    val found: Boolean? = null,
    val logPath: String = "",
    val jobs: List<RepoJobSummary> = emptyList(),
)

@JsonClassDescription("End this turn and let Field wait for background jobs without the model, then resume this conversation with how each ended " +
    "(state, exit code, time and the last lines of its log), e.g. jobIds [`job-213045-1`], maxMinutes 60. " +
    "Use it after RepoStartCommandRequest when a job will take more than a couple of minutes — a docs build, a smoke run — instead of repeated " +
    "RepoCommandStatusRequest waits: every wait costs a model round and this costs none. Returns at once with suspended=true; call no other tool after it in this turn. " +
    "If every job has already ended it returns their outcomes with suspended=false and the turn goes on. " +
    "Fails for an unknown jobId, more than 10 jobs, or maxMinutes outside 1..720.")
@AgentToolMeta("repo", effects = [ToolEffect.AGENT_STATE])
data class AwaitRepoCommandsRequest(val jobIds: List<String>, val maxMinutes: Int = 60)

data class RepoAwaitResult(
    val success: Boolean,
    val message: String,
    val suspended: Boolean = false,
    val jobs: List<RepoJobSummary> = emptyList(),
    val resumesBy: String = "",
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    override val suspension: afield.ai.conversation.TurnSuspension? = null,
) : afield.ai.conversation.SuspendsTurn

internal fun awaitRepoCommands(q: AwaitRepoCommandsRequest, now: Long = System.currentTimeMillis()): RepoAwaitResult {
    if (q.maxMinutes !in 1..720) return RepoAwaitResult(false, "maxMinutes must be 1..720")
    val ids = q.jobIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (ids.isEmpty() || ids.size > 10) return RepoAwaitResult(false, "name between 1 and 10 jobIds, as RepoStartCommandRequest returned them")
    val jobs = ids.map { id -> RepoCommandJobs.get(id) ?: return RepoAwaitResult(false, "no job '$id'; known: " +
        RepoCommandJobs.all().joinToString { it.id }.ifBlank { "(none)" }) }
    if (jobs.none { it.alive }) return RepoAwaitResult(true, "Every job has already ended; nothing to wait for. Read each with RepoCommandStatusRequest.",
        jobs = jobs.map { it.summary() })
    val deadline = now + q.maxMinutes * 60_000L
    val awaited = jobs.map { afield.ai.conversation.AwaitedBuild(boxId = "", name = it.name, jobId = it.id,
        label = (listOf(it.name) + it.args).joinToString(" ").take(120), kind = afield.ai.conversation.AwaitedBuild.REPO_JOB) }
    return RepoAwaitResult(true, "Your turn ends here. Field waits for these jobs without the model and resumes this conversation " +
        "with how they ended when every one has, or at resumesBy. Call no other tool in this turn.",
        suspended = true, jobs = jobs.map { it.summary() }, resumesBy = java.time.Instant.ofEpochMilli(deadline).toString(),
        suspension = afield.ai.conversation.TurnSuspension(awaited, deadline))
}
