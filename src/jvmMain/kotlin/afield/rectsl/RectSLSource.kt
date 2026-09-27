package afield.rectsl

import afield.Color4
import afield.KotlinScriptEngine
import afield.stacksl.StackSLSeverity
import afield.stdlib.Functions
import java.util.Locale

/**
 * RectSL read as *source*: what the editor and the study tools need that a plain compile does not
 * give — where things are.
 *
 * [compileRectSL] answers "does it compile, and to what". A person editing a scene, or a model
 * talking one into existence, also needs to know *which line* a problem is on, *which line* made a
 * given rectangle, and *which numbers* in the text are worth turning. The tokenizer records where each
 * token began ([RectLangTokenizer.offsets]), the evaluator records what it was evaluating when it
 * failed ([RectLangVm.failedAt]), and the scene reports each node as it is made
 * ([SceneBuilder.onNode]); this file turns those into lines.
 *
 * Nothing here changes what a program means: [compileRectSLWithSource] runs the same tokenizer and
 * evaluator as [compileRectSL], with the same curves, engine and pre-compiled escapes.
 */

/** A problem with a RectSL program, as the editor shows it. [line] and [column] are 1-based. */
data class RectSLProblem(val message: String, val line: Int?, val column: Int?, val error: Boolean = true) {
    /** The problem in one line, with its place, as tool results quote it. */
    val summary: String get() = (line?.let { "line $it: " } ?: "") + message
}

/**
 * Where a node came from: the line and column of the innermost word that made it (a `rect`, the
 * `array` that cloned it, a word inside a definition), and the line of the root-level statement that
 * was running — the same for a rectangle made directly, the call site for one made inside a definition.
 */
data class RectSLOrigin(val line: Int, val column: Int, val statementLine: Int)

/** [compileRectSLWithSource]'s answer. */
class RectSLCompiled(
    val source: String,
    /** The compiled scene, or null when it did not compile. */
    val program: SceneProgram?,
    /** Every problem, errors first; material (StackSL) problems included. */
    val problems: List<RectSLProblem>,
    /** Which line made each node, by node id. */
    val origins: Map<String, RectSLOrigin>,
    /** The materials the scene's StackSL compiled to, when it compiled. */
    val materials: List<StackSLRectMaterialCompileResult>,
    /**
     * What took each literal off the stack, by the literal's offset in [source]: the word, and how many
     * values that word had taken before it — the evaluator's own record ([RectLangVm.onLiteralTaken]).
     */
    val takenBy: Map<Int, Pair<String, Int>> = emptyMap()
) {
    val ok: Boolean get() = program != null && problems.none { it.error }
}

/** The 1-based line and column of [offset] in [source]. */
fun rectSLLineAndColumn(source: String, offset: Int): Pair<Int, Int> {
    var line = 1
    var lineStart = 0
    for (n in 0 until offset.coerceIn(0, source.length)) if (source[n] == '\n') { line++; lineStart = n + 1 }
    return line to (offset - lineStart + 1)
}

/**
 * Compile [source] as [compileRectSL] does, and say where: each problem on its line, and which line
 * made each rectangle and group. A material whose StackSL does not compile is a problem on the
 * `material` line that defines it, because the GPU would draw it magenta.
 */
fun compileRectSLWithSource(
    source: String,
    curves: Map<String, Functions.Fn> = emptyMap(),
    engine: KotlinScriptEngine? = null,
    precompiledEscapes: Map<String, (RectEscapeContext) -> Any?> = emptyMap()
): RectSLCompiled {
    val tokenizer = RectLangTokenizer(source)
    val tokens = try {
        tokenizer.tokenize()
    } catch (e: RectLangException) {
        val at = tokenizer.failureOffset?.let { rectSLLineAndColumn(source, it) }
        return RectSLCompiled(source, null, listOf(RectSLProblem(e.message ?: "$e", at?.first, at?.second)), emptyMap(), emptyList())
    }
    val offsets = tokenizer.offsets
    val origins = linkedMapOf<String, RectSLOrigin>()
    val scene = SceneBuilder()
    lateinit var vm: RectLangVm
    scene.onNode = { id, renamedFrom ->
        val inherited = renamedFrom?.let { origins.remove(it) }
        val chain = vm.evaluatingTokens()
        val innermost = chain.lastOrNull { it in offsets }
        val outermost = chain.firstOrNull { it in offsets }
        val origin = inherited ?: innermost?.let { token ->
            val (line, column) = rectSLLineAndColumn(source, offsets.getValue(token))
            RectSLOrigin(line, column, rectSLLineAndColumn(source, offsets.getValue(outermost ?: token)).first)
        }
        if (origin != null) origins[id] = origin
    }
    vm = RectLangVm(scene = scene, namedCurves = curves, escapeEngine = engine, precompiledEscapes = precompiledEscapes)
    val takenBy = HashMap<Int, Pair<String, Int>>()
    vm.onLiteralTaken = { token, word, depth -> offsets[token]?.let { takenBy.putIfAbsent(it, word to depth) } }
    val program = try {
        vm.eval(tokens)
    } catch (t: Throwable) {
        val at = vm.failedAt?.let { offsets[it] }?.let { rectSLLineAndColumn(source, it) }
        return RectSLCompiled(source, null, listOf(RectSLProblem(t.message ?: t.toString(), at?.first, at?.second)), origins, emptyList())
    }
    val materials = runCatching { compileStackSLMaterials(program) }.getOrElse { emptyList() }
    val problems = materials.flatMap { compiled ->
        val line = materialLine(source, compiled.module.name)
        compiled.stackSL.diagnostics.filter { it.severity == StackSLSeverity.ERROR || it.severity == StackSLSeverity.WARNING }.map {
            RectSLProblem("material ${compiled.module.name}: ${it.message}", line, null, it.severity == StackSLSeverity.ERROR)
        }
    }.sortedBy { !it.error }
    return RectSLCompiled(source, program, problems, origins, materials, takenBy)
}

