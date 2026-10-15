package afield.runtime.execution

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.app.protocol.ExecutionReport
import afield.app.protocol.Message
import afield.app.protocol.Output
import afield.app.protocol.payload
import kotlin.test.*

class ExecutionVerdictTest {
    @Test fun errorClearDoesNotFailButCompilerErrorNamesSource() {
        val verdict = ExecutionVerdict("box", "code", "bad code")
        verdict.observe(Message("output").payload(Output(uid="box", kind="error", html="", line=-1, frame=0)))
        verdict.observe(Message("output").payload(Output(uid="other", kind="error", html="not ours", line=-1, frame=0)))
        verdict.check()
        verdict.observe(Message("output").payload(Output(uid="box", kind="error", html="Unresolved reference", line=4, frame=0)))
        verdict.observe(Message("output").payload(Output(uid="box", kind="ok", html="ok", line=-1, frame=0)))
        val failure = assertFailsWith<IllegalStateException> { verdict.check() }
        assertTrue(failure.message!!.contains("box/code:4"))
        assertTrue(failure.message!!.contains(sourceRevision("bad code")))
        assertTrue(failure.message!!.contains("Unresolved reference"))
    }
    @Test fun falseReceiptSurvivesSerializationWithoutAnOutputDiagnostic() {
        val report = ExecutionReport("box", "code", "hash", success=false, stage="failed", error="cancelled")
        val json = kotlinx.serialization.json.Json.encodeToString(ExecutionReport.serializer(), report)
        assertTrue(json.contains("\"success\":false"))
        val verdict = ExecutionVerdict("box", "code", "source")
        verdict.observe(Message("execution-verdict").payload(report))
        assertFailsWith<IllegalStateException> { verdict.check() }
    }
}
