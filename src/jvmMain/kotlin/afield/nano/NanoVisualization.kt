package afield.nano

import kotlin.math.min
import kotlin.random.Random

/**
 * Runs a Nano program and hands back its final machine state, either as data or as a self-contained
 * HTML panel for a box's output area.
 *
 * Nothing in the repository calls these; they exist for a Kotlin box sitting next to a Nano box, so
 * a document can show a register file and 32 bytes of RAM without reaching into [NanoVM] itself.
 * `nanoRunReportHtml` is the one-liner and `nanoRunReport` is the same run with the state left as a
 * [NanoRunReport] to read or render differently.
 *
 * The two kinds of failure a Nano program can have come back by different routes. Source that will
 * not assemble throws `NanoAssemblyException` from either entry point, because there is no machine
 * state to report. A program that assembles and then faults at run time fills `runtimeError` and
 * returns everything else, because the registers and RAM at the point of the fault are what a
 * reader wants; `nanoRunReportHtml` turns that into a thrown `IllegalStateException` by default,
 * and `throwOnRuntimeError = false` gets the panel with the error drawn into it instead. A program
 * that never halts is neither: `maxSteps` bounds the run and `halted` comes back false.
 *
 * `RNG` draws from `Random` unless `rngSeed` is given, so a program using it is reproducible only
 * when seeded.
 *
 * The rendered panel distinguishes three things about a RAM cell, and only two of them survive this
 * route. A cell whose value differs from the program byte loaded there is drawn as mutated, and the
 * cell under the program counter is drawn as current. `changedRegisters` and `changedRam` draw what
 * the most recent step touched, and only a single-step caller fills them, so a panel from
 * `nanoRunReport` shows accumulated mutation and never per-step change.
 */

/**
 * Snapshot of a Nano execution, suitable for HTML rendering in Field output areas.
 *
 * A value over copies: the program bytes are copied in and the register, RAM and output arrays are
 * taken as lists, so a report does not track a [NanoVM] that keeps running. Note that a `data class`
 * holding a `ByteArray` gets a generated `equals` that compares that array by identity, not by
 * content.
 */
data class NanoRunReport(
    /** The assembly this ran, kept so the panel can show it with a line highlighted. Null when unknown. */
    val source: String?,
    /** The assembled bytes. The first 32 of these are also RAM's initial contents. */
    val program: ByteArray,
    /** Instructions executed. Reaching the step bound is how a program that never stops shows up here. */
    val steps: Int,
    /** Every byte `OUT` emitted, in order, masked to 0–255. */
    val outputs: List<Int>,
    /** The four registers r0 to r3, each 0–255. */
    val registers: List<Int>,
    /** All 32 bytes of RAM. Nano's addresses wrap, so there is no out-of-range cell. */
    val ram: List<Int>,
    /** The program counter where the machine stopped. */
    val pc: Int,
    /** The address the panel highlights, which defaults to [pc] but need not be it for a single step. */
    val focusPc: Int = pc,
    /** The source line the panel highlights, resolved through the assembler's map, or null. */
    val focusLine: Int? = null,
    /**
     * Whether the machine stopped on its own, by `HLT` or by a fault.
     *
     * False means the run hit its step bound instead, so a false here and a `runtimeError` of null
     * together say the program was still going.
     */
    val halted: Boolean,
    /** Zero flag as the last arithmetic left it. */
    val flagZ: Boolean,
    /** Carry flag as the last arithmetic left it. */
    val flagC: Boolean,
    /** Sign flag as the last arithmetic left it. */
    val flagS: Boolean,
    /** Overflow flag as the last arithmetic left it. */
    val flagO: Boolean,
    /**
     * Registers the most recent step wrote, for the panel to highlight.
     *
     * Empty for a whole-program run, which has no most recent step worth pointing at.
     */
    val changedRegisters: Set<Int> = emptySet(),
    /** RAM addresses the most recent step wrote. Empty for a whole-program run, like [changedRegisters]. */
    val changedRam: Set<Int> = emptySet(),
    /**
     * The VM fault that stopped the run, already formatted with its address, or null.
     *
     * A fault does not invalidate the rest of the report: the state here is the state at the point
     * it happened.
     */
    val runtimeError: String? = null
)

