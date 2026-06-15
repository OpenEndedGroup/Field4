package afield.nano

/**
 * Nano assembler: converts Nano assembly source text into a [ByteArray] bytecode program.
 *
 * ## Syntax
 *
 * ```
 * ; line comment (rest of line)
 * label:            ; defines a label at the current address
 *
 * ; Data directive – place raw bytes into the program stream
 * .data 0x1A 0x2B 42 ...
 *
 * ; Instructions
 * nop
 * hlt
 * ret
 * inc r0
 * dec r1
 * not r2
 * neg r3
 * clr r0
 * out r1           ; print reg value
 * rng r2           ; fill with random byte
 *
 * mov r0, r1       ; r0 = r1
 * add r0, r1       ; r0 += r1
 * sub r0, r1
 * mul r0, r1
 * and r0, r1
 * or  r0, r1
 * xor r0, r1
 * cmp r0, r1       ; flags = r0 <=> r1  (no reg change)
 * ldr r0, r1       ; r0 = RAM[r1]  (load indirect)
 * str r0, r1       ; RAM[r0] = r1  (store indirect)
 * div r0, r1       ; r0 = r0 / r1  (integer; r1==0 → r0=0xFF)
 *
 * ldi r0, 42       ; r0 = 42   (immediate, 0–255)
 * ldi r0, 0xFF
 * lda r0, 5        ; r0 = RAM[5]  (absolute load)
 * sta r0, 5        ; RAM[5] = r0  (absolute store)
 * addi r0, -3      ; r0 += -3     (signed immediate, -128..127)
 * ldrr r0, -2      ; r0 = RAM[PC + (-2)]  (PC-relative load)
 * strr r0, 4       ; RAM[PC + 4] = r0     (PC-relative store)
 * shl r0, 2        ; r0 <<= 2
 * shr r0, 1        ; r0 >>= 1
 *
 * jmp  target      ; unconditional (relative, or label)
 * jz   target
 * jnz  target
 * jgt  target
 * jlt  target
 * jge  target
 * jle  target
 * jc   target
 * jnc  target
 * call target      ; relative call
 * jmpa target      ; absolute jump (0–255)
 * calla target     ; absolute call
 * ```
 *
 * Labels can appear on their own line (`start:`) or before a mnemonic on the same line
 * (`start: ldi r0, 0`).  Labels used in branch instructions are resolved as the
 * **signed 8-bit relative offset from the byte following the branch** (i.e. PC+2 for 2-byte
 * instructions like `jmp`), capped to −128..+127.  For [JMPA] and [CALLA], labels resolve
 * to the absolute address (0–255).
 *
 * Returns [AssemblyResult] which contains either the bytecode or a list of [AssemblyError]s.
 */
class NanoAssembler {

    data class AssemblyError(val line: Int, val message: String)

    sealed class AssemblyResult {
        data class Ok(val bytes: ByteArray) : AssemblyResult()
        data class Err(val errors: List<AssemblyError>) : AssemblyResult()
    }

    data class AssemblyDebugInfo(
        val bytes: ByteArray,
        val pcToLine: IntArray
    )

    sealed class AssemblyDebugResult {
        data class Ok(val info: AssemblyDebugInfo) : AssemblyDebugResult()
        data class Err(val errors: List<AssemblyError>) : AssemblyDebugResult()
    }

    fun assemble(source: String): AssemblyResult {
        return when (val result = assembleInternal(source, captureSourceMap = false)) {
            is InternalAssemblyResult.Ok -> AssemblyResult.Ok(result.bytes)
            is InternalAssemblyResult.Err -> AssemblyResult.Err(result.errors)
        }
    }

    fun assembleWithSourceMap(source: String): AssemblyDebugResult {
        return when (val result = assembleInternal(source, captureSourceMap = true)) {
            is InternalAssemblyResult.Ok ->
                AssemblyDebugResult.Ok(
                    AssemblyDebugInfo(
                        bytes = result.bytes,
                        pcToLine = result.pcToLine ?: IntArray(0)
                    )
                )
            is InternalAssemblyResult.Err -> AssemblyDebugResult.Err(result.errors)
        }
    }

    private sealed class InternalAssemblyResult {
        data class Ok(val bytes: ByteArray, val pcToLine: IntArray?) : InternalAssemblyResult()
        data class Err(val errors: List<AssemblyError>) : InternalAssemblyResult()
    }

