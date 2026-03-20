package afield.nano

import kotlin.random.Random

/**
 * Nano – a minimal 8-bit virtual machine designed for emergence and artificial-life simulations.
 *
 * Specification:
 *  - 4 registers: r0, r1, r2, r3  (8-bit unsigned, i.e. 0–255)
 *  - 32 bytes of RAM (addresses wrap modulo 32)
 *  - Flags: Z (zero), C (carry), S (sign/negative), O (overflow)
 *  - Small call stack (8 levels deep) for CALL/RET
 *  - All values are unsigned bytes; arithmetic wraps mod 256
 *  - Programs and RAM data together fit comfortably in 64 bytes
 *
 * Opcode encoding (see [Opcode] companion for full table):
 *  - Single-byte no-operand instructions: NOP (0x00), HLT (0x01), RET (0x02)
 *  - Single-reg instructions (4 per op, one per register):
 *      INC, DEC, NOT, NEG, CLR, OUT, RNG  (0x04–0x1F)
 *  - Two-reg instructions (16 per op, encoded as OP_BASE + (dst<<2) + src):
 *      MOV, ADD, SUB, MUL, AND, OR, XOR, CMP, LDR, STR  (0x20–0xBF)
 *  - Reg + immediate/address (2 bytes each, 4 per op, one per register):
 *      LDI, LDA, STA, ADDI, LDRR, STRR, SHL, SHR  (0xC0–0xDF)
 *  - Branch instructions (2 bytes: opcode + signed offset):
 *      JMP, JZ, JNZ, JGT, JLT, JGE, JLE, JC, JNC, CALL  (0xE0–0xE9)
 *  - Absolute branch (2 bytes: opcode + absolute address):
 *      JMPA, CALLA  (0xEA–0xEB)
 *  - Two-reg DIV  (0xEC–0xFF)
 *
 * Assembly syntax quick reference:
 * ```
 * ; comment
 * start:
 *     ldi r0, 10     ; r0 = 10
 *     ldi r1, 0      ; r1 = 0
 * loop:
 *     add r1, r0     ; r1 += r0
 *     dec r0         ; r0--  (sets Z flag when r0 reaches 0)
 *     jnz loop       ; jump back if not zero
 *     out r1         ; print r1
 *     hlt
 * ```
 */