/** The line of `material <name>`, or null. */
private fun materialLine(source: String, name: String): Int? {
    val at = Regex("""(^|\s)material\s+"?${Regex.escape(name)}"?(\s|$)""").find(source) ?: return null
    return rectSLLineAndColumn(source, at.range.first + at.groupValues[1].length).first
}

/**
 * A number (or a colour) typed in a RectSL program, which a person or a model can turn without
 * rewriting the line around it.
 *
 * @property knob its index in the program's knob list, in source order
 * @property text the number exactly as typed
 * @property role what reads it: `->xy x`, `rgb green`, `->wall width`, `keyframes of ->x: value`, …
 * @property colour for a colour literal (`0xffaa33`), its hex; the three knobs of an `rgb` share one
 */
data class RectSLKnob(
    val knob: Int,
    val value: Double,
    val text: String,
    val line: Int,
    val column: Int,
    val start: Int,
    val end: Int,
    val role: String,
    val context: String,
    val colour: String? = null
)

/** The number of literal tokens each of these words reads straight from the source after it, and what each one is. */
private val LITERAL_ARGUMENTS = mapOf(
    "rgb" to listOf("red", "green", "blue"),
    "rgba" to listOf("red", "green", "blue", "alpha"),
    "->wall" to listOf("width", "height"),
    "->duration" to listOf("seconds"),
    "->fps" to listOf("frames per second"),
    // Only a hex colour straight after it; otherwise ->background takes a colour from the stack.
    "->background" to listOf("colour"),
)

/**
 * Every knob in [source]: each number and hex colour, in source order, with what reads it. Numbers
 * inside `{{ … }}` Kotlin escapes are not knobs; numbers inside a `material` quote are (they are
 * StackSL numbers, and turning one re-shades the material). A source that does not tokenize has none.
 */
