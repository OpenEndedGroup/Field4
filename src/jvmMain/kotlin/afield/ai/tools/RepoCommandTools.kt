package afield.ai.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * #### Bounded commands for a headless AI4 job
 *
 * The `repo` command tool lets a job that already has [RepoFileTools] run a *named* command in its
 * checkout, so it can reproduce a failure and confirm a fix (the landing queue's fixer needs both).
 * It is not a shell. A job lists the names it allows ([RepoCommandScope.allowed]); each name is an
 * argv template ([RepoCommandTemplates]) whose free slots take only what that slot's check admits
 * (a Gradle task name, a `tools/test_*.py` path the job may read, a git revision). The process:
 *
 * - is started from an argv list (no shell, no interpolation), with the job's root as its cwd and
 *   stdin closed;
 * - gets the Field JVM's environment minus every `GIT_*` variable, `VIRTUAL_ENV` (uv would warn and
 *   ignore it) and the verification-lock owner marker, plus `AFIELD_VERIFICATION_LOCK=fail`, `GIT_TERMINAL_PROMPT=0` and `GIT_PAGER=cat`;
 * - is refused outright while another live process holds the root's `.field-backdoor/verification.lock`
 *   (unless that process is an ancestor of this JVM, i.e. the job's own runner);
 * - is stopped with its whole process tree (TERM, then KILL after a grace) at its wall-clock cap. A
 *   Gradle daemon in that tree is left alone: daemons are shared by every checkout, and killing the
 *   client cancels its build;
 * - has stdout and stderr merged and capped: the first [HEAD_BYTES] and the last bytes up to the cap
 *   are kept, the middle is dropped and counted.
 *
 * One command runs at a time per job.
 */

/** What one job may run, and how long and how loud each run may be. */
data class RepoCommandScope(
    val allowed: Set<String>,
    val maxTimeoutSec: Int = 900,
    val maxOutputBytes: Int = 48 * 1024,
)

@JsonClassDescription("Run one of this job's allowed commands in its repository (not a shell: a named argv template). " +
    "command is the name (e.g. `gradle`, `python-test`, `git-diff`; the job's prompt lists which it allows), args fill its slots: " +
    "gradle: task names and `--tests <pattern>`, e.g. [\"jvmTest\", \"--tests\", \"afield.ai.tools.RepoFileToolsTest\"]; " +
    "python-test: a `tools/test_*.py` path then optional unittest names; check: a `tools/check_*.py` path; " +
    "git-status: none; git-diff: optional `--stat`/`--cached`, revisions, then `--` and paths; git-log: optional `-n N`, revisions, `--`, paths; " +
    "git-show: one revision. Returns exitCode, timedOut and the merged output (head and tail kept when long). " +
    "Use it to reproduce a failure and to confirm a fix after patching; to look at files, use RepoReadFileRequest or RepoSearchRequest instead. " +
    "Refuses a command the job does not allow, an argument its slot rejects, and any run while another process holds the checkout's verification lock; " +
    "a run past its timeout is stopped and comes back with timedOut=true.")
@AgentToolMeta("repo", effects = [ToolEffect.EXECUTE_CODE, ToolEffect.FILESYSTEM_WRITE])
data class RepoRunCommandRequest(
    val command: String,
    val args: List<String> = emptyList(),
    @param:JsonPropertyDescription("Wall-clock limit in seconds; 0 = the command's default. Capped by the job.")
    val timeoutSec: Int = 0,
)

data class RepoCommandResult(
    val success: Boolean,
    val message: String,
    val command: String = "",
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val elapsedMs: Long = 0,
    val output: String = "",
    val outputBytes: Long = 0,
    val truncated: Boolean = false,
)

/**
 * One allowed command: a fixed argv prefix, a check that turns the model's args into the rest, and its
 * default timeout. [touchesBuild] says whether this argv may compile Field (Gradle, or a runner that
 * starts a Field of its own), which [RepoCommandTools.liveBuildHazard] refuses under a running Field.
 */
class RepoCommandTemplate(
    val name: String,
    val summary: String,
    val defaultTimeoutSec: Int,
    val touchesBuild: (argv: List<String>) -> Boolean = { false },
    val build: (args: List<String>, files: RepoFileTools) -> List<String>,
)

/** The command names a job can allow. [FIXER_SET] is what the landing queue's fixer gets. */
object RepoCommandTemplates {
    private val TASK = Regex("^:?[A-Za-z][A-Za-z0-9_-]*(:[A-Za-z][A-Za-z0-9_-]*)*$")
    private val DENIED_TASKS = Regex("(?i)^:?(.*:)?(clean|wrapper|run[A-Z]\\w*|runJvm|runDesktop|publish\\w*|generateLocalKeystore|init)$")
    private val TEST_FILTER = Regex("^[A-Za-z0-9_.*$#]+( [A-Za-z0-9_.*$#]+)*$")
    private val UNITTEST_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$")
    private val REVISION = Regex("^[A-Za-z0-9_][A-Za-z0-9_./~^@{}-]*(\\.\\.\\.?[A-Za-z0-9_][A-Za-z0-9_./~^@{}-]*)?$")
    private val GIT_PREFIX = listOf("git", "--no-pager", "-c", "core.fsmonitor=false", "-c", "diff.external=")

    private fun refuse(message: String): Nothing = throw RepoPathRefused(message)

    private fun readablePath(raw: String, files: RepoFileTools, pattern: Regex, what: String): String {
        if (!pattern.matches(raw)) refuse("'$raw' is not $what")
        val path = files.resolve(raw, write = false)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) refuse("no such file: $raw")
        return raw
    }

    private fun revision(raw: String): String {
        if (raw.startsWith("-") || !REVISION.matches(raw)) refuse("'$raw' is not a git revision")
        return raw
    }

    /** `[flags/revisions] [-- paths]` for git-diff and git-log; [flags] maps an allowed flag to how many values follow it. */
    private fun gitTail(args: List<String>, files: RepoFileTools, flags: Map<String, Regex?>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < args.size && args[i] != "--") {
            val a = args[i]
            if (a.startsWith("-")) {
                if (a !in flags) refuse("git option '$a' is not allowed here; allowed: ${flags.keys.joinToString(", ")}")
                out += a
                flags[a]?.let { value ->
                    val v = args.getOrNull(i + 1) ?: refuse("'$a' needs a value")
                    if (!value.matches(v)) refuse("'$v' is not a value for '$a'")
                    out += v; i++
                }
            } else out += revision(a)
            i++
        }
        if (i < args.size) {
            out += "--"
            for (p in args.drop(i + 1)) { files.resolve(p, write = false); out += p }
        }
        return out
    }

    val ALL: Map<String, RepoCommandTemplate> = listOf(
        RepoCommandTemplate("gradle", "./gradlew --console=plain <tasks> [--tests <pattern>]", 900, touchesBuild = { true }) { args, files ->
            val root = files.resolve("gradlew", write = false)
            if (!Files.isRegularFile(root, NOFOLLOW_LINKS)) refuse("this repository has no gradlew")
            val out = mutableListOf("./gradlew", "--console=plain")
            var i = 0
            var tasks = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "--tests" -> {
                        val v = args.getOrNull(i + 1) ?: refuse("--tests needs a pattern")
                        if (!TEST_FILTER.matches(v)) refuse("'$v' is not a test filter (class or method names, * wildcards)")
                        out += listOf(a, v); i++
                    }
                    a == "--offline" || a == "--continue" || a == "--rerun" -> out += a
                    a.startsWith("-") -> refuse("gradle option '$a' is not allowed; only task names, --tests, --offline, --continue, --rerun")
                    !TASK.matches(a) -> refuse("'$a' is not a task name")
                    DENIED_TASKS.matches(a) -> refuse("task '$a' is not allowed (it cleans, publishes, or starts a long-running app)")
                    else -> { out += a; tasks++ }
                }
                i++
            }
            if (tasks == 0) refuse("name at least one task, e.g. compileKotlinJvm or jvmTest --tests <Class>")
            out
        },
        RepoCommandTemplate("python-test", "uv run --project . --group backdoor python -m unittest tools.test_* [Class or Class.test_method]: give the tools/test_*.py path", 600) { args, files ->
            val script = args.firstOrNull() ?: refuse("give the test file, e.g. tools/test_field_agent.py")
            val names = args.drop(1).onEach { if (!UNITTEST_NAME.matches(it)) refuse("'$it' is not a unittest name (Class or Class.test_method)") }
            val path = readablePath(script, files, Regex("^(tools|python)/([A-Za-z0-9_]+/)*test_[A-Za-z0-9_]+\\.py$"), "a tools/test_*.py file")
            // As a module from the root, the way tools/run_python_tests.py loads them: most suites import `from tools import ...`,
            // which fails when the file is run as a script (seen 2026-11-07 with test_ai4_conversation_review.py).
            val module = path.removeSuffix(".py").replace('/', '.')
            listOf("uv", "run", "--project", ".", "--group", "backdoor", "python", "-m", "unittest") +
                (if (names.isEmpty()) listOf(module) else names.map { "$module.$it" })
        },
        RepoCommandTemplate("check", "uv run --project . --group backdoor python tools/check_*.py", 600) { args, files ->
            if (args.size != 1) refuse("give exactly one tools/check_*.py path and no options")
            listOf("uv", "run", "--project", ".", "--group", "backdoor", "python",
                readablePath(args[0], files, Regex("^tools/check_[A-Za-z0-9_]+\\.py$"), "a tools/check_*.py file"))
        },
        RepoCommandTemplate("git-status", "git status --short --branch", 60) { args, _ ->
            if (args.isNotEmpty()) refuse("git-status takes no arguments")
            GIT_PREFIX + listOf("status", "--short", "--branch")
        },
        RepoCommandTemplate("git-diff", "git diff --no-color --no-ext-diff --no-textconv [--stat|--cached] [revs] [-- paths]", 60) { args, files ->
            GIT_PREFIX + listOf("diff", "--no-color", "--no-ext-diff", "--no-textconv") +
                gitTail(args, files, mapOf("--stat" to null, "--cached" to null, "--name-only" to null, "--name-status" to null))
        },
        RepoCommandTemplate("git-log", "git log --oneline --no-color [-n N] [revs] [-- paths]", 60) { args, files ->
            val tail = gitTail(args, files, mapOf("-n" to Regex("^[1-9][0-9]{0,2}$"), "--stat" to null))
            GIT_PREFIX + listOf("log", "--oneline", "--no-color", "--no-ext-diff") + (if ("-n" in tail) emptyList() else listOf("-n", "20")) + tail
        },
        RepoCommandTemplate("git-show", "git show --stat --no-color <rev>", 60) { args, _ ->
            if (args.size != 1) refuse("git-show takes one revision")
            GIT_PREFIX + listOf("show", "--stat", "--no-color", "--no-ext-diff", "--no-textconv", revision(args[0]))
        },
        RepoCommandTemplate("field-test", "uv run --project . --group backdoor python tools/run_field_tests.py (--id <catalog-id> | --tag <tag>)... [--fail-fast]: the canonical smoke runner", 3600,
            touchesBuild = { true }) { args, _ ->
            val out = mutableListOf("uv", "run", "--project", ".", "--group", "backdoor", "python", "tools/run_field_tests.py")
            var i = 0
            var selected = 0
            while (i < args.size) {
                when (val a = args[i]) {
                    "--id", "--tag" -> {
                        val v = args.getOrNull(i + 1) ?: refuse("$a needs a value")
                        if (!CATALOG_ID.matches(v)) refuse("'$v' is not a smoke-catalog id or tag")
                        out += listOf(a, v); i++; selected++
                    }
                    "--fail-fast", "--refresh-catalog" -> out += a
                    else -> refuse("field-test takes --id <id>, --tag <tag>, --fail-fast and --refresh-catalog; not '$a'")
                }
                i++
            }
            if (selected == 0) refuse("name what to run: --id <catalog-id> or --tag <tag> (the whole catalog takes hours)")
            out
        },
        RepoCommandTemplate("jvm-main", "a Field JVM entrypoint through the backdoor's run-jvm-main, which adds the JVM flags Field needs: <afield.Class> [args...]", 1800,
            touchesBuild = { true }) { args, _ ->
            val entry = args.firstOrNull() ?: refuse("give the entrypoint class, e.g. afield.examples.DocumentToTranscript")
            if (!ENTRYPOINT.matches(entry)) refuse("'$entry' is not an afield.* class name")
            val rest = args.drop(1).onEach { if (it.length > 4096 || it.any { c -> c == '\u0000' || c == '\n' }) refuse("an argument must be one line") }
            val command = (listOf("run-jvm-main", entry) + rest.flatMap { listOf("--arg", it) }).joinToString(" ", transform = ::shellQuote)
            listOf("uv", "run", "--project", ".", "--group", "backdoor", "python", "tools/field_backdoor.py", "--json", "--command", command)
        },
        RepoCommandTemplate("docs-build", "FIELD_DOCS_RUNNER_MODE=<never|incremental|strict|always> tools/build_zensical_docs.py [--page <page.md>]... [--clean]", 3600,
            touchesBuild = { argv -> "FIELD_DOCS_RUNNER_MODE=never" !in argv }) { args, files ->
            val mode = args.firstOrNull() ?: refuse("give the runner mode first: never (HTML only), incremental, strict or always")
            if (mode !in RUNNER_MODES) refuse("'$mode' is not a runner mode; one of ${RUNNER_MODES.joinToString(", ")}")
            val out = mutableListOf("env", "FIELD_DOCS_RUNNER_MODE=$mode", "uv", "run", "--project", ".", "--group", "docs", "python", "tools/build_zensical_docs.py")
            var i = 1
            while (i < args.size) {
                when (val a = args[i]) {
                    "--page" -> {
                        val v = args.getOrNull(i + 1) ?: refuse("--page needs a page, e.g. knight_tour_workshop.md")
                        readablePath("public/docs/$v", files, Regex("^public/docs/[A-Za-z0-9_./-]+\\.md$"), "a page under public/docs")
                        out += listOf(a, v); i++
                    }
                    "--clean" -> out += a
                    else -> refuse("docs-build takes the mode, then --page <page.md> and --clean; not '$a'")
                }
                i++
            }
            out
        },
        RepoCommandTemplate("docs-publications", "tools/docs_publications.py <sync|check|list> [--mode <mode>] [--group pilot|gallery] [--name <recipe>]...", 3600,
            touchesBuild = { argv -> "check" !in argv && "list" !in argv }) { args, _ ->
            val action = args.firstOrNull() ?: refuse("give the action: sync, check or list")
            if (action !in setOf("sync", "check", "list")) refuse("'$action' is not one of sync, check, list")
            val out = mutableListOf("uv", "run", "--project", ".", "--group", "docs", "python", "tools/docs_publications.py", action)
            var i = 1
            while (i < args.size) {
                val a = args[i]
                val v = args.getOrNull(i + 1)
                when {
                    a == "--mode" && v in RUNNER_MODES -> { out += listOf(a, v!!); i++ }
                    a == "--group" && v in setOf("pilot", "gallery") -> { out += listOf(a, v!!); i++ }
                    a == "--name" && v != null && CATALOG_ID.matches(v) -> { out += listOf(a, v); i++ }
                    else -> refuse("docs-publications takes --mode <${RUNNER_MODES.joinToString("|")}>, --group <pilot|gallery>, --name <recipe>; not '$a ${v ?: ""}'".trim())
                }
                i++
            }
            out
        },
        RepoCommandTemplate("land-precheck", "uv run python tools/land.py precheck (merges main in uncommitted, runs the guards your diff can trip, undoes the merge)", 1800) { args, _ ->
            if (args.isNotEmpty()) refuse("land-precheck takes no arguments")
            listOf("uv", "run", "python", "tools/land.py", "precheck")
        },
    ).associateBy { it.name }

    /** Reproduce and confirm, nothing that changes git state: what `field_agent.headless_argv("field", edits=True)` allows. */
    val FIXER_SET: List<String> = listOf("gradle", "python-test", "check", "git-status", "git-diff", "git-log", "git-show")

    /**
     * What the document chat may run in Field's own checkout: the fixer's set plus the docs, smoke and
     * entrypoint runners a publishing turn needs (the knight-tour session reached for all of them through
     * `ProcessBuilder`). Nothing that changes git state, and nothing that lands.
     */
    val CHAT_SET: List<String> = FIXER_SET + listOf("field-test", "jvm-main", "docs-build", "docs-publications")

    private val CATALOG_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,120}$")
    private val ENTRYPOINT = Regex("^afield(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    private val RUNNER_MODES = listOf("never", "incremental", "strict", "always")

    /** POSIX single-quoting, for the one template ([jvm-main]) whose argument is a command line another tool splits. */
    internal fun shellQuote(s: String): String =
        if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "@%+=:,./_-" }) s else "'" + s.replace("'", "'\"'\"'") + "'"
}

