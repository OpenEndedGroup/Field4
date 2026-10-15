package afield.ai.tools

import afield.document.graph.Box
import afield.runtime.lifecycle.Runloop
import afield.ai.tools.BoxTool
import afield.ai.tools.BoxToolArg
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.typeOf
import kotlin.test.*

class BoxToolArgumentBindingTest {
    @Test fun ioDispatcherDoesNotMakeBlockingTheOwnerSafe() {
        val previous = Runloop.mainThread
        Runloop.mainThread = Thread.currentThread()
        try {
            val error = assertFailsWith<IllegalStateException> {
                runBlocking(Dispatchers.IO) { withTimeout(2000) { reviewTool().call("{}") } }
            }
            assertTrue(error.message.orEmpty().contains("even with Dispatchers.IO"))
        } finally { Runloop.mainThread = previous }
    }

    @Test fun testEntryRejectsBlockedOwnerBeforeVerificationCanDispatch() {
        val previous = Runloop.mainThread
        Runloop.mainThread = Thread.currentThread()
        try {
            val error = assertFailsWith<IllegalStateException> {
                runBlocking(Dispatchers.IO) { withTimeout(2000) { reviewTool().test() } }
            }
            assertTrue(error.message.orEmpty().contains("would deadlock"))
        } finally { Runloop.mainThread = previous }
    }
    private fun reviewTool() = BoxTool("review", "Inspect one item", "binding-test", Box("binding-test"),
        listOf(BoxToolArg("id", typeOf<String>(), "Optional item", true, "")), body = {
        fail("A binding rejection must not execute the action")
    })

    @Test fun unknownNameCannotSilentlyUseTheUnfilteredDefault() = runBlocking {
        val tool = reviewTool()
        val error = assertFailsWith<IllegalArgumentException> { tool.call("""{"routeId":"socket"}""") }
        assertTrue(error.message.orEmpty().contains("Unknown argument(s) routeId"))
        assertTrue(error.message.orEmpty().contains("Allowed arguments: id"))
        assertFalse(tool.diagnostics.calls().single().success)
    }

    @Test fun unknownNamesAreRejectedEvenWhenDeclaredArgumentsArePresent() = runBlocking {
        val error = assertFailsWith<IllegalArgumentException> { reviewTool().call("""{"id":"socket","surprise":true}""") }
        assertTrue(error.message.orEmpty().contains("surprise"))
    }

    @Test fun nonObjectPayloadsCannotBecomeDefaultArguments() = runBlocking {
        for (json in listOf("[]", "null", "123", "\"socket\"")) {
            val error = assertFailsWith<IllegalArgumentException> { reviewTool().call(json) }
            assertTrue(error.message.orEmpty().contains("must be a JSON object"), json)
        }
    }

    @Test fun validNamesAndOmittedDefaultsReachNormalDispatchAndSchemaMatchesRuntime() {
        val tool = reviewTool()
        assertEquals("false", tool.parametersSchema.getValue("additionalProperties").jsonPrimitive.content)
        assertFalse("required" in tool.parametersSchema)
        val previous = Runloop.mainThread
        Runloop.mainThread = Thread.currentThread()
        try {
            for (json in listOf("{}", "", """{"id":"socket"}""")) {
                val error = assertFailsWith<IllegalStateException> { runBlocking { tool.call(json) } }
                assertTrue(error.message.orEmpty().contains("would deadlock"), "Valid arguments must reach the ordinary dispatch guard")
            }
        } finally { Runloop.mainThread = previous }
    }
}
