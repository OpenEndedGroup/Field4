package afield.ai.core

import kotlin.test.*

class AiToastPolicyTest {
    @Test fun rawDiagnosticsNeverBecomeToastsIncludingRawErrors() {
        val policy = AiToastPolicy()
        for (event in listOf(
            LogEvent("info,ai.bridge", "creating response (follow-up) <<spinner>>"),
            LogEvent("info,ai.bridge", "tool: name=ViewBoxOutputRequest callId=secret {\"box\":\"private-id\"}"),
            LogEvent("info,ai.tools", "Registered AI tool 'pool.publish'"),
            LogEvent("error,ai.bridge", "request failed: secret payload"),
            LogEvent("info,ai.toolio", "log=/private/path")
        )) assertNull(policy.message(event, 1_000))
    }

    @Test fun busyWorkIsThrottledButProblemsAreImmediate() {
        val policy = AiToastPolicy()
        fun activity(text: String, time: Long) = policy.message(LogEvent("info,ai.user", text), time)
        assertEquals("Checking the view…", activity("Checking the view…", 0))
        assertNull(activity("Checking the code…", 1_000))
        val failure = LogEvent("warning,ai.user", "An action could not finish. See the chat.")
        assertEquals(failure.message, policy.message(failure, 1_001))
        assertNull(policy.message(failure, 2_000))
        assertEquals("Checking the code…", activity("Checking the code…", 5_000))
        assertNull(activity("Checking the code…", 12_000))
        assertEquals("Checking the code…", activity("Checking the code…", 20_000))
    }

    @Test fun toolMessagesContainAnActionAndNoInternalNames() {
        val events = mutableListOf<LogEvent>()
        val log = LogSink { events += it }
        log.toolActivity("ViewBoxOutputRequest")
        log.toolActivity("ListBoxesRequest")
        log.toolActivity("custom.internal.tool")
        log.toolActivity("CompileKotlinRequest")
        assertEquals(listOf("Checking the view…", "Checking the code…"), events.map { it.message })
        assertTrue(events.all { it.channel == "info,ai.user" })
    }
}