/**
 * Assemble and execute Nano source, returning a structured snapshot of the final machine state.
 */
fun nanoRunReport(
    source: String,
    maxSteps: Int = 100_000,
    rngSeed: Int? = null
): NanoRunReport {
    val assembly = when (val result = NanoAssembler().assembleWithSourceMap(source)) {
        is NanoAssembler.AssemblyDebugResult.Ok -> result.info
        is NanoAssembler.AssemblyDebugResult.Err ->
            throw NanoAssemblyException(result.errors.joinToString("\n") { "line ${it.line}: ${it.message}" })
    }
    val program = assembly.bytes
    val random = rngSeed?.let(::Random)
    val vm = if (random != null) {
        NanoVM(maxSteps = maxSteps) { random.nextInt(256) }
    } else {
        NanoVM(maxSteps = maxSteps)
    }
    val outputs = mutableListOf<Int>()
    var runtimeError: String? = null
    vm.onOutput = { outputs += it and 0xFF }
    vm.onError = { msg, pc ->
        runtimeError = "Nano VM error at PC=0x${pc.toString(16).padStart(2, '0')}: $msg"
    }
    vm.load(program)
    val steps = vm.run(program)
    val focusPc = vm.pc
    return vm.toNanoRunReport(
        program = program,
        source = source,
        steps = steps,
        outputs = outputs,
        focusPc = focusPc,
        focusLine = assembly.pcToLine.lineForPc(focusPc),
        runtimeError = runtimeError
    )
}

/**
 * Assemble, execute, and render Nano source as a compact HTML report.
 */
fun nanoRunReportHtml(
    source: String,
    maxSteps: Int = 100_000,
    rngSeed: Int? = null,
    title: String? = null,
    includeSource: Boolean = true,
    includeDisassembly: Boolean = true,
    includeProgramBytes: Boolean = true,
    throwOnRuntimeError: Boolean = true
): String {
    val report = nanoRunReport(source = source, maxSteps = maxSteps, rngSeed = rngSeed)
    if (throwOnRuntimeError && report.runtimeError != null) {
        throw IllegalStateException(report.runtimeError)
    }
    return report.toHtml(
        title = title,
        includeSource = includeSource,
        includeDisassembly = includeDisassembly,
        includeProgramBytes = includeProgramBytes
    )
}

/**
 * Renders a report as one self-contained HTML block, ready to hand to a box's output area.
 *
 * Everything is inline style, so the block needs no stylesheet to travel with it. The accents are
 * `var()` references to Field's own cyan and orange with literal fallbacks, so the panel follows
 * the editor's theme where it can; the greys around them are literal and assume a dark one. The
 * width is the smaller of 510px and the viewport less a margin, which is what keeps it inside an
 * output area narrower than that.
 *
 * The program bytes, the source and the disassembly are each optional. Turning them off matters for
 * a long program, since the last two render one row per line.
 */