class NanoVM(
    val maxSteps: Int = 100_000,
    var rng: () -> Int = { Random.nextInt(256) }
) {
    companion object {
        const val RAM_SIZE = 32
        const val NUM_REGS = 4
        const val STACK_DEPTH = 8
    }

    // ── Machine state ──────────────────────────────────────────────────────────

    val reg = IntArray(NUM_REGS)
    val ram = IntArray(RAM_SIZE)
    var pc = 0
    var flagZ = false  // Zero
    var flagC = false  // Carry
    var flagS = false  // Sign (negative)
    var flagO = false  // Overflow
    var halted = false

    private val callStack = IntArray(STACK_DEPTH)
    private var callSP = 0

    var onOutput: (Int) -> Unit = {}
    var onError: (String, Int) -> Unit = { _, _ -> }

    data class StepResult(
        val executedPc: Int,
        val currentPc: Int,
        val halted: Boolean,
        val executed: Boolean
    )

    // ── Reset ──────────────────────────────────────────────────────────────────

    fun reset() {
        reg.fill(0)
        ram.fill(0)
        pc = 0
        flagZ = false; flagC = false; flagS = false; flagO = false
        halted = false
        callSP = 0
        callStack.fill(0)
    }

    /**
     * Reset only registers and execution state, preserving RAM contents.
     * Used in [NanoInterface] launch mode so RAM acts as persistent working memory
     * that survives across per-tick executions (important for ALife state machines).
     */
    fun resetRegisters() {
        reg.fill(0)
        pc = 0
        flagZ = false; flagC = false; flagS = false; flagO = false
        halted = false
        callSP = 0
        callStack.fill(0)
    }

    /** Load a program into a fresh machine (resets full state including RAM). */
    fun load(program: ByteArray) {
        reset()
        for (i in program.indices) {
            if (i < RAM_SIZE) ram[i] = program[i].toInt() and 0xFF
        }
    }

    /**
     * Run up to [maxSteps] instructions from current [pc].
     * Returns the number of steps executed.
     */
    fun run(program: ByteArray): Int {
        if (program.isEmpty()) return 0
        var steps = 0
        while (!halted && steps < maxSteps) {
            val stepResult = stepOnce(program)
            if (!stepResult.executed) break
            steps++
        }
        return steps
    }

    /**
     * Execute exactly one instruction at the current [pc].
     */
    fun stepOnce(program: ByteArray): StepResult {
        if (program.isEmpty() || halted) {
            return StepResult(executedPc = pc, currentPc = pc, halted = halted, executed = false)
        }
        if (pc < 0 || pc >= program.size) {
            halted = true
            return StepResult(executedPc = pc, currentPc = pc, halted = true, executed = false)
        }
        val executedPc = pc
        val opcode = program[pc].toInt() and 0xFF
        val nextPc = step(opcode, program)
        if (nextPc >= 0) {
            pc = nextPc
        } else {
            halted = true
        }
        return StepResult(
            executedPc = executedPc,
            currentPc = pc,
            halted = halted,
            executed = true
        )
    }

    // ── Flag helpers ──────────────────────────────────────────────────────────

    private fun setArithFlags(result: Int) {
        flagZ = (result and 0xFF) == 0
        flagS = (result and 0x80) != 0
    }

    private fun setAddFlags(a: Int, b: Int, result: Int) {
        flagC = result > 0xFF
        flagO = ((a xor result) and (b xor result) and 0x80) != 0
        setArithFlags(result)
    }

    private fun setSubFlags(a: Int, b: Int, result: Int) {
        flagC = a < b
        flagO = ((a xor b) and (a xor result) and 0x80) != 0
        setArithFlags(result)
    }

    // ── Single step ──────────────────────────────────────────────────────────

    private fun readByte(prog: ByteArray, addr: Int): Int {
        if (addr < 0 || addr >= prog.size) {
            onError("PC out of bounds: $addr", pc)
            halted = true
            return 0
        }
        return prog[addr].toInt() and 0xFF
    }

    private fun ramRead(addr: Int): Int = ram[addr and (RAM_SIZE - 1)]
    private fun ramWrite(addr: Int, v: Int) { ram[addr and (RAM_SIZE - 1)] = v and 0xFF }

    /**
     * Execute the instruction at current [pc] with [opcode].
     * @return the new PC value, or -1 on fatal error / halt.
     */
    private fun step(opcode: Int, prog: ByteArray): Int {
        return when {
            // ── No-operand ───────────────────────────────────────────────────
            opcode == 0x00 -> pc + 1 // NOP
            opcode == 0x01 -> { halted = true; -1 } // HLT
            opcode == 0x02 -> { // RET
                if (callSP <= 0) { onError("RET with empty call stack", pc); halted = true; -1 }
                else { callSP--; callStack[callSP] }
            }

            // ── Single-register instructions (4 per op, base + regIndex) ────
            opcode in 0x04..0x07 -> { // INC rX
                val r = opcode - 0x04
                val res = reg[r] + 1
                flagC = res > 0xFF
                reg[r] = res and 0xFF
                setArithFlags(reg[r])
                pc + 1
            }
            opcode in 0x08..0x0B -> { // DEC rX
                val r = opcode - 0x08
                val res = reg[r] - 1
                flagC = res < 0
                reg[r] = res and 0xFF
                setArithFlags(reg[r])
                pc + 1
            }
            opcode in 0x0C..0x0F -> { // NOT rX
                val r = opcode - 0x0C
                reg[r] = reg[r].inv() and 0xFF
                setArithFlags(reg[r])
                pc + 1
            }
            opcode in 0x10..0x13 -> { // NEG rX
                val r = opcode - 0x10
                val res = (0x100 - reg[r]) and 0xFF
                flagC = reg[r] != 0
                reg[r] = res
                setArithFlags(reg[r])
                pc + 1
            }
            opcode in 0x14..0x17 -> { // CLR rX
                val r = opcode - 0x14
                reg[r] = 0; flagZ = true; flagS = false
                pc + 1
            }
            opcode in 0x18..0x1B -> { // OUT rX
                val r = opcode - 0x18
                onOutput(reg[r])
                pc + 1
            }
            opcode in 0x1C..0x1F -> { // RNG rX
                val r = opcode - 0x1C
                reg[r] = rng() and 0xFF
                setArithFlags(reg[r])
                pc + 1
            }

            // ── Two-register instructions (OP_BASE + (dst<<2) + src) ─────────
            opcode in 0x20..0x2F -> { // MOV rD, rS
                val d = (opcode - 0x20) shr 2; val s = (opcode - 0x20) and 3
                reg[d] = reg[s]
                pc + 1
            }
            opcode in 0x30..0x3F -> { // ADD rD, rS
                val d = (opcode - 0x30) shr 2; val s = (opcode - 0x30) and 3
                val res = reg[d] + reg[s]
                setAddFlags(reg[d], reg[s], res)
                reg[d] = res and 0xFF
                pc + 1
            }
            opcode in 0x40..0x4F -> { // SUB rD, rS
                val d = (opcode - 0x40) shr 2; val s = (opcode - 0x40) and 3
                val res = reg[d] - reg[s]
                setSubFlags(reg[d], reg[s], res)
                reg[d] = res and 0xFF
                pc + 1
            }
            opcode in 0x50..0x5F -> { // MUL rD, rS
                val d = (opcode - 0x50) shr 2; val s = (opcode - 0x50) and 3
                val res = reg[d] * reg[s]
                flagC = res > 0xFF
                reg[d] = res and 0xFF
                setArithFlags(reg[d])
                pc + 1
            }
            opcode in 0x60..0x6F -> { // AND rD, rS
                val d = (opcode - 0x60) shr 2; val s = (opcode - 0x60) and 3
                reg[d] = reg[d] and reg[s]
                setArithFlags(reg[d])
                pc + 1
            }
            opcode in 0x70..0x7F -> { // OR rD, rS
                val d = (opcode - 0x70) shr 2; val s = (opcode - 0x70) and 3
                reg[d] = reg[d] or reg[s]
                setArithFlags(reg[d])
                pc + 1
            }
            opcode in 0x80..0x8F -> { // XOR rD, rS
                val d = (opcode - 0x80) shr 2; val s = (opcode - 0x80) and 3
                reg[d] = reg[d] xor reg[s]
                setArithFlags(reg[d])
                pc + 1
            }
            opcode in 0x90..0x9F -> { // CMP rD, rS  (flags only, no reg change)
                val d = (opcode - 0x90) shr 2; val s = (opcode - 0x90) and 3
                val res = reg[d] - reg[s]
                setSubFlags(reg[d], reg[s], res)
                pc + 1
            }
            opcode in 0xA0..0xAF -> { // LDR rD, (rS)  rD = RAM[rS]
                val d = (opcode - 0xA0) shr 2; val s = (opcode - 0xA0) and 3
                reg[d] = ramRead(reg[s])
                setArithFlags(reg[d])
                pc + 1
            }
            opcode in 0xB0..0xBF -> { // STR (rD), rS  RAM[rD] = rS
                val d = (opcode - 0xB0) shr 2; val s = (opcode - 0xB0) and 3
                ramWrite(reg[d], reg[s])
                pc + 1
            }

            // ── Reg + immediate/address (2-byte instructions) ─────────────────
            opcode in 0xC0..0xC3 -> { // LDI rX, imm
                val r = opcode - 0xC0
                val imm = readByte(prog, pc + 1)
                reg[r] = imm
                setArithFlags(reg[r])
                pc + 2
            }
            opcode in 0xC4..0xC7 -> { // LDA rX, addr
                val r = opcode - 0xC4
                val addr = readByte(prog, pc + 1)
                reg[r] = ramRead(addr)
                setArithFlags(reg[r])
                pc + 2
            }
            opcode in 0xC8..0xCB -> { // STA rX, addr
                val r = opcode - 0xC8
                val addr = readByte(prog, pc + 1)
                ramWrite(addr, reg[r])
                pc + 2
            }
            opcode in 0xCC..0xCF -> { // ADDI rX, imm (signed)
                val r = opcode - 0xCC
                val imm = readByte(prog, pc + 1).let { if (it > 127) it - 256 else it }
                val res = reg[r] + imm
                setAddFlags(reg[r], imm and 0xFF, res)
                reg[r] = res and 0xFF
                pc + 2
            }
            opcode in 0xD0..0xD3 -> { // LDRR rX, rel  rX = RAM[PC + rel]
                val r = opcode - 0xD0
                val rel = readByte(prog, pc + 1).let { if (it > 127) it - 256 else it }
                reg[r] = ramRead(pc + rel)
                setArithFlags(reg[r])
                pc + 2
            }
            opcode in 0xD4..0xD7 -> { // STRR rX, rel  RAM[PC + rel] = rX
                val r = opcode - 0xD4
                val rel = readByte(prog, pc + 1).let { if (it > 127) it - 256 else it }
                ramWrite(pc + rel, reg[r])
                pc + 2
            }
            opcode in 0xD8..0xDB -> { // SHL rX, imm
                val r = opcode - 0xD8
                val imm = readByte(prog, pc + 1) and 7
                val res = reg[r] shl imm
                flagC = (res and 0xFF00) != 0
                reg[r] = res and 0xFF
                setArithFlags(reg[r])
                pc + 2
            }
            opcode in 0xDC..0xDF -> { // SHR rX, imm
                val r = opcode - 0xDC
                val imm = readByte(prog, pc + 1) and 7
                reg[r] = (reg[r] ushr imm) and 0xFF
                setArithFlags(reg[r])
                pc + 2
            }

            // ── Branch instructions (2-byte: opcode + signed offset) ──────────
            opcode == 0xE0 -> { // JMP rel
                val rel = readByte(prog, pc + 1).let { if (it > 127) it - 256 else it }
                pc + 2 + rel
            }
            opcode == 0xE1 -> { // JZ rel
                val rel = readByte(prog, pc + 1).let { if (it > 127) it - 256 else it }
                if (flagZ) pc + 2 + rel else pc + 2
            }
            opcode == 0xE2 -> { // JNZ rel
                val rel = readByte(prog, pc + 1).let { if (it > 127) it - 256 else it }
                if (!flagZ) pc + 2 + rel else pc + 2
            }
            opcode == 0xE3 -> { // JGT rel  (> 0: not zero and not negative)
                val rel = readByte(prog, pc + 1).let { if (it > 127) it - 256 else it }
                if (!flagZ && !flagS) pc + 2 + rel else pc + 2
            }
            opcode == 0xE4 -> { // JLT rel  (< 0: negative)
                val rel = readByte(prog, pc + 1).let { if (it > 127) it - 256 else it }
                if (flagS) pc + 2 + rel else pc + 2
            }
            opcode == 0xE5 -> { // JGE rel  (>= 0: not negative)
                val rel = readByte(prog, pc + 1).let { if (it > 127) it - 256 else it }
                if (!flagS) pc + 2 + rel else pc + 2
            }
            opcode == 0xE6 -> { // JLE rel  (<= 0: zero or negative)
                val rel = readByte(prog, pc + 1).let { if (it > 127) it - 256 else it }
                if (flagZ || flagS) pc + 2 + rel else pc + 2
            }
            opcode == 0xE7 -> { // JC rel
                val rel = readByte(prog, pc + 1).let { if (it > 127) it - 256 else it }
                if (flagC) pc + 2 + rel else pc + 2
            }
            opcode == 0xE8 -> { // JNC rel
                val rel = readByte(prog, pc + 1).let { if (it > 127) it - 256 else it }
                if (!flagC) pc + 2 + rel else pc + 2
            }
            opcode == 0xE9 -> { // CALL rel
                val rel = readByte(prog, pc + 1).let { if (it > 127) it - 256 else it }
                if (callSP >= STACK_DEPTH) {
                    onError("Call stack overflow", pc); halted = true; -1
                } else {
                    callStack[callSP++] = pc + 2
                    pc + 2 + rel
                }
            }
            opcode == 0xEA -> { // JMPA addr (absolute)
                val addr = readByte(prog, pc + 1)
                addr
            }
            opcode == 0xEB -> { // CALLA addr (call absolute)
                val addr = readByte(prog, pc + 1)
                if (callSP >= STACK_DEPTH) {
                    onError("Call stack overflow", pc); halted = true; -1
                } else {
                    callStack[callSP++] = pc + 2
                    addr
                }
            }

            // ── DIV (two-reg, 0xEC–0xFF) ──────────────────────────────────────
            opcode in 0xEC..0xFF -> { // DIV rD, rS  rD = rD / rS (rS==0 → rD=0xFF)
                val d = (opcode - 0xEC) shr 2; val s = (opcode - 0xEC) and 3
                reg[d] = if (reg[s] == 0) 0xFF else (reg[d] / reg[s]) and 0xFF
                setArithFlags(reg[d])
                pc + 1
            }

            else -> { onError("Unknown opcode 0x${opcode.toString(16)}", pc); halted = true; -1 }
        }
    }

    // ── State inspection ──────────────────────────────────────────────────────

    fun stateString(): String {
        val flags = buildString {
            if (flagZ) append('Z') else append('.')
            if (flagC) append('C') else append('.')
            if (flagS) append('S') else append('.')
            if (flagO) append('O') else append('.')
        }
        val regs = reg.mapIndexed { i, v -> "r$i=${v.toString(16).padStart(2, '0')}" }.joinToString(" ")
        val ramStr = ram.mapIndexed { i, v -> v.toString(16).padStart(2, '0') }.joinToString(" ")
        return "PC=${pc.toString(16).padStart(2, '0')} flags=$flags  $regs\nRAM: $ramStr"
    }
}

