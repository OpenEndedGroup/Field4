package afield.stacksl

import java.util.Locale

enum class StackSLSeverity {
    ERROR,
    WARNING,
    INFO
}

data class StackSLSourceLocation(
    val line: Int,
    val column: Int,
    val index: Int
) {
    override fun toString(): String = "$line:$column"
}

data class StackSLDiagnostic(
    val severity: StackSLSeverity,
    val message: String,
    val location: StackSLSourceLocation? = null,
    val token: String? = null
) {
    override fun toString(): String {
        val where = location?.let { "$it: " } ?: ""
        val near = token?.let { " near '$it'" } ?: ""
        return "${severity.name.lowercase(Locale.US)}: $where$message$near"
    }
}

data class StackSLCompileOptions(
    val prefix: String = "stacksl",
    val entrypoint: String = "main",
    val tolerant: Boolean = false,
    val includeHeader: Boolean = true,
    val declareSamplers: Boolean = true,
    val sampleTextures: List<String> = listOf("source"),
    val externalizeConstants: Boolean = false,
    val defaultValues: List<String> = listOf(
        "input",
        "vec4(0.0)",
        "vec4(1.0)",
        "vec4(0.5, 0.5, 0.5, 1.0)"
    ),
    val useEntrypointAsRoot: Boolean = false
)

data class StackSLFunctionAnalysis(
    val name: String,
    val emittedName: String,
    val tokenCount: Int,
    val producedValues: Int,
    val usedValues: Int,
    val prunedValues: Int,
    val maxStackDepth: Int,
    val finalStackDepth: Int,
    val calls: Set<String>,
    val channelWrites: Set<Int> = emptySet()
)

data class StackSLDanglingWire(
    val index: Int,
    val initial: String,
    val line: Int? = null,
    val column: Int? = null,
    val token: String? = null,
    val note: String = "constant"
)

data class StackSLAnalysis(
    val prefix: String,
    val entrypoint: String,
    val functions: List<StackSLFunctionAnalysis>,
    val requiredHelpers: Set<String>,
    val requiredSamplers: Set<String>,
    val danglingWires: List<StackSLDanglingWire> = emptyList()
) {
    val prunedValues: Int get() = functions.sumOf { it.prunedValues }
}

data class StackSLCompileResult(
    val glsl: String,
    val diagnostics: List<StackSLDiagnostic>,
    val analysis: StackSLAnalysis
) {
    val ok: Boolean get() = diagnostics.none { it.severity == StackSLSeverity.ERROR }
}

private data class StackSLArityVariant(val base: String, val arity: Int?, val all: Boolean = false)

object StackSLCompiler {
    fun compile(source: String, options: StackSLCompileOptions = StackSLCompileOptions()): StackSLCompileResult {
        val diagnostics = mutableListOf<StackSLDiagnostic>()
        val normalized = NormalizedOptions.from(options, diagnostics)
        val tokens = tokenize(source)
        val parsed = parse(tokens, diagnostics)
        val definitions = linkedMapOf<String, Definition>()

        parsed.definitions.forEach { definition ->
            val key = definition.name.lowercase(Locale.US)
            when {
                !definitionNameRegex.matches(definition.name) -> diagnostics += StackSLDiagnostic(
                    StackSLSeverity.ERROR,
                    "Definition names must start with a letter or '_' and contain only letters, digits, '_' or '-'.",
                    definition.location,
                    definition.name
                )

                key in builtinWords -> diagnostics += StackSLDiagnostic(
                    StackSLSeverity.ERROR,
                    "Definition '${definition.name}' shadows a built-in StackSL word.",
                    definition.location,
                    definition.name
                )

                key in definitions -> diagnostics += StackSLDiagnostic(
                    StackSLSeverity.ERROR,
                    "Duplicate StackSL definition '${definition.name}'.",
                    definition.location,
                    definition.name
                )

                else -> definitions[key] = definition
            }
        }

        val functionResults = mutableListOf<CompiledFunction>()
        if (!normalized.useEntrypointAsRoot) {
            for (definition in definitions.values) {
                functionResults += FunctionCompiler(
                    options = normalized,
                    definitions = definitions,
                    diagnostics = diagnostics,
                    functionWord = definition.name.lowercase(Locale.US),
                    emittedName = "${normalized.prefix}_${sanitizeGlslIdentifier(definition.name)}"
                ).compile(definition.name, definition.body)
            }
        }

        val entrypointKey = normalized.entrypoint.lowercase(Locale.US)
        val rootTokens = if (normalized.useEntrypointAsRoot) {
            if (entrypointKey !in definitions) {
                diagnostics += StackSLDiagnostic(
                    StackSLSeverity.ERROR,
                    "Entrypoint definition '${normalized.entrypoint}' was not found.",
                    null,
                    normalized.entrypoint
                )
            }
            listOf(Token(normalized.entrypoint, StackSLSourceLocation(1, 1, 0)))
        } else if (parsed.root.isEmpty() && entrypointKey in definitions) {
            listOf(Token(normalized.entrypoint, StackSLSourceLocation(1, 1, 0)))
        } else {
            parsed.root
        }
        val rootResult = FunctionCompiler(
            options = normalized,
            definitions = definitions,
            diagnostics = diagnostics,
            functionWord = null,
            emittedName = normalized.prefix
        ).compile("<root>", rootTokens)

        if (functionResults.isNotEmpty()) {
            detectCallCycles(functionResults, diagnostics)
        }

        val allFunctions = functionResults + rootResult
        val helpers = linkedSetOf<String>()
        val samplerIndices = linkedSetOf<Int>()
        allFunctions.forEach {
            helpers += it.requiredHelpers
            samplerIndices += it.requiredSamplerIndices
        }
        val requiredSamplers = samplerIndices
            .mapNotNull { normalized.sampleTextures.getOrNull(it)?.emittedName }
            .toCollection(linkedSetOf())
        val usingWires = normalized.wires.enabled && normalized.wires.count > 0
        val wireParam = if (usingWires) ", float ${normalized.wires.arrayName}[${normalized.wires.countName}]" else ""
        val wireArg = if (usingWires) ", ${normalized.wires.arrayName}" else ""

        fun finalizeSource(sourceText: String): String =
            sourceText
                .replace(normalized.wireParamPlaceholder, wireParam)
                .replace(normalized.wireArgPlaceholder, wireArg)

        val glsl = buildString {
            if (normalized.includeHeader) {
                appendLine("// StackSL generated by Field.")
                appendLine("// prefix=${normalized.prefix} entrypoint=${normalized.entrypoint}")
                appendLine()
            }
            if (usingWires) {
                normalized.wires.declarations().forEach { appendLine(it) }
                appendLine()
            }
            if (normalized.declareSamplers && samplerIndices.isNotEmpty()) {
                samplerIndices.forEach { samplerIndex ->
                    val sampler = normalized.sampleTextures.getOrNull(samplerIndex)
                    if (sampler != null) appendLine("uniform sampler2D ${sampler.emittedName};")
                }
                appendLine()
            }
            functionResults.forEach { appendLine("vec4 ${it.emittedName}(vec4 ${normalized.inputName}$wireParam);") }
            if (functionResults.isNotEmpty()) appendLine()
            append(helperSource(normalized.prefix, helpers))
            functionResults.forEach {
                append(finalizeSource(it.source))
                appendLine()
            }
            append(finalizeSource(rootResult.source))
            if (usingWires) {
                appendLine()
                appendLine("vec4 ${normalized.prefix}(vec4 ${normalized.inputName}) {")
                appendLine("    float ${normalized.wires.arrayName}[${normalized.wires.countName}];")
                appendLine("    ${normalized.wires.initName}(${normalized.wires.arrayName});")
                appendLine("    return ${normalized.prefix}(${normalized.inputName}, ${normalized.wires.arrayName});")
                appendLine("}")
            }
        }

        return StackSLCompileResult(
            glsl = glsl,
            diagnostics = diagnostics,
            analysis = StackSLAnalysis(
                prefix = normalized.prefix,
                entrypoint = normalized.entrypoint,
                functions = allFunctions.map { it.analysis },
                requiredHelpers = helpers,
                requiredSamplers = requiredSamplers,
                danglingWires = normalized.wires.wires.toList()
            )
        )
    }
}

fun compileStackSL(source: String, options: StackSLCompileOptions = StackSLCompileOptions()): StackSLCompileResult =
    StackSLCompiler.compile(source, options)

fun compileStackSLToGlsl(
    source: String,
    prefix: String = "stacksl",
    entrypoint: String = "main",
    sampleTextures: List<String> = listOf("source"),
    tolerant: Boolean = false,
    declareSamplers: Boolean = true,
    externalizeConstants: Boolean = false
): String =
    StackSLCompiler.compile(
        source,
        StackSLCompileOptions(
            prefix = prefix,
            entrypoint = entrypoint,
            sampleTextures = sampleTextures,
            tolerant = tolerant,
            declareSamplers = declareSamplers,
            externalizeConstants = externalizeConstants
        )
    ).glsl

private data class NormalizedOptions(
    val prefix: String,
    val entrypoint: String,
    val useEntrypointAsRoot: Boolean,
    val tolerant: Boolean,
    val includeHeader: Boolean,
    val declareSamplers: Boolean,
    val sampleTextures: List<SamplerName>,
    val inputName: String,
    val defaultValues: List<String>,
    val wires: DanglingWireAllocator,
    val wireParamPlaceholder: String,
    val wireArgPlaceholder: String
) {
    companion object {
        fun from(options: StackSLCompileOptions, diagnostics: MutableList<StackSLDiagnostic>): NormalizedOptions {
            val prefix = sanitizeGlslIdentifier(options.prefix.ifBlank { "stacksl" })
            if (prefix != options.prefix) {
                diagnostics += StackSLDiagnostic(
                    StackSLSeverity.WARNING,
                    "Prefix '${options.prefix}' was sanitized to '$prefix' for GLSL output."
                )
            }

            val inputName = "${prefix}_in"
            val entrypoint = options.entrypoint.ifBlank { "main" }
            val wires = DanglingWireAllocator(prefix, options.externalizeConstants)
            val samplers = (options.sampleTextures.ifEmpty { listOf("source") }).mapIndexed { index, name ->
                val base = sanitizeGlslIdentifier(name.ifBlank { "source$index" })
                SamplerName(sourceName = name, emittedName = base)
            }

            return NormalizedOptions(
                prefix = prefix,
                entrypoint = entrypoint,
                useEntrypointAsRoot = options.useEntrypointAsRoot,
                tolerant = options.tolerant,
                includeHeader = options.includeHeader,
                declareSamplers = options.declareSamplers,
                sampleTextures = samplers,
                inputName = inputName,
                defaultValues = options.defaultValues
                    .ifEmpty { listOf("input", "vec4(0.0)") }
                    .map { glslInputIdentifier.replace(it, inputName) },
                wires = wires,
                wireParamPlaceholder = "__STACKSL_WIRE_PARAM__",
                wireArgPlaceholder = "__STACKSL_WIRE_ARG__"
            )
        }
    }
}

private data class SamplerName(val sourceName: String, val emittedName: String)

private class DanglingWireAllocator(
    private val prefix: String,
    val enabled: Boolean
) {
    val arrayName: String = "${prefix}_wires"
    val countName: String = "${prefix}_wire_count"
    val initName: String = "${prefix}_init_wires"
    val wires = mutableListOf<StackSLDanglingWire>()
    private val tokenRefs = linkedMapOf<String, Int>()

    val count: Int get() = wires.size

    fun ref(number: String, token: Token?, note: String): String {
        val initial = normalizeFloat(number)
        if (!enabled) return initial
        val key = token?.let { "${it.location.index}|$initial|$note" }
        if (key != null && key in tokenRefs) return "$arrayName[${tokenRefs.getValue(key)}]"
        val index = wires.size
        wires += StackSLDanglingWire(
            index = index,
            initial = initial,
            line = token?.location?.line,
            column = token?.location?.column,
            token = token?.text,
            note = note
        )
        if (key != null) tokenRefs[key] = index
        return "$arrayName[$index]"
    }

    fun replaceNumbers(expression: String, token: Token?, note: String): String {
        if (!enabled) return expression
        return defaultNumberRegex.replace(expression) { match ->
            ref(match.value, token, note)
        }
    }

    fun declarations(): List<String> {
        if (!enabled || wires.isEmpty()) return emptyList()
        val lines = mutableListOf(
            "const int $countName = ${wires.size};",
            "void $initName(out float $arrayName[$countName]) {"
        )
        wires.forEach { wire ->
            lines += "    $arrayName[${wire.index}] = ${wire.initial};"
        }
        lines += "}"
        return lines
    }
}

private data class Token(val text: String, val location: StackSLSourceLocation)

private data class Definition(
    val name: String,
    val body: List<Token>,
    val location: StackSLSourceLocation
)

private data class ParsedProgram(
    val root: List<Token>,
    val definitions: List<Definition>
)

private data class StackValue(
    val id: Int,
    val expression: String,
    val deps: List<Int>,
    val token: Token?,
    val leaf: Boolean,
    val note: String
)

