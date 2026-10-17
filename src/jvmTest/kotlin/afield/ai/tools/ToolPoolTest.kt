package afield.ai.tools

import afield.ai.completion.*
import afield.ai.conversation.*
import afield.ai.diagnostics.*
import afield.ai.models.*
import afield.ai.tools.*
import afield.runtime.languages.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ToolPoolTest {
    @Test fun refusalExamplesAcceptTheExpectedExceptionAndStructuredError() = runBlocking {
        val example = BoxToolTest("refuse changed input", "{}", "input material changed")
        val thrown = evaluateToolTest(example, { error("input material changed: lost 42") }, { fail("No success verifier") })
        val returned = evaluateToolTest(example, { BoxToolError(listOf("input material changed: gained 3")) }, { fail("No success verifier") })
        assertTrue(thrown.passed)
        assertTrue(returned.passed)
        assertTrue(thrown.message.contains("lost 42"))
    }

    @Test fun refusalExamplesRejectSuccessEvenWhenItsTextContainsTheExpectedMessage() = runBlocking {
        val example = BoxToolTest("refuse changed input", "{}", "input material changed")
        val outcome = evaluateToolTest(example, { mapOf("ok" to true, "message" to "input material changed") }, {})
        assertFalse(outcome.passed)
        assertTrue(outcome.message.contains("tool succeeded"))
    }

    @Test fun unrelatedErrorsCannotSatisfyRefusalExamples() = runBlocking {
        val example = BoxToolTest("refuse changed input", "{}", "input material changed")
        val outcome = evaluateToolTest(example, { error("compiler unavailable") }, {})
        assertFalse(outcome.passed)
        assertTrue(outcome.message.contains("compiler unavailable"))
    }

    @Test fun cancellationNeverPassesAsAnExpectedFailure() = runBlocking {
        val cancelled = java.util.concurrent.CancellationException("input material changed")
        val caught = assertFailsWith<java.util.concurrent.CancellationException> {
            evaluateToolTest(BoxToolTest("refusal", "{}", "input material changed"), { throw cancelled }, {})
        }
        assertSame(cancelled, caught)
    }

    @Test fun successExamplesStillRejectToolFailuresAndWrongResults() = runBlocking {
        val example = BoxToolTest("volume", "{}", {})
        assertFalse(evaluateToolTest(example, { BoxToolError(listOf("bad input")) }, {}).passed)
        assertFalse(evaluateToolTest(example, { 41 }, { check(it == 42) { "wrong volume" } }).passed)
        assertTrue(evaluateToolTest(example, { 42 }, { check(it == 42) }).passed)
    }

    @Test fun refusalExamplesRequireSpecificMessagesAndUniqueNames() {
        val builder = BoxToolBuilder(afield.document.graph.Box("test"), "measure")
        assertFailsWith<IllegalArgumentException> { builder.testFailure("invalid", messageContains=" ") }
        builder.testFailure("invalid", messageContains="input is empty")
        assertFailsWith<IllegalArgumentException> { builder.test("invalid") {} }
        assertFailsWith<IllegalArgumentException> { builder.testFailure("invalid", messageContains="other") }
    }

    @Test fun versionsAreImmutableAndReopenAcrossProjectsWithTheirSourceEvidence() {
        val dir=Files.createTempDirectory("tool-pool-test").toFile()
        try {
            val pool=ToolPool(dir)
            val source="val utility = aiTool(\"measure\") { action { 1 } }"
            val checks=mapOf("measure" to ToolTestEvidence(ToolPool.hash(source),tests=listOf(ToolTestOutcome("one",true,"Passed",1))))
            val tools=listOf(PooledTool("measure","Measure", "{}",listOf("one")))
            val first=pool.store("measure","A reusable utility",source,tools,checks,"project-a","box-a")
            val second=pool.store("measure","Revised utility",source.replace("{ 1 }","{ 2 }"),tools,emptyMap(),"project-b","box-b")
            assertNotEquals(first.revision,second.revision)
            val reopened=ToolPool(dir)
            assertEquals(first,reopened.read("measure",first.revision))
            assertEquals(second,reopened.read("measure",second.revision))
            assertEquals(2,reopened.list("measure").size)
            assertTrue(second.checks.isEmpty(),"A new source must not inherit earlier test results")
            val path=dir.resolve("measure/${first.revision}.json")
            path.writeText(path.readText().replace("A reusable utility","Changed behind the pool"))
            assertFailsWith<IllegalArgumentException> { reopened.read("measure",first.revision) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidPathsAndUnknownRevisionsCannotReadOutsidePool() {
        val pool=ToolPool(Files.createTempDirectory("tool-pool-path").toFile())
        try {
            assertFailsWith<IllegalArgumentException> { pool.read("../outside","a".repeat(64)) }
            assertFailsWith<IllegalArgumentException> { pool.read("measure","../file") }
        } finally { pool.directory.deleteRecursively() }
    }

    @Test fun structuredToolFailuresAreNotRecordedAsSuccessfulCalls() {
        assertFalse(ToolDiagnostics.succeeded(BoxToolError(listOf("Invalid geometry"))))
        assertFalse(ToolDiagnostics.succeeded(mapOf("ok" to false)))
        assertFalse(ToolDiagnostics.succeeded(mapOf("success" to false)))
        assertTrue(ToolDiagnostics.succeeded(mapOf("ok" to true)))
        assertFalse(ToolDiagnostics.succeeded(mapOf("error" to "The check did not pass")))
        assertFalse(ToolDiagnostics.succeeded(mapOf("errors" to listOf("invalid opening"))))
        assertFalse(ToolDiagnostics.succeeded(mapOf("status" to "held")))
        assertFalse(ToolDiagnostics.succeeded(mapOf("status" to "FAILED")))
        assertFalse(ToolDiagnostics.succeeded(mapOf("tested" to true, "passed" to false)))
        assertTrue(ToolDiagnostics.succeeded(mapOf("tested" to false, "passed" to false)))
        assertTrue(ToolDiagnostics.succeeded(mapOf("error" to "", "errors" to emptyList<String>(), "status" to "running")))
        assertTrue(ToolDiagnostics.succeeded(mapOf("success" to true, "history" to listOf(mapOf("status" to "failed")))))
        assertTrue(ToolDiagnostics.summary("x".repeat(10000)).length<8100)
        assertFalse(ToolDiagnostics.summary(BoxToolImages(imageDataUrls=listOf("data:image/png;base64,secret"))).contains("secret"))
    }
}