fun NanoRunReport.toHtml(
    title: String? = null,
    includeSource: Boolean = true,
    includeDisassembly: Boolean = true,
    includeProgramBytes: Boolean = true
): String {
    val heading = escapeHtml(title ?: "Nano execution")
    val flags = buildFlags(flagZ, flagC, flagS, flagO)
    val initialRam = IntArray(NanoVM.RAM_SIZE)
    for (i in 0 until min(program.size, NanoVM.RAM_SIZE)) {
        initialRam[i] = program[i].toInt() and 0xFF
    }
    val sourceHtml = if (includeSource) formatSourcePanel(source.orEmpty(), focusLine) else ""
    val disassemblyHtml = if (includeDisassembly) formatDisassembly(program, focusPc) else ""
    return buildString {
        append("<div style=\"")
        append("font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;")
        append("font-variant-numeric:tabular-nums;")
        append("--nano-orange:var(--field-orange, rgb(217, 115, 38));")
        append("--nano-orange-rgb:217, 115, 38;")
        append("background:rgba(8,12,18,0.28);color:#d7e4ef;")
        append("border:1px solid rgba(var(--field-cyan-rgb, 72, 201, 255), 0.22);")
        append("border-radius:12px;padding:12px;display:grid;gap:10px;box-sizing:border-box;")
        append("width:min(510px, calc(100vw - 72px));\">")

        append("<div style=\"display:flex;justify-content:space-between;align-items:flex-start;gap:10px;flex-wrap:wrap;\">")
        append("<div style=\"font-size:15px;font-weight:600;color:#f2f7fb;\">$heading</div>")
        append("<div style=\"display:flex;gap:6px;flex-wrap:wrap;justify-content:flex-end;\">")
        append(metricChip("steps", steps.toString()))
        append(metricChip("pc", "0x${focusPc.toString(16).padStart(2, '0')}"))
        focusLine?.let { append(metricChip("line", it.toString())) }
        append(metricChip("state", if (halted) "halted" else "running"))
        append(metricChip("flags", flags))
        append("</div>")
        append("</div>")

        runtimeError?.let {
            append("<div style=\"padding:8px 10px;border-radius:8px;border:1px solid rgba(var(--nano-orange-rgb),0.85);background:rgba(var(--nano-orange-rgb),0.08);color:#ffd7b6;\">")
            append(escapeHtml(it))
            append("</div>")
        }

        append("<div style=\"display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:6px;\">")
        registers.forEachIndexed { index, value ->
            append(registerCard("r$index", value, changedRegisters.contains(index)))
        }
        append("</div>")

        append("<div>")
        append("<div style=\"font-size:11px;color:rgba(var(--field-cyan-rgb,72,201,255),0.76);margin-bottom:5px;\">Output stream</div>")
        if (outputs.isEmpty()) {
            append("<div style=\"padding:8px 10px;border-radius:8px;border:1px solid rgba(var(--field-cyan-rgb,72,201,255),0.18);color:rgba(215,228,239,0.62);\">No OUT instructions emitted a value.</div>")
        } else {
            append("<div style=\"display:flex;gap:6px;flex-wrap:wrap;\">")
            outputs.forEachIndexed { index, value ->
                append(outputChip(index, value))
            }
            append("</div>")
        }
        append("</div>")

        append("<div>")
        append("<div style=\"font-size:11px;color:rgba(var(--field-cyan-rgb,72,201,255),0.76);margin-bottom:5px;\">RAM (32 bytes)</div>")
        append("<div style=\"display:grid;grid-template-columns:repeat(8,minmax(0,1fr));gap:6px;\">")
        for (address in 0 until NanoVM.RAM_SIZE) {
            val value = ram[address]
            val initialValue = initialRam[address]
            val loadedFromProgram = address < min(program.size, NanoVM.RAM_SIZE)
            val mutated = value != initialValue
            val recentChange = changedRam.contains(address)
            val pcHere = address == focusPc
            append(
                ramCell(
                    address = address,
                    value = value,
                    loadedFromProgram = loadedFromProgram,
                    mutated = mutated,
                    recentChange = recentChange,
                    pcHere = pcHere
                )
            )
        }
        append("</div>")
        append("</div>")

        if (includeProgramBytes) {
            append(panel("Program bytes", formatProgramBytes(program, focusPc)))
        }

        if (includeSource || includeDisassembly) {
            append("<div style=\"display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:8px;\">")
            if (includeSource) {
                append(panel("Source", sourceHtml))
            }
            if (includeDisassembly) {
                append(panel("Disassembly", disassemblyHtml))
            }
            append("</div>")
        }

        append("</div>")
    }
}

