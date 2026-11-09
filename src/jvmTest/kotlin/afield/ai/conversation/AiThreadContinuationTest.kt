package afield.ai.conversation

import afield.ai.models.LlmBridge
import afield.ai.models.LlmThreadState
import afield.ai.models.ScriptedLlmBridge
import afield.document.graph.Box
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AiThreadContinuationTest {
    @Test fun onlyTheMatchingTranscriptPropertyAndAssistanceModeMayResume() {
        val box = Box("continuation-probe")
        val transcript = "<<you>>\nBuild it\n<<ai>>\nDone"
        AiBoxConversation.write(box, transcript)
        val state = LlmThreadState("local", "conv_example", "box/code", previousResponseId = "resp_example")
        var restores = 0
        val bridge = object : LlmBridge by ScriptedLlmBridge(emptyList()) {
            override fun checkpointThread(state: LlmThreadState) = "structured-history-or-hosted-id"
            override fun restoreThread(checkpoint: String, report: (String) -> Unit): LlmThreadState {
                assertEquals("structured-history-or-hosted-id", checkpoint)
                restores++
                report("fixture_restored")
                return state
            }
        }
        AiThreadContinuation.save(box, bridge, state, "general")
        assertSame(state, AiThreadContinuation.restore(box, bridge, "box/code", "general", transcript))
        assertEquals(true, AiThreadContinuation.diagnostics(box).contains("checkpoint_resumed"))
        assertNull(AiThreadContinuation.restore(box, bridge, "box/code", "edit", transcript))
        assertNull(AiThreadContinuation.restore(box, bridge, "box/code", "general", transcript + "edited"))
        assertEquals(true, AiThreadContinuation.diagnostics(box).contains("transcript_changed_or_rewound"))
        assertEquals(1, restores, "Mismatched transcript and mode must never contact the provider")
        assertNull(AiThreadContinuation.restore(box, bridge, "box/fragment", "general", transcript))
        assertEquals(1, restores, "A different property must not contact the provider")
        assertNull(AiThreadContinuation.restore(box, ScriptedLlmBridge(emptyList()), "box/code", "general", transcript))
        assertEquals(true, AiThreadContinuation.diagnostics(box).contains("unsupported_provider"))
        AiThreadContinuation.clear(box)
        assertNull(AiThreadContinuation.restore(box, bridge, "box/code", "general", transcript))
        assertEquals(false, AiThreadContinuation.diagnostics(box).contains("structured-history-or-hosted-id"))
        assertEquals(false, AiThreadContinuation.diagnostics(box).contains(transcript))
        box.setPropertyRaw(AiThreadContinuation.PROPERTY, "broken checkpoint")
        assertNull(AiThreadContinuation.restore(box, bridge, "box/code", "general", transcript))
    }
}