private val glslInputIdentifier = Regex("""\binput\b""")

private data class CompiledFunction(
    val emittedName: String,
    val source: String,
    val analysis: StackSLFunctionAnalysis,
    val requiredHelpers: Set<String>,
    val requiredSamplerIndices: Set<Int>
)

private class FunctionCompiler(
    private val options: NormalizedOptions,
    private val definitions: Map<String, Definition>,
    private val diagnostics: MutableList<StackSLDiagnostic>,
    private val functionWord: String?,
    private val emittedName: String
) {
    private val values = mutableListOf<StackValue>()
    private val stack = mutableListOf<Int>()
    private val expressionCache = linkedMapOf<String, Int>()
    private val requiredHelpers = linkedSetOf<String>()
    private val requiredSamplerIndices = linkedSetOf<Int>()
    private val calls = linkedSetOf<String>()
    private val channelWrites = linkedMapOf<Int, Int>()
    private val externalWrites = mutableListOf<Pair<String, Int>>()
    private val inputStack = mutableListOf<Int>()
    private val inlineCallStack = mutableListOf<String>()
    private var maxStackDepth = 0

    fun compile(sourceName: String, tokens: List<Token>): CompiledFunction {
        val inputValue = leaf(options.inputName, null, "function input")
        inputStack += inputValue
        push(inputValue)
        try {
            tokens.forEach { process(it) }
        } finally {
            inputStack.removeAt(inputStack.lastIndex)
        }
        val result = if (stack.isNotEmpty()) {
            stack.last()
        } else {
            warn(tokens.lastOrNull(), "Function '$sourceName' produced an empty stack; returning the default input color.")
            leaf(options.inputName, tokens.lastOrNull(), "empty-stack result")
        }

        val reachable = linkedSetOf<Int>()
        markReachable(result, reachable)
        channelWrites.values.forEach { markReachable(it, reachable) }
        externalWrites.forEach { markReachable(it.second, reachable) }
        val ordered = values.filter { it.id in reachable && !it.leaf }
        val producedValues = values.count { !it.leaf }
        val prunedValues = values.count { !it.leaf && it.id !in reachable }

        val source = buildString {
            appendLine("vec4 $emittedName(vec4 ${options.inputName}${options.wireParamPlaceholder}) {")
            ordered.forEach { value ->
                appendLine("    vec4 ${localName(value.id)} = ${value.expression};")
            }
            if (channelWrites.isNotEmpty()) {
                val outputName = "_${options.prefix}_out"
                val channels = "xyzw"
                appendLine("    vec4 $outputName = ${ref(result)};")
                channelWrites.toSortedMap().forEach { (channel, value) ->
                    appendLine("    $outputName.${channels[channel]} = ${ref(value)}.x;")
                }
                externalWrites.forEach { (name, value) ->
                    appendLine("    $name = ${ref(value)};")
                }
                appendLine("    return $outputName;")
            } else {
                externalWrites.forEach { (name, value) ->
                    appendLine("    $name = ${ref(value)};")
                }
                appendLine("    return ${ref(result)};")
            }
            appendLine("}")
        }

        return CompiledFunction(
            emittedName = emittedName,
            source = source,
            analysis = StackSLFunctionAnalysis(
                name = sourceName,
                emittedName = emittedName,
                tokenCount = tokens.size,
                producedValues = producedValues,
                usedValues = ordered.size,
                prunedValues = prunedValues,
                maxStackDepth = maxStackDepth,
                finalStackDepth = stack.size,
                calls = calls.toSet(),
                channelWrites = channelWrites.keys.toSet()
            ),
            requiredHelpers = requiredHelpers.toSet(),
            requiredSamplerIndices = requiredSamplerIndices.toSet()
        )
    }

    private fun process(token: Token) {
        val raw = token.text
        val rawWord = raw.lowercase(Locale.US)
        val arityVariant = parseStackSLArityVariant(rawWord)
        val word = arityVariant?.base ?: rawWord
        val requestedArity = arityVariant?.arity
        val requestedAll = arityVariant?.all == true

        parseExternalRead(raw)?.let {
            push(leaf(it, token, "external GLSL read"))
            return
        }

        parseExternalWrite(raw)?.let {
            externalWrites += it to pop(token, "external GLSL write $it", "vec4(0.0)")
            return
        }

        parseTopModifier(raw)?.let {
            applyTopModifier(it, token)
            return
        }

        parseNumberModifier(raw)?.let {
            pushNumberModifier(it, token)
            return
        }

        parseNumberWave(raw)?.let {
            pushNumberWave(it, token)
            return
        }

        parseNumberSuffix(raw)?.let {
            pushNumberSuffix(it.first, it.second, token)
            return
        }

        parseNumberRange(raw)?.let {
            pushNumberRange(it.first, it.second, token)
            return
        }

        parseVecLiteral(raw)?.let {
            push(vecLiteral(it, token))
            return
        }

        parseNumberLiteral(raw)?.let {
            push(constantVec(it, token, "number"))
            return
        }

        namedColors[rawWord]?.let {
            push(leaf(it, token, "color"))
            return
        }

        if (rawWord.startsWith(".") || rawWord !in bareSwizzleReservedWords) {
            parseSwizzle(rawWord)?.let { swizzle ->
                val value = pop(token, "swizzle source")
                push(expr(swizzleExpression(ref(value), swizzle), listOf(value), token, "swizzle .$swizzle"))
                return
            }
        }

        when (word) {
            "noop" -> return
            "0->", "1->", "2->", "3->" -> readStoreChannel(word.first().digitToInt(), token)
            "->0", "->1", "->2", "->3" -> writeStoreChannel(word.last().digitToInt(), token)
            "input", "in" -> push(inputExpr(token, "input") { it })
            "uv" -> push(inputExpr(token, "input uv") { "vec4($it.xy, 0.0, 1.0)" })
            "p", "pos", "center", "centered" -> push(inputExpr(token, "centered input uv") { "vec4($it.xy - vec2(0.5), 0.0, 1.0)" })
            "time", "t" -> push(inputExpr(token, "input time") { "vec4($it.w)" })
            "hash", "h" -> push(inputExpr(token, "input hash") { "vec4($it.z)" })
            "pi" -> push(leaf("vec4(3.141592653589793)", token, "pi"))
            "tau" -> push(leaf("vec4(6.283185307179586)", token, "tau"))
            "e" -> push(leaf("vec4(2.718281828459045)", token, "e"))

            "dup" -> push(peekOrDefault(token, "dup"))
            "drop" -> pop(token, "drop")
            "swap" -> swap(token)
            "over" -> over(token)
            "rot" -> rot(token)
            "nip" -> nip(token)
            "tuck" -> tuck(token)

            "vec2", "v2" -> constructVec(token, 2)
            "vec3", "v3" -> constructVec(token, 3)
            "vec4", "v4" -> constructVec(token, 4)

            "neg", "negate" -> unary(token, "negate") { "-(${ref(it)})" }
            "1-" -> unary(token, "one minus") { "(vec4(1.0) - ${ref(it)})" }
            "abs", "floor", "ceil", "fract", "sqrt", "inversesqrt", "exp", "log",
            "sin", "cos", "tan", "asin", "acos", "atan", "normalize" -> {
                unary(token, word) { "$word(${ref(it)})" }
            }

            "len", "length", "len2", "length2" -> unary(token, "length2") { "vec4(length(${ref(it)}.xy))" }
            "len3", "length3" -> unary(token, "length3") { "vec4(length(${ref(it)}.xyz))" }
            "len4", "length4" -> unary(token, "length4") { "vec4(length(${ref(it)}))" }
            "luma" -> unary(token, "luma") {
                "vec4(dot(${ref(it)}.rgb, vec3(0.2126, 0.7152, 0.0722)))"
            }
            "average", "avg", "mean" -> {
                if (arityVariant != null) {
                    val args = consumeBlastArgs(token, raw, requestedArity, requestedAll) ?: return
                    push(expr(blastMeanExpression(args), args, token, "$raw mean"))
                } else {
                    requiredHelpers += "reduce"
                    unary(token, "average") { "vec4(${options.prefix}_avg4(${ref(it)}))" }
                }
            }
            "median", "med" -> {
                if (arityVariant != null) {
                    val args = consumeBlastArgs(token, raw, requestedArity, requestedAll) ?: return
                    push(blastMedian(token, raw, args))
                } else {
                    requiredHelpers += "reduce"
                    unary(token, "median") { "vec4(${options.prefix}_median4(${ref(it)}))" }
                }
            }
            "sort" -> {
                val args = consumeBlastArgs(token, raw, requestedArity, requestedAll || arityVariant == null) ?: return
                blastSort(token, raw, args).forEach(::push)
            }
            "intermediate" -> {
                val args = consumeBlastArgs(token, raw, requestedArity, requestedAll || arityVariant == null) ?: return
                args.zipWithNext().forEachIndexed { index, (a, b) ->
                    push(expr("((${ref(a)} + ${ref(b)}) * vec4(0.5))", listOf(a, b), token, "$raw midpoint $index"))
                }
            }
            "sat", "saturate", "clamp" -> unary(token, "saturate") {
                "clamp(${ref(it)}, vec4(0.0), vec4(1.0))"
            }
            "mirror" -> unary(token, "mirror") {
                "(vec4(1.0) - abs(fract(${ref(it)} * 0.5) * 2.0 - vec4(1.0)))"
            }
            "tonemap" -> {
                requiredHelpers += "tonemap"
                unary(token, "tonemap") { "${options.prefix}_tonemap(${ref(it)})" }
            }
            "ryb", "ryb2rgb", "from-ryb" -> {
                requiredHelpers += "ryb2rgb"
                unary(token, "ryb to rgb") { "${options.prefix}_ryb2rgb(${ref(it)})" }
            }
            "ryb.c", "ryb2rgb.c", "from-ryb.c" -> {
                requiredHelpers += "ryb2rgbClamped"
                unary(token, "clamped ryb to rgb") { "${options.prefix}_ryb2rgb_c(${ref(it)})" }
            }
            "rgb2ryb", "to-ryb" -> {
                requiredHelpers += "rgb2ryb"
                unary(token, "rgb to ryb") { "${options.prefix}_rgb2ryb(${ref(it)})" }
            }
            "rgb2ryb.c", "to-ryb.c" -> {
                requiredHelpers += "rgb2rybClamped"
                unary(token, "clamped rgb to ryb") { "${options.prefix}_rgb2ryb_c(${ref(it)})" }
            }
            "oklab", "oklab2rgb", "from-oklab" -> {
                requiredHelpers += "oklab2rgb"
                unary(token, "oklab to rgb") { "${options.prefix}_oklab2rgb(${ref(it)})" }
            }
            "rgb2oklab", "to-oklab" -> {
                requiredHelpers += "rgb2oklab"
                unary(token, "rgb to oklab") { "${options.prefix}_rgb2oklab(${ref(it)})" }
            }

            "+", "add" -> binary(token, "add") { a, b -> "(${ref(a)} + ${ref(b)})" }
            "-", "sub" -> binary(token, "subtract") { a, b -> "(${ref(a)} - ${ref(b)})" }
            "*", "mul" -> binary(token, "multiply") { a, b -> "(${ref(a)} * ${ref(b)})" }
            "/", "div" -> {
                requiredHelpers += "safeDiv"
                binary(token, "safe divide") { a, b -> "${options.prefix}_safeDiv(${ref(a)}, ${ref(b)})" }
            }
            "mod" -> {
                requiredHelpers += "safeMod"
                binary(token, "safe mod") { a, b -> "${options.prefix}_safeMod(${ref(a)}, ${ref(b)})" }
            }
            "pow" -> binary(token, "pow") { a, b ->
                "pow(${ref(a)}, ${ref(b)})"
            }
            "pow.safe" -> binary(token, "safe pow") { a, b ->
                "pow(max(abs(${ref(a)}), vec4(0.00001)), ${ref(b)})"
            }
            "vmin", "minimum" -> {
                requiredHelpers += "reduce"
                unary(token, "component min") { "vec4(${options.prefix}_min4(${ref(it)}))" }
            }
            "min" -> {
                if (requestedAll || (requestedArity ?: 0) >= 3) {
                    val args = consumeBlastArgs(token, raw, requestedArity, requestedAll) ?: return
                    push(expr(blastReduceExpression(args, "min"), args, token, "$raw blast min"))
                    return
                }
                when (requestedArity ?: if (shouldReduceTop()) 1 else 2) {
                    1 -> {
                        requiredHelpers += "reduce"
                        unary(token, "component min") { "vec4(${options.prefix}_min4(${ref(it)}))" }
                    }
                    2 -> binary(token, "min") { a, b -> "min(${ref(a)}, ${ref(b)})" }
                    else -> unsupportedArity(token, raw, "min", requestedArity, setOf(1, 2))
                }
            }
            "vmax", "maximum" -> {
                requiredHelpers += "reduce"
                unary(token, "component max") { "vec4(${options.prefix}_max4(${ref(it)}))" }
            }
            "max" -> {
                if (requestedAll || (requestedArity ?: 0) >= 3) {
                    val args = consumeBlastArgs(token, raw, requestedArity, requestedAll) ?: return
                    push(expr(blastReduceExpression(args, "max"), args, token, "$raw blast max"))
                    return
                }
                when (requestedArity ?: if (shouldReduceTop()) 1 else 2) {
                    1 -> {
                        requiredHelpers += "reduce"
                        unary(token, "component max") { "vec4(${options.prefix}_max4(${ref(it)}))" }
                    }
                    2 -> binary(token, "max") { a, b -> "max(${ref(a)}, ${ref(b)})" }
                    else -> unsupportedArity(token, raw, "max", requestedArity, setOf(1, 2))
                }
            }
            "atan2" -> binary(token, "atan2") { y, x -> "atan(${ref(y)}, ${ref(x)})" }
            "dot" -> binary(token, "dot") { a, b -> "vec4(dot(${ref(a)}, ${ref(b)}))" }
            "distance2" -> binary(token, "distance2") { a, b -> "vec4(distance(${ref(a)}.xy, ${ref(b)}.xy))" }
            "distance3" -> binary(token, "distance3") { a, b -> "vec4(distance(${ref(a)}.xyz, ${ref(b)}.xyz))" }
            "distance" -> binary(token, "distance") { a, b -> "vec4(distance(${ref(a)}, ${ref(b)}))" }
            "cross" -> binary(token, "cross") { a, b -> "vec4(cross(${ref(a)}.xyz, ${ref(b)}.xyz), 1.0)" }
            "standoff", "standoff2", "standoff3" -> {
                requiredHelpers += "safeNormalize"
                standoff(word, token)
            }
            "rotate" -> rotate(token)
            "rotates" -> rotates(token)
            ">x", ">y", ">z", ">w" -> replaceLane(word[1], token)

            "lt", "<" -> compare(token, "lessThan")
            "lte", "<=" -> compare(token, "lessThanEqual")
            "gt", ">" -> compare(token, "greaterThan")
            "gte", ">=" -> compare(token, "greaterThanEqual")
            "eq", "=" -> compare(token, "equal")
            "neq", "!=", "<>" -> compare(token, "notEqual")
            "and" -> binary(token, "and") { a, b -> "min(step(vec4(0.5), abs(${ref(a)})), step(vec4(0.5), abs(${ref(b)})))" }
            "or" -> binary(token, "or") { a, b -> "max(step(vec4(0.5), abs(${ref(a)})), step(vec4(0.5), abs(${ref(b)})))" }
            "not" -> unary(token, "not") { "vec4(1.0) - step(vec4(0.5), abs(${ref(it)}))" }
            "xor", "bitxor" -> {
                requiredHelpers += "bits"
                binary(token, "xor") { a, b -> "${options.prefix}_xor(${ref(a)}, ${ref(b)})" }
            }
            "gray-code", "graycode", "gray-encode" -> {
                requiredHelpers += "bits"
                unary(token, "gray code") { "${options.prefix}_grayCode(${ref(it)})" }
            }
            "gray-decode", "ungray" -> {
                requiredHelpers += "bits"
                unary(token, "gray decode") { "${options.prefix}_grayDecode(${ref(it)})" }
            }
            "lfsr" -> {
                requiredHelpers += "bits"
                unary(token, "lfsr") { "${options.prefix}_lfsr(${ref(it)})" }
            }
            "bitcrush", "bit-crush" -> {
                requiredHelpers += "bits"
                val args = consumeArityArgs(
                    token,
                    raw,
                    listOf(
                        StackSLArg("value", "input"),
                        StackSLArg("bits", "vec4(4.0)")
                    ),
                    requestedArity ?: if (stack.size < 2) 1 else 2
                ) ?: return
                val (value, bits) = args
                push(expr("${options.prefix}_bitcrush(${ref(value)}, ${ref(bits)})", args, token, "bitcrush"))
            }

            "mix", "lerp", "mix.c", "lerp.c", "mix.sc", "lerp.sc" -> {
                val t = pop(token, "mix amount", "vec4(0.5)")
                val b = pop(token, "mix b", "vec4(1.0)")
                val a = pop(token, "mix a", "vec4(0.0)")
                val amount = when (word) {
                    "mix.c", "lerp.c" -> "clamp(${ref(t)}, vec4(0.0), vec4(1.0))"
                    "mix.sc", "lerp.sc" -> {
                        requiredHelpers += "softClamp01"
                        "${options.prefix}_softClamp01(${ref(t)})"
                    }
                    else -> ref(t)
                }
                push(expr("mix(${ref(a)}, ${ref(b)}, $amount)", listOf(a, b, t), token, word))
            }
            "select", "if" -> {
                val condition = pop(token, "select condition", "vec4(0.0)")
                val trueValue = pop(token, "select true", "vec4(1.0)")
                val falseValue = pop(token, "select false", "vec4(0.0)")
                val mask = "step(vec4(0.5), abs(${ref(condition)}))"
                push(expr("mix(${ref(falseValue)}, ${ref(trueValue)}, $mask)", listOf(falseValue, trueValue, condition), token, "select"))
            }
            "step" -> {
                val x = pop(token, "step x", "input")
                val edge = pop(token, "step edge", "vec4(0.5)")
                push(expr("step(${ref(edge)}, ${ref(x)})", listOf(edge, x), token, "step"))
            }
            "smoothstep" -> {
                val x = pop(token, "smoothstep x", "input")
                val edge1 = pop(token, "smoothstep high", "vec4(0.75)")
                val edge0 = pop(token, "smoothstep low", "vec4(0.25)")
                push(expr("smoothstep(${ref(edge0)}, ${ref(edge1)}, ${ref(x)})", listOf(edge0, edge1, x), token, "smoothstep"))
            }

            "noise", "perlin" -> {
                requiredHelpers += "noise"
                unary(token, "noise") { "vec4(${options.prefix}_noise(${ref(it)}.xy + ${ref(it)}.zw * 0.07))" }
            }
            "fbm" -> {
                requiredHelpers += "fbm"
                unary(token, "fbm") { "vec4(${options.prefix}_fbm(${ref(it)}.xy + ${ref(it)}.zw * 0.07))" }
            }
            "pulse" -> {
                requiredHelpers += "pulse"
                val args = consumeArityArgs(
                    token,
                    raw,
                    listOf(
                        StackSLArg("x", "input"),
                        StackSLArg("center", "vec4(0.5)"),
                        StackSLArg("width", "vec4(0.08)")
                    ),
                    requestedArity ?: if (stack.size < 3) 1 else 3
                ) ?: return
                val (x, center, width) = args
                push(expr("${options.prefix}_pulse(${ref(x)}, ${ref(center)}, ${ref(width)})", args, token, "pulse"))
            }
            "fm" -> {
                requiredHelpers += "fm"
                val args = consumeArityArgs(
                    token,
                    raw,
                    listOf(
                        StackSLArg("phase", "input"),
                        StackSLArg("carrier", "input"),
                        StackSLArg("modulator", "vec4(1.0)")
                    ),
                    requestedArity ?: if (stack.size < 3) 1 else 3
                ) ?: return
                val (phase, carrier, modulator) = args
                push(expr("${options.prefix}_fm(${ref(phase)}, ${ref(carrier)}, ${ref(modulator)})", args, token, "fm"))
            }
            "fmchirp" -> {
                requiredHelpers += "fmchirp"
                val arity = requestedArity ?: if (stack.size < 9) 1 else 9
                val params = if (arity == 8) {
                    val args = consumeArityArgs(
                        token,
                        raw,
                        listOf(
                            StackSLArg("freq", "vec4(0.0)"),
                            StackSLArg("mod", "vec4(0.0)"),
                            StackSLArg("modPhase", "vec4(0.0)"),
                            StackSLArg("phase", "vec4(0.0)"),
                            StackSLArg("chirp", "vec4(0.0)"),
                            StackSLArg("modChirp", "vec4(0.0)"),
                            StackSLArg("inputPow", "vec4(1.0)"),
                            StackSLArg("outputPow", "vec4(1.0)")
                        ),
                        8
                    ) ?: return
                    listOf(inputExpr(token, "$raw implicit x") { "vec4($it.x)" }) + args
                } else {
                    consumeArityArgs(
                        token,
                        raw,
                        listOf(
                            StackSLArg("x", "input"),
                            StackSLArg("freq", "vec4(0.0)"),
                            StackSLArg("mod", "vec4(0.0)"),
                            StackSLArg("modPhase", "vec4(0.0)"),
                            StackSLArg("phase", "vec4(0.0)"),
                            StackSLArg("chirp", "vec4(0.0)"),
                            StackSLArg("modChirp", "vec4(0.0)"),
                            StackSLArg("inputPow", "vec4(1.0)"),
                            StackSLArg("outputPow", "vec4(1.0)")
                        ),
                        arity
                    ) ?: return
                }
                push(
                    expr(
                        "${options.prefix}_fmchirp(${params.joinToString(", ") { ref(it) }})",
                        params,
                        token,
                        raw
                    )
                )
            }
            "palette", "cos-palette", "cosine-palette" -> {
                requiredHelpers += "palette"
                val args = consumeArityArgs(
                    token,
                    raw,
                    listOf(
                        StackSLArg("t", "input"),
                        StackSLArg("a", "vec4(0.50, 0.50, 0.50, 1.00)"),
                        StackSLArg("b", "vec4(0.50, 0.50, 0.50, 0.00)"),
                        StackSLArg("c", "vec4(1.00, 1.00, 1.00, 1.00)"),
                        StackSLArg("d", "vec4(0.00, 0.10, 0.20, 0.00)")
                    ),
                    requestedArity ?: if (stack.size < 5) 1 else 5
                ) ?: return
                val (t, a, b, c, d) = args
                push(expr("${options.prefix}_palette(${ref(t)}, ${ref(a)}, ${ref(b)}, ${ref(c)}, ${ref(d)})", args, token, "cosine palette"))
            }
            "saturation", "sat-color" -> {
                requiredHelpers += "saturation"
                val args = consumeArityArgs(
                    token,
                    raw,
                    listOf(
                        StackSLArg("color", "input"),
                        StackSLArg("amount", "vec4(1.0)")
                    ),
                    requestedArity ?: if (stack.size < 2) 1 else 2
                ) ?: return
                val (color, amount) = args
                push(expr("${options.prefix}_saturation(${ref(color)}, ${ref(amount)}.x)", args, token, "saturation"))
            }
            "contrast" -> {
                requiredHelpers += "contrast"
                val args = consumeArityArgs(
                    token,
                    raw,
                    listOf(
                        StackSLArg("color", "input"),
                        StackSLArg("amount", "vec4(1.2)")
                    ),
                    requestedArity ?: if (stack.size < 2) 1 else 2
                ) ?: return
                val (color, amount) = args
                push(expr("${options.prefix}_contrast(${ref(color)}, ${ref(amount)})", args, token, "contrast"))
            }
            "grade" -> {
                requiredHelpers += "grade"
                val args = consumeArityArgs(
                    token,
                    raw,
                    listOf(
                        StackSLArg("color", "input"),
                        StackSLArg("lift", "vec4(0.0)"),
                        StackSLArg("gain", "vec4(1.0)"),
                        StackSLArg("gamma", "vec4(1.0)")
                    ),
                    requestedArity ?: if (stack.size < 4) 1 else 4
                ) ?: return
                val (color, lift, gain, gamma) = args
                push(expr("${options.prefix}_grade(${ref(color)}, ${ref(lift)}, ${ref(gain)}, ${ref(gamma)})", args, token, "grade"))
            }
            "alpha", "alpha.c", "alpha.sc" -> {
                val range = pop(token, "alpha range", "vec4(4.0)")
                val modulation = pop(token, "alpha modulation", "vec4(0.0)")
                val baseAlpha = pop(token, "alpha base", "vec4(0.08)")
                val helper = when (word) {
                    "alpha.c" -> {
                        requiredHelpers += "alphaClamped"
                        "${options.prefix}_alpha_c"
                    }
                    "alpha.sc" -> {
                        requiredHelpers += "alphaSoft"
                        requiredHelpers += "softClamp01"
                        "${options.prefix}_alpha_sc"
                    }
                    else -> {
                        requiredHelpers += "alpha"
                        "${options.prefix}_alpha"
                    }
                }
                push(expr("$helper(${ref(baseAlpha)}, ${ref(modulation)}, ${ref(range)})", listOf(baseAlpha, modulation, range), token, word))
            }
            "ema", "ema.c", "ema.sc" -> {
                requiredHelpers += "ema"
                val args = consumeArityArgs(
                    token,
                    raw,
                    listOf(
                        StackSLArg("current", "input"),
                        StackSLArg("previous", "input"),
                        StackSLArg("alpha", "vec4(0.08)")
                    ),
                    requestedArity ?: if (stack.size < 3) 1 else 3
                ) ?: return
                val (current, previous, alpha) = args
                val alphaExpression = when (word) {
                    "ema.c" -> "clamp(${ref(alpha)}, vec4(0.0), vec4(1.0))"
                    "ema.sc" -> {
                        requiredHelpers += "softClamp01"
                        "${options.prefix}_softClamp01(${ref(alpha)})"
                    }
                    else -> ref(alpha)
                }
                push(expr("${options.prefix}_ema(${ref(current)}, ${ref(previous)}, $alphaExpression)", args, token, word))
            }
            "ema-diff", "diff-ema", "dema", "ema-diff.c", "diff-ema.c", "dema.c", "ema-diff.sc", "diff-ema.sc", "dema.sc" -> {
                requiredHelpers += "ema"
                val args = consumeArityArgs(
                    token,
                    raw,
                    listOf(
                        StackSLArg("current", "input"),
                        StackSLArg("previous", "input"),
                        StackSLArg("alpha", "vec4(0.08)")
                    ),
                    requestedArity ?: if (stack.size < 3) 1 else 3
                ) ?: return
                val (current, previous, alpha) = args
                val alphaExpression = when (word) {
                    "ema-diff.c", "diff-ema.c", "dema.c" -> "clamp(${ref(alpha)}, vec4(0.0), vec4(1.0))"
                    "ema-diff.sc", "diff-ema.sc", "dema.sc" -> {
                        requiredHelpers += "softClamp01"
                        "${options.prefix}_softClamp01(${ref(alpha)})"
                    }
                    else -> ref(alpha)
                }
                push(expr("(${ref(current)} - ${options.prefix}_ema(${ref(current)}, ${ref(previous)}, $alphaExpression))", args, token, word))
            }
            "sample", "sample0", "sample1", "sample2", "sample3",
            "sample.w", "sample0.w", "sample1.w", "sample2.w", "sample3.w",
            "sample-abs", "sample0-abs", "sample1-abs", "sample2-abs", "sample3-abs",
            "sample-abs.w", "sample0-abs.w", "sample1-abs.w", "sample2-abs.w", "sample3-abs.w",
            "prev", "state", "gather", "gather0", "gather1", "gather2", "gather3",
            "gather.w", "gather0.w", "gather1.w", "gather2.w", "gather3.w" -> sampleWord(word, token)

            else -> {
                val definition = definitions[rawWord]
                if (definition != null) {
                    callDefinition(rawWord, token)
                } else {
                    errorOrWarning(token, "Unknown StackSL word '$raw'.")
                }
            }
        }
    }

    private fun currentInputId(): Int = inputStack.lastOrNull() ?: leaf(options.inputName, null, "function input")

    private fun inputExpr(token: Token, note: String, build: (String) -> String): Int {
        val input = currentInputId()
        val expression = build(ref(input))
        val value = values[input]
        return if (value.leaf && expression == value.expression) input else expr(expression, listOf(input), token, note)
    }

    private fun storeValue(token: Token): Int {
        val sampler = options.sampleTextures.firstOrNull()
        if (sampler == null) {
            errorOrWarning(token, "Store words need at least one sample texture.")
            return leaf("vec4(0.0)", token, "missing store")
        }
        requiredSamplerIndices += 0
        requiredHelpers += "sample"
        val base = currentInputId()
        val offset = leaf("vec4(0.0)", token, "store zero offset")
        return expr(
            "${options.prefix}_sample(${sampler.emittedName}, ${ref(base)}, ${ref(offset)})",
            listOf(base, offset),
            token,
            "store read"
        )
    }

    private fun readStoreChannel(channel: Int, token: Token) {
        val store = storeValue(token)
        val swizzle = "xyzw"[channel]
        push(expr("vec4(${ref(store)}.$swizzle)", listOf(store), token, "store read $swizzle"))
    }

    private fun writeStoreChannel(channel: Int, token: Token) {
        channelWrites[channel] = pop(token, "store write ${"xyzw"[channel]}", "vec4(0.0)")
    }

    private fun callDefinition(word: String, token: Token) {
        val definition = definitions.getValue(word)
        if (functionWord == word || word in inlineCallStack) {
            errorOrWarning(token, "Recursive StackSL definition '${definition.name}' is not valid GLSL.")
            return
        }
        calls += definition.name.lowercase(Locale.US)
        val callInput = pop(token, "function input", "input")
        val callerStack = stack.toList()
        stack.clear()
        stack += callInput
        inputStack += callInput
        inlineCallStack += word
        val result = try {
            definition.body.forEach { process(it) }
            stack.lastOrNull() ?: callInput
        } finally {
            inlineCallStack.removeAt(inlineCallStack.lastIndex)
            inputStack.removeAt(inputStack.lastIndex)
            stack.clear()
            stack += callerStack
        }
        push(result)
    }

    private fun constantFloat(number: String, token: Token?, note: String): String {
        return options.wires.ref(number, token, note)
    }

    private fun constantVec(number: String, token: Token?, note: String): Int {
        return leaf("vec4(${constantFloat(number, token, note)})", token, note)
    }

    private fun pushNumberSuffix(number: String, suffix: String, token: Token) {
        val amount = constantFloat(number, token, "number suffix $suffix")
        when (suffix.lowercase(Locale.US)) {
            "t" -> push(leaf("(vec4(${options.inputName}.w) * vec4($amount))", token, "time-scaled number"))
            "r" -> push(leaf("vec4($amount, 0.0, 0.0, 0.0)", token, "red channel number"))
            "g" -> push(leaf("vec4(0.0, $amount, 0.0, 0.0)", token, "green channel number"))
            "b" -> push(leaf("vec4(0.0, 0.0, $amount, 0.0)", token, "blue channel number"))
            "a" -> push(leaf("vec4(0.0, 0.0, 0.0, $amount)", token, "alpha channel number"))
        }
    }

    private fun pushNumberWave(parsed: ParsedModifier, token: Token) {
        val wave = parsed.modifier
        val amount = constantFloat(parsed.amount ?: "1.0", token, "$wave amount")
        val factor = modifierFactor(parsed, token, null)
        push(leaf("(vec4($amount) * $factor)", token, "$wave wave number"))
    }

    private fun pushNumberModifier(parsed: ParsedModifier, token: Token) {
        val modifier = parsed.modifier
        val amount = constantFloat(parsed.amount ?: "1.0", token, "$modifier amount")
        val factor = modifierFactor(parsed, token, null)
        push(leaf("(vec4($amount) * $factor)", token, "$modifier modifier number"))
    }

    private fun applyTopModifier(parsed: ParsedModifier, token: Token) {
        val modifier = parsed.modifier
        val value = pop(token, "${token.text} source", "vec4(1.0)")
        val valueRef = ref(value)
        val expression = if (parsed.mode == "]") {
            modifierFactor(parsed, token, valueRef)
        } else {
            val factor = modifierFactor(parsed, token, null)
            "($valueRef * $factor)"
        }
        push(expr(expression, listOf(value), token, "top modifier $modifier"))
    }

    private fun modifierFactor(parsed: ParsedModifier, token: Token, driver: String?): String {
        val modifier = parsed.modifier
        val frequency = parsed.frequency?.let { constantFloat(it, token, "$modifier frequency") } ?: "1.0"
        val phaseOffset = parsed.phase?.let { constantFloat(it, token, "$modifier phase") } ?: "0.0"
        val duty = parsed.duty?.let { constantFloat(it, token, "$modifier duty") } ?: "0.5"
        val scalarDriver = if (driver != null) "$driver.x" else "${options.inputName}.w"
        val coordDriver = if (driver != null) "$driver.xy" else "${options.inputName}.xy"
        val hashDriver = if (driver != null) "$driver.w" else "${options.inputName}.z"
        val centered = if (driver != null) "($coordDriver)" else "($coordDriver - vec2(0.5))"

        fun scalarFactor(expression: String): String = "vec4($expression)"
        fun affine(expression: String): String = "(($expression) * $frequency + $phaseOffset)"

        when (modifier) {
            "t" -> return scalarFactor(scalarDriver)
            "r" -> return "vec4(${if (driver != null) "$driver.x" else "1.0"}, 0.0, 0.0, 0.0)"
            "g" -> return "vec4(0.0, ${if (driver != null) "$driver.x" else "1.0"}, 0.0, 0.0)"
            "b" -> return "vec4(0.0, 0.0, ${if (driver != null) "$driver.x" else "1.0"}, 0.0)"
            "a" -> return "vec4(0.0, 0.0, 0.0, ${if (driver != null) "$driver.x" else "1.0"})"
            "x" -> return scalarFactor(affine("$coordDriver.x"))
            "y" -> return scalarFactor(affine("$coordDriver.y"))
            "cx" -> return scalarFactor(affine("$centered.x"))
            "cy" -> return scalarFactor(affine("$centered.y"))
            "hash" -> return scalarFactor(affine(hashDriver))
            "rad" -> return scalarFactor(affine("length($centered)"))
            "ang" -> return scalarFactor("fract(${affine("(atan($centered.y, $centered.x) / 6.283185307179586 + 0.5)")})")
        }

        val phase = "($scalarDriver * $frequency + $phaseOffset)"
        val cycle = "fract($phase / 6.283185307179586)"
        when (modifier) {
            "c" -> return scalarFactor("((cos($phase) + 1.0) * 0.5)")
            "s" -> return scalarFactor("((sin($phase) + 1.0) * 0.5)")
            "sq" -> return scalarFactor("step(0.0, sin($phase))")
            "saw" -> return scalarFactor(cycle)
            "tri" -> return scalarFactor("(1.0 - abs($cycle * 2.0 - 1.0))")
            "pulse" -> return scalarFactor("(1.0 - step(clamp($duty, 0.0, 1.0), $cycle))")
            "sh" -> {
                requiredHelpers += "valueNoise"
                return scalarFactor("${options.prefix}_hash11(floor($phase))")
            }
            "n" -> {
                requiredHelpers += "valueNoise"
                return scalarFactor("${options.prefix}_valueNoise1($phase)")
            }
            "j" -> {
                requiredHelpers += "valueNoise"
                return scalarFactor("${options.prefix}_hash11(floor($phase))")
            }
        }

        val coords = "($coordDriver * $frequency + vec2($phaseOffset))"
        when (modifier) {
            "wx" -> return scalarFactor("((sin(($coordDriver.x * $frequency) * 6.283185307179586 + $phaseOffset) + 1.0) * 0.5)")
            "wy" -> return scalarFactor("((sin(($coordDriver.y * $frequency) * 6.283185307179586 + $phaseOffset) + 1.0) * 0.5)")
            "wr" -> return scalarFactor("((sin((length($centered) * $frequency) * 6.283185307179586 + $phaseOffset) + 1.0) * 0.5)")
            "wa" -> return scalarFactor("((sin(((atan($centered.y, $centered.x) / 6.283185307179586 + 0.5) * $frequency) * 6.283185307179586 + $phaseOffset) + 1.0) * 0.5)")
            "noise" -> {
                requiredHelpers += "noise"
                return scalarFactor("${options.prefix}_noise($coords)")
            }
            "fbm" -> {
                requiredHelpers += "fbm"
                return scalarFactor("${options.prefix}_fbm($coords)")
            }
            "grain" -> {
                requiredHelpers += "noise"
                return scalarFactor("${options.prefix}_hash21($coords + vec2(${options.inputName}.w))")
            }
        }

        errorOrWarning(token, "Unknown StackSL modifier '$modifier'.")
        return "vec4(1.0)"
    }

    private fun pushNumberRange(start: String, end: String, token: Token) {
        val a = start.toDoubleOrNull() ?: 0.0
        val b = end.toDoubleOrNull() ?: a
        val parts = (0 until 4).map { index ->
            val value = a + (b - a) * index / 3.0
            constantFloat(value.formatFloat(), token, "range vector")
        }
        push(leaf("vec4(${parts.joinToString(", ")})", token, "range vector"))
    }

    private fun shouldReduceTop(): Boolean {
        if (stack.size == 1) return true
        if (stack.size == 2) {
            val bottom = values[stack[0]]
            return bottom.leaf && bottom.note == "function input"
        }
        return false
    }

    private fun sampleWord(word: String, token: Token) {
        val wrapped = word.endsWith(".w")
        val baseWord = word.removeSuffix(".w")
        val gather = baseWord.startsWith("gather")
        val absolute = baseWord.endsWith("-abs")
        val samplerText = baseWord
            .removePrefix("sample")
            .removePrefix("gather")
            .removeSuffix("-abs")
        val index = if (samplerText.isBlank()) 0 else samplerText.toIntOrNull() ?: 0
        if (index !in options.sampleTextures.indices) {
            errorOrWarning(token, "Sample word '$word' asks for texture $index, but only ${options.sampleTextures.size} texture(s) were configured.")
        }
        val samplerIndex = index.coerceIn(0, options.sampleTextures.lastIndex)
        val sampler = options.sampleTextures[samplerIndex]
        requiredSamplerIndices += samplerIndex
        val offset = if (baseWord == "prev" || baseWord == "state") {
            leaf("vec4(0.0)", token, "zero sample offset")
        } else {
            pop(token, if (absolute) "sample coord" else "sample offset", "vec4(0.0)")
        }
        val helper = when {
            gather && wrapped -> "gatherWrap"
            gather -> "gather"
            absolute && wrapped -> "sampleAbsWrap"
            absolute -> "sampleAbs"
            wrapped -> "sampleWrap"
            else -> "sample"
        }
        requiredHelpers += helper
        val base = currentInputId()
        val call = when (helper) {
            "gatherWrap" -> "${options.prefix}_gather_w(${sampler.emittedName}, ${ref(base)}, ${ref(offset)})"
            "gather" -> "${options.prefix}_gather(${sampler.emittedName}, ${ref(base)}, ${ref(offset)})"
            "sampleAbsWrap" -> "${options.prefix}_sampleAbs_w(${sampler.emittedName}, ${ref(offset)})"
            "sampleAbs" -> "${options.prefix}_sampleAbs(${sampler.emittedName}, ${ref(offset)})"
            "sampleWrap" -> "${options.prefix}_sample_w(${sampler.emittedName}, ${ref(base)}, ${ref(offset)})"
            else -> "${options.prefix}_sample(${sampler.emittedName}, ${ref(base)}, ${ref(offset)})"
        }
        val deps = if (helper == "sampleAbs" || helper == "sampleAbsWrap") listOf(offset) else listOf(base, offset)
        push(expr(call, deps, token, helper))
    }

    private fun compare(token: Token, glslPredicate: String) {
        binary(token, glslPredicate) { a, b ->
            "mix(vec4(0.0), vec4(1.0), $glslPredicate(${ref(a)}, ${ref(b)}))"
        }
    }

    private fun vecLiteral(numbers: List<String>, token: Token): Int {
        val values = numbers.map { constantFloat(it, token, "vec literal") }
        val expression = when (values.size) {
            1 -> "vec4(${values[0]})"
            2 -> "vec4(${values[0]}, ${values[1]}, 0.0, 1.0)"
            3 -> "vec4(${values[0]}, ${values[1]}, ${values[2]}, 1.0)"
            else -> "vec4(${values[0]}, ${values[1]}, ${values[2]}, ${values[3]})"
        }
        return leaf(expression, token, "vec${values.size} literal")
    }

    private fun constructVec(token: Token, size: Int) {
        val parts = popOperands(
            token = token,
            roles = (0 until size).map { "vec$size component" },
            defaults = (0 until size).map { "vec4(0.0)" }
        ).reversed()
        val exprText = when (size) {
            2 -> "vec4(${ref(parts[0])}.x, ${ref(parts[1])}.x, 0.0, 1.0)"
            3 -> "vec4(${ref(parts[0])}.x, ${ref(parts[1])}.x, ${ref(parts[2])}.x, 1.0)"
            else -> "vec4(${ref(parts[0])}.x, ${ref(parts[1])}.x, ${ref(parts[2])}.x, ${ref(parts[3])}.x)"
        }
        push(expr(exprText, parts, token, "vec$size"))
    }

    private fun rotate(token: Token) {
        binary(token, "rotate") { a, b ->
            val av = ref(a)
            val bv = ref(b)
            """
            vec4(
                $av.x * cos($bv.x) - $av.y * sin($bv.x),
                $av.x * sin($bv.x) + $av.y * cos($bv.x),
                $av.z * cos($bv.y) - $av.w * sin($bv.y),
                $av.z * sin($bv.y) + $av.w * cos($bv.y)
            )
            """.trimIndent().replace("\n", " ")
        }
    }

    private fun rotates(token: Token) {
        val operands = popOperands(token, listOf("rotates angle-scale", "rotates center", "rotates value"))
        val c = operands[0]
        val b = operands[1]
        val a = operands[2]
        val av = ref(a)
        val bv = ref(b)
        val cv = ref(c)
        val expression = """
            vec4(
                $bv.x + (($av.x - $bv.x) * cos($cv.x) - ($av.y - $bv.y) * sin($cv.x)) * $cv.y,
                $bv.y + (($av.x - $bv.x) * sin($cv.x) + ($av.y - $bv.y) * cos($cv.x)) * $cv.y,
                $bv.z + (($av.z - $bv.z) * cos($cv.z) - ($av.w - $bv.w) * sin($cv.z)) * $cv.w,
                $bv.w + (($av.z - $bv.z) * sin($cv.z) + ($av.w - $bv.w) * cos($cv.z)) * $cv.w
            )
        """.trimIndent().replace("\n", " ")
        push(expr(expression, listOf(a, b, c), token, "rotates"))
    }

    private fun standoff(word: String, token: Token) {
        val operands = popOperands(token, listOf("$word amount", "$word anchor", "$word value"))
        val c = operands[0]
        val b = operands[1]
        val a = operands[2]
        val helper = when (word) {
            "standoff2" -> "${options.prefix}_safeNormalize2"
            "standoff3" -> "${options.prefix}_safeNormalize3"
            else -> "${options.prefix}_safeNormalize"
        }
        push(expr("($helper(${ref(a)} - ${ref(b)}) * ${ref(c)} + ${ref(b)})", listOf(a, b, c), token, word))
    }

    private fun replaceLane(channel: Char, token: Token) {
        binary(token, "replace $channel") { a, b ->
            val av = ref(a)
            val bv = ref(b)
            val channels = "xyzw"
            "vec4(${channels.map { if (it == channel) "$bv.$it" else "$av.$it" }.joinToString(", ")})"
        }
    }

    private fun unary(token: Token, note: String, op: (Int) -> String) {
        val value = pop(token, note)
        push(expr(op(value), listOf(value), token, note))
    }

    private fun binary(token: Token, note: String, op: (Int, Int) -> String) {
        val operands = popOperands(token, listOf("$note b", "$note a"))
        val b = operands[0]
        val a = operands[1]
        push(expr(op(a, b), listOf(a, b), token, note))
    }

    private data class StackSLArg(val role: String, val defaultExpression: String)

    private fun consumeArityArgs(token: Token, word: String, args: List<StackSLArg>, arity: Int): List<Int>? {
        if (arity !in 1..args.size) {
            unsupportedArity(token, word, word.substringBefore('.'), arity, (1..args.size).toSet())
            return null
        }

        val available = minOf(arity, stack.size)
        val consumed = if (available == 0) {
            emptyList()
        } else {
            val start = stack.size - available
            val values = stack.subList(start, stack.size).toList()
            repeat(available) { stack.removeAt(stack.lastIndex) }
            values
        }

        val missing = if (available < arity) {
            args.drop(available).take(arity - available).mapIndexed { index, arg ->
                defaultValue(token, "$word ${arg.role}", available + index, arg.defaultExpression)
            }
        } else {
            emptyList()
        }

        val defaults = args.drop(arity).map { arg ->
            optionalDefault(token, "$word ${arg.role}", arg.defaultExpression)
        }

        return consumed + missing + defaults
    }

    private fun unsupportedArity(token: Token, word: String, base: String, arity: Int?, supported: Set<Int>) {
        val suffix = arity?.let { ".$it" } ?: ""
        errorOrWarning(token, "Unsupported StackSL arity '$base$suffix'; supported arities are ${supported.sorted().joinToString(", ")}.")
    }

    private fun consumeBlastArgs(token: Token, word: String, requestedArity: Int?, requestedAll: Boolean): List<Int>? {
        val count = if (requestedAll) maxOf(2, stack.size) else requestedArity ?: maxOf(2, stack.size)
        if (!requestedAll && count !in 2..8) {
            errorOrWarning(token, "Unsupported StackSL blast arity '$word'; supported variants are .2 through .8 and .all.")
            return null
        }

        val available = minOf(count, stack.size)
        val consumed = if (available == 0) {
            emptyList()
        } else {
            val start = stack.size - available
            val values = stack.subList(start, stack.size).toList()
            repeat(available) { stack.removeAt(stack.lastIndex) }
            values
        }

        val missing = (available until count).map { index ->
            defaultValue(token, "$word blast value", index, "input")
        }
        return consumed + missing
    }

    private fun blastMeanExpression(args: List<Int>): String {
        return "((${args.joinToString(" + ") { ref(it) }}) / vec4(${args.size.toDouble().formatFloat()}))"
    }

    private fun blastReduceExpression(args: List<Int>, op: String): String {
        return args.drop(1).fold(ref(args.first())) { acc, id -> "$op($acc, ${ref(id)})" }
    }

    private fun blastSort(token: Token, word: String, args: List<Int>): List<Int> {
        val sorted = args.toMutableList()
        for (i in 1 until sorted.size) {
            var j = i
            while (j > 0) {
                val a = sorted[j - 1]
                val b = sorted[j]
                val low = expr("min(${ref(a)}, ${ref(b)})", listOf(a, b), token, "$word sort low")
                val high = expr("max(${ref(a)}, ${ref(b)})", listOf(a, b), token, "$word sort high")
                sorted[j - 1] = low
                sorted[j] = high
                j--
            }
        }
        return sorted
    }

    private fun blastMedian(token: Token, word: String, args: List<Int>): Int {
        val sorted = blastSort(token, word, args)
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            val a = sorted[middle - 1]
            val b = sorted[middle]
            expr("((${ref(a)} + ${ref(b)}) * vec4(0.5))", listOf(a, b), token, "$word median")
        }
    }

    private fun popOperands(
        token: Token,
        roles: List<String>,
        defaults: List<String?> = emptyList(),
        wrap: Boolean = true
    ): List<Int> {
        val before = stack.toList()
        var warned = false
        return roles.mapIndexed { index, role ->
            if (stack.isNotEmpty()) {
                stack.removeAt(stack.lastIndex)
            } else {
                val wrapped = if (wrap && before.isNotEmpty()) {
                    before[(before.size - 1 - index).floorMod(before.size)]
                } else {
                    null
                }
                if (wrapped != null) {
                    if (!warned) {
                        warn(token, "Word '${token.text}' needed ${roles.size} values but found ${before.size}; reusing wrapped stack values.")
                        warned = true
                    }
                    wrapped
                } else {
                    defaultValue(token, role, index, defaults.getOrNull(index))
                }
            }
        }
    }

    private fun swap(token: Token) {
        ensureStackSize(2, token, "swap")
        val last = stack.lastIndex
        val tmp = stack[last]
        stack[last] = stack[last - 1]
        stack[last - 1] = tmp
    }

    private fun over(token: Token) {
        ensureStackSize(2, token, "over")
        push(stack[stack.lastIndex - 1])
    }

    private fun rot(token: Token) {
        ensureStackSize(3, token, "rot")
        val n = stack.size
        val a = stack[n - 3]
        stack[n - 3] = stack[n - 2]
        stack[n - 2] = stack[n - 1]
        stack[n - 1] = a
    }

    private fun nip(token: Token) {
        ensureStackSize(2, token, "nip")
        stack.removeAt(stack.lastIndex - 1)
    }

    private fun tuck(token: Token) {
        ensureStackSize(2, token, "tuck")
        val top = stack.last()
        stack.add(stack.lastIndex - 1, top)
        observeDepth()
    }

    private fun ensureStackSize(size: Int, token: Token, word: String) {
        val before = stack.toList()
        if (stack.size >= size) return
        warn(token, "Word '$word' needed stack depth $size but found ${stack.size}; filling by wrap/default convention.")
        while (stack.size < size) {
            val wrapped = if (before.isNotEmpty()) before[(before.size - 1 - stack.size).floorMod(before.size)] else null
            stack += wrapped ?: defaultValue(token, word, stack.size, null)
        }
        observeDepth()
    }

    private fun peekOrDefault(token: Token, role: String): Int {
        if (stack.isNotEmpty()) return stack.last()
        warn(token, "Word '$role' read an empty stack; using default input.")
        return defaultValue(token, role, 0, "input")
    }

    private fun pop(token: Token?, role: String, defaultExpression: String? = null, wrap: Boolean = true): Int {
        if (stack.isNotEmpty()) return stack.removeAt(stack.lastIndex)
        return defaultValue(token, role, 0, defaultExpression, wrapWarning = true)
    }

    private fun optionalDefault(token: Token?, role: String, defaultExpression: String): Int {
        return leaf(defaultExpression.glslDefault(token, "optional default $role"), token, "optional default $role")
    }

    private fun defaultValue(
        token: Token?,
        role: String,
        index: Int,
        overrideExpression: String?,
        wrapWarning: Boolean = true
    ): Int {
        if (wrapWarning) warn(token, "Stack underflow while reading $role; using a default value.")
        val expression = (overrideExpression ?: options.defaultValues[index.floorMod(options.defaultValues.size)])
            .glslDefault(token, "default $role")
        return leaf(expression, token, "default $role")
    }

    private fun String.glslDefault(token: Token?, note: String): String {
        return options.wires.replaceNumbers(glslInputIdentifier.replace(this, options.inputName), token, note)
    }

    private fun push(value: Int) {
        stack += value
        observeDepth()
    }

    private fun observeDepth() {
        if (stack.size > maxStackDepth) maxStackDepth = stack.size
    }

    private fun leaf(expression: String, token: Token?, note: String): Int {
        val id = values.size
        values += StackValue(id, expression, emptyList(), token, leaf = true, note = note)
        return id
    }

    private fun expr(expression: String, deps: List<Int>, token: Token, note: String): Int {
        expressionCache[expression]?.let { return it }
        val id = values.size
        values += StackValue(id, expression, deps, token, leaf = false, note = note)
        expressionCache[expression] = id
        return id
    }

    private fun ref(id: Int): String {
        val value = values[id]
        return if (value.leaf) value.expression else localName(id)
    }

    private fun localName(id: Int): String = "_${options.prefix}_v$id"

    private fun markReachable(id: Int, reachable: MutableSet<Int>) {
        if (!reachable.add(id)) return
        values[id].deps.forEach { markReachable(it, reachable) }
    }

    private fun warn(token: Token?, message: String) {
        diagnostics += StackSLDiagnostic(StackSLSeverity.WARNING, message, token?.location, token?.text)
    }

    private fun errorOrWarning(token: Token, message: String) {
        diagnostics += StackSLDiagnostic(
            if (options.tolerant) StackSLSeverity.WARNING else StackSLSeverity.ERROR,
            if (options.tolerant) "$message Treating it as noop." else message,
            token.location,
            token.text
        )
    }
}