internal fun NanoVM.toNanoRunReport(
    program: ByteArray,
    source: String?,
    steps: Int,
    outputs: List<Int>,
    focusPc: Int = pc,
    focusLine: Int? = null,
    changedRegisters: Set<Int> = emptySet(),
    changedRam: Set<Int> = emptySet(),
    runtimeError: String? = null
): NanoRunReport {
    return NanoRunReport(
        source = source,
        program = program.copyOf(),
        steps = steps,
        outputs = outputs.toList(),
        registers = reg.toList(),
        ram = ram.toList(),
        pc = pc,
        focusPc = focusPc,
        focusLine = focusLine,
        halted = halted,
        flagZ = flagZ,
        flagC = flagC,
        flagS = flagS,
        flagO = flagO,
        changedRegisters = changedRegisters,
        changedRam = changedRam,
        runtimeError = runtimeError
    )
}

private fun metricChip(label: String, value: String): String {
    return """
        <div style="padding:4px 8px;border-radius:999px;border:1px solid rgba(var(--field-cyan-rgb,72,201,255),0.24);color:rgba(215,228,239,0.82);font-size:11px;">
            <span style="color:rgba(var(--field-cyan-rgb,72,201,255),0.72);">${escapeHtml(label)}:</span>
            <span style="color:#f2f7fb;">${escapeHtml(value)}</span>
        </div>
    """.trimIndent()
}

private fun registerCard(name: String, value: Int, changed: Boolean): String {
    val border = if (changed) {
        "rgba(var(--nano-orange-rgb), 0.95)"
    } else {
        "rgba(var(--field-cyan-rgb,72,201,255), 0.28)"
    }
    val fill = if (changed) "rgba(var(--nano-orange-rgb), 0.08)" else "transparent"
    val valueColor = if (changed) "var(--nano-orange)" else "#f2f7fb"
    return """
        <div style="padding:7px 8px;border-radius:8px;border:1px solid $border;background:$fill;">
            <div style="font-size:9px;color:rgba(var(--field-cyan-rgb,72,201,255),0.72);text-transform:uppercase;letter-spacing:0.08em;">${escapeHtml(name)}</div>
            <div style="font-size:12px;line-height:1.02;color:$valueColor;margin-top:4px;">0x${hexByte(value)}</div>
            <div style="font-size:9px;line-height:1.05;color:rgba(215,228,239,0.72);margin-top:3px;">$value</div>
        </div>
    """.trimIndent()
}

private fun outputChip(index: Int, value: Int): String {
    return """
        <div style="padding:6px 8px;border-radius:8px;border:1px solid rgba(var(--field-cyan-rgb,72,201,255),0.24);">
            <div style="font-size:10px;color:rgba(var(--field-cyan-rgb,72,201,255),0.72);">out[$index]</div>
            <div style="font-size:12px;color:#f2f7fb;">$value <span style="color:rgba(215,228,239,0.62);">/ 0x${hexByte(value)}</span></div>
        </div>
    """.trimIndent()
}

private fun ramCell(
    address: Int,
    value: Int,
    loadedFromProgram: Boolean,
    mutated: Boolean,
    recentChange: Boolean,
    pcHere: Boolean
): String {
    val border = when {
        recentChange -> "rgba(var(--nano-orange-rgb), 1.0)"
        pcHere -> "rgba(var(--field-cyan-rgb,72,201,255), 0.95)"
        mutated -> "rgba(var(--nano-orange-rgb), 0.58)"
        loadedFromProgram -> "rgba(var(--field-cyan-rgb,72,201,255), 0.78)"
        else -> "rgba(var(--field-cyan-rgb,72,201,255), 0.18)"
    }
    val fill = when {
        recentChange -> "rgba(var(--nano-orange-rgb), 0.08)"
        pcHere -> "rgba(var(--field-cyan-rgb,72,201,255), 0.05)"
        else -> "transparent"
    }
    val accent = when {
        recentChange -> "var(--nano-orange)"
        pcHere -> "var(--field-cyan, rgb(72, 201, 255))"
        else -> "#f2f7fb"
    }
    return """
        <div style="padding:5px 6px;border-radius:8px;border:1px solid $border;background:$fill;line-height:0.95;">
            <div style="font-size:8px;color:rgba(215,228,239,0.55);">0x${address.toString(16).padStart(2, '0')}</div>
            <div style="font-size:11px;color:$accent;margin-top:3px;">${hexByte(value)}</div>
            <div style="font-size:8px;color:rgba(215,228,239,0.68);margin-top:2px;">$value</div>
        </div>
    """.trimIndent()
}