fun rectSLKnobs(source: String, takenBy: Map<Int, Pair<String, Int>> = emptyMap()): List<RectSLKnob> {
    val tokenizer = RectLangTokenizer(source)
    val tokens = runCatching { tokenizer.tokenize() }.getOrElse { return emptyList() }
    val offsets = tokenizer.offsets
    val out = mutableListOf<RectSLKnob>()
    val lines = source.lines()

    fun endOf(start: Int): Int {
        var i = start
        while (i < source.length) {
            val c = source[i]
            if (c.isWhitespace() || c == '#' || c == '"' || c == ';' || c == ':' || c == '=' || c == ')') break
            if (source.startsWith("'(", i)) break
            i++
        }
        return i
    }

    // A material's quote is StackSL: its numbers are described as StackSL's compiler describes them, by offset.
    val materialPurposes = HashMap<Int, String>()
    fun describeMaterial(quote: Token.Quote) {
        val at = offsets[quote]?.let { source.indexOf(quote.source, it) }?.takeIf { it >= 0 } ?: return
        val quoteLines = quote.source.lines()
        val lineStarts = quoteLines.runningFold(0) { acc, l -> acc + l.length + 1 }
        runCatching { afield.stacksl.study.StackSLStudyShader.knobs(quote.source) }.getOrDefault(emptyList()).forEach { k ->
            val purpose = afield.stacksl.study.StackSLStudyViewer.purpose(k, quoteLines.getOrElse(k.line - 1) { "" })
            k.spans.forEach { span -> materialPurposes[at + lineStarts[k.line - 1] + span.column - 1] = purpose }
        }
    }

    fun nextWord(block: List<Token>, from: Int): String? =
        block.drop(from + 1).firstOrNull { it is Token.Word }?.let { (it as Token.Word).name }

    fun walk(block: List<Token>, quoteRole: String?) {
        var consumer: Pair<String, Int>? = null // literal-reading word, and how many of its literals have been seen
        val readForward = HashSet<Int>() // the literals a word before them read out of the source, not off the stack
        block.forEachIndexed { index, token ->
            val start = offsets[token]
            when (token) {
                is Token.Word -> consumer = LITERAL_ARGUMENTS[token.name]?.let { token.name to 0 }
                is Token.Quote -> {
                    consumer = null
                    val before = block.getOrNull(index - 1)
                    val beforeThat = block.getOrNull(index - 2)
                    val role = when {
                        beforeThat is Token.Word && beforeThat.name == "material" -> "material ${(before as? Token.Word)?.name ?: (before as? Token.StringLit)?.value}"
                        beforeThat is Token.Word && beforeThat.name.endsWith("track") -> "keyframes of track ${(before as? Token.StringLit)?.value ?: (before as? Token.Word)?.name}"
                        else -> nextWord(block, index)?.let { word ->
                            // `'( 0=0 6=6.28 ) linear` is keyframes whatever reads them; only a mutator says which channel.
                            if (!keyframes(token.tokens)) "in a quote before $word"
                            else if (word.startsWith("->")) "keyframes of $word" else "keyframes before $word"
                        } ?: "in a quote"
                    }
                    if (role.startsWith("material ")) describeMaterial(token)
                    walk(token.tokens, role)
                }
                is Token.Number, is Token.ColorLit -> {
                    if (start == null) return@forEachIndexed
                    val end = endOf(start)
                    val raw = source.substring(start, end).trimEnd(',')
                    val (line, column) = rectSLLineAndColumn(source, start)
                    val literal = consumer?.takeIf { it.first != "->background" || token is Token.ColorLit }?.let { (word, seen) ->
                        LITERAL_ARGUMENTS.getValue(word).getOrNull(seen)?.let { part -> consumer = word to seen + 1; readForward += index; "$word $part" }
                    }
                    if (consumer?.first == "->background") consumer = null
                    val guessed = when {
                        literal != null -> literal
                        quoteRole != null && quoteRole.startsWith("keyframes") -> "$quoteRole: " + keyPart(block, index)
                        quoteRole != null && !quoteRole.startsWith("in a quote") -> quoteRole
                        else -> {
                            // What reads it: the word right after it, or — when that is arithmetic —
                            // the mutator the arithmetic feeds (`fc 6.5 * ->x` is "into ->x via *").
                            val body = quoteRole?.removePrefix("in a quote")?.removePrefix(" before ")?.takeIf { it.isNotBlank() }?.let { " in the $it body" }.orEmpty()
                            // The numbers pushed before it, back to the last word — not counting `->fps 30`'s 30 on the line above.
                            val run = (0..index).reversed().takeWhile { block[it] !is Token.Word && block[it] !is Token.Quote && it !in readForward }.size
                            val arity = block.drop(index + 1).takeWhile { it !is Token.Word && it !is Token.Quote }.size + run
                            val direct = (block.drop(index + 1).firstOrNull { it is Token.Word || it is Token.Quote } as? Token.Word)?.name
                            val mutator = block.drop(index + 1).firstOrNull { it is Token.Word && it.name.startsWith("->") }?.let { (it as Token.Word).name }
                            when {
                                // A number before a quote is the quote's word's argument: `12 '( … ) array` is array's count.
                                direct == null -> nextWord(block, index) ?: "left on the stack"
                                direct.startsWith("->") || mutator == null -> if (arity > 1) "$direct argument $run of $arity" else direct
                                else -> "into $mutator via $direct"
                            } + body
                        }
                    }
                    // What the evaluator saw take it, where the guess above does not already name that word.
                    val evaluated = takenBy[start]?.takeIf { (word, _) -> literal == null && word !in guessed }?.let { (word, depth) ->
                        "into $word" + (if (depth > 0) " (${depth + 1} down the stack)" else "")
                    }
                    val role = materialPurposes[start]?.let { "${quoteRole}: $it" } ?: evaluated ?: guessed
                    val colour = (token as? Token.ColorLit)?.color?.let(::hexOf)
                    val value = when (token) {
                        is Token.Number -> token.value
                        is Token.ColorLit -> raw.drop(2).toLong(16).toDouble()
                        else -> 0.0
                    }
                    out += RectSLKnob(out.size, value, raw, line, column, start, start + raw.length, role,
                        lines.getOrElse(line - 1) { "" }.trim(), colour)
                }
                else -> consumer = null
            }
        }
    }
    walk(tokens, null)
    // The three (or four) knobs of an rgb share its colour, so a card can offer one swatch for them.
    val grouped = out.toMutableList()
    var i = 0
    while (i < grouped.size) {
        val k = grouped[i]
        if (k.role == "rgb red" || k.role == "rgba red") {
            val parts = grouped.subList(i, minOf(grouped.size, i + 3))
            if (parts.size == 3 && parts[1].role.endsWith("green") && parts[2].role.endsWith("blue")) {
                val hex = hexOf(Color4(parts[0].value, parts[1].value, parts[2].value))
                for (j in 0 until 3) grouped[i + j] = grouped[i + j].copy(colour = hex)
            }
        }
        i++
    }
    return grouped
}

