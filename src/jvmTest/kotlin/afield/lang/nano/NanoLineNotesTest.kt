package afield.lang.nano

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A Nano run's line notes: how often each instruction line ran, the registers after it, and what it output. */
class NanoLineNotesTest {
    @Test
    fun eachInstructionLineSaysHowOftenItRanAndWhatFollowed() {
        val code = """
            ldi r0, 3
            ldi r1, 0
            loop:
            addi r1, 2
            out r1
            dec r0
            jnz loop
            hlt
            jmp loop
        """.trimIndent()
        val assembled = NanoAssembler().assembleWithSourceMap(code)
        assertIs<NanoAssembler.AssemblyDebugResult.Ok>(assembled)
        val vm = NanoVM()
        vm.load(assembled.info.bytes)
        val outputs = mutableListOf<Int>()
        vm.onOutput = { outputs += it }
        val trace = vm.runTracingLines(assembled.info.bytes, assembled.info.pcToLine)
        assertEquals(listOf(2, 4, 6), outputs, "the VM's own output still arrives")

        val notes = nanoLineNotes(assembled.info.pcToLine, trace).associate { it.firstLine to it.caption }
        assertEquals("ran once, then r0=3 r1=0 r2=0 r3=0", notes[1])
        assertEquals("ran 3 times, then r0=1 r1=6 r2=0 r3=0, output 2 4 6", notes[5])
        assertTrue(notes[7]!!.startsWith("ran 3 times"), notes[7])
        assertEquals("never ran", notes[9], "the jmp after hlt is never reached")
        assertTrue(3 !in notes, "a label line emits nothing and has no note")
    }
}
