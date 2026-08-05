package afield

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Field's update check is a question about a git checkout, so half of these tests build one — a
 * bare "origin" and a clone of it in a temp directory — and ask the real [checkUpstream] the real
 * question. The other half pin the parsing, which is where a format string and a shell can lie to
 * each other quietly.
 */
class UpstreamCheckTest {

    // ---- parsing -------------------------------------------------------------------------------

    @Test
    fun countsAheadThenBehind() {
        assertEquals(3 to 7, parseAheadBehind("3\t7"))
        assertEquals(0 to 0, parseAheadBehind("0\t0\n"))
        assertEquals(0 to 2, parseAheadBehind("  0   2  "))
    }

    @Test
    fun rejectsCountsItCannotRead() {
        assertNull(parseAheadBehind(""))
        assertNull(parseAheadBehind("3"))
        assertNull(parseAheadBehind("fatal: bad revision"))
    }

    @Test
    fun readsShaSubjectAndNotices() {
        val raw = record("abc123", "fix(pat): a bar line is not two notes", "rebuild the REPL") +
            record("def456", "docs: a page", null)

        val commits = parseUpstreamLog(raw)

        assertEquals(2, commits.size)
        assertEquals("abc123", commits[0].sha)
        assertEquals("fix(pat): a bar line is not two notes", commits[0].subject)
        assertEquals(listOf("rebuild the REPL"), commits[0].notices)
        assertEquals(emptyList(), commits[1].notices)
    }

    @Test
    fun aSubjectCannotBreakTheFraming() {
        // The whole reason the separators are control characters: a subject is free text, and
        // people write tabs, pipes and newlines in them.
        val hostile = "fix: tabs\there | pipes | and \\n literals"
        val commits = parseUpstreamLog(record("abc123", hostile, null))

        assertEquals(1, commits.size)
        assertEquals(hostile, commits[0].subject)
    }

    @Test
    fun carriesEveryNoticeOnOneCommit() {
        val commits = parseUpstreamLog(record("abc123", "merge: two things", "first thing", "second thing"))

        assertEquals(listOf("first thing", "second thing"), commits[0].notices)
    }

    @Test
    fun emptyLogIsNoCommits() {
        assertEquals(emptyList(), parseUpstreamLog(""))
        assertEquals(emptyList(), parseUpstreamLog("\n"))
    }

    // ---- what the user is told -----------------------------------------------------------------

    @Test
    fun upToDateSaysSoAndMentionsLocalWork() {
        assertEquals(
            listOf("Up to date with origin/main"),
            updateCheckMessages(UpstreamStatus.UpToDate("origin/main", ahead = 0)),
        )
        assertEquals(
            listOf("Up to date with origin/main, plus 2 local commits of your own"),
            updateCheckMessages(UpstreamStatus.UpToDate("origin/main", ahead = 2)),
        )
    }

    @Test
    fun behindLeadsWithTheCountAndTheInstruction() {
        val messages = updateCheckMessages(
            UpstreamStatus.Behind("origin/main", behind = 7, ahead = 0, commits = listOf(commit("a", "docs: a page")))
        )

        assertEquals("7 new commits on origin/main — `git pull` and rebuild", messages.first())
        assertEquals("Latest: docs: a page", messages[1])
    }

    @Test
    fun oneCommitIsSingular() {
        val messages = updateCheckMessages(
            UpstreamStatus.Behind("origin/main", behind = 1, ahead = 1, commits = listOf(commit("a", "docs: a page")))
        )

        assertEquals("1 new commit on origin/main, and 1 local commit it does not have — `git pull` and rebuild", messages.first())
    }

    @Test
    fun noticesReplaceTheLatestSubject() {
        val messages = updateCheckMessages(
            UpstreamStatus.Behind(
                "origin/main", behind = 2, ahead = 0,
                commits = listOf(
                    commit("a", "feat: a thing", "the Kotlin fork moved — run ./gradlew clean"),
                    commit("b", "docs: a page"),
                ),
            )
        )

        assertEquals(listOf(
            "2 new commits on origin/main — `git pull` and rebuild",
            "the Kotlin fork moved — run ./gradlew clean",
        ), messages)
        assertTrue(messages.none { it.startsWith("Latest:") }, "a notice outranks the latest subject")
    }

    @Test
    fun repeatedNoticesAreSaidOnceAndManyAreCapped() {
        val messages = updateCheckMessages(
            UpstreamStatus.Behind(
                "origin/main", behind = 4, ahead = 0,
                commits = listOf(
                    commit("a", "one", "rebuild"),
                    commit("b", "two", "rebuild"),
                    commit("c", "three", "n2"),
                    commit("d", "four", "n3"),
                ),
            ),
            maxNotices = 2,
        )

        assertEquals(listOf(
            "4 new commits on origin/main — `git pull` and rebuild",
            "rebuild",
            "n2",
            "…and 1 more notices in the new commits",
        ), messages)
    }

    @Test
    fun unavailableExplainsItself() {
        assertEquals(
            listOf("Cannot check for updates: this checkout has no 'origin' remote"),
            updateCheckMessages(UpstreamStatus.Unavailable("this checkout has no 'origin' remote")),
        )
    }

    // ---- against real git ----------------------------------------------------------------------