/** The command tool over one job's [RepoFileTools]. */
class RepoCommandTools(val files: RepoFileTools, val scope: RepoCommandScope) {
    private val root: File = files.scope.root.toAbsolutePath().normalize().toFile()
    private val lock = Any()

    /** Every run, in order, for the job's report: `name args -> exit` (or `timeout`). */
    val runs: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    init {
        val unknown = scope.allowed - RepoCommandTemplates.ALL.keys
        require(unknown.isEmpty()) { "unknown repo command(s) $unknown; known: ${RepoCommandTemplates.ALL.keys}" }
    }

    /** The lines the job's prompt shows: one per allowed command. */
    fun describeAllowed(): String = scope.allowed.sorted().joinToString("\n") { "- ${it}: ${RepoCommandTemplates.ALL.getValue(it).summary}" }

    /** A command checked and ready to start, or why it may not. */
    private sealed interface Prepared {
        data class Ready(val name: String, val argv: List<String>, val timeoutSec: Int) : Prepared
        data class Refused(val message: String, val command: String = "") : Prepared
    }

    private fun prepare(command: String, args: List<String>, timeoutSec: Int): Prepared {
        val name = command.trim()
        val template = RepoCommandTemplates.ALL[name]
        if (template == null || name !in scope.allowed)
            return Prepared.Refused("'$name' is not a command this job may run; allowed: ${scope.allowed.sorted().joinToString(", ").ifBlank { "(none)" }}")
        val argv = try { template.build(args, files) } catch (e: RepoPathRefused) {
            return Prepared.Refused(e.message ?: "refused", name)
        }
        if (template.touchesBuild(argv)) liveBuildHazard()?.let { return Prepared.Refused(it, argv.joinToString(" ")) }
        lockHolder()?.let { holder ->
            val job = Regex("\"pid\"\\s*:\\s*(\\d+)").find(holder)?.groupValues?.get(1)?.toLongOrNull()?.let(RepoCommandJobs::owning)
            return Prepared.Refused(
                if (job != null) "this checkout's verification lock is held by your own background job ${job.id} (${job.name}); " +
                    "wait for it with RepoCommandStatusRequest(jobId=\"${job.id}\", waitSeconds=120), or stop it"
                else "this checkout's verification lock is held by $holder; try again later or work from the files",
                argv.joinToString(" "))
        }
        val timeout = (if (timeoutSec > 0) timeoutSec else template.defaultTimeoutSec).coerceIn(1, scope.maxTimeoutSec)
        return Prepared.Ready(name, argv, timeout)
    }

