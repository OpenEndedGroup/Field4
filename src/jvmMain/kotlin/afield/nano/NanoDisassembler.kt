package afield.nano

/**
 * Reads a Nano bytecode `ByteArray` back as assembly text, one `DisasmLine` per instruction.
 *
 * It walks the program twice. The first walk collects every branch target, so that the second can
 * put a name on each one and a branch reads as a label rather than as a raw offset. The name is
 * the target address in hex, so `L00` and `L0A`, and it arrives as its own `DisasmLine` with an
 * empty `bytes` list, which is how `NanoVisualization.kt` tells a label apart from an instruction
 * when it is deciding which line to highlight.
 *
 * `DisasmLine.toString` is the printable form:
 *
 *     addr:  hex  hex   mnemonic  operands    ; optional annotation
 *
 * The annotation is the part the bytes do not say: the signed offset behind a relative branch, and
 * the absolute address behind `jmpa` or `calla`. A byte that decodes to no instruction prints as
 * `???` with its hex value, which is the five of the 256 that `NanoVM.kt` lists as executing
 * nothing: `0x03`, and `0xFC`–`0xFF` past `DIV`'s last register pair.
 */

/**
 * The disassembler. Like the assembler it keeps no state between calls.
 */
class NanoDisassembler {

    /**
     * One output line: its [addr], the program [bytes] it decoded from, and the [text] to show.
     *
     * A label line carries the same address as the instruction it precedes and an empty [bytes],
     * so looking a program counter up by [addr] alone finds both of them.
     */
    data class DisasmLine(
        val addr: Int,
        val bytes: List<Int>,
        val text: String
    ) {
        override fun toString(): String {
            val hexPart = bytes.joinToString(" ") { it.toString(16).padStart(2, '0') }
                .padEnd(8)
            return "${addr.toString(16).padStart(2, '0')}:  $hexPart  $text"
        }
    }

    /**
     * Disassemble [program] starting at offset [startAddr].
     * Returns a list of [DisasmLine]s.
     */
    fun disassemble(program: ByteArray, startAddr: Int = 0): List<DisasmLine> {
        // First pass: collect branch targets so we can emit labels
        val branchTargets = mutableSetOf<Int>()
        collectBranchTargets(program, startAddr, branchTargets)

        val lines = mutableListOf<DisasmLine>()
        var i = startAddr
        while (i < program.size) {
            val op = program[i].toInt() and 0xFF

            if (branchTargets.contains(i)) {
                // Emit a label pseudo-line (zero bytes, just a label marker)
                lines += DisasmLine(i, emptyList(), labelFor(i) + ":")
            }

            val line = decodeAt(i, op, program, startAddr)
            lines += line
            i += line.bytes.size
        }
        return lines
    }

    /** Disassemble and join into a single printable string. */
    fun disassembleToString(program: ByteArray, startAddr: Int = 0): String =
        disassemble(program, startAddr).joinToString("\n")

    // ── Label naming ─────────────────────────────────────────────────────────

    private fun labelFor(addr: Int) = "L${addr.toString(16).padStart(2, '0').uppercase()}"

    // ── Branch target collection ──────────────────────────────────────────────

    private fun collectBranchTargets(prog: ByteArray, start: Int, targets: MutableSet<Int>) {
        var i = start
        while (i < prog.size) {
            val op = prog[i].toInt() and 0xFF
            val size = instrSize(op, prog, i)
            when {
                op in 0xE0..0xE9 -> { // relative branches + CALL
                    if (i + 1 < prog.size) {
                        val rel = prog[i + 1].toInt().let { if (it > 127) it - 256 else it }
                        val target = i + 2 + rel
                        if (target in start until prog.size) targets += target
                    }
                }
                op == 0xEA || op == 0xEB -> { // JMPA, CALLA
                    if (i + 1 < prog.size) {
                        val target = prog[i + 1].toInt() and 0xFF
                        if (target in start until prog.size) targets += target
                    }
                }
            }
            i += size
        }
    }