private fun panel(label: String, content: String): String {
    return buildString {
        append("<div>")
        append("<div style=\"font-size:11px;color:rgba(var(--field-cyan-rgb,72,201,255),0.76);margin-bottom:5px;\">${escapeHtml(label)}</div>")
        append("<div style=\"padding:8px 9px;border-radius:8px;border:1px solid rgba(var(--field-cyan-rgb,72,201,255),0.18);overflow:auto;font-size:11px;line-height:1.35;\">")
        append(content.ifEmpty { "<span style=\"color:rgba(215,228,239,0.52);\">&lt;empty&gt;</span>" })
        append("</div>")
        append("</div>")
    }
}

private fun formatProgramBytes(program: ByteArray, focusPc: Int): String {
    return buildString {
        append("<div style=\"display:flex;gap:6px;flex-wrap:wrap;\">")
        program.forEachIndexed { index, byte ->
            val active = index == focusPc
            val style = if (active) {
                "color:var(--field-cyan, rgb(72, 201, 255));border-bottom:1px solid rgba(var(--field-cyan-rgb,72,201,255),0.9);"
            } else {
                "color:#d7e4ef;border-bottom:1px solid transparent;"
            }
            append("<span style=\"padding-bottom:1px;$style\">${escapeHtml(hexByte(byte.toInt() and 0xFF))}</span>")
        }
        append("</div>")
    }
}

private fun formatSourcePanel(source: String, focusLine: Int?): String {
    if (source.isBlank()) return ""
    return buildString {
        source.lines().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val highlighted = lineNumber == focusLine
            val accent = if (highlighted) {
                "border-left:1px solid rgba(var(--nano-orange-rgb),0.95);padding-left:8px;margin-left:-9px;background:rgba(var(--nano-orange-rgb),0.08);"
            } else {
                ""
            }
            append("<div style=\"white-space:pre-wrap;$accent\">")
            append("<span style=\"display:inline-block;width:2.3em;color:rgba(215,228,239,0.42);\">${lineNumber.toString().padStart(2, ' ')}</span> ")
            append(escapeHtml(rawLine))
            append("</div>")
        }
    }
}

private fun formatDisassembly(program: ByteArray, focusPc: Int): String {
    val lines = NanoDisassembler().disassemble(program)
    return buildString {
        lines.forEach { line ->
            val highlighted = line.bytes.isNotEmpty() && line.addr == focusPc
            val accent = if (highlighted) {
                "border-left:1px solid rgba(var(--nano-orange-rgb),0.95);padding-left:8px;margin-left:-9px;background:rgba(var(--nano-orange-rgb),0.08);color:var(--nano-orange);"
            } else {
                ""
            }
            append("<div style=\"white-space:pre;$accent\">")
            append(escapeHtml(line.toString()))
            append("</div>")
        }
    }
}

private fun IntArray.lineForPc(pc: Int): Int? {
    return if (pc in indices) this[pc] else null
}

private fun hexByte(value: Int): String = (value and 0xFF).toString(16).padStart(2, '0')

private fun buildFlags(z: Boolean, c: Boolean, s: Boolean, o: Boolean): String = buildString {
    append(if (z) "Z" else ".")
    append(if (c) "C" else ".")
    append(if (s) "S" else ".")
    append(if (o) "O" else ".")
}

private fun escapeHtml(text: String): String {
    return buildString(text.length) {
        text.forEach {
            when (it) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                else -> append(it)
            }
        }
    }
}
