package afield.nano

import afield.*
import afield.logger.lprintln
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.stdlib.Delegation
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.yield

/**
 * Field4 [BoxExecution] implementation for the **Nano** micro-computer language.
 *
 * Nano is a compact 8-bit virtual machine with 4 registers (r0–r3), 32 bytes of
 * RAM (address wrap), a small call stack, and an assembly language designed for
 * emergence and artificial-life grid simulations.
 *
 * Feature highlights inside Field4:
 *  - **executeFragment / executeAll**: assemble and run Nano code, display register state
 *    and any `out rX` output in the box's output area.
 *  - **launchAll**: run the VM in a cooperative per-tick loop (for animation / ALife step).
 *  - **complete**: offer mnemonic, register, and label completion.
 *  - **disassemble**: Box extension function to disassemble raw bytes.
 *
 * To enable Nano for a box hierarchy, set the parent box's `isNanoRoot` property to `true`.
 * Child boxes then become Nano boxes (green border) whose `code` property is Nano assembly.
 *
 * Quick example:
 * ```nano
 * ; Fibonacci up to ~250
 * ldi r0, 0
 * ldi r1, 1
 * loop:
 *     out r0          ; print current value
 *     mov r2, r1
 *     add r1, r0
 *     mov r0, r2
 *     cmp r1, r0      ; compare to detect overflow
 *     jlt  loop       ; loop while r1 < 255
 * hlt
 * ```
 */
class NanoExecution(val inside: Box) : BoxExecution {

    private val assembler = NanoAssembler()
    private val disassembler = NanoDisassembler()
    private val vm = NanoVM()

    // ── Output wiring ─────────────────────────────────────────────────────────

    private fun configureOutput(
        inside: Box,
        executeLine: Int,
        reply: (Message) -> Unit,
        originSession: WebSocketSession?
    ) {
        configureOutputProxies(inside, reply, originSession)
        vm.onOutput = { value ->
            inside.output.proxy(value.toString(), true, executeLine)
        }
        vm.onError = { msg, pcVal ->
            inside.error(msg, line = executeLine, property = "code")
            lprintln("error,nano", "Nano VM error at PC=0x${pcVal.toString(16)}: $msg")
        }
    }

    private fun clearErrorStatus(reply: (Message) -> Unit, originSession: WebSocketSession?) {
        dispatchExecutionOutput(
            inside,
            reply,
            Output(
                uid = inside.id,
                line = -1,
                kind = "err",
                property = "code",
                html = "",
                frame = Runloop.runLoop.frame.toInt()
            ),
            originSession = originSession
        )
    }

