package afield

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Field is distributed as source: you clone it and you build it. So "is there an update" is not a
 * question about a version manifest and a zip, it is a question about your checkout and its remote
 * — and git already answers it. This asks, and reports. It never writes to the working tree; pulling
 * is yours to do, because only you know whether your checkout has work in it.
 */

/** The trailer a commit carries when it has something to say to everyone who pulls it. */
const val NOTICE_TRAILER = "Field-Notice"

/** One commit that origin has and this checkout does not. */
data class UpstreamCommit(
    val sha: String,
    val subject: String,
    /** The commit's [NOTICE_TRAILER] values, in the order they appear. Usually empty. */
    val notices: List<String>,
)

sealed interface UpstreamStatus {
    /** HEAD is at the remote branch's tip, or ahead of it. */
    data class UpToDate(val remoteRef: String, val ahead: Int) : UpstreamStatus

    /** The remote branch has [behind] commits this checkout does not. */
    data class Behind(
        val remoteRef: String,
        val behind: Int,
        val ahead: Int,
        val commits: List<UpstreamCommit>,
    ) : UpstreamStatus

    /** The question could not be asked, and why: no git, no checkout, no remote, no network. */
    data class Unavailable(val reason: String) : UpstreamStatus
}

// ASCII's own separators, which is what they are for: nothing anyone can write in a commit
// subject or a notice collides with them, so the framing survives any message.
private const val RECORD = '\u001d'
private const val FIELD = '\u001f'
private const val NOTICE = '\u001e'

private const val LOG_FORMAT =
    "%H%x1f%s%x1f%(trailers:key=$NOTICE_TRAILER,valueonly,separator=%x1e)%x1d"

/**
 * Asks [remote] what it has on this branch, and — only if that differs from HEAD — fetches so the
 * new commits can be described. The cheap case costs one `git ls-remote`; nothing is fetched, and
 * no ref is moved, when you are already up to date.
 */
suspend fun checkUpstream(
    repoDir: File = File("."),
    remote: String = "origin",
    timeoutSeconds: Long = 20,
): UpstreamStatus = withContext(Dispatchers.IO) {
    val git = GitRunner(repoDir, timeoutSeconds)

    val toplevel = git.run("rev-parse", "--show-toplevel")
    if (!toplevel.ok) {
        return@withContext UpstreamStatus.Unavailable(
            "${repoDir.absolutePath} is not a git checkout — Field updates by `git pull`, so clone it " +
                "from https://github.com/OpenEndedGroup/Field4 rather than unpacking it"
        )
    }

    val branch = git.run("symbolic-ref", "--quiet", "--short", "HEAD")
    if (!branch.ok || branch.text.isEmpty()) {
        return@withContext UpstreamStatus.Unavailable("HEAD is detached, so there is no branch to compare with $remote")
    }

    if (!git.run("config", "--get", "remote.$remote.url").ok) {
        return@withContext UpstreamStatus.Unavailable("this checkout has no '$remote' remote")
    }

    // An explicitly configured upstream wins, so a fork tracking `upstream/main` is asked about the
    // branch it actually follows rather than one that merely shares its name.
    val configured = git.run("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}")
    val remoteBranch = configured.text
        .takeIf { configured.ok }   // on failure `text` is git's error message, not a ref
        ?.substringAfter("$remote/", missingDelimiterValue = "")
        ?.ifEmpty { null }
        ?: branch.text
    val remoteRef = "$remote/$remoteBranch"

    val lsRemote = git.run("ls-remote", remote, "refs/heads/$remoteBranch")
    if (!lsRemote.ok) {
        return@withContext UpstreamStatus.Unavailable("could not reach $remote (${lsRemote.text.oneLine()})")
    }
    val remoteSha = firstObjectId(lsRemote.text)
        ?: return@withContext UpstreamStatus.Unavailable("$remote has no branch named '$remoteBranch'")

    val localSha = firstObjectId(git.run("rev-parse", "HEAD").text)
        ?: return@withContext UpstreamStatus.Unavailable("could not resolve HEAD")
    if (localSha == remoteSha) return@withContext UpstreamStatus.UpToDate(remoteRef, ahead = 0)

    // The shas differ, so it is worth the round trip. Fetching a branch by name writes FETCH_HEAD
    // and leaves every local ref — including refs/remotes — exactly where it was.
    val fetch = git.run("fetch", "--quiet", remote, remoteBranch)
    if (!fetch.ok) {
        return@withContext UpstreamStatus.Unavailable("could not fetch from $remote (${fetch.text.oneLine()})")
    }
    // Read the target back from FETCH_HEAD rather than trusting the sha ls-remote reported: if the
    // branch moved between the two calls, this describes what we actually have.
    val target = firstObjectId(git.run("rev-parse", "FETCH_HEAD").text)
        ?: return@withContext UpstreamStatus.Unavailable("fetched from $remote but could not resolve FETCH_HEAD")

    val counts = git.run("rev-list", "--left-right", "--count", "HEAD...$target")
    val (ahead, behind) = parseAheadBehind(counts.text.takeIf { counts.ok } ?: "")
        ?: return@withContext UpstreamStatus.Unavailable("could not count the commits between HEAD and $remoteRef")

    if (behind == 0) return@withContext UpstreamStatus.UpToDate(remoteRef, ahead)

    val log = git.run("log", "--reverse", "--format=$LOG_FORMAT", "HEAD..$target")
    UpstreamStatus.Behind(remoteRef, behind, ahead, parseUpstreamLog(log.text.takeIf { log.ok } ?: ""))
}

