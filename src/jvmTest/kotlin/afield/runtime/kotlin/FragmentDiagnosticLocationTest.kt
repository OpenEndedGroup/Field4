package afield.runtime.kotlin

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.runtime.lifecycle.end
import afield.ui.output.Location
import afield.runtime.macros.rebaseFragmentDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class FragmentDiagnosticLocationTest {
    @Test
    fun shiftsBothEndsAfterFragmentBreakAndPreservesColumns() {
        val diagnostic = ScriptDiagnostic(
            ScriptDiagnostic.unspecifiedError,
            "Expecting an element",
            ScriptDiagnostic.Severity.ERROR,
            null,
            SourceCode.Location(
                SourceCode.Position(2, 7, 12),
                SourceCode.Position(3, 4, 20)
            ),
            null
        )

        val shifted = rebaseFragmentDiagnostics(listOf(diagnostic), 21).single()
        assertEquals(23, shifted.location?.start?.line)
        assertEquals(7, shifted.location?.start?.col)
        assertEquals(24, shifted.location?.end?.line)
        assertEquals(4, shifted.location?.end?.col)
        assertNull(shifted.location?.start?.absolutePos)
        assertNull(shifted.location?.end?.absolutePos)
        assertEquals(diagnostic.message, shifted.message)
    }

    @Test
    fun leavesFirstFragmentAndUnlocatedDiagnosticsAlone() {
        val diagnostic = ScriptDiagnostic(
            ScriptDiagnostic.unspecifiedError,
            "error",
            ScriptDiagnostic.Severity.ERROR,
            null,
            SourceCode.Location(SourceCode.Position(2, 1, 4)),
            null
        )
        val unlocated = diagnostic.copy(location = null)

        assertSame(diagnostic, rebaseFragmentDiagnostics(listOf(diagnostic), 0).single())
        assertSame(unlocated, rebaseFragmentDiagnostics(listOf(unlocated), 5).single())
    }
}