    // ── BoxExecution ──────────────────────────────────────────────────────────

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        inMain {
            val wm = ++inside.fragmentGeneration
            send(Message("is-running").payload(IsRunning(execute.uid, true, wm, "__fragment__")))
            configureOutput(inside, execute.line, reply, originSession)
            try {
                runNano(execute.code, execute.line, reply, originSession, maxSteps = 100_000)
            } finally {
                send(Message("is-running").payload(IsRunning(execute.uid, false, wm, "__fragment__")))
            }
        }
    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        executeFragment(inside, execute, reply)
    }

    override fun launchAll(
        inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit
    ) {
        val originSession = thisSession.get()
        inMain {
            val wm = ++inside.fragmentGeneration

            configureOutput(inside, execute.line, reply, originSession)

            // Assemble once
            val result = assembler.assemble(code)
            if (result is NanoAssembler.AssemblyResult.Err) {
                result.errors.forEach { e ->
                    inside.error(e.message, line = execute.line + e.line - 1, property = "code")
                }
                send(Message("is-running").payload(IsRunning(execute.uid, false, wm, strand)))
                return@inMain
            }

            val program = (result as NanoAssembler.AssemblyResult.Ok).bytes
            clearErrorStatus(reply, originSession)

            val generation = inside.runGeneration.getOrPut(strand) { -1 } + 1
            inside.runGeneration[strand] = generation
            send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand)))

            // Initial full load (sets up RAM with any embedded .data bytes from the program).
            vm.load(program)

            inMain {
                try {
                    while (inside.runGeneration[strand] == generation) {
                        // Each tick: reset registers/flags/PC only, preserving RAM.
                        // This means RAM acts as persistent working memory across ticks —
                        // crucial for ALife agents that store state between steps.
                        vm.resetRegisters()
                        vm.run(program)
                        yield()
                    }
                } finally {
                    send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                }
            }
        }
    }

    override fun stop(inside: Box) {
        vm.halted = true
        inside.runGeneration.keys.toList().forEach { strand ->
            inside.runGeneration[strand] = (inside.runGeneration[strand] ?: 0) + 1
        }
    }

    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {
        val code = completionRequest.allCode
        val cursor = completionRequest.position.coerceIn(0, code.length)

        // Find the token being typed
        var start = cursor
        while (start > 0 && !code[start - 1].isWhitespace() && code[start - 1] != ',') start--
        val prefix = code.substring(start, cursor).lowercase()

        val completions = mutableListOf<Metadata>()

        // Mnemonics
        for ((mnemonic, info) in MNEMONIC_HELP) {
            if (mnemonic.startsWith(prefix)) {
                completions += Metadata(
                    icon = "keyword",
                    tail = "nano",
                    documentation = info,
                    text = mnemonic,
                    displayText = mnemonic
                )
            }
        }

        // Registers
        for (r in listOf("r0", "r1", "r2", "r3")) {
            if (r.startsWith(prefix)) {
                completions += Metadata(
                    icon = "variable",
                    tail = "reg",
                    documentation = "Nano 8-bit register $r (0–255)",
                    text = r,
                    displayText = r
                )
            }
        }

        // Labels from current code
        if (prefix.isNotEmpty() && prefix[0].isLetter()) {
            for (label in assembler.extractLabels(code)) {
                if (label.lowercase().startsWith(prefix)) {
                    completions += Metadata(
                        icon = "symbol",
                        tail = "label",
                        documentation = "Label '$label' defined in this program",
                        text = label,
                        displayText = label
                    )
                }
            }
        }

        if (completions.isNotEmpty()) {
            reply(
                Message("complete").payload(
                    Completion(
                        boundsStart = start,
                        boundsEnd = cursor,
                        metadata = completions
                    )
                )
            )
        }
    }

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        // For Nano, find the label and run the program up to that label
        val exec = Execute(
            uid = executeSymbol.uid,
            type = "fragment",
            line = executeSymbol.line,
            code = executeSymbol.symbol,
            singleLine = true
        )
        executeFragment(inside, exec, reply)
    }

    override val languageName: String get() = "nano"

    // ── Internal: assemble + run ──────────────────────────────────────────────

    private fun runNano(
        code: String,
        executeLine: Int,
        reply: (Message) -> Unit,
        originSession: WebSocketSession?,
        maxSteps: Int = 100_000
    ) {
        val result = assembler.assemble(code)
        when (result) {
            is NanoAssembler.AssemblyResult.Err -> {
                result.errors.forEach { e ->
                    inside.error(e.message, line = executeLine + e.line - 1, property = "code")
                }
            }
            is NanoAssembler.AssemblyResult.Ok -> {
                clearErrorStatus(reply, originSession)
                val program = result.bytes
                val freshVm = NanoVM(maxSteps = maxSteps)
                freshVm.onOutput = vm.onOutput
                freshVm.onError = vm.onError
                freshVm.load(program)
                val steps = freshVm.run(program)

                val outputs = mutableListOf<String>()
                // Show final register state
                val regs = freshVm.reg.mapIndexed { i, v -> "r$i=0x${v.toString(16).padStart(2, '0')}($v)" }
                outputs += "  ${regs.joinToString("  ")}  [steps=$steps]"

                val html = "<pre style='margin:0;font-size:11px;color:#aaa'>${outputs.joinToString("\n")}</pre>"
                dispatchExecutionOutput(
                    inside,
                    reply,
                    Output(
                        uid = inside.id,
                        line = executeLine + code.lines().size,
                        kind = "ok",
                        html = html,
                        frame = Runloop.runLoop.frame.toInt()
                    ),
                    originSession = originSession
                )
            }
        }
    }
}

// ── Box property for root detection ──────────────────────────────────────────

var Box.isNanoRoot by Delegation<Boolean?>()

@RunsAt(early)
fun installNano() {
    Box.ioPersistentProperties.add("isNanoRoot")
}

@RunsAt(plugins)
fun installNanoPlugin() {
    TopologyWatcher("nano") { nanoTopologyNeedsUpdating(it) }.start()
}

fun nanoTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isNanoRoot },
        runtimeClass = NanoExecution::class,
        newExecution = { child -> NanoExecution(child) },
        language = "Nano",
        customization = """this.style.border = "1px dashed #1a6b3a"; this.style.margin = "-5px" """,
        notifyCategory = "nano"
    )
}

// ── Disassembler helper accessible from Kotlin live code ─────────────────────

/**
 * Disassemble Nano bytecode [bytes] and display the result in this box's output area.
 *
 * ```kotlin
 * val prog = nanoAssemble("""
 *     ldi r0, 10
 *     loop:
 *         dec r0
 *         jnz loop
 *     hlt
 * """)
 * box.nanoDisassemble(prog)
 * ```
 */