    fun run(req: RepoRunCommandRequest): RepoCommandResult = synchronized(lock) {
        val ready = when (val p = prepare(req.command, req.args, req.timeoutSec)) {
            is Prepared.Refused -> return RepoCommandResult(false, p.message, p.command)
            is Prepared.Ready -> p
        }
        val result = execute(resolveExecutable(ready.argv), ready.timeoutSec)
        runs += "${ready.name} ${req.args.joinToString(" ")} -> ${if (result.timedOut) "timeout" else result.exitCode}".trim()
        result.copy(command = ready.argv.joinToString(" "))
    }

    /** Start [req] in the background ([RepoCommandJobs]) and report it after a moment. */
    fun start(req: RepoStartCommandRequest): RepoJobResult = synchronized(lock) {
        val ready = when (val p = prepare(req.command, req.args, req.timeoutSec)) {
            is Prepared.Refused -> return RepoJobResult(false, p.message, command = p.command)
            is Prepared.Ready -> p
        }
        val running = RepoCommandJobs.running(root)
        if (running.size >= RepoCommandJobs.MAX_RUNNING_PER_ROOT)
            return RepoJobResult(false, "${running.size} jobs are already running here (${running.joinToString { "${it.id} ${it.name}" }}); " +
                "wait for one with RepoCommandStatusRequest or stop it with RepoStopCommandRequest", command = ready.argv.joinToString(" "))
        val job = try {
            RepoCommandJobs.start(root, ready.name, req.args, resolveExecutable(ready.argv), environment(System.getenv()), ready.timeoutSec,
                File(root, "build/ai4-jobs"))
        } catch (e: java.io.IOException) {
            return RepoJobResult(false, "could not start ${ready.argv.first()}: ${e.message}", command = ready.argv.joinToString(" "))
        }
        runs += "${ready.name} ${req.args.joinToString(" ")} -> started ${job.id}".trim()
        RepoCommandJobs.peek(job)
        val read = RepoCommandJobs.read(job, from = 0, waitSec = 0, until = "", maxBytes = RepoCommandJobs.DEFAULT_READ_BYTES)
        read.copy(message = (if (job.alive) "started ${job.id}; " else "started ${job.id}, and it already ended: ") + read.message +
            (if (job.alive) ". Follow it with RepoCommandStatusRequest(jobId=\"${job.id}\", waitSeconds=120, fromByte=${read.nextByte})." else ""))
    }