private fun tokenize(source: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var i = 0
    var line = 1
    var column = 1

    fun advance(ch: Char) {
        i++
        if (ch == '\n') {
            line++
            column = 1
        } else {
            column++
        }
    }

    fun skipLineComment() {
        while (i < source.length && source[i] != '\n') advance(source[i])
    }

    while (i < source.length) {
        val ch = source[i]
        when {
            ch.isWhitespace() -> advance(ch)
            ch == '#' -> skipLineComment()
            ch == '/' && i + 1 < source.length && source[i + 1] == '/' -> skipLineComment()
            ch == '(' -> {
                while (i < source.length && source[i] != ')') advance(source[i])
                if (i < source.length) advance(source[i])
            }
            else -> {
                val startLine = line
                val startColumn = column
                val startIndex = i
                val text = buildString {
                    while (i < source.length) {
                        val c = source[i]
                        if (c.isWhitespace() || c == '#' || c == '(') break
                        if (c == '/' && i + 1 < source.length && source[i + 1] == '/') break
                        append(c)
                        advance(c)
                    }
                }
                if (text.isNotEmpty()) tokens += Token(text, StackSLSourceLocation(startLine, startColumn, startIndex))
            }
        }
    }
    return tokens
}

private fun parse(tokens: List<Token>, diagnostics: MutableList<StackSLDiagnostic>): ParsedProgram {
    val root = mutableListOf<Token>()
    val definitions = mutableListOf<Definition>()
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        if (token.text == ":") {
            val name = tokens.getOrNull(index + 1)
            if (name == null) {
                diagnostics += StackSLDiagnostic(StackSLSeverity.ERROR, "Expected a definition name after ':'.", token.location, token.text)
                break
            }
            val body = mutableListOf<Token>()
            index += 2
            while (index < tokens.size && tokens[index].text != ";") {
                body += tokens[index]
                index++
            }
            if (index >= tokens.size) {
                diagnostics += StackSLDiagnostic(StackSLSeverity.ERROR, "Definition '${name.text}' is missing a terminating ';'.", name.location, name.text)
            } else {
                index++
            }
            definitions += Definition(name.text, body, name.location)
        } else if (token.text == ";") {
            diagnostics += StackSLDiagnostic(StackSLSeverity.ERROR, "Unexpected ';' outside a StackSL definition.", token.location, token.text)
            index++
        } else {
            root += token
            index++
        }
    }
    return ParsedProgram(root, definitions)
}