    // ── Instruction size ──────────────────────────────────────────────────────

    private fun instrSize(op: Int, prog: ByteArray, addr: Int): Int = when {
        op in 0xC0..0xDF -> 2  // reg + immediate/address
        op in 0xE0..0xEB -> 2  // branches
        else -> 1
    }

    // ── Decode single instruction ─────────────────────────────────────────────

    private fun decodeAt(addr: Int, op: Int, prog: ByteArray, startAddr: Int): DisasmLine {
        fun byte1(): Int = if (addr + 1 < prog.size) prog[addr + 1].toInt() and 0xFF else 0
        fun signed1(): Int = byte1().let { if (it > 127) it - 256 else it }
        fun regName(r: Int) = "r$r"
        fun twoRegs(base: Int): Pair<String, String> {
            val n = op - base
            return Pair(regName(n shr 2), regName(n and 3))
        }

        val (text, rawBytes) = when {
            op == 0x00 -> Pair("nop", listOf(op))
            op == 0x01 -> Pair("hlt", listOf(op))
            op == 0x02 -> Pair("ret", listOf(op))

            op in 0x04..0x07 -> Pair("inc  ${regName(op - 0x04)}", listOf(op))
            op in 0x08..0x0B -> Pair("dec  ${regName(op - 0x08)}", listOf(op))
            op in 0x0C..0x0F -> Pair("not  ${regName(op - 0x0C)}", listOf(op))
            op in 0x10..0x13 -> Pair("neg  ${regName(op - 0x10)}", listOf(op))
            op in 0x14..0x17 -> Pair("clr  ${regName(op - 0x14)}", listOf(op))
            op in 0x18..0x1B -> Pair("out  ${regName(op - 0x18)}", listOf(op))
            op in 0x1C..0x1F -> Pair("rng  ${regName(op - 0x1C)}", listOf(op))

            op in 0x20..0x2F -> { val (d, s) = twoRegs(0x20); Pair("mov  $d, $s", listOf(op)) }
            op in 0x30..0x3F -> { val (d, s) = twoRegs(0x30); Pair("add  $d, $s", listOf(op)) }
            op in 0x40..0x4F -> { val (d, s) = twoRegs(0x40); Pair("sub  $d, $s", listOf(op)) }
            op in 0x50..0x5F -> { val (d, s) = twoRegs(0x50); Pair("mul  $d, $s", listOf(op)) }
            op in 0x60..0x6F -> { val (d, s) = twoRegs(0x60); Pair("and  $d, $s", listOf(op)) }
            op in 0x70..0x7F -> { val (d, s) = twoRegs(0x70); Pair("or   $d, $s", listOf(op)) }
            op in 0x80..0x8F -> { val (d, s) = twoRegs(0x80); Pair("xor  $d, $s", listOf(op)) }
            op in 0x90..0x9F -> { val (d, s) = twoRegs(0x90); Pair("cmp  $d, $s", listOf(op)) }
            op in 0xA0..0xAF -> { val (d, s) = twoRegs(0xA0); Pair("ldr  $d, $s", listOf(op)) }
            op in 0xB0..0xBF -> { val (d, s) = twoRegs(0xB0); Pair("str  $d, $s", listOf(op)) }

            op in 0xC0..0xC3 -> {
                val r = regName(op - 0xC0); val imm = byte1()
                Pair("ldi  $r, 0x${imm.toString(16).padStart(2, '0')}", listOf(op, imm))
            }
            op in 0xC4..0xC7 -> {
                val r = regName(op - 0xC4); val addr2 = byte1()
                Pair("lda  $r, ${addr2}", listOf(op, addr2))
            }
            op in 0xC8..0xCB -> {
                val r = regName(op - 0xC8); val addr2 = byte1()
                Pair("sta  $r, ${addr2}", listOf(op, addr2))
            }
            op in 0xCC..0xCF -> {
                val r = regName(op - 0xCC); val imm = signed1()
                Pair("addi $r, $imm", listOf(op, byte1()))
            }
            op in 0xD0..0xD3 -> {
                val r = regName(op - 0xD0); val rel = signed1()
                Pair("ldrr $r, $rel", listOf(op, byte1()))
            }
            op in 0xD4..0xD7 -> {
                val r = regName(op - 0xD4); val rel = signed1()
                Pair("strr $r, $rel", listOf(op, byte1()))
            }
            op in 0xD8..0xDB -> {
                val r = regName(op - 0xD8); val n = byte1()
                Pair("shl  $r, $n", listOf(op, n))
            }
            op in 0xDC..0xDF -> {
                val r = regName(op - 0xDC); val n = byte1()
                Pair("shr  $r, $n", listOf(op, n))
            }

            op == 0xE0 -> {
                val rel = signed1(); val target = addr + 2 + rel
                Pair("jmp  ${labelFor(target)}  ; ${showRel(rel)}", listOf(op, byte1()))
            }
            op == 0xE1 -> {
                val rel = signed1(); val target = addr + 2 + rel
                Pair("jz   ${labelFor(target)}  ; ${showRel(rel)}", listOf(op, byte1()))
            }
            op == 0xE2 -> {
                val rel = signed1(); val target = addr + 2 + rel
                Pair("jnz  ${labelFor(target)}  ; ${showRel(rel)}", listOf(op, byte1()))
            }
            op == 0xE3 -> {
                val rel = signed1(); val target = addr + 2 + rel
                Pair("jgt  ${labelFor(target)}  ; ${showRel(rel)}", listOf(op, byte1()))
            }
            op == 0xE4 -> {
                val rel = signed1(); val target = addr + 2 + rel
                Pair("jlt  ${labelFor(target)}  ; ${showRel(rel)}", listOf(op, byte1()))
            }
            op == 0xE5 -> {
                val rel = signed1(); val target = addr + 2 + rel
                Pair("jge  ${labelFor(target)}  ; ${showRel(rel)}", listOf(op, byte1()))
            }
            op == 0xE6 -> {
                val rel = signed1(); val target = addr + 2 + rel
                Pair("jle  ${labelFor(target)}  ; ${showRel(rel)}", listOf(op, byte1()))
            }
            op == 0xE7 -> {
                val rel = signed1(); val target = addr + 2 + rel
                Pair("jc   ${labelFor(target)}  ; ${showRel(rel)}", listOf(op, byte1()))
            }
            op == 0xE8 -> {
                val rel = signed1(); val target = addr + 2 + rel
                Pair("jnc  ${labelFor(target)}  ; ${showRel(rel)}", listOf(op, byte1()))
            }
            op == 0xE9 -> {
                val rel = signed1(); val target = addr + 2 + rel
                Pair("call ${labelFor(target)}  ; ${showRel(rel)}", listOf(op, byte1()))
            }
            op == 0xEA -> {
                val a = byte1()
                Pair("jmpa ${labelFor(a)}  ; abs 0x${a.toString(16)}", listOf(op, a))
            }
            op == 0xEB -> {
                val a = byte1()
                Pair("calla ${labelFor(a)}  ; abs 0x${a.toString(16)}", listOf(op, a))
            }

            // 0xEC–0xFB only: 0xFC–0xFF decode no (dst,src) pair the VM will execute, so they
            // belong in the `???` arm rather than rendering as a div against a register r4 that
            // does not exist.
            op in 0xEC..0xFB -> {
                val (d, s) = twoRegs(0xEC)
                Pair("div  $d, $s", listOf(op))
            }

            else -> Pair("???  0x${op.toString(16).padStart(2, '0')}", listOf(op))
        }
        return DisasmLine(addr, rawBytes, text)
    }

    private fun showRel(rel: Int) = if (rel >= 0) "+$rel" else "$rel"
}