    fun status(req: RepoCommandStatusRequest): RepoJobResult {
        if (req.jobId.isBlank()) {
            val all = RepoCommandJobs.all()
            return RepoJobResult(true, if (all.isEmpty()) "no background jobs in this Field" else "${all.size} job(s), ${all.count { it.alive }} running",
                jobs = all.map { it.summary() })
        }
        val job = RepoCommandJobs.get(req.jobId) ?: return RepoJobResult(false, "no job '${req.jobId}'; known: " +
            RepoCommandJobs.all().joinToString { it.id }.ifBlank { "(none)" })
        return RepoCommandJobs.read(job, req.fromByte, req.waitSeconds, req.untilText, scope.maxOutputBytes.coerceAtMost(RepoCommandJobs.DEFAULT_READ_BYTES * 4))
    }

    fun stop(req: RepoStopCommandRequest): RepoJobResult {
        val job = RepoCommandJobs.get(req.jobId) ?: return RepoJobResult(false, "no job '${req.jobId}'; known: " +
            RepoCommandJobs.all().joinToString { it.id }.ifBlank { "(none)" })
        val stopped = RepoCommandJobs.stop(job)
        val deadline = System.currentTimeMillis() + 5_000
        while (job.alive && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val read = RepoCommandJobs.read(job, from = -1, waitSec = 0, until = "", maxBytes = 4 * 1024)
        return read.copy(success = !job.alive, message = (if (stopped) "stopped ${job.id}: " else "${job.id} had already ended: ") + read.message)
    }

    /**
     * Why a build may not run here now, or null. Field loads its classes from `<root>/build/classes`
     * while it runs; a compile there rewrites them, and a class it has not loaded yet then fails to
     * resolve for the rest of the process's life (`NoClassDefFoundError`, cached at the call site).
     * So when this JVM runs from [root]'s build output and a source file is newer than this JVM,
     * which is exactly when Gradle would recompile, the build is refused. Unchanged sources mean an
     * up-to-date compile, which rewrites nothing: the knight-tour session's Gradle run was that case.
     */
    internal fun liveBuildHazard(
        classPath: String = System.getProperty("java.class.path").orEmpty(),
        startedMs: Long? = ProcessHandle.current().info().startInstant().orElse(null)?.toEpochMilli(),
    ): String? {
        val classes = runCatching { File(root, "build/classes/kotlin/jvm/main").canonicalFile }.getOrNull() ?: return null
        val runsFromHere = classPath.split(File.pathSeparator).any { entry -> runCatching { File(entry).canonicalFile == classes }.getOrDefault(false) }
        if (!runsFromHere || startedMs == null) return null
        val newer = SOURCE_ROOTS.map { File(root, it) }.filter { it.exists() }.asSequence()
            .flatMap { it.walkTopDown().filter { f -> f.isFile && (f.extension == "kt" || f.extension == "kts" || f.extension == "java") && f.lastModified() > startedMs } }
            .take(3).toList()
        if (newer.isEmpty()) return null
        return "refused: this Field runs from this checkout's build output, and source has changed since it started " +
            "(e.g. ${newer.joinToString { it.relativeTo(root).invariantSeparatorsPath }}). A build here would recompile classes under the running Field, " +
            "and any class it has not loaded yet would then fail for the rest of its life. Ask the person to restart Field first, " +
            "or do the build in a separate worktree. Commands that do not build (git-*, check, python-test, docs-build never) still run."
    }

    internal fun resolveExecutable(argv: List<String>): List<String> {
        val head = argv.first()
        if (head.startsWith("./")) return argv
        if (head == "env") {
            // `env NAME=value cmd ...`: env finds cmd on its own PATH, which is thin in a Field started from the Dock.
            val at = argv.indexOfFirst { it != "env" && '=' !in it }
            if (at < 0) return argv
            val cmd = argv[at].takeUnless { it.startsWith("./") }?.let(::findExecutable) ?: argv[at]
            return listOf(findExecutable("env") ?: "env") + argv.subList(1, at) + cmd + argv.drop(at + 1)
        }
        val found = findExecutable(head) ?: return argv
        return listOf(found) + argv.drop(1)
    }

    /** The live, foreign owner of `<root>/.field-backdoor/verification.lock`, or null when free, stale, or an ancestor of this JVM. */
    internal fun lockHolder(): String? {
        val text = runCatching { File(root, ".field-backdoor/verification.lock").readText().trim() }.getOrNull().orEmpty()
        val pid = Regex("\"pid\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val handle = ProcessHandle.of(pid).orElse(null) ?: return null
        if (!handle.isAlive) return null
        if (generateSequence(ProcessHandle.current()) { it.parent().orElse(null) }.any { it.pid() == pid }) return null
        return text.take(300)
    }

    internal fun environment(base: Map<String, String>): Map<String, String> = base.filterKeys { !it.startsWith("GIT_") && it != OWNER_ENV && it != "VIRTUAL_ENV" } + mapOf(
        "AFIELD_VERIFICATION_LOCK" to "fail", "GIT_TERMINAL_PROMPT" to "0", "GIT_PAGER" to "cat", "PAGER" to "cat",
    )

    internal fun execute(argv: List<String>, timeoutSec: Int): RepoCommandResult {
        val started = System.nanoTime()
        val pb = ProcessBuilder(argv).directory(root).redirectErrorStream(true)
        pb.environment().apply { val env = environment(toMap()); clear(); putAll(env) }
        val proc = try { pb.start() } catch (e: java.io.IOException) {
            return RepoCommandResult(false, "could not start ${argv.first()}: ${e.message}")
        }
        proc.outputStream.close()
        val sink = CappedOutput(scope.maxOutputBytes)
        val reader = Thread({ proc.inputStream.use { input -> val buf = ByteArray(8192); while (true) { val n = input.read(buf); if (n < 0) break; sink.add(buf, n) } } },
            "repo-command-output").apply { isDaemon = true; start() }
        val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) stopTree(proc.toHandle())
        reader.join(5_000)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val exit = if (finished) proc.exitValue() else null
        val message = when {
            !finished -> "timed out after ${timeoutSec}s; its process tree was stopped"
            exit == 0 -> "exit 0"
            else -> "exit $exit"
        }
        return RepoCommandResult(finished && exit == 0, message, argv.joinToString(" "), exit, !finished, elapsed,
            sink.text(), sink.total, sink.truncated)
    }

    /** The head and tail of a stream, and how much went by. */
    internal class CappedOutput(private val cap: Int) {
        private val headCap = minOf(HEAD_BYTES, cap / 4)
        private val head = java.io.ByteArrayOutputStream()
        private val tail = ArrayDeque<Byte>()
        private val tailCap = cap - headCap
        var total = 0L; private set
        val truncated get() = total > cap

        @Synchronized fun add(buf: ByteArray, n: Int) {
            for (i in 0 until n) {
                total++
                if (head.size() < headCap) head.write(buf[i].toInt())
                else { tail.addLast(buf[i]); if (tail.size > tailCap) tail.removeFirst() }
            }
        }

        @Synchronized fun text(): String {
            val h = head.toString(Charsets.UTF_8)
            val t = String(tail.toByteArray(), Charsets.UTF_8)
            return if (!truncated) h + t else "$h\n... [${total - head.size() - tail.size} bytes dropped] ...\n$t"
        }
    }

    companion object {
        const val HEAD_BYTES = 8 * 1024
        private val SOURCE_ROOTS = listOf("src/jvmMain", "src/commonMain", "src/generatedLinalg", "build.gradle.kts", "settings.gradle.kts")
        const val OWNER_ENV = "AFIELD_VERIFICATION_LOCK_OWNER"
        private val INSTALL_DIRS = listOf(".local/bin", ".cargo/bin").map { File(System.getProperty("user.home"), it) } +
            listOf(File("/opt/homebrew/bin"), File("/usr/local/bin"), File("/usr/bin"))

        /** [name] on PATH or in the usual install places (a Field started from the Dock has a thin PATH). */
        fun findExecutable(name: String): String? =
            ((System.getenv("PATH") ?: "").split(File.pathSeparator).filter { it.isNotBlank() }.map(::File) + INSTALL_DIRS)
                .map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath

        /** Stop [process] and its descendants, deepest first: TERM, a grace, then KILL. Gradle daemons are spared. */
        internal fun stopTree(process: ProcessHandle, graceMs: Long = 3_000) {
            val victims = (process.descendants().toList().reversed() + process)
                .filter { p -> p == process || !p.info().commandLine().orElse("").contains("GradleDaemon") }
            victims.forEach { it.destroy() }
            val deadline = System.currentTimeMillis() + graceMs
            for (p in victims) {
                val left = deadline - System.currentTimeMillis()
                if (left > 0) runCatching { p.onExit().get(left, TimeUnit.MILLISECONDS) }
                if (p.isAlive) p.destroyForcibly()
            }
        }
    }
}

internal fun registerRepoCommandTools(tools: ToolRegistry) {
    tools.register(RepoRunCommandRequest::class.java, { it.repoCommands != null }) { ctx, req -> ctx.repoCommands!!.run(req) }
    tools.register(RepoStartCommandRequest::class.java, { it.repoCommands != null }) { ctx, req -> ctx.repoCommands!!.start(req) }
    tools.register(RepoCommandStatusRequest::class.java, { it.repoCommands != null }) { ctx, req -> ctx.repoCommands!!.status(req) }
    tools.register(RepoStopCommandRequest::class.java, { it.repoCommands != null }) { ctx, req -> ctx.repoCommands!!.stop(req) }
    tools.register(AwaitRepoCommandsRequest::class.java, { it.repoCommands != null && it.suspendable }) { _, req -> awaitRepoCommands(req) }
}