private fun detectCallCycles(functions: List<CompiledFunction>, diagnostics: MutableList<StackSLDiagnostic>) {
    val graph = functions.associate { it.analysis.name.lowercase(Locale.US) to it.analysis.calls }
    val visiting = linkedSetOf<String>()
    val visited = linkedSetOf<String>()

    fun dfs(node: String): Boolean {
        if (node in visiting) return true
        if (!visited.add(node)) return false
        visiting += node
        val cycle = graph[node].orEmpty().any { dfs(it) }
        visiting -= node
        return cycle
    }

    graph.keys.forEach { name ->
        if (dfs(name)) {
            diagnostics += StackSLDiagnostic(StackSLSeverity.ERROR, "Recursive StackSL call cycle includes '$name'; GLSL recursion is not supported.")
        }
    }
}

private val numberPattern = """[+-]?(?:(?:\d+\.\d*)|(?:\d*\.\d+)|(?:\d+))(?:[eE][+-]?\d+)?"""
private val numberRegex = Regex("^$numberPattern$")
private val numberSuffixRegex = Regex("^($numberPattern)([trgba])$", RegexOption.IGNORE_CASE)
private val numberWaveRegex = Regex(
    "^($numberPattern)(c|s|sq|saw|tri|pulse)($numberPattern)?(?:p($numberPattern))?(?:d($numberPattern))?$",
    RegexOption.IGNORE_CASE
)
private val modifierAlternatives = "pulse|noise|grain|hash|saw|tri|fbm|rad|ang|wx|wy|wr|wa|cx|cy|sq|sh|n|j|c|s|t|r|g|b|a|x|y"
private val numberModifierRegex = Regex(
    "^($numberPattern)($modifierAlternatives)($numberPattern)?(?:p($numberPattern))?(?:d($numberPattern))?$",
    RegexOption.IGNORE_CASE
)
private val topModifierRegex = Regex(
    "^(\\||\\])($modifierAlternatives)($numberPattern)?(?:p($numberPattern))?(?:d($numberPattern))?$",
    RegexOption.IGNORE_CASE
)
private val numberRangeRegex = Regex("^($numberPattern)_($numberPattern)$", RegexOption.IGNORE_CASE)
private val vecLiteralRegex = Regex("""^\[\s*($numberPattern)(?:\s*,\s*($numberPattern))?(?:\s*,\s*($numberPattern))?(?:\s*,\s*($numberPattern))?\s*]$""")
private val defaultNumberRegex = Regex("""(?<![A-Za-z_])($numberPattern)(?![A-Za-z_])""")
private val definitionNameRegex = Regex("""^[A-Za-z_][A-Za-z0-9_-]*$""")
private val stackSLArityVariantRegex = Regex("""^(.+)\.(\d+|all)$""")
private val stackSLArityVariantArities = mapOf(
    "min" to (1..8).toSet(),
    "max" to (1..8).toSet(),
    "average" to (2..8).toSet(),
    "avg" to (2..8).toSet(),
    "mean" to (2..8).toSet(),
    "median" to (2..8).toSet(),
    "med" to (2..8).toSet(),
    "sort" to (2..8).toSet(),
    "intermediate" to (2..8).toSet(),
    "pulse" to (1..3).toSet(),
    "fm" to (1..3).toSet(),
    "fmchirp" to (1..9).toSet(),
    "palette" to (1..5).toSet(),
    "cos-palette" to (1..5).toSet(),
    "cosine-palette" to (1..5).toSet(),
    "saturation" to (1..2).toSet(),
    "sat-color" to (1..2).toSet(),
    "contrast" to (1..2).toSet(),
    "grade" to (1..4).toSet(),
    "ema" to (1..3).toSet(),
    "ema.c" to (1..3).toSet(),
    "ema.sc" to (1..3).toSet(),
    "ema-diff" to (1..3).toSet(),
    "ema-diff.c" to (1..3).toSet(),
    "ema-diff.sc" to (1..3).toSet(),
    "diff-ema" to (1..3).toSet(),
    "diff-ema.c" to (1..3).toSet(),
    "diff-ema.sc" to (1..3).toSet(),
    "dema" to (1..3).toSet(),
    "dema.c" to (1..3).toSet(),
    "dema.sc" to (1..3).toSet(),
    "bitcrush" to (1..2).toSet(),
    "bit-crush" to (1..2).toSet()
)
private val stackSLAllVariantWords = setOf(
    "min", "max",
    "average", "avg", "mean",
    "median", "med",
    "sort", "intermediate"
)
private val stackSLArityVariantWords = stackSLArityVariantArities.flatMap { (word, arities) ->
    arities.map { "$word.$it" } + if (word in stackSLAllVariantWords) listOf("$word.all") else emptyList()
}.toSet()

