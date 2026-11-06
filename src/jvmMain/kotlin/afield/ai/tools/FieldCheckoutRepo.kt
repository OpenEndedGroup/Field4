package afield.ai.tools

import java.io.File

/**
 * The `repo` tools over Field's own checkout, for the document chat.
 *
 * Until 2026-11 only a headless job ([afield.ai.agent.AgentTask] with a `repoRoot`) had them, and a chat
 * that needed a file or a process outside its document got there through `EvaluateKotlinExpressionRequest`:
 * `java.io.File(...).writeText`, `ProcessBuilder("./gradlew", ...)`, Python wait-loops in `build/`. The
 * knight-tour session published a whole gallery entry that way (2026-11-06). These tools do the same work
 * with the checkout's verification lock, a process-tree stop, a log, and a refusal to build under the
 * running Field ([RepoCommandTools.liveBuildHazard]).
 *
 * The root is this JVM's working directory when it is a checkout (it has `gradlew` and `.git`), which is
 * how `./field.sh` starts Field. `AFIELD_AI4_REPO_TOOLS=off` (environment or system property) withholds them.
 */
object FieldCheckoutRepo {
    val root: File? by lazy {
        val off = (System.getProperty("AFIELD_AI4_REPO_TOOLS") ?: System.getenv("AFIELD_AI4_REPO_TOOLS")).orEmpty()
        if (off.equals("off", ignoreCase = true) || off == "0" || off.equals("false", ignoreCase = true)) return@lazy null
        File(System.getProperty("user.dir")).canonicalFile.takeIf { File(it, "gradlew").isFile && File(it, ".git").exists() }
    }

    val files: RepoFileTools? by lazy { root?.let { RepoFileTools(RepoScope(it.toPath(), readable = listOf("**"), writable = listOf("**"))) } }

    val commands: RepoCommandTools? by lazy {
        files?.let { RepoCommandTools(it, RepoCommandScope(RepoCommandTemplates.CHAT_SET.toSet(), maxTimeoutSec = 3600)) }
    }

    /** What the chat's instructions say about these tools, or null when they are withheld. */
    fun guidance(commands: RepoCommandTools): String =
        "- Repository: Field's own checkout, `${commands.files.scope.root}`. Read, search, list, patch and create its files with " +
            "RepoReadFileRequest, RepoSearchRequest, RepoListFilesRequest, RepoPatchFileRequest and RepoWriteFileRequest (paths relative to the root). " +
            "Run its named commands with RepoRunCommandRequest (waits; for quick ones) or RepoStartCommandRequest (background; anything over a minute), " +
            "then follow a job with RepoCommandStatusRequest(waitSeconds up to 120), or end the turn on AwaitRepoCommandsRequest when it will take longer. " +
            "Use these, not java.io.File or ProcessBuilder inside EvaluateKotlinExpressionRequest, for anything outside the document: they hold the " +
            "checkout's verification lock, stop what they start, keep a log, and refuse a build that would recompile classes under this running Field. " +
            "Commands:\n" + commands.describeAllowed().lines().joinToString("\n") { "  $it" }
}