    private fun assembleInternal(source: String, captureSourceMap: Boolean): InternalAssemblyResult {
        val errors = mutableListOf<AssemblyError>()
        val bytes = mutableListOf<Int>()          // output bytes (0–255)
        val pcToLine = if (captureSourceMap) mutableListOf<Int>() else null
        val labels = mutableMapOf<String, Int>()  // label name → address
        // Patches: list of (address-of-offset-byte, instruction-end-pc, label, lineNo, isAbsolute)
        data class Patch(val offsetAddr: Int, val instrEndPc: Int, val label: String, val lineNo: Int, val absolute: Boolean)
        val patches = mutableListOf<Patch>()

        val lines = source.lines()

        for ((lineIdx, rawLine) in lines.withIndex()) {
            val lineNo = lineIdx + 1
            // strip comment
            val noComment = rawLine.substringBefore(';').trim()
            if (noComment.isBlank()) continue

            // parse optional leading label "name:"
            var rest = noComment
            val labelMatch = Regex("""^([A-Za-z_]\w*)\s*:\s*""").find(rest)
            if (labelMatch != null) {
                val name = labelMatch.groupValues[1]
                if (labels.containsKey(name)) {
                    errors += AssemblyError(lineNo, "Duplicate label '$name'")
                } else {
                    labels[name] = bytes.size
                }
                rest = rest.removePrefix(labelMatch.value).trim()
                if (rest.isBlank()) continue
            }

            val tokens = tokenize(rest)
            if (tokens.isEmpty()) continue
            val mnemonic = tokens[0].lowercase()

            fun arg(i: Int): String? = tokens.getOrNull(i + 1)?.trimEnd(',')

            fun parseReg(s: String?, argName: String = "register"): Int {
                if (s == null) { errors += AssemblyError(lineNo, "Missing $argName"); return 0 }
                val clean = s.lowercase().trimEnd(',')
                return when (clean) {
                    "r0" -> 0; "r1" -> 1; "r2" -> 2; "r3" -> 3
                    else -> { errors += AssemblyError(lineNo, "Unknown register '$s'"); 0 }
                }
            }

            fun parseImm(s: String?, signed: Boolean = false, argName: String = "immediate"): Int {
                if (s == null) { errors += AssemblyError(lineNo, "Missing $argName"); return 0 }
                val n = parseNumber(s.trimEnd(','))
                if (n == null) { errors += AssemblyError(lineNo, "Bad number '$s'"); return 0 }
                if (signed) {
                    if (n < -128 || n > 127) errors += AssemblyError(lineNo, "Signed immediate out of range: $n")
                    return n and 0xFF
                } else {
                    if (n < 0 || n > 255) errors += AssemblyError(lineNo, "Immediate out of range: $n")
                    return n and 0xFF
                }
            }

            fun emitByte(b: Int) {
                bytes.add(b and 0xFF)
                pcToLine?.add(lineNo)
            }

            fun emitBranchRel(op: Int, labelOrNum: String?) {
                if (labelOrNum == null) { errors += AssemblyError(lineNo, "Missing branch target"); return }
                emitByte(op)
                val instrEnd = bytes.size + 1  // address after the offset byte
                val n = parseNumber(labelOrNum)
                if (n != null) {
                    // numeric literal: treat as absolute target, compute relative
                    val rel = n - instrEnd
                    if (rel < -128 || rel > 127) errors += AssemblyError(lineNo, "Branch offset out of range: $rel")
                    emitByte(rel and 0xFF)
                } else {
                    // label reference: patch later
                    patches += Patch(bytes.size, instrEnd, labelOrNum, lineNo, false)
                    emitByte(0) // placeholder
                }
            }

            fun emitAbsTarget(op: Int, labelOrNum: String?) {
                if (labelOrNum == null) { errors += AssemblyError(lineNo, "Missing target"); return }
                emitByte(op)
                val n = parseNumber(labelOrNum)
                if (n != null) {
                    if (n < 0 || n > 255) errors += AssemblyError(lineNo, "Absolute address out of range: $n")
                    emitByte(n and 0xFF)
                } else {
                    patches += Patch(bytes.size, bytes.size + 1, labelOrNum, lineNo, true)
                    emitByte(0)
                }
            }

            when (mnemonic) {
                "nop"  -> emitByte(Opcode.NOP)
                "hlt"  -> emitByte(Opcode.HLT)
                "ret"  -> emitByte(Opcode.RET)

                "inc"  -> emitByte(Opcode.oneReg(Opcode.INC_BASE, parseReg(arg(0))))
                "dec"  -> emitByte(Opcode.oneReg(Opcode.DEC_BASE, parseReg(arg(0))))
                "not"  -> emitByte(Opcode.oneReg(Opcode.NOT_BASE, parseReg(arg(0))))
                "neg"  -> emitByte(Opcode.oneReg(Opcode.NEG_BASE, parseReg(arg(0))))
                "clr"  -> emitByte(Opcode.oneReg(Opcode.CLR_BASE, parseReg(arg(0))))
                "out"  -> emitByte(Opcode.oneReg(Opcode.OUT_BASE, parseReg(arg(0))))
                "rng"  -> emitByte(Opcode.oneReg(Opcode.RNG_BASE, parseReg(arg(0))))

                "mov"  -> emitByte(Opcode.twoReg(Opcode.MOV_BASE, parseReg(arg(0)), parseReg(arg(1))))
                "add"  -> emitByte(Opcode.twoReg(Opcode.ADD_BASE, parseReg(arg(0)), parseReg(arg(1))))
                "sub"  -> emitByte(Opcode.twoReg(Opcode.SUB_BASE, parseReg(arg(0)), parseReg(arg(1))))
                "mul"  -> emitByte(Opcode.twoReg(Opcode.MUL_BASE, parseReg(arg(0)), parseReg(arg(1))))
                "and"  -> emitByte(Opcode.twoReg(Opcode.AND_BASE, parseReg(arg(0)), parseReg(arg(1))))
                "or"   -> emitByte(Opcode.twoReg(Opcode.OR_BASE,  parseReg(arg(0)), parseReg(arg(1))))
                "xor"  -> emitByte(Opcode.twoReg(Opcode.XOR_BASE, parseReg(arg(0)), parseReg(arg(1))))
                "cmp"  -> emitByte(Opcode.twoReg(Opcode.CMP_BASE, parseReg(arg(0)), parseReg(arg(1))))
                "ldr"  -> emitByte(Opcode.twoReg(Opcode.LDR_BASE, parseReg(arg(0)), parseReg(arg(1))))
                "str"  -> emitByte(Opcode.twoReg(Opcode.STR_BASE, parseReg(arg(0)), parseReg(arg(1))))
                "div"  -> emitByte(Opcode.twoReg(Opcode.DIV_BASE, parseReg(arg(0)), parseReg(arg(1))))

                "ldi"  -> {
                    val r = parseReg(arg(0))
                    val imm = parseImm(arg(1))
                    emitByte(Opcode.oneReg(Opcode.LDI_BASE, r)); emitByte(imm)
                }
                "lda"  -> {
                    val r = parseReg(arg(0))
                    val addr = parseImm(arg(1), argName = "address")
                    emitByte(Opcode.oneReg(Opcode.LDA_BASE, r)); emitByte(addr)
                }
                "sta"  -> {
                    val r = parseReg(arg(0))
                    val addr = parseImm(arg(1), argName = "address")
                    emitByte(Opcode.oneReg(Opcode.STA_BASE, r)); emitByte(addr)
                }
                "addi" -> {
                    val r = parseReg(arg(0))
                    val imm = parseImm(arg(1), signed = true)
                    emitByte(Opcode.oneReg(Opcode.ADDI_BASE, r)); emitByte(imm)
                }
                "ldrr" -> {
                    val r = parseReg(arg(0))
                    val rel = parseImm(arg(1), signed = true, argName = "relative offset")
                    emitByte(Opcode.oneReg(Opcode.LDRR_BASE, r)); emitByte(rel)
                }
                "strr" -> {
                    val r = parseReg(arg(0))
                    val rel = parseImm(arg(1), signed = true, argName = "relative offset")
                    emitByte(Opcode.oneReg(Opcode.STRR_BASE, r)); emitByte(rel)
                }
                "shl"  -> {
                    val r = parseReg(arg(0))
                    val imm = parseImm(arg(1), argName = "shift count")
                    emitByte(Opcode.oneReg(Opcode.SHL_BASE, r)); emitByte(imm)
                }
                "shr"  -> {
                    val r = parseReg(arg(0))
                    val imm = parseImm(arg(1), argName = "shift count")
                    emitByte(Opcode.oneReg(Opcode.SHR_BASE, r)); emitByte(imm)
                }

                "jmp"  -> emitBranchRel(Opcode.JMP,  arg(0))
                "jz"   -> emitBranchRel(Opcode.JZ,   arg(0))
                "jnz"  -> emitBranchRel(Opcode.JNZ,  arg(0))
                "jgt"  -> emitBranchRel(Opcode.JGT,  arg(0))
                "jlt"  -> emitBranchRel(Opcode.JLT,  arg(0))
                "jge"  -> emitBranchRel(Opcode.JGE,  arg(0))
                "jle"  -> emitBranchRel(Opcode.JLE,  arg(0))
                "jc"   -> emitBranchRel(Opcode.JC,   arg(0))
                "jnc"  -> emitBranchRel(Opcode.JNC,  arg(0))
                "call" -> emitBranchRel(Opcode.CALL,  arg(0))
                "jmpa" -> emitAbsTarget(Opcode.JMPA, arg(0))
                "calla"-> emitAbsTarget(Opcode.CALLA, arg(0))

                ".data" -> {
                    // emit raw bytes
                    for (i in 1 until tokens.size) {
                        val n = parseNumber(tokens[i])
                        if (n == null) {
                            errors += AssemblyError(lineNo, "Bad .data value '${tokens[i]}'")
                        } else {
                            emitByte(n and 0xFF)
                        }
                    }
                }

                else -> errors += AssemblyError(lineNo, "Unknown mnemonic '$mnemonic'")
            }
        }

        // Resolve label patches
        for (p in patches) {
            val target = labels[p.label]
            if (target == null) {
                errors += AssemblyError(p.lineNo, "Undefined label '${p.label}'")
                continue
            }
            if (p.absolute) {
                if (target > 255) errors += AssemblyError(p.lineNo, "Label '${p.label}' address too large for absolute branch")
                bytes[p.offsetAddr] = target and 0xFF
            } else {
                val rel = target - p.instrEndPc
                if (rel < -128 || rel > 127) {
                    errors += AssemblyError(p.lineNo, "Branch to '${p.label}' out of range (offset $rel)")
                }
                bytes[p.offsetAddr] = rel and 0xFF
            }
        }

        return if (errors.isEmpty()) {
            InternalAssemblyResult.Ok(
                bytes = ByteArray(bytes.size) { bytes[it].toByte() },
                pcToLine = pcToLine?.let { IntArray(it.size) { index -> it[index] } }
            )
        } else {
            InternalAssemblyResult.Err(errors)
        }
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private fun tokenize(s: String): List<String> {
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        for (c in s) {
            if (c == ',') { if (buf.isNotEmpty()) result += buf.toString().also { buf.clear() } }
            else if (c.isWhitespace()) { if (buf.isNotEmpty()) result += buf.toString().also { buf.clear() } }
            else buf.append(c)
        }
        if (buf.isNotEmpty()) result += buf.toString()
        return result
    }

    /** Parse an integer literal: decimal, 0x hex, or 0b binary.  Returns null on failure. */
    fun parseNumber(s: String): Int? {
        val clean = s.trim().lowercase()
        return try {
            when {
                clean.startsWith("0x") -> clean.removePrefix("0x").toInt(16)
                clean.startsWith("0b") -> clean.removePrefix("0b").toInt(2)
                clean.startsWith("-0x") -> -clean.removePrefix("-0x").toInt(16)
                clean.startsWith("-0b") -> -clean.removePrefix("-0b").toInt(2)
                else -> clean.toInt()
            }
        } catch (_: NumberFormatException) { null }
    }

    /**
     * Returns all label names defined in [source].
     * Useful for completion.
     */
    fun extractLabels(source: String): List<String> {
        val labels = mutableListOf<String>()
        for (rawLine in source.lines()) {
            val noComment = rawLine.substringBefore(';').trim()
            val m = Regex("""^([A-Za-z_]\w*)\s*:""").find(noComment)
            if (m != null) labels += m.groupValues[1]
        }
        return labels
    }
}