fun Box.nanoDisassemble(bytes: ByteArray) {
    val text = NanoDisassembler().disassembleToString(bytes)
    output.display("disasm", "<pre style='font-family:monospace;font-size:12px'>$text</pre>")
}

/**
 * Assemble Nano source and return the bytecode, or throw [NanoAssemblyException] on error.
 * Convenient from Kotlin live-coding boxes:
 * ```kotlin
 * val prog = nanoAssemble("ldi r0, 42\nhlt")
 * ```
 */
fun nanoAssemble(source: String): ByteArray {
    val result = NanoAssembler().assemble(source)
    return when (result) {
        is NanoAssembler.AssemblyResult.Ok -> result.bytes
        is NanoAssembler.AssemblyResult.Err ->
            throw NanoAssemblyException(result.errors.joinToString("\n") { "line ${it.line}: ${it.message}" })
    }
}

class NanoAssemblyException(message: String) : RuntimeException(message)

// ── Completion documentation ──────────────────────────────────────────────────

private val MNEMONIC_HELP: Map<String, String> = mapOf(
    "nop"   to "No operation",
    "hlt"   to "Halt the VM",
    "ret"   to "Return from CALL (pop return address)",
    "inc"   to "inc rX  — increment register rX (wraps mod 256)",
    "dec"   to "dec rX  — decrement register rX (wraps mod 256)",
    "not"   to "not rX  — bitwise NOT of rX",
    "neg"   to "neg rX  — two's-complement negate of rX",
    "clr"   to "clr rX  — clear register rX (set to 0)",
    "out"   to "out rX  — print value of rX as decimal",
    "rng"   to "rng rX  — fill rX with a pseudorandom byte (0–255)",
    "mov"   to "mov rD, rS  — rD = rS",
    "add"   to "add rD, rS  — rD = rD + rS  (wraps mod 256; sets Z/C/S)",
    "sub"   to "sub rD, rS  — rD = rD - rS  (wraps; sets Z/C/S)",
    "mul"   to "mul rD, rS  — rD = (rD * rS) & 0xFF  (sets Z/C)",
    "and"   to "and rD, rS  — rD = rD & rS  (bitwise AND)",
    "or"    to "or  rD, rS  — rD = rD | rS  (bitwise OR)",
    "xor"   to "xor rD, rS  — rD = rD ^ rS  (bitwise XOR)",
    "cmp"   to "cmp rD, rS  — set flags from (rD - rS); registers unchanged",
    "ldr"   to "ldr rD, rS  — rD = RAM[rS]  (load indirect)",
    "str"   to "str rD, rS  — RAM[rD] = rS  (store indirect)",
    "div"   to "div rD, rS  — rD = rD / rS  (integer; rS=0 → rD=0xFF)",
    "ldi"   to "ldi rX, N   — rX = N  (load immediate byte, 0–255)",
    "lda"   to "lda rX, A   — rX = RAM[A]  (load from absolute address 0–31)",
    "sta"   to "sta rX, A   — RAM[A] = rX  (store to absolute address 0–31)",
    "addi"  to "addi rX, N  — rX += N  (signed immediate, -128..127)",
    "ldrr"  to "ldrr rX, N  — rX = RAM[PC + N]  (PC-relative load, N signed)",
    "strr"  to "strr rX, N  — RAM[PC + N] = rX  (PC-relative store, N signed)",
    "shl"   to "shl rX, N   — rX <<= N  (logical shift left)",
    "shr"   to "shr rX, N   — rX >>= N  (logical shift right)",
    "jmp"   to "jmp label   — unconditional relative jump",
    "jz"    to "jz  label   — jump if Zero flag (last result == 0)",
    "jnz"   to "jnz label   — jump if not Zero",
    "jgt"   to "jgt label   — jump if > 0 (not zero and not negative)",
    "jlt"   to "jlt label   — jump if < 0 (negative / sign flag set)",
    "jge"   to "jge label   — jump if >= 0 (not negative)",
    "jle"   to "jle label   — jump if <= 0 (zero or negative)",
    "jc"    to "jc  label   — jump if Carry flag set",
    "jnc"   to "jnc label   — jump if Carry flag clear",
    "call"  to "call label  — relative call (push return address, jump)",
    "jmpa"  to "jmpa label  — absolute jump (0–255)",
    "calla" to "calla label — absolute call (push return address, absolute jump)",
    ".data" to ".data b0 b1 …  — emit raw bytes into the program stream"
)