/** Whether a quote reads as keyframes: numbers only, as `t v t v …` or `t=v t=v …`. */
private fun keyframes(tokens: List<Token>): Boolean {
    val numbers = tokens.count { it is Token.Number }
    return numbers > 0 && numbers % 2 == 0 && tokens.all { it is Token.Number || (it is Token.Word && it.name == "=") }
}

/** Whether the number at [index] of a keyframe quote is a key's time or its value: `t=v` says so, `t v` goes by position. */
private fun keyPart(block: List<Token>, index: Int): String = when {
    (block.getOrNull(index + 1) as? Token.Word)?.name == "=" -> "time"
    (block.getOrNull(index - 1) as? Token.Word)?.name == "=" -> "value"
    else -> if (block.subList(0, index).count { it is Token.Number } % 2 == 0) "time" else "value"
}

private fun hexOf(c: Color4): String {
    fun byte(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
    return "#%02x%02x%02x".format(byte(c.r), byte(c.g), byte(c.b))
}

/** [value] written the way [original] was: as many decimals as it had (at least enough to show the change), no exponent. */
fun formatRectSLKnob(value: Double, original: String): String {
    if (original.startsWith("0x", ignoreCase = true)) {
        val digits = original.length - 2
        return "0x" + value.toLong().coerceIn(0, if (digits > 6) 0xffffffffL else 0xffffffL).toString(16).padStart(digits, '0')
    }
    val decimals = original.substringAfter('.', "").takeWhile { it.isDigit() }.length
    for (d in decimals..6) {
        val text = String.format(Locale.US, "%.${d}f", value)
        if (text.toDouble() == value || d == 6) return if (d == 0) text.removeSuffix(".0") else text
    }
    return value.toString()
}

/**
 * [source] with knobs rewritten in place (knob index to new value). A colour knob takes its value as
 * the 24-bit number of its hex (`0xff8800` is 16746496). Refuses an index that is not a knob, and a
 * value the number's reader cannot hold — a wall, duration or frame rate that is not positive, an `rgb`
 * channel outside 0..1, a colour outside 24 bits — saying why, rather than writing a scene that fails
 * or silently clamps. The same knobs must exist afterwards.
 */
fun setRectSLKnobs(source: String, values: Map<Int, Double>): String {
    val knobs = rectSLKnobs(source)
    values.keys.forEach { require(it in knobs.indices) { "No knob $it; the program has ${knobs.size}" } }
    values.forEach { (index, value) ->
        require(value.isFinite()) { "Knob values must be finite numbers" }
        val k = knobs[index]
        val role = k.role
        when {
            k.text.startsWith("0x", ignoreCase = true) ->
                require(value >= 0 && value <= 0xffffff && value == Math.rint(value)) { "k$index is a colour ($role); give its 24-bit value, 0..16777215 (0xffffff)" }
            role.startsWith("->wall ") || role == "->duration seconds" ->
                require(value > 0) { "k$index is $role, which must be more than 0" }
            role == "->fps frames per second" ->
                require(value > 0 && value <= 240) { "k$index is $role, which must be more than 0 and at most 240" }
            Regex("^rgba? (red|green|blue|alpha)$").matches(role) ->
                require(value in 0.0..1.0) { "k$index is $role, an sRGB channel as typed, 0..1" }
        }
    }
    val out = StringBuilder(source)
    values.entries.sortedByDescending { knobs[it.key].start }.forEach { (index, value) ->
        val k = knobs[index]
        out.replace(k.start, k.end, formatRectSLKnob(value, k.text))
    }
    val rewritten = out.toString()
    require(rectSLKnobs(rewritten).size == knobs.size) {
        "Writing those values changed which numbers the scene has (${knobs.size} before, ${rectSLKnobs(rewritten).size} after)"
    }
    return rewritten
}
