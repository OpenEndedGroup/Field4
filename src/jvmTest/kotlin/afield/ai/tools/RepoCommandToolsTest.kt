package afield.ai.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepoCommandToolsTest {
    private class Fixture(allowed: Set<String> = RepoCommandTemplates.ALL.keys, read: List<String> = listOf("**"),
                          maxOutputBytes: Int = 48 * 1024) : AutoCloseable {
        val outer: Path = Files.createTempDirectory("field-repo-commands")
        val root: Path = Files.createDirectories(outer.resolve("repo"))
        init {
            file("gradlew", "#!/bin/sh\necho gradle \"$@\"\n").toFile().setExecutable(true)
            file("tools/test_thing.py", "print('ok')\n")
            file("tools/check_thing.py", "print('ok')\n")
            file("tools/other.py", "print('no')\n")
            file("docs/a.md", "a\n")
            Files.writeString(outer.resolve("secret.txt"), "secret\n")
        }
        val files = RepoFileTools(RepoScope(root, read))
        val tools = RepoCommandTools(files, RepoCommandScope(allowed, maxTimeoutSec = 30, maxOutputBytes = maxOutputBytes))
        fun file(path: String, text: String): Path = root.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
        fun build(name: String, vararg args: String) = RepoCommandTemplates.ALL.getValue(name).build(args.toList(), files)
        override fun close() { outer.toFile().deleteRecursively() }
    }

    @Test fun templatesBuildFixedArgvFromCheckedSlots() = Fixture().use { f ->
        assertEquals(listOf("./gradlew", "--console=plain", "jvmTest", "--tests", "afield.ai.tools.RepoFileToolsTest"),
            f.build("gradle", "jvmTest", "--tests", "afield.ai.tools.RepoFileToolsTest"))
        assertEquals(listOf("uv", "run", "--project", ".", "--group", "backdoor", "python", "-m", "unittest", "tools.test_thing.FieldAgentTest.test_x"),
            f.build("python-test", "tools/test_thing.py", "FieldAgentTest.test_x"))
        assertEquals("tools.test_thing", f.build("python-test", "tools/test_thing.py").last())
        assertEquals("tools/check_thing.py", f.build("check", "tools/check_thing.py").last())
        val diff = f.build("git-diff", "--stat", "main...HEAD", "--", "docs/a.md")
        assertEquals(listOf("--stat", "main...HEAD", "--", "docs/a.md"), diff.takeLast(4))
        assertTrue(f.build("git-log").containsAll(listOf("-n", "20")))
        assertEquals(listOf("-n", "5"), f.build("git-log", "-n", "5").takeLast(2))
    }

    @Test fun allowListRefusesOptionsTasksAndShellText() = Fixture().use { f ->
        val bad = listOf(
            listOf("gradle", "--stop"), listOf("gradle", "clean"), listOf("gradle", "runJvm"), listOf("gradle", "-Dx=y", "jvmTest"),
            listOf("gradle", "-I", "evil.gradle", "jvmTest"), listOf("gradle", "jvmTest; rm -rf /"), listOf("gradle"),
            listOf("gradle", "jvmTest", "--tests", "\$(touch x)"), listOf("gradle", ":publishToMavenLocal"),
            listOf("python-test", "tools/other.py"), listOf("python-test", "tools/test_thing.py", "-c"), listOf("python-test", "tools/test_missing.py"),
            listOf("check", "tools/check_thing.py", "--fix"), listOf("git-status", "-uall"),
            listOf("git-diff", "--output=/tmp/x"), listOf("git-diff", "-c"), listOf("git-log", "-n", "99999"), listOf("git-show", "--format=%x"),
            listOf("land-precheck", "--force"),
        )
        for (b in bad) assertFailsWith<RepoPathRefused>("${b} should be refused") { f.build(b[0], *b.drop(1).toTypedArray()) }
        val r = f.tools.run(RepoRunCommandRequest("bash", listOf("-c", "echo hi")))
        assertFalse(r.success)
        assertTrue(r.message.contains("not a command this job may run"), r.message)
    }

    @Test fun pathsAreConfinedToTheRootAndItsReadList() = Fixture(read = listOf("docs/**", "tools/test_*.py")).use { f ->
        for (p in listOf("../secret.txt", f.outer.resolve("secret.txt").toString(), ".git/config", "tools/check_thing.py"))
            assertFailsWith<RepoPathRefused>(p) { f.build("git-diff", "--", p) }
        assertFailsWith<RepoPathRefused> { f.build("check", "tools/check_thing.py") }
        assertFailsWith<RepoPathRefused> { f.build("python-test", "tools/../tools/test_thing.py") }
        assertEquals("tools.test_thing", f.build("python-test", "tools/test_thing.py").last())
    }

    @Test fun onlyTheJobsNamesRun(): Unit = Fixture(allowed = setOf("git-status")).use { f ->
        val r = f.tools.run(RepoRunCommandRequest("gradle", listOf("jvmTest")))
        assertFalse(r.success)
        assertTrue(r.message.contains("allowed: git-status"), r.message)
        assertFailsWith<IllegalArgumentException> { RepoCommandTools(f.files, RepoCommandScope(setOf("rm"))) }
    }

    @Test fun runsInTheRootWithoutAShell() = Fixture(allowed = setOf("gradle")).use { f ->
        f.file("gradlew", "#!/bin/sh\npwd -P\necho \"args:$*\"\necho \"lock:\$AFIELD_VERIFICATION_LOCK git:\${GIT_DIR:-unset}\"\n")
        val r = f.tools.run(RepoRunCommandRequest("gradle", listOf("jvmTest", "--tests", "A*")))
        assertTrue(r.success, r.message + r.output)
        val lines = r.output.trim().lines()
        assertEquals(f.root.toRealPath().toString(), lines[0])
        assertEquals("args:--console=plain jvmTest --tests A*", lines[1], "a glob in an argument reaches the process verbatim: no shell expanded it")
        assertEquals("lock:fail git:unset", lines[2])
        assertEquals(listOf("gradle jvmTest --tests A* -> 0"), f.tools.runs)
    }

    @Test fun environmentDropsGitVariablesAndTheLockOwner() = Fixture().use { f ->
        val env = f.tools.environment(mapOf("GIT_DIR" to "/x", "GIT_INDEX_FILE" to "/y", "AFIELD_VERIFICATION_LOCK_OWNER" to "1", "VIRTUAL_ENV" to "/v", "PATH" to "/bin"))
        assertEquals(mapOf("PATH" to "/bin", "AFIELD_VERIFICATION_LOCK" to "fail", "GIT_TERMINAL_PROMPT" to "0", "GIT_PAGER" to "cat", "PAGER" to "cat"), env)
    }

    @Test fun environmentDropsThisFieldsOwnPortsAndDebugger() = Fixture().use { f ->
        // A backdoor-launched Field carries these; a Field its job starts must pick its own or it fails to bind them.
        val env = f.tools.environment(mapOf("PORT" to "9491", "AFIELD_HTTP_PORT" to "19491", "AFIELD_JDWP_PORT" to "5005",
            "AFIELD_JDWP_HOST" to "127.0.0.1", "AFIELD_JDWP_SUSPEND" to "n", "AFIELD_SKIP_WORKSPACE_PERSIST" to "1", "PATH" to "/bin"))
        assertTrue(RepoCommandTools.INSTANCE_ENV.none { it in env }, env.toString())
        assertEquals("1", env["AFIELD_SKIP_WORKSPACE_PERSIST"], "the rest is inherited")
    }

    @Test fun nonZeroExitIsReportedNotThrown() = Fixture(allowed = setOf("gradle")).use { f ->
        f.file("gradlew", "#!/bin/sh\necho broken >&2\nexit 3\n")
        val r = f.tools.run(RepoRunCommandRequest("gradle", listOf("jvmTest")))
        assertFalse(r.success)
        assertEquals(3, r.exitCode)
        assertTrue(r.output.contains("broken"), "stderr is merged into output")
    }

    @Test fun timeoutStopsTheWholeProcessTree() = Fixture(allowed = setOf("gradle")).use { f ->
        val pidFile = f.outer.resolve("child.pid")
        f.file("gradlew", "#!/bin/sh\nsleep 300 &\necho \$! > '$pidFile'\nwait\n")
        val started = System.nanoTime()
        val r = f.tools.run(RepoRunCommandRequest("gradle", listOf("jvmTest"), timeoutSec = 1))
        val elapsedSec = (System.nanoTime() - started) / 1e9
        assertTrue(r.timedOut, r.message)
        assertNull(r.exitCode)
        assertTrue(elapsedSec < 15, "stopped promptly, took $elapsedSec s")
        val child = Files.readString(pidFile).trim().toLong()
        val handle = ProcessHandle.of(child)
        if (handle.isPresent) runCatching { handle.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS) }
        assertFalse(ProcessHandle.of(child).map { it.isAlive }.orElse(false), "the grandchild sleep was stopped too")
        assertEquals(listOf("gradle jvmTest -> timeout"), f.tools.runs)
    }

    @Test fun timeoutIsCappedByTheJob() = Fixture(allowed = setOf("gradle")).use { f ->
        f.file("gradlew", "#!/bin/sh\nexit 0\n")
        // maxTimeoutSec = 30 in the fixture: asking for an hour is not an error, it is clamped.
        assertTrue(f.tools.run(RepoRunCommandRequest("gradle", listOf("jvmTest"), timeoutSec = 3600)).success)
    }

    @Test fun outputKeepsHeadAndTailUnderTheCap() = Fixture(allowed = setOf("gradle"), maxOutputBytes = 4096).use { f ->
        f.file("gradlew", "#!/bin/sh\necho FIRST\ni=0; while [ \$i -lt 5000 ]; do echo line\$i; i=\$((i+1)); done\necho LAST\n")
        val r = f.tools.run(RepoRunCommandRequest("gradle", listOf("jvmTest")))
        assertTrue(r.success, r.message)
        assertTrue(r.truncated)
        assertTrue(r.outputBytes > 4096)
        assertTrue(r.output.startsWith("FIRST"), r.output.take(80))
        assertTrue(r.output.trimEnd().endsWith("LAST"))
        assertTrue(r.output.contains("bytes dropped"))
        assertTrue(r.output.toByteArray().size < 4096 + 200)
    }

    @Test fun aLiveForeignVerificationLockRefusesTheRun() = Fixture(allowed = setOf("gradle")).use { f ->
        val holder = ProcessBuilder("sleep", "30").start()
        try {
            f.file(".field-backdoor/verification.lock", "{\"pid\":${holder.pid()},\"label\":\"smoke\"}")
            val r = f.tools.run(RepoRunCommandRequest("gradle", listOf("jvmTest")))
            assertFalse(r.success)
            assertTrue(r.message.contains("verification lock is held"), r.message)
            assertTrue(f.tools.runs.isEmpty())
            // Our own JVM (an ancestor of every command we start) never blocks itself.
            f.file(".field-backdoor/verification.lock", "{\"pid\":${ProcessHandle.current().pid()}}")
            assertNull(f.tools.lockHolder())
        } finally { holder.destroyForcibly() }
        f.file(".field-backdoor/verification.lock", "{\"pid\":${holder.pid()}}")
        holder.onExit().get()
        assertNull(f.tools.lockHolder(), "a dead owner is stale")
    }
}