private data class ParsedModifier(
    val mode: String?,
    val amount: String?,
    val modifier: String,
    val frequency: String?,
    val phase: String?,
    val duty: String?
)

private fun parseNumberLiteral(text: String): String? {
    if (!numberRegex.matches(text)) return null
    return normalizeFloat(text)
}

private fun parseVecLiteral(text: String): List<String>? {
    val match = vecLiteralRegex.matchEntire(text) ?: return null
    return match.groupValues
        .drop(1)
        .filter { it.isNotEmpty() }
        .map(::normalizeFloat)
}

private fun parseStackSLArityVariant(text: String): StackSLArityVariant? {
    val match = stackSLArityVariantRegex.matchEntire(text) ?: return null
    val base = match.groupValues[1]
    val rawArity = match.groupValues[2]
    if (rawArity == "all") {
        if (base !in stackSLAllVariantWords) return null
        return StackSLArityVariant(base, null, all = true)
    }
    val arity = rawArity.toIntOrNull() ?: return null
    if (base !in stackSLArityVariantArities) return null
    return StackSLArityVariant(base, arity)
}

private fun parseNumberSuffix(text: String): Pair<String, String>? {
    val match = numberSuffixRegex.matchEntire(text) ?: return null
    return normalizeFloat(match.groupValues[1]) to match.groupValues[2].lowercase(Locale.US)
}

