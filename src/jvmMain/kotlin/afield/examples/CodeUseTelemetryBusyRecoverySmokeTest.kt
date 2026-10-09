package afield.examples

import afield.CodeUseSummary
import afield.CodeUseTelemetryService
import java.nio.file.Files
import java.sql.DriverManager

/*
FIELD_TEST
id: examples.code-use.writer-busy-recovery
runner: jvm-main
entrypoint: afield.examples.CodeUseTelemetryBusyRecoverySmokeTest
tags: [smoke, code-use, telemetry, sqlite]
timeoutSec: 60
expect:
  exitCode: 0
  stdoutContains:
    - "CodeUseTelemetryBusyRecoverySmokeTest ok"
*/
/**
 * A second connection holds the telemetry database's write lock through two of the writer's
 * 5 s busy timeouts. The writer must stay enabled, report the SQLITE_BUSY retries and the events it
 * is holding, and commit every one of them once the lock is released. Analysis of a held compile
 * event must wait for that commit.
 */
object CodeUseTelemetryBusyRecoverySmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val tempDir = Files.createTempDirectory("afield-code-use-busy")
        val database = tempDir.resolve("field-code-use.sqlite").toString()
        System.setProperty("afield.codeUse.path", database)
        System.setProperty("afield.codeUse.semanticSidecar.url", "")

        val context = CodeUseTelemetryService.CodeUseContext(
            document = "code-use-busy",
            boxUid = "box-code-use-busy",
            boxName = "Code Use Busy",
            property = "code",
            mode = "execute",
            strand = "__main__"
        )

        // Let the writer create the schema and commit once before the lock is taken. A launch event
        // with no source queues no analysis, so no analyzer transaction competes for the lock.
        CodeUseTelemetryService.recordLaunchLifecycle(
            snippetId = CodeUseTelemetryService.newSnippetKey(),
            source = "",
            context = context,
            lifecycle = "execute-start"
        )
        waitForSummary("the primer event to commit") { it.launchEvents >= 1 }

        val blocker = DriverManager.getConnection("jdbc:sqlite:$database")
        blocker.autoCommit = false
        blocker.createStatement().use {
            it.executeUpdate("INSERT INTO classpath_entries(hash, path, mtime, size) VALUES('busy-smoke', 'write-lock', 0, 0)")
        }
        val lockTakenAt = System.currentTimeMillis()

        val launchIds = List(3) { CodeUseTelemetryService.newSnippetKey() }
        launchIds.forEach {
            CodeUseTelemetryService.recordLaunchLifecycle(snippetId = it, source = "", context = context, lifecycle = "execute-start")
        }
        val evalId = CodeUseTelemetryService.newSnippetKey()
        CodeUseTelemetryService.recordEvaluationResult(
            snippetId = evalId,
            context = context.copy(mode = "evaluate"),
            success = true,
            returnType = "kotlin.Unit",
            elapsedMs = 0.5
        )
        // `sin` matches several source definitions, so the analyzer records an ambiguity row.
        val compileId = CodeUseTelemetryService.newSnippetKey()
        CodeUseTelemetryService.recordCompileResult(
            snippetId = compileId,
            source = "sin(0.0)",
            sourceName = "CodeUseTelemetryBusyRecovery.kts",
            context = context.copy(mode = "compile:user-code"),
            success = true,
            diagnostics = emptyList(),
            elapsedMs = 0.25
        )
        val heldEvents = launchIds.size + 2

        val waiting = waitForSummary("two SQLITE_BUSY retries", timeoutMs = 30_000) { it.writerBusyRetries >= 2 }
        val heldForMs = System.currentTimeMillis() - lockTakenAt
        require(heldForMs > 5_000) { "Lock was held only $heldForMs ms, not past busy_timeout" }
        require(waiting.enabled) { "Writer disabled itself on SQLITE_BUSY: $waiting" }
        require("SQLITE_BUSY" in waiting.writerError) { "Summary does not name the busy error: $waiting" }
        require("write lock" in waiting.message) { "Summary message does not report the wait: $waiting" }
        require(waiting.queueSize >= heldEvents) { "Held events missing from queueSize: $waiting" }
        require(launchRows(database, launchIds) == 0) { "Held events were committed while the lock was held" }
        // Queued before the commit, the analysis job would have run, waited out the lock, and counted.
        require(waiting.analyzerCompletedJobs == 0L && waiting.analyzerQueueSize == 0) {
            "Analysis was queued before its compile event committed: $waiting"
        }

        blocker.rollback()
        blocker.close()

        waitFor("the held events to commit", timeoutMs = 30_000) {
            launchRows(database, launchIds) == launchIds.size &&
                rowCount(database, "eval_events", evalId) == 1 &&
                rowCount(database, "compile_events", compileId) == 1
        }
        val recovered = waitForSummary("the writer to report recovery") { it.writerError.isEmpty() && it.queueSize == 0 }
        require(recovered.enabled) { "Writer did not stay enabled: $recovered" }
        require(recovered.writerBusyRetries >= 2) { "Retry count was lost: $recovered" }
        require(recovered.droppedEvents == 0L) { "Events were dropped: $recovered" }
        require("write lock" !in recovered.message) { "Summary still reports a wait: $recovered" }
        require(recovered.launchEvents >= 1L + launchIds.size) { "Summary does not count the held launches: $recovered" }

        waitFor("analysis of the held compile event") { unresolvedRows(database, compileId) > 0 }
        val plan = DriverManager.getConnection("jdbc:sqlite:$database").use { conn ->
            conn.prepareStatement("EXPLAIN QUERY PLAN DELETE FROM unresolved_uses WHERE snippet_id = ?").use { ps ->
                ps.setString(1, compileId)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("detail")) } }
            }
        }
        require(plan.any { "USING INDEX idx_unresolved_uses_snippet" in it }) {
            "Per-snippet unresolved_uses delete does not use its index: $plan"
        }

        println(
            "CodeUseTelemetryBusyRecoverySmokeTest ok (lock held ${heldForMs} ms, " +
                "${recovered.writerBusyRetries} busy retries, $heldEvents held events committed)"
        )
    }

    private fun waitForSummary(
        what: String,
        timeoutMs: Long = 10_000,
        predicate: (CodeUseSummary) -> Boolean
    ): CodeUseSummary {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = CodeUseTelemetryService.summary()
        while (!predicate(latest)) {
            require(System.currentTimeMillis() < deadline) { "Timed out waiting for $what: $latest" }
            Thread.sleep(100)
            latest = CodeUseTelemetryService.summary()
        }
        return latest
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            require(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(100)
        }
    }

    private fun launchRows(database: String, snippetIds: List<String>): Int =
        snippetIds.sumOf { rowCount(database, "launch_events", it) }

    private fun unresolvedRows(database: String, snippetId: String): Int =
        rowCount(database, "unresolved_uses", snippetId)

    private fun rowCount(database: String, table: String, snippetId: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$database").use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM $table WHERE snippet_id = ?").use { ps ->
                ps.setString(1, snippetId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
}