private val OBJECT_ID = Regex("^([0-9a-f]{7,64})(\\s|$)")

/**
 * The first object id in [raw]. Because stderr is folded into the stream we read, git's advice and
 * warnings — "warning: redirecting to https://…" ahead of an `ls-remote`, say — can arrive before
 * the answer. A sha is therefore something to find, not something to assume is on the first line.
 */
internal fun firstObjectId(raw: String): String? =
    raw.lineSequence()
        .mapNotNull { OBJECT_ID.find(it.trim())?.groupValues?.get(1) }
        .firstOrNull()

/** Parses `rev-list --left-right --count A...B` output into (ahead, behind). */
internal fun parseAheadBehind(raw: String): Pair<Int, Int>? {
    val parts = raw.trim().split(Regex("\\s+"))
    if (parts.size != 2) return null
    val ahead = parts[0].toIntOrNull() ?: return null
    val behind = parts[1].toIntOrNull() ?: return null
    return ahead to behind
}

/** Parses the record stream [LOG_FORMAT] produces. Separators are control characters, so a commit
 *  subject or a notice containing tabs, pipes or newlines cannot break the framing. */
internal fun parseUpstreamLog(raw: String): List<UpstreamCommit> =
    raw.split(RECORD)
        .map { it.trim('\n', '\r') }
        .filter { it.isNotBlank() }
        .mapNotNull { record ->
            val fields = record.split(FIELD)
            if (fields.size < 2) return@mapNotNull null
            UpstreamCommit(
                sha = fields[0].trim(),
                subject = fields[1].trim(),
                notices = fields.getOrNull(2)
                    ?.split(NOTICE)
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList(),
            )
        }

/**
 * The lines to show for [status], most important first. Notices come out whole and unabbreviated —
 * a commit only carries one when someone decided every puller needs to read it.
 */
fun updateCheckMessages(status: UpstreamStatus, maxNotices: Int = 4): List<String> = when (status) {
    is UpstreamStatus.Unavailable -> listOf("Cannot check for updates: ${status.reason}")

    is UpstreamStatus.UpToDate -> listOf(
        buildString {
            append("Up to date with ${status.remoteRef}")
            if (status.ahead > 0) append(", plus ${plural(status.ahead, "local commit")} of your own")
        }
    )

    is UpstreamStatus.Behind -> buildList {
        add(
            buildString {
                append("${plural(status.behind, "new commit")} on ${status.remoteRef}")
                if (status.ahead > 0) append(", and ${plural(status.ahead, "local commit")} it does not have")
                append(" — `git pull` and rebuild")
            }
        )
        val notices = status.commits.flatMap { it.notices }.distinct()
        notices.take(maxNotices).forEach { add(it) }
        if (notices.size > maxNotices) add("…and ${notices.size - maxNotices} more notices in the new commits")
        if (notices.isEmpty()) status.commits.lastOrNull()?.let { add("Latest: ${it.subject}") }
    }
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun String.oneLine() = trim().lineSequence().firstOrNull()?.take(120).orEmpty()

/** Runs git in [repoDir], never blocking longer than [timeoutSeconds] — a hung network call must
 *  not become a hung command palette. */
private class GitRunner(private val repoDir: File, private val timeoutSeconds: Long) {
    data class Result(val ok: Boolean, val text: String)

    fun run(vararg args: String): Result {
        // Output goes to a file rather than a pipe on purpose. Reading a pipe to EOF blocks until
        // the child exits, which would make waitFor's timeout unreachable — exactly backwards for
        // the case it exists to cover, a `fetch` against a remote that has stopped answering.
        var sink: File? = null
        return try {
            sink = File.createTempFile("afield-git-", ".out")
            val process = ProcessBuilder(listOf("git") + args)
                .directory(repoDir)
                .redirectErrorStream(true)
                .redirectOutput(sink)
                .start()
            process.outputStream.close()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Result(false, "git ${args.firstOrNull()} timed out after ${timeoutSeconds}s")
            }
            Result(process.exitValue() == 0, sink.readText(StandardCharsets.UTF_8).trim())
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        } finally {
            sink?.delete()
        }
    }
}