private fun parseNumberWave(text: String): ParsedModifier? {
    val match = numberWaveRegex.matchEntire(text) ?: return null
    return ParsedModifier(
        mode = null,
        amount = normalizeFloat(match.groupValues[1]),
        modifier = match.groupValues[2].lowercase(Locale.US),
        frequency = match.groupValues[3].ifBlank { null }?.let(::normalizeFloat),
        phase = match.groupValues[4].ifBlank { null }?.let(::normalizeFloat),
        duty = match.groupValues[5].ifBlank { null }?.let(::normalizeFloat)
    )
}

private fun parseNumberModifier(text: String): ParsedModifier? {
    val match = numberModifierRegex.matchEntire(text) ?: return null
    return ParsedModifier(
        mode = null,
        amount = normalizeFloat(match.groupValues[1]),
        modifier = match.groupValues[2].lowercase(Locale.US),
        frequency = match.groupValues[3].ifBlank { null }?.let(::normalizeFloat),
        phase = match.groupValues[4].ifBlank { null }?.let(::normalizeFloat),
        duty = match.groupValues[5].ifBlank { null }?.let(::normalizeFloat)
    )
}

private fun parseTopModifier(text: String): ParsedModifier? {
    val match = topModifierRegex.matchEntire(text) ?: return null
    return ParsedModifier(
        mode = match.groupValues[1],
        amount = null,
        modifier = match.groupValues[2].lowercase(Locale.US),
        frequency = match.groupValues[3].ifBlank { null }?.let(::normalizeFloat),
        phase = match.groupValues[4].ifBlank { null }?.let(::normalizeFloat),
        duty = match.groupValues[5].ifBlank { null }?.let(::normalizeFloat)
    )
}

private fun parseNumberRange(text: String): Pair<String, String>? {
    val match = numberRangeRegex.matchEntire(text) ?: return null
    return normalizeFloat(match.groupValues[1]) to normalizeFloat(match.groupValues[2])
}

private fun normalizeFloat(text: String): String {
    return if (text.contains('.') || text.contains('e', ignoreCase = true)) text else "$text.0"
}

private fun Double.formatFloat(): String {
    val text = String.format(Locale.US, "%.8f", this).trimEnd('0').trimEnd('.')
    return if (text.contains('.')) text else "$text.0"
}

private fun parseSwizzle(word: String): String? {
    val body = if (word.startsWith(".")) word.drop(1) else word
    if (body.length !in 1..4) return null
    if (body !in swizzleWords) return null
    return body.map {
        when (it) {
            'r' -> 'x'
            'g' -> 'y'
            'b' -> 'z'
            'a' -> 'w'
            else -> it
        }
    }.joinToString("")
}

private fun parseExternalRead(word: String): String? {
    if (!word.startsWith("?")) return null
    val name = word.drop(1)
    return name.takeIf { hostGlslVec4Regex.matches(it) }
}

private fun parseExternalWrite(word: String): String? {
    if (!word.startsWith("->?")) return null
    val name = word.drop(3)
    return name.takeIf { hostGlslVec4Regex.matches(it) }
}

private fun swizzleExpression(value: String, swizzle: String): String = when (swizzle.length) {
    1 -> "vec4($value.${swizzle[0]})"
    2 -> "vec4($value.$swizzle, 0.0, 1.0)"
    3 -> "vec4($value.$swizzle, 1.0)"
    else -> "$value.$swizzle"
}

private fun sanitizeGlslIdentifier(name: String): String {
    val cleaned = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
    val nonEmpty = cleaned.ifBlank { "stacksl" }
    return if (nonEmpty.first().isLetter() || nonEmpty.first() == '_') nonEmpty else "_$nonEmpty"
}

