package afield.nano

import afield.*
import afield.logger.lprintln
import afield.runtime.TopologyWatcher
import afield.runtime.MainLanguageRuntime
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.stdlib.Delegation
import afield.stdlib.Inheritance
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/**
 * Field4 language adapter for the Nano 8-bit assembly language.
 *
 * Nano is a compact virtual machine: 4 registers (r0–r3), 32 bytes of RAM with address
 * wrap, a small call stack, and an instruction set sized for emergence and artificial-life
 * grid experiments. [NanoExecution] is the [BoxExecution] implementation; setting a parent
 * box's `isNanoRoot` to `true` turns its children into Nano assembly boxes.
 *
 * [installNano] registers the runtime with Field's language dispatch; [installNanoPlugin]
 * starts the topology watcher that activates it when the box tree changes. [nanoAssemble]
 * and [Box.nanoDisassemble] are available to neighbouring Kotlin boxes that need to inspect
 * or construct the VM's byte representation directly.
 */

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
    private val vm = NanoVM()
    private val stepDecorationKey = "__nano_step_line__"
    private val stepDelayMs = 220L

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
        runOnce(inside, execute, "fragment", reply)
    }

    /** One assemble-and-run of the program, reported under [mode]. See DatalogExecution.runOnce. */
    private fun runOnce(inside: Box, execute: Execute, mode: String, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, mode, reply)
        inMain {
            val wm = ++inside.fragmentGeneration
            send(Message("is-running").payload(IsRunning(execute.uid, true, wm, "__fragment__")))
            clearStepDecoration()
            configureOutput(inside, execute.line, trace.reply, originSession)
            var ok = false
            try {
                runNano(execute.code, execute.line, trace.reply, originSession, maxSteps = 100_000)
                ok = true
            } finally {
                trace.finish(ok)
                send(Message("is-running").payload(IsRunning(execute.uid, false, wm, "__fragment__")))
            }
        }
    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        runOnce(inside, execute, "run", reply)
    }

    override fun launchAll(
        inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit
    ) {
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, "launch", reply)
        inMain {
            val wm = ++inside.fragmentGeneration

            configureOutputProxies(inside, trace.reply, originSession)
            val outputs = mutableListOf<Int>()
            var runtimeError: String? = null
            vm.onOutput = { value ->
                outputs += value and 0xFF
                inside.output.proxy(value.toString(), true, execute.line)
            }
            vm.onError = { msg, pcVal ->
                runtimeError = "Nano VM error at PC=0x${pcVal.toString(16).padStart(2, '0')}: $msg"
                inside.error(msg, line = execute.line, property = "code")
                lprintln("error,nano", runtimeError)
            }

            val result = assembler.assembleWithSourceMap(code)
            if (result is NanoAssembler.AssemblyDebugResult.Err) {
                clearStepDecoration()
                result.errors.forEach { e ->
                    inside.error(e.message, line = execute.line + e.line - 1, property = "code")
                }
                trace.finish(false, "the program did not assemble")
                send(Message("is-running").payload(IsRunning(execute.uid, false, wm, strand)))
                return@inMain
            }

            result as NanoAssembler.AssemblyDebugResult.Ok
            val assembly = result.info
            val program = assembly.bytes
            clearErrorStatus(reply, originSession)
            clearStepDecoration()

            val generation = inside.runGeneration.getOrPut(strand) { -1 } + 1
            inside.runGeneration[strand] = generation
            send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand)))

            val memoryBefore = loadProgramIntoVm(vm, program)
            inside.displayOpen("nano")

            var steps = 0
            var lastFocusedLine: Int? = null

            fun publishState(focusPc: Int, changedRegisters: Set<Int>, changedRam: Set<Int>) {
                val focusLine = assembly.pcToLine.getOrNull(focusPc)
                setStepDecoration(code, focusLine)
                if (focusLine != null && focusLine != lastFocusedLine) {
                    focusEditorLine(focusLine, originSession)
                    lastFocusedLine = focusLine
                }
                inside.nanoMemoryBefore = memoryBefore
                inside.nanoMemoryAfter = vm.ram.toList()
                send(
                    Message("set-output").payload(
                        Set_output(
                            inside.id,
                            "outputarea",
                            "nano",
                            vm.toNanoRunReport(
                                program = program,
                                source = code,
                                steps = steps,
                                outputs = outputs,
                                focusPc = focusPc,
                                focusLine = focusLine,
                                changedRegisters = changedRegisters,
                                changedRam = changedRam,
                                runtimeError = runtimeError
                            ).toHtml(
                                title = "Nano state",
                                includeSource = false,
                                includeDisassembly = true,
                                includeProgramBytes = true
                            )
                        )
                    )
                )
            }

            publishState(vm.pc, emptySet(), emptySet())

            // The program assembled, loaded and published its first state; the stepping loop below
            // is the run, not the setup. Same boundary Kotlin's launch receipt draws.
            trace.finish(runtimeError == null)

            inMain {
                try {
                    while (inside.runGeneration[strand] == generation && !vm.halted) {
                        delay(configuredStepDelayMs())
                        if (inside.runGeneration[strand] != generation || vm.halted) break

                        val previousRegisters = vm.reg.copyOf()
                        val previousRam = vm.ram.copyOf()
                        val stepResult = vm.stepOnce(program)
                        if (!stepResult.executed) break

                        steps++
                        publishState(
                            focusPc = stepResult.executedPc,
                            changedRegisters = diffChangedIndices(previousRegisters, vm.reg),
                            changedRam = diffChangedIndices(previousRam, vm.ram)
                        )
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
        val result = assembler.assembleWithSourceMap(code)
        when (result) {
            is NanoAssembler.AssemblyDebugResult.Err -> {
                result.errors.forEach { e ->
                    inside.error(e.message, line = executeLine + e.line - 1, property = "code")
                }
            }
            is NanoAssembler.AssemblyDebugResult.Ok -> {
                clearErrorStatus(reply, originSession)
                val assembly = result.info
                val program = assembly.bytes
                val freshVm = NanoVM(maxSteps = maxSteps)
                val outputs = mutableListOf<Int>()
                var runtimeError: String? = null
                freshVm.onOutput = { value ->
                    outputs += value and 0xFF
                    vm.onOutput(value)
                }
                freshVm.onError = { msg, pc ->
                    runtimeError = "Nano VM error at PC=0x${pc.toString(16).padStart(2, '0')}: $msg"
                    vm.onError(msg, pc)
                }
                val memoryBefore = loadProgramIntoVm(freshVm, program)
                val steps = freshVm.run(program)
                inside.nanoMemoryBefore = memoryBefore
                inside.nanoMemoryAfter = freshVm.ram.toList()
                val html = freshVm
                    .toNanoRunReport(
                        program = program,
                        source = code,
                        steps = steps,
                        outputs = outputs,
                        focusPc = freshVm.pc,
                        focusLine = assembly.pcToLine.getOrNull(freshVm.pc),
                        runtimeError = runtimeError
                    )
                    .toHtml(
                        title = "Nano state",
                        includeSource = false,
                        includeDisassembly = false,
                        includeProgramBytes = false
                    )
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

    private fun diffChangedIndices(previous: IntArray, current: IntArray): Set<Int> {
        return previous.indices.filterTo(linkedSetOf()) { index -> previous[index] != current[index] }
    }

    private fun configuredStepDelayMs(): Long {
        val raw = runCatching { inside.nanoLaunchDelayMs }.getOrNull()
        return raw?.toLong()?.coerceAtLeast(0L) ?: stepDelayMs
    }

    private fun loadProgramIntoVm(machine: NanoVM, program: ByteArray): List<Int> {
        machine.load(program)
        applyConfiguredMemory(machine.ram, runCatching { inside.nanoMemoryInit }.getOrNull())
        return machine.ram.toList()
    }

    private fun applyConfiguredMemory(memory: IntArray, spec: Any?) {
        when (spec) {
            null -> return
            is ByteArray -> spec.forEachIndexed { index, value -> writeMemoryValue(memory, index, value.toInt()) }
            is ShortArray -> spec.forEachIndexed { index, value -> writeMemoryValue(memory, index, value.toInt()) }
            is IntArray -> spec.forEachIndexed { index, value -> writeMemoryValue(memory, index, value) }
            is LongArray -> spec.forEachIndexed { index, value -> writeMemoryValue(memory, index, value.toInt()) }
            is FloatArray -> spec.forEachIndexed { index, value -> writeMemoryValue(memory, index, value.toInt()) }
            is DoubleArray -> spec.forEachIndexed { index, value -> writeMemoryValue(memory, index, value.toInt()) }
            is Array<*> -> spec.forEachIndexed { index, value -> value.asNanoByteOrNull()?.let { writeMemoryValue(memory, index, it) } }
            is Iterable<*> -> spec.forEachIndexed { index, value -> value.asNanoByteOrNull()?.let { writeMemoryValue(memory, index, it) } }
            is Map<*, *> -> spec.forEach { (address, value) ->
                val addr = address.asNanoIndexOrNull() ?: return@forEach
                val byte = value.asNanoByteOrNull() ?: return@forEach
                writeMemoryValue(memory, addr, byte)
            }
            else -> lprintln("warning,nano", "Ignoring unsupported nanoMemoryInit value of type ${spec::class.simpleName}")
        }
    }

    private fun writeMemoryValue(memory: IntArray, index: Int, value: Int) {
        if (memory.isEmpty()) return
        memory[index and (memory.size - 1)] = value and 0xFF
    }

    private fun Any?.asNanoIndexOrNull(): Int? {
        return when (this) {
            is Number -> this.toInt()
            is String -> this.trim().toIntOrNull()
            else -> null
        }
    }

    private fun Any?.asNanoByteOrNull(): Int? {
        return when (this) {
            is Number -> this.toInt() and 0xFF
            is String -> parseNanoStringNumber(this)
            else -> null
        }
    }

    private fun parseNanoStringNumber(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val parsed = when {
            trimmed.startsWith("-0x", ignoreCase = true) ->
                trimmed.substring(3).toIntOrNull(16)?.let { -it }
            trimmed.startsWith("0x", ignoreCase = true) ->
                trimmed.substring(2).toIntOrNull(16)
            else -> trimmed.toIntOrNull()
        } ?: return null
        return parsed and 0xFF
    }

    private fun clearStepDecoration() {
        inside.decorationState.remove(stepDecorationKey)
    }

    private fun setStepDecoration(code: String, focusLine: Int?) {
        if (focusLine == null) {
            clearStepDecoration()
            return
        }
        val lines = code.lines()
        if (focusLine !in 1..lines.size) {
            clearStepDecoration()
            return
        }
        val lineText = lines[focusLine - 1]
        val endColumn = maxOf(1, lineText.length + 1)
        val className = "nanoStep_${inside.id}".map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        val css = """
            .${className} {
                background: rgba(217, 115, 38, 0.10);
                border-bottom: 1px solid rgba(217, 115, 38, 0.95);
                border-radius: 2px;
            }
        """.trimIndent().replace("\n", " ")
        inside.decorationState[stepDecorationKey] = RawDecoration(
            lineStart = focusLine,
            colStart = 1,
            lineEnd = focusLine,
            colEnd = endColumn,
            classname = className,
            classDef = mapOf(className to css)
        )
    }

    private fun focusEditorLine(line: Int, originSession: WebSocketSession?) {
        val message = Message("select-and-edit-box").payload(
            SelectAndEditBox(
                uid = inside.id,
                property = "code",
                scrollToLine = line,
                highlightLine = line
            )
        )
        if (originSession != null) {
            _send(originSession, message)
        } else {
            send(message)
        }
    }
}

// ── Box property for root detection ──────────────────────────────────────────

var Box.isNanoRoot by Delegation<Boolean?>()
var Box.nanoLaunchDelayMs by Inheritance<Number?>()
var Box.nanoMemoryInit by Inheritance<Any?>()
var Box.nanoMemoryBefore by Delegation<List<Int>?>()
var Box.nanoMemoryAfter by Delegation<List<Int>?>()

@RunsAt(early)
fun installNano() {
    Box.ioPersistentProperties.addAll(setOf("isNanoRoot", "nanoLaunchDelayMs", "nanoMemoryInit"))
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = "nano",
            label = "Nano",
            runtimeClass = NanoExecution::class,
            setLegacyRoot = { box, enabled -> box.isNanoRoot = enabled },
            applyTopology = ::nanoTopologyNeedsUpdating,
        ),
    )
}

@RunsAt(plugins)
fun installNanoPlugin() {
    TopologyWatcher("nano") { nanoTopologyNeedsUpdating(it) }.start()
}

fun nanoTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isNanoRoot == true || it.isMainLanguage("nano") },
        runtimeClass = NanoExecution::class,
        newExecution = { child -> NanoExecution(child) },
        language = "Nano",
        customization = "",
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