/**
 * Opcode constants for the Nano VM.
 * These mirror the ranges used in [NanoVM.step].
 */
object Opcode {
    const val NOP  = 0x00
    const val HLT  = 0x01
    const val RET  = 0x02

    const val INC_BASE  = 0x04
    const val DEC_BASE  = 0x08
    const val NOT_BASE  = 0x0C
    const val NEG_BASE  = 0x10
    const val CLR_BASE  = 0x14
    const val OUT_BASE  = 0x18
    const val RNG_BASE  = 0x1C

    const val MOV_BASE  = 0x20
    const val ADD_BASE  = 0x30
    const val SUB_BASE  = 0x40
    const val MUL_BASE  = 0x50
    const val AND_BASE  = 0x60
    const val OR_BASE   = 0x70
    const val XOR_BASE  = 0x80
    const val CMP_BASE  = 0x90
    const val LDR_BASE  = 0xA0  // rD = RAM[rS]
    const val STR_BASE  = 0xB0  // RAM[rD] = rS

    const val LDI_BASE  = 0xC0
    const val LDA_BASE  = 0xC4
    const val STA_BASE  = 0xC8
    const val ADDI_BASE = 0xCC
    const val LDRR_BASE = 0xD0
    const val STRR_BASE = 0xD4
    const val SHL_BASE  = 0xD8
    const val SHR_BASE  = 0xDC

    const val JMP  = 0xE0
    const val JZ   = 0xE1
    const val JNZ  = 0xE2
    const val JGT  = 0xE3
    const val JLT  = 0xE4
    const val JGE  = 0xE5
    const val JLE  = 0xE6
    const val JC   = 0xE7
    const val JNC  = 0xE8
    const val CALL = 0xE9
    const val JMPA = 0xEA
    const val CALLA= 0xEB

    const val DIV_BASE  = 0xEC

    /** Encode a two-register opcode: op_base + (dst << 2) + src */
    fun twoReg(base: Int, dst: Int, src: Int) = base + (dst shl 2) + src

    /** Encode a single-register opcode: op_base + regIndex */
    fun oneReg(base: Int, reg: Int) = base + reg
}