private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

private val swizzleWords: Set<String> = buildSet {
    val chars = listOf("x", "y", "z", "w", "r", "g", "b", "a")
    fun build(prefix: String, depth: Int) {
        if (depth > 0) add(prefix)
        if (depth == 4) return
        chars.forEach { build(prefix + it, depth + 1) }
    }
    build("", 0)
}

private val hostGlslIdentifierPattern = """[A-Za-z_][A-Za-z0-9_]*"""
private val hostGlslIndexPattern = """\[[^\]\s]+\]"""
private val hostGlslVec4Regex = Regex("""$hostGlslIdentifierPattern(?:$hostGlslIndexPattern)*(?:\.$hostGlslIdentifierPattern(?:$hostGlslIndexPattern)*)*""")

private val namedColors = mapOf(
    "black" to "vec4(0.0, 0.0, 0.0, 1.0)",
    "white" to "vec4(1.0, 1.0, 1.0, 1.0)",
    "gray" to "vec4(0.5, 0.5, 0.5, 1.0)",
    "grey" to "vec4(0.5, 0.5, 0.5, 1.0)",
    "red" to "vec4(1.0, 0.0, 0.0, 1.0)",
    "green" to "vec4(0.0, 1.0, 0.0, 1.0)",
    "blue" to "vec4(0.0, 0.0, 1.0, 1.0)",
    "cyan" to "vec4(0.0, 1.0, 1.0, 1.0)",
    "magenta" to "vec4(1.0, 0.0, 1.0, 1.0)",
    "yellow" to "vec4(1.0, 1.0, 0.0, 1.0)",
    "orange" to "vec4(1.0, 0.45, 0.0, 1.0)"
)

private val builtinWords = setOf(
    "noop", "input", "in", "uv", "p", "pos", "center", "centered", "time", "t", "hash", "h",
    "pi", "tau", "e",
    "dup", "drop", "swap", "over", "rot", "nip", "tuck",
    "vec2", "v2", "vec3", "v3", "vec4", "v4",
    "neg", "negate", "1-", "abs", "floor", "ceil", "fract", "sqrt", "inversesqrt", "exp", "log",
    "sin", "cos", "tan", "asin", "acos", "atan", "normalize",
    "len", "length", "len2", "length2", "len3", "length3", "len4", "length4", "luma",
    "average", "avg", "mean", "median", "med", "sort", "intermediate", "vmin", "minimum", "vmax", "maximum",
    "sat", "saturate", "clamp", "mirror", "tonemap",
    "+", "add", "-", "sub", "*", "mul", "/", "div", "mod", "pow", "min", "max", "atan2",
    "dot", "distance2", "distance3", "distance", "cross", "standoff", "standoff2", "standoff3", "rotate", "rotates", ">x", ">y", ">z", ">w", "lt", "<", "lte", "<=", "gt", ">", "gte", ">=", "eq", "=", "neq", "!=", "<>",
    "and", "or", "not", "xor", "bitxor", "gray-code", "graycode", "gray-encode", "gray-decode", "ungray", "lfsr", "bitcrush", "bit-crush",
    "mix", "lerp", "mix.c", "lerp.c", "mix.sc", "lerp.sc", "select", "if", "step", "smoothstep", "pow.safe",
    "noise", "perlin", "fbm", "pulse", "fm", "fmchirp", "palette", "cos-palette", "cosine-palette",
    "ryb", "ryb2rgb", "from-ryb", "rgb2ryb", "to-ryb", "ryb.c", "ryb2rgb.c", "from-ryb.c", "rgb2ryb.c", "to-ryb.c",
    "oklab", "oklab2rgb", "from-oklab", "rgb2oklab", "to-oklab",
    "saturation", "sat-color", "contrast", "grade",
    "alpha", "alpha.c", "alpha.sc",
    "ema", "ema.c", "ema.sc", "ema-diff", "ema-diff.c", "ema-diff.sc", "diff-ema", "diff-ema.c", "diff-ema.sc", "dema", "dema.c", "dema.sc",
    "sample", "sample0", "sample1", "sample2", "sample3",
    "sample.w", "sample0.w", "sample1.w", "sample2.w", "sample3.w",
    "sample-abs", "sample0-abs", "sample1-abs", "sample2-abs", "sample3-abs",
    "sample-abs.w", "sample0-abs.w", "sample1-abs.w", "sample2-abs.w", "sample3-abs.w",
    "prev", "state", "gather", "gather0", "gather1", "gather2", "gather3",
    "gather.w", "gather0.w", "gather1.w", "gather2.w", "gather3.w",
    "0->", "1->", "2->", "3->", "->0", "->1", "->2", "->3"
) + stackSLArityVariantWords + namedColors.keys + swizzleWords + swizzleWords.map { ".$it" }

private val bareSwizzleReservedWords = setOf("ryb")