    @Test
    fun aCloneLevelWithItsOriginIsUpToDate() {
        val (clone, _) = cloneWithOrigin()

        val status = runBlocking { checkUpstream(clone) }

        val upToDate = assertIs<UpstreamStatus.UpToDate>(status, "got $status")
        assertEquals(0, upToDate.ahead)
        assertTrue(upToDate.remoteRef.startsWith("origin/"), upToDate.remoteRef)
    }

    @Test
    fun aCloneSeesCommitsPushedToOriginAfterIt() {
        val (clone, upstream) = cloneWithOrigin()

        commitIn(upstream, "second.txt", "docs: the second commit")
        commitIn(
            upstream, "third.txt",
            "feat: the third commit\n\n$NOTICE_TRAILER: the Kotlin fork moved — run ./gradlew clean",
        )
        git(upstream, "push", "--quiet", "origin", "HEAD")

        val status = runBlocking { checkUpstream(clone) }

        val behind = assertIs<UpstreamStatus.Behind>(status, "got $status")
        assertEquals(2, behind.behind)
        assertEquals(0, behind.ahead)
        assertEquals(
            listOf("docs: the second commit", "feat: the third commit"),
            behind.commits.map { it.subject },
            "oldest first, so the story reads forwards",
        )
        assertEquals(
            listOf("the Kotlin fork moved — run ./gradlew clean"),
            behind.commits.flatMap { it.notices },
        )
    }

    @Test
    fun localCommitsCountAsAheadNotBehind() {
        val (clone, _) = cloneWithOrigin()
        commitIn(clone, "mine.txt", "wip: something of my own")

        val status = runBlocking { checkUpstream(clone) }

        val upToDate = assertIs<UpstreamStatus.UpToDate>(status, "got $status")
        assertEquals(1, upToDate.ahead)
    }

    @Test
    fun somewhereThatIsNotACheckoutSaysSoRatherThanThrowing() {
        val loose = Files.createTempDirectory("afield-upstream-loose").toFile()

        val status = runBlocking { checkUpstream(loose) }

        val unavailable = assertIs<UpstreamStatus.Unavailable>(status, "got $status")
        assertTrue("not a git checkout" in unavailable.reason, unavailable.reason)
    }

    @Test
    fun aCheckoutWithNoRemoteSaysSoRatherThanThrowing() {
        val solo = Files.createTempDirectory("afield-upstream-solo").toFile()
        git(solo, "init", "--quiet", "--initial-branch=main")
        commitIn(solo, "first.txt", "first")

        val status = runBlocking { checkUpstream(solo) }

        val unavailable = assertIs<UpstreamStatus.Unavailable>(status, "got $status")
        assertTrue("no 'origin' remote" in unavailable.reason, unavailable.reason)
    }

    @Test
    fun detachedHeadSaysSoRatherThanGuessingABranch() {
        val (clone, _) = cloneWithOrigin()
        git(clone, "checkout", "--quiet", "--detach", "HEAD")

        val status = runBlocking { checkUpstream(clone) }

        val unavailable = assertIs<UpstreamStatus.Unavailable>(status, "got $status")
        assertTrue("detached" in unavailable.reason, unavailable.reason)
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** A bare repo standing in for GitHub, a working clone of it, and one commit on both. */
    private fun cloneWithOrigin(): Pair<File, File> {
        val root = Files.createTempDirectory("afield-upstream").toFile()
        val bare = File(root, "origin.git")
        val upstream = File(root, "upstream")
        val clone = File(root, "clone")

        git(root, "init", "--quiet", "--bare", "--initial-branch=main", bare.absolutePath)

        git(root, "clone", "--quiet", bare.absolutePath, upstream.absolutePath)
        commitIn(upstream, "first.txt", "first: the commit everyone has")
        git(upstream, "push", "--quiet", "origin", "HEAD:refs/heads/main")

        git(root, "clone", "--quiet", bare.absolutePath, clone.absolutePath)
        return clone to upstream
    }

    private fun commitIn(repo: File, fileName: String, message: String) {
        File(repo, fileName).writeText("$fileName\n")
        git(repo, "add", fileName)
        git(repo, "commit", "--quiet", "-m", message)
    }

    private val hooksVoid: File = Files.createTempDirectory("afield-upstream-nohooks").toFile()

    /** Runs git with identity and hooks forced, so a developer's own git config cannot change the
     *  result of a test — `commit.gpgsign`, a global hooksPath and `init.defaultBranch` all would. */
    private fun git(cwd: File, vararg args: String): String {
        val config = listOf(
            "-c", "user.name=Field Test",
            "-c", "user.email=test@example.invalid",
            "-c", "commit.gpgsign=false",
            "-c", "core.hooksPath=${hooksVoid.absolutePath}",
            "-c", "protocol.file.allow=always",
        )
        val process = ProcessBuilder(listOf("git") + config + args)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val exit = process.waitFor()
        check(exit == 0) { "git ${args.joinToString(" ")} failed ($exit): ${output.trim()}" }
        return output.trim()
    }

    /** One record in exactly the framing the `git log --format` in upstreamcheck.kt emits. */
    private fun record(sha: String, subject: String, vararg notices: String?): String {
        val joined = notices.filterNotNull().joinToString("\u001e")
        return "$sha\u001f$subject\u001f$joined\u001d\n"
    }

    private fun commit(sha: String, subject: String, vararg notices: String) =
        UpstreamCommit(sha, subject, notices.toList())
}
