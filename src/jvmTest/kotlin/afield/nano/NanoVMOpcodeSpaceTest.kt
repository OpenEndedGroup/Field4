package afield.nano

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Nano is built to run byte strings nobody wrote.
 *
 * That is the whole point of the VM — artificial-life work mutates programs, and `.data 0xfc` puts
 * an arbitrary byte in front of the decoder from the editor too. So the decode ladder owes a total
 * function over all 256 opcodes: every byte either executes, or goes through `onError` and sets
 * `halted`. Nothing may throw, because neither call site in `NanoInterface` has a `catch` — both
 * `try` blocks there are `try { … } finally { … }`, so an exception out of `step` escapes the
 * box's execution path entirely rather than landing in the box's error decoration.
 *
 * 0xFC–0xFF were the hole. The DIV arm claimed `0xEC..0xFF` — twenty encodings for a sixteen-
 * encoding instruction — so `(opcode - 0xEC) shr 2` produced a register index of 4 against an
 * `IntArray(4)` and threw ArrayIndexOutOfBoundsException.
 *
 * WHAT THIS CAN CATCH
 *  - any opcode whose arm computes an out-of-range register, RAM or stack index (the sweep steps
 *    all 256 and would surface the throw);
 *  - a range in the ladder drifting out of agreement with `Opcode`'s encoders, in either
 *    direction: an encodable opcode the ladder no longer decodes as DIV, or an unassigned byte
 *    that stops reporting an error;
 *  - the disassembler and the VM disagreeing about which bytes are DIV, which is how a run report
 *    comes to show `div r4, r0` for a byte the VM refuses.
 *
 * WHAT IT CANNOT CATCH
 *  - whether an instruction's *semantics* are right; only DIV is checked here, and only enough to
 *    pin the register decode. The arithmetic and flag behaviour of the other instructions is not
 *    this test's subject.
 *  - what the user sees. The sweep proves the VM reports rather than throws; it does not exercise
 *    `NanoInterface`'s output path.
 */
class NanoVMOpcodeSpaceTest {

    /** Bytes no instruction encodes. Everything else in 0x00–0xFF is a real opcode. */
    private val unassigned = listOf(0x03) + (0xFC..0xFF)

    @Test
    fun `every opcode reports rather than throws`() {
        for (opcode in 0x00..0xFF) {
            val vm = NanoVM(maxSteps = 8)
            var reported: String? = null
            vm.onError = { msg, _ -> reported = msg }

            // Two bytes so the two-byte forms have an operand to read; the step itself is what is
            // under test, not the bounds behaviour of a truncated program.
            val program = byteArrayOf(opcode.toByte(), 0x00)
            val result = runCatching { vm.stepOnce(program) }

            assertTrue(
                result.isSuccess,
                "opcode 0x${opcode.toString(16).padStart(2, '0')} threw " +
                    "${result.exceptionOrNull()} instead of reporting through onError"
            )
            if (opcode in unassigned) {
                assertTrue(
                    reported != null,
                    "unassigned opcode 0x${opcode.toString(16)} executed silently; " +
                        "expected an onError report"
                )
                assertTrue(
                    vm.halted,
                    "unassigned opcode 0x${opcode.toString(16)} reported but left the VM running"
                )
            }
        }
    }

    @Test
    fun `0xFC to 0xFF are unknown opcodes, not a fifth register`() {
        for (opcode in 0xFC..0xFF) {
            val vm = NanoVM()
            var reported: String? = null
            vm.onError = { msg, _ -> reported = msg }
            vm.reg[0] = 10; vm.reg[1] = 2

            val step = runCatching { vm.stepOnce(byteArrayOf(opcode.toByte())) }.getOrElse {
                throw AssertionError("stepping 0x${opcode.toString(16)} threw $it", it)
            }

            assertEquals(
                "Unknown opcode 0x${opcode.toString(16)}",
                reported,
                "0x${opcode.toString(16)} should be reported as an unknown opcode"
            )
            assertTrue(step.halted && vm.halted, "0x${opcode.toString(16)} should halt the VM")
            assertEquals(
                listOf(10, 2, 0, 0),
                vm.reg.toList(),
                "a rejected opcode must not have written a register"
            )
        }
    }

    @Test
    fun `the DIV range and Opcode's encoder agree`() {
        for (dst in 0 until NanoVM.NUM_REGS) {
            for (src in 0 until NanoVM.NUM_REGS) {
                val opcode = Opcode.twoReg(Opcode.DIV_BASE, dst, src)
                assertTrue(
                    opcode in 0xEC..0xFB,
                    "twoReg(DIV_BASE, $dst, $src) = 0x${opcode.toString(16)} is outside the " +
                        "range the ladder decodes as DIV"
                )

                val vm = NanoVM()
                var reported: String? = null
                vm.onError = { msg, _ -> reported = msg }
                // Distinct per-register values, so a misdecoded dst or src shows up as a wrong
                // quotient rather than coinciding with the right answer.
                vm.reg[0] = 100; vm.reg[1] = 50; vm.reg[2] = 4; vm.reg[3] = 2

                vm.stepOnce(byteArrayOf(opcode.toByte()))

                assertEquals(null, reported, "div r$dst, r$src should not report an error")
                val expected = intArrayOf(100, 50, 4, 2).also {
                    it[dst] = if (it[src] == 0) 0xFF else it[dst] / it[src]
                }
                assertEquals(
                    expected.toList(),
                    vm.reg.toList(),
                    "div r$dst, r$src (0x${opcode.toString(16)}) decoded to the wrong registers"
                )
            }
        }
    }

    @Test
    fun `the disassembler agrees with the VM about which bytes are DIV`() {
        for (opcode in 0xEC..0xFF) {
            val text = NanoDisassembler().disassemble(byteArrayOf(opcode.toByte()))
                .single()
                .text
            if (opcode in 0xFC..0xFF) {
                assertTrue(
                    text.startsWith("???"),
                    "0x${opcode.toString(16)} is not executable, so it should disassemble as " +
                        "unknown; got '$text'"
                )
            } else {
                val d = (opcode - 0xEC) shr 2
                val s = (opcode - 0xEC) and 3
                assertEquals("div  r$d, r$s", text)
            }
        }
    }
}