private fun helperSource(prefix: String, helpers: Set<String>): String {
    if (helpers.isEmpty()) return ""
    return buildString {
        if ("softClamp01" in helpers) {
            appendLine("vec4 ${prefix}_softClamp01(vec4 x) {")
            appendLine("    vec4 k = vec4(5.0);")
            appendLine("    vec4 lo = vec4(1.0) / (vec4(1.0) + exp(k));")
            appendLine("    vec4 hi = vec4(1.0) / (vec4(1.0) + exp(-k));")
            appendLine("    vec4 y = vec4(1.0) / (vec4(1.0) + exp(-k * (x * vec4(2.0) - vec4(1.0))));")
            appendLine("    return clamp((y - lo) / (hi - lo), vec4(0.0), vec4(1.0));")
            appendLine("}")
            appendLine()
        }
        if ("safeDiv" in helpers || "safeMod" in helpers) {
            appendLine("vec4 ${prefix}_safeDiv(vec4 a, vec4 b) {")
            appendLine("    return a / mix(vec4(0.00001), b, greaterThan(abs(b), vec4(0.00001)));")
            appendLine("}")
            appendLine()
        }
        if ("safeMod" in helpers) {
            appendLine("vec4 ${prefix}_safeMod(vec4 a, vec4 b) {")
            appendLine("    vec4 safeB = mix(vec4(1.0), b, greaterThan(abs(b), vec4(0.00001)));")
            appendLine("    return mod(a, safeB);")
            appendLine("}")
            appendLine()
        }
        if ("safeNormalize" in helpers) {
            appendLine("vec4 ${prefix}_safeNormalize(vec4 value) {")
            appendLine("    return value / max(length(value), 0.00001);")
            appendLine("}")
            appendLine()
            appendLine("vec4 ${prefix}_safeNormalize2(vec4 value) {")
            appendLine("    return value / max(length(value.xy), 0.00001);")
            appendLine("}")
            appendLine()
            appendLine("vec4 ${prefix}_safeNormalize3(vec4 value) {")
            appendLine("    return value / max(length(value.xyz), 0.00001);")
            appendLine("}")
            appendLine()
        }
        if ("valueNoise" in helpers) {
            appendLine("float ${prefix}_hash11(float p) {")
            appendLine("    return fract(sin(p * 127.1) * 43758.5453123);")
            appendLine("}")
            appendLine()
            appendLine("float ${prefix}_valueNoise1(float p) {")
            appendLine("    float i = floor(p);")
            appendLine("    float f = fract(p);")
            appendLine("    float u = f * f * (3.0 - 2.0 * f);")
            appendLine("    return mix(${prefix}_hash11(i), ${prefix}_hash11(i + 1.0), u);")
            appendLine("}")
            appendLine()
        }
        if ("noise" in helpers || "fbm" in helpers) {
            appendLine("float ${prefix}_hash21(vec2 p) {")
            appendLine("    p = fract(p * vec2(123.34, 456.21));")
            appendLine("    p += dot(p, p + 45.32);")
            appendLine("    return fract(p.x * p.y);")
            appendLine("}")
            appendLine()
            appendLine("float ${prefix}_noise(vec2 p) {")
            appendLine("    vec2 i = floor(p);")
            appendLine("    vec2 f = fract(p);")
            appendLine("    vec2 u = f * f * (3.0 - 2.0 * f);")
            appendLine("    float a = ${prefix}_hash21(i + vec2(0.0, 0.0));")
            appendLine("    float b = ${prefix}_hash21(i + vec2(1.0, 0.0));")
            appendLine("    float c = ${prefix}_hash21(i + vec2(0.0, 1.0));")
            appendLine("    float d = ${prefix}_hash21(i + vec2(1.0, 1.0));")
            appendLine("    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);")
            appendLine("}")
            appendLine()
        }
        if ("fbm" in helpers) {
            appendLine("float ${prefix}_fbm(vec2 p) {")
            appendLine("    float value = 0.0;")
            appendLine("    float amplitude = 0.5;")
            appendLine("    mat2 warp = mat2(1.6, 1.2, -1.2, 1.6);")
            appendLine("    for (int i = 0; i < 5; i++) {")
            appendLine("        value += amplitude * ${prefix}_noise(p);")
            appendLine("        p = warp * p + 17.17;")
            appendLine("        amplitude *= 0.5;")
            appendLine("    }")
            appendLine("    return value;")
            appendLine("}")
            appendLine()
        }
        if ("pulse" in helpers) {
            appendLine("vec4 ${prefix}_pulse(vec4 x, vec4 center, vec4 width) {")
            appendLine("    vec4 w = max(abs(width), vec4(0.00001));")
            appendLine("    return smoothstep(center - w, center, x) - smoothstep(center, center + w, x);")
            appendLine("}")
            appendLine()
        }
        if ("fm" in helpers) {
            appendLine("vec4 ${prefix}_fm(vec4 phase, vec4 carrier, vec4 modulator) {")
            appendLine("    return sin(phase + modulator * sin(carrier));")
            appendLine("}")
            appendLine()
        }
        if ("fmchirp" in helpers) {
            appendLine("vec4 ${prefix}_fmchirpSignedPow(vec4 x, vec4 exponent) {")
            appendLine("    vec4 ax = abs(x);")
            appendLine("    vec4 e = max(exponent, vec4(0.0001));")
            appendLine("    vec4 y = pow(max(ax, vec4(0.00000001)), e);")
            appendLine("    return sign(x) * y;")
            appendLine("}")
            appendLine()
            appendLine("vec4 ${prefix}_fmchirp(vec4 x, vec4 freq, vec4 mod, vec4 modPhase, vec4 phase, vec4 chirp, vec4 modChirp, vec4 inputPow, vec4 outputPow) {")
            appendLine("    vec4 xWarped = ${prefix}_fmchirpSignedPow(x, inputPow);")
            appendLine("    vec4 xx = xWarped * xWarped;")
            appendLine("    vec4 modPhaseValue = 6.283185307179586 * (mod * xWarped + modPhase + modChirp * xx);")
            appendLine("    vec4 phaseValue = 6.283185307179586 * (freq * xWarped + phase + chirp * xx);")
            appendLine("    vec4 q = sin(phaseValue + sin(modPhaseValue));")
            appendLine("    return x + ${prefix}_fmchirpSignedPow(q, outputPow);")
            appendLine("}")
            appendLine()
        }
        if ("palette" in helpers) {
            appendLine("vec4 ${prefix}_palette(vec4 t, vec4 a, vec4 b, vec4 c, vec4 d) {")
            appendLine("    return a + b * cos(6.283185307179586 * (c * t + d));")
            appendLine("}")
            appendLine()
        }
        if ("saturation" in helpers) {
            appendLine("vec4 ${prefix}_saturation(vec4 color, float amount) {")
            appendLine("    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));")
            appendLine("    return vec4(mix(vec3(luma), color.rgb, amount), color.a);")
            appendLine("}")
            appendLine()
        }
        if ("contrast" in helpers) {
            appendLine("vec4 ${prefix}_contrast(vec4 color, vec4 amount) {")
            appendLine("    return (color - vec4(0.5)) * amount + vec4(0.5);")
            appendLine("}")
            appendLine()
        }
        if ("grade" in helpers) {
            appendLine("vec4 ${prefix}_grade(vec4 color, vec4 lift, vec4 gain, vec4 gamma) {")
            appendLine("    vec4 safeGamma = max(abs(gamma), vec4(0.0001));")
            appendLine("    return pow(max((color + lift) * gain, vec4(0.0)), safeGamma);")
            appendLine("}")
            appendLine()
        }
        if ("tonemap" in helpers) {
            appendLine("vec4 ${prefix}_tonemap(vec4 color) {")
            appendLine("    vec3 c = max(color.rgb, vec3(0.0));")
            appendLine("    c = c / (c + vec3(1.0));")
            appendLine("    return vec4(c, color.a);")
            appendLine("}")
            appendLine()
        }
        if ("reduce" in helpers) {
            appendLine("float ${prefix}_avg4(vec4 v) {")
            appendLine("    return dot(v, vec4(0.25));")
            appendLine("}")
            appendLine()
            appendLine("float ${prefix}_min4(vec4 v) {")
            appendLine("    return min(min(v.x, v.y), min(v.z, v.w));")
            appendLine("}")
            appendLine()
            appendLine("float ${prefix}_max4(vec4 v) {")
            appendLine("    return max(max(v.x, v.y), max(v.z, v.w));")
            appendLine("}")
            appendLine()
            appendLine("float ${prefix}_median4(vec4 v) {")
            appendLine("    float a = min(v.x, v.y);")
            appendLine("    float b = max(v.x, v.y);")
            appendLine("    float c = min(v.z, v.w);")
            appendLine("    float d = max(v.z, v.w);")
            appendLine("    return 0.5 * (max(a, c) + min(b, d));")
            appendLine("}")
            appendLine()
        }
        if ("bits" in helpers) {
            appendLine("uvec4 ${prefix}_toBits16(vec4 v) {")
            appendLine("    return uvec4(round(clamp(v, vec4(0.0), vec4(1.0)) * 65535.0));")
            appendLine("}")
            appendLine()
            appendLine("vec4 ${prefix}_fromBits16(uvec4 v) {")
            appendLine("    return vec4(v & uvec4(65535u)) / 65535.0;")
            appendLine("}")
            appendLine()
            appendLine("vec4 ${prefix}_xor(vec4 a, vec4 b) {")
            appendLine("    return ${prefix}_fromBits16(${prefix}_toBits16(a) ^ ${prefix}_toBits16(b));")
            appendLine("}")
            appendLine()
            appendLine("vec4 ${prefix}_bitcrush(vec4 value, vec4 bits) {")
            appendLine("    vec4 b = clamp(floor(abs(bits)), vec4(1.0), vec4(16.0));")
            appendLine("    vec4 levels = exp2(b) - vec4(1.0);")
            appendLine("    return floor(clamp(value, vec4(0.0), vec4(1.0)) * levels + vec4(0.5)) / levels;")
            appendLine("}")
            appendLine()
            appendLine("vec4 ${prefix}_grayCode(vec4 v) {")
            appendLine("    uvec4 x = ${prefix}_toBits16(v);")
            appendLine("    return ${prefix}_fromBits16(x ^ (x >> uvec4(1u)));")
            appendLine("}")
            appendLine()
            appendLine("vec4 ${prefix}_grayDecode(vec4 v) {")
            appendLine("    uvec4 x = ${prefix}_toBits16(v);")
            appendLine("    x ^= x >> uvec4(1u);")
            appendLine("    x ^= x >> uvec4(2u);")
            appendLine("    x ^= x >> uvec4(4u);")
            appendLine("    x ^= x >> uvec4(8u);")
            appendLine("    return ${prefix}_fromBits16(x);")
            appendLine("}")
            appendLine()
            appendLine("vec4 ${prefix}_lfsr(vec4 state) {")
            appendLine("    uvec4 x = max(${prefix}_toBits16(state), uvec4(1u));")
            appendLine("    uvec4 neighbor = x.yzwx;")
            appendLine("    uvec4 feedback = (x ^ (x >> uvec4(2u)) ^ (x >> uvec4(3u)) ^ (x >> uvec4(5u)) ^ neighbor) & uvec4(1u);")
            appendLine("    x = ((x >> uvec4(1u)) | (feedback << uvec4(15u))) & uvec4(65535u);")
            appendLine("    return ${prefix}_fromBits16(x);")
            appendLine("}")
            appendLine()
        }
        if ("ryb2rgb" in helpers || "ryb2rgbClamped" in helpers) {
            appendLine("vec4 ${prefix}_ryb2rgb(vec4 color) {")
            appendLine("    vec3 c = color.rgb;")
            appendLine("    float r = c.r;")
            appendLine("    float y = c.g;")
            appendLine("    float b = c.b;")
            appendLine("    float white = min(min(r, y), b);")
            appendLine("    r -= white;")
            appendLine("    y -= white;")
            appendLine("    b -= white;")
            appendLine("    float maxYellow = max(max(r, y), b);")
            appendLine("    float g = min(y, b);")
            appendLine("    y -= g;")
            appendLine("    b -= g;")
            appendLine("    if (b > 0.0 && g > 0.0) {")
            appendLine("        b *= 2.0;")
            appendLine("        g *= 2.0;")
            appendLine("    }")
            appendLine("    r += y;")
            appendLine("    g += y;")
            appendLine("    float maxGreen = max(max(r, g), b);")
            appendLine("    if (maxGreen > 0.0) {")
            appendLine("        float n = maxYellow / maxGreen;")
            appendLine("        r *= n;")
            appendLine("        g *= n;")
            appendLine("        b *= n;")
            appendLine("    }")
            appendLine("    return vec4(vec3(r + white, g + white, b + white), color.a);")
            appendLine("}")
            appendLine()
        }
        if ("ryb2rgbClamped" in helpers) {
            appendLine("vec4 ${prefix}_ryb2rgb_c(vec4 color) {")
            appendLine("    vec4 converted = ${prefix}_ryb2rgb(vec4(clamp(color.rgb, vec3(0.0), vec3(1.0)), color.a));")
            appendLine("    return vec4(clamp(converted.rgb, vec3(0.0), vec3(1.0)), color.a);")
            appendLine("}")
            appendLine()
        }
        if ("rgb2ryb" in helpers || "rgb2rybClamped" in helpers) {
            appendLine("vec4 ${prefix}_rgb2ryb(vec4 color) {")
            appendLine("    vec3 c = color.rgb;")
            appendLine("    float r = c.r;")
            appendLine("    float g = c.g;")
            appendLine("    float b = c.b;")
            appendLine("    float white = min(min(r, g), b);")
            appendLine("    r -= white;")
            appendLine("    g -= white;")
            appendLine("    b -= white;")
            appendLine("    float maxGreen = max(max(r, g), b);")
            appendLine("    float y = min(r, g);")
            appendLine("    r -= y;")
            appendLine("    g -= y;")
            appendLine("    if (b > 0.0 && g > 0.0) {")
            appendLine("        b *= 0.5;")
            appendLine("        g *= 0.5;")
            appendLine("    }")
            appendLine("    y += g;")
            appendLine("    b += g;")
            appendLine("    float maxYellow = max(max(r, y), b);")
            appendLine("    if (maxYellow > 0.0) {")
            appendLine("        float n = maxGreen / maxYellow;")
            appendLine("        r *= n;")
            appendLine("        y *= n;")
            appendLine("        b *= n;")
            appendLine("    }")
            appendLine("    return vec4(vec3(r + white, y + white, b + white), color.a);")
            appendLine("}")
            appendLine()
        }
        if ("rgb2rybClamped" in helpers) {
            appendLine("vec4 ${prefix}_rgb2ryb_c(vec4 color) {")
            appendLine("    vec4 converted = ${prefix}_rgb2ryb(vec4(clamp(color.rgb, vec3(0.0), vec3(1.0)), color.a));")
            appendLine("    return vec4(clamp(converted.rgb, vec3(0.0), vec3(1.0)), color.a);")
            appendLine("}")
            appendLine()
        }
        if ("rgb2oklab" in helpers) {
            appendLine("vec4 ${prefix}_rgb2oklab(vec4 color) {")
            appendLine("    vec3 rgb = color.rgb;")
            appendLine("    vec3 lms = mat3(")
            appendLine("        0.4122214708, 0.2119034982, 0.0883024619,")
            appendLine("        0.5363325363, 0.6806995451, 0.2817188376,")
            appendLine("        0.0514459929, 0.1073969566, 0.6299787005")
            appendLine("    ) * rgb;")
            appendLine("    vec3 lmsPrime = sign(lms) * pow(abs(lms), vec3(1.0 / 3.0));")
            appendLine("    vec3 lab = mat3(")
            appendLine("        0.2104542553, 1.9779984951, 0.0259040371,")
            appendLine("        0.7936177850, -2.4285922050, 0.7827717662,")
            appendLine("        -0.0040720468, 0.4505937099, -0.8086757660")
            appendLine("    ) * lmsPrime;")
            appendLine("    return vec4(lab, color.a);")
            appendLine("}")
            appendLine()
        }
        if ("oklab2rgb" in helpers) {
            appendLine("vec4 ${prefix}_oklab2rgb(vec4 color) {")
            appendLine("    float l = color.x + 0.3963377774 * color.y + 0.2158037573 * color.z;")
            appendLine("    float m = color.x - 0.1055613458 * color.y - 0.0638541728 * color.z;")
            appendLine("    float s = color.x - 0.0894841775 * color.y - 1.2914855480 * color.z;")
            appendLine("    vec3 lms = vec3(l * l * l, m * m * m, s * s * s);")
            appendLine("    vec3 rgb = mat3(")
            appendLine("        4.0767416621, -1.2684380046, -0.0041960863,")
            appendLine("        -3.3077115913, 2.6097574011, -0.7034186147,")
            appendLine("        0.2309699292, -0.3413193965, 1.7076147010")
            appendLine("    ) * lms;")
            appendLine("    return vec4(rgb, color.a);")
            appendLine("}")
            appendLine()
        }
        if ("ema" in helpers) {
            appendLine("vec4 ${prefix}_ema(vec4 current, vec4 previous, vec4 alpha) {")
            appendLine("    return mix(previous, current, alpha);")
            appendLine("}")
            appendLine()
        }
        if ("alpha" in helpers) {
            appendLine("vec4 ${prefix}_alpha(vec4 baseAlpha, vec4 modulation, vec4 range) {")
            appendLine("    vec4 alphaSign = mix(vec4(1.0), vec4(-1.0), lessThan(baseAlpha, vec4(0.0)));")
            appendLine("    vec4 lambda = -log(max(abs(baseAlpha), vec4(0.000001)));")
            appendLine("    vec4 rateMul = pow(range, modulation);")
            appendLine("    return alphaSign * exp(-lambda * rateMul);")
            appendLine("}")
            appendLine()
        }
        if ("alphaClamped" in helpers) {
            appendLine("vec4 ${prefix}_alpha_c(vec4 baseAlpha, vec4 modulation, vec4 range) {")
            appendLine("    baseAlpha = clamp(baseAlpha, vec4(0.000001), vec4(0.999999));")
            appendLine("    modulation = clamp(modulation, vec4(-1.0), vec4(1.0));")
            appendLine("    vec4 lambda = -log(baseAlpha);")
            appendLine("    vec4 rateMul = pow(range, modulation);")
            appendLine("    return exp(-lambda * rateMul);")
            appendLine("}")
            appendLine()
        }
        if ("alphaSoft" in helpers) {
            appendLine("vec4 ${prefix}_alpha_sc(vec4 baseAlpha, vec4 modulation, vec4 range) {")
            appendLine("    baseAlpha = clamp(${prefix}_softClamp01(baseAlpha), vec4(0.000001), vec4(0.999999));")
            appendLine("    modulation = ${prefix}_softClamp01(modulation * vec4(0.5) + vec4(0.5)) * vec4(2.0) - vec4(1.0);")
            appendLine("    vec4 lambda = -log(baseAlpha);")
            appendLine("    vec4 rateMul = pow(range, modulation);")
            appendLine("    return exp(-lambda * rateMul);")
            appendLine("}")
            appendLine()
        }
        if ("sample" in helpers || "sampleAbs" in helpers || "gather" in helpers ||
            "sampleWrap" in helpers || "sampleAbsWrap" in helpers || "gatherWrap" in helpers) {
            if ("sample" in helpers) {
                appendLine("vec4 ${prefix}_sample(sampler2D sourceTexture, vec4 base, vec4 offset) {")
                appendLine("    return texture(sourceTexture, base.xy + offset.xy);")
                appendLine("}")
                appendLine()
            }
            if ("sampleWrap" in helpers) {
                appendLine("vec4 ${prefix}_sample_w(sampler2D sourceTexture, vec4 base, vec4 offset) {")
                appendLine("    return texture(sourceTexture, fract(base.xy + offset.xy));")
                appendLine("}")
                appendLine()
            }
            if ("sampleAbs" in helpers) {
                appendLine("vec4 ${prefix}_sampleAbs(sampler2D sourceTexture, vec4 coord) {")
                appendLine("    return texture(sourceTexture, coord.xy);")
                appendLine("}")
                appendLine()
            }
            if ("sampleAbsWrap" in helpers) {
                appendLine("vec4 ${prefix}_sampleAbs_w(sampler2D sourceTexture, vec4 coord) {")
                appendLine("    return texture(sourceTexture, fract(coord.xy));")
                appendLine("}")
                appendLine()
            }
            if ("gather" in helpers) {
                appendLine("vec4 ${prefix}_gather(sampler2D sourceTexture, vec4 base, vec4 offset) {")
                appendLine("    return textureGather(sourceTexture, base.xy + offset.xy, 0);")
                appendLine("}")
                appendLine()
            }
            if ("gatherWrap" in helpers) {
                appendLine("vec4 ${prefix}_gather_w(sampler2D sourceTexture, vec4 base, vec4 offset) {")
                appendLine("    return textureGather(sourceTexture, fract(base.xy + offset.xy), 0);")
                appendLine("}")
                appendLine()
            }
        }
    }
}
