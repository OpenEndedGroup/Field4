package afield.stacksl.study

import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLOutline
import afield.stacksl.StackSLWord
import afield.stacksl.cpu.CpuRaster
import afield.stacksl.cpu.GlslSamplers
import afield.stacksl.cpu.GlslType
import afield.stacksl.cpu.GlslValue
import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLWires
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The one shader a StackSL study renders through, and the three things done with it: render on the
 * CPU (the evidence a model is shown), translate to WebGL2 (the live preview a person turns knobs
 * on), and find the numbers a person typed (the knobs).
 *
 * ### One frame convention, three consumers
 *
 * A study frame is square and is `program(vec4(uv, hash, seconds))` — `uv` runs `0..1` with `y`
 * upwards, `hash` is a per-pixel value in `0..1`, and `seconds` is time. [FRAGMENT] is that shader,
 * byte for byte the animated template the StackSL gallery renders its clips through, so a program
 * a conversation settles on runs unchanged in a real `afield.four` fullscreen module whose fragment
 * is [FRAGMENT] and whose push constant carries the time. The CPU reference renders [FRAGMENT]; the
 * WebGL2 preview renders [webgl2], which is [FRAGMENT]'s body under GLSL ES 3.00 with the program's
 * literals lifted into uniforms. `StackSLStudyShaderTest` pins all three to each other.
 *
 * ### Knobs are the numbers a person typed
 *
 * `StackSLCompileOptions.externalizeConstants` already records every literal the emitter uses, with
 * the word it came from. Only some of those are *typed*: `0.2t`, `]wr14`, `5.0` and each component of
 * `[7,7,7,0.048]` are; the default coefficients `palette` supplies when given no operands are not.
 * [knobs] keeps the typed ones and locates the digits inside their word — `StackSLWires.rewrite`
 * replaces a whole word, which would turn `0.2t` into `0.2` — so a knob can be written back without
 * changing what the word means. [setKnobs] re-reads the rewritten program and refuses a value the
 * word cannot hold, rather than trusting its own arithmetic.
 */
object StackSLStudyShader {

    /** The time a single still is rendered at, matching the StackSL gallery's stills. */
    const val REFERENCE_TIME = 1.75

    /**
     * The study's fragment shader: the StackSL gallery's animated template. A GPU module that uses
     * this text as its fragment and pushes seconds into `pc.params.x` renders what the study renders.
     */
    val FRAGMENT: String = """
        #version 450
        layout(location = 0) in vec2 fragUV;
        layout(location = 0) out vec4 outColor;

        layout(push_constant) uniform Push { vec4 params; } pc;

        float gallery_hash12(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        {{ stack.auto.program }}

        void main() {
            vec2 uv = fragUV;
            vec4 stackInput = vec4(uv, gallery_hash12(uv), pc.params.x);
            vec3 c = program(stackInput).rgb;
            if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
            outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    /**
     * [FRAGMENT] without its last two lines: the program's raw `vec4`, unclamped and with NaN left in.
     * Every measurement is taken from this — clipping cannot be counted after the clamp — and the
     * displayed colour is derived from it by [displayed], which applies exactly those two lines.
     */
    val RAW_FRAGMENT: String = FRAGMENT.replace(
        """
            vec3 c = program(stackInput).rgb;
            if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
            outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        """.trimIndent().prependIndent("    "),
        "    outColor = program(stackInput);"
    ).also { require(it != FRAGMENT) { "RAW_FRAGMENT did not find FRAGMENT's colour lines" } }

    /** The fullscreen-triangle vertex shader the GPU module uses with [FRAGMENT]. */
    val VERTEX: String = """
        #version 450
        layout(location = 0) out vec2 fragUV;

        void main() {
            // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
            vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
            gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
            fragUV = pos;
        }
    """.trimIndent()

    /** Render [program]'s raw output at [size] square and time [seconds]. Throws on a program that does not compile. */
    fun renderRaw(program: String, size: Int, seconds: Double, parallel: Boolean = true): CpuRaster =
        StackSLCpu.renderProgram(program, RAW_FRAGMENT, size, size,
            pushParams = floatArrayOf(seconds.toFloat(), 0f, 0f, 0f), samplers = GlslSamplers.REFUSE, parallel = parallel)

    /** What [FRAGMENT] draws for a raw raster: non-finite colour becomes black, the rest is clamped, alpha is one. */
    fun displayed(raw: CpuRaster): CpuRaster {
        val out = FloatArray(raw.rgba.size)
        for (p in 0 until raw.width * raw.height) {
            val r = raw.rgba[p * 4]; val g = raw.rgba[p * 4 + 1]; val b = raw.rgba[p * 4 + 2]
            val finite = r.isFinite() && g.isFinite() && b.isFinite()
            out[p * 4] = if (finite) r.coerceIn(0f, 1f) else 0f
            out[p * 4 + 1] = if (finite) g.coerceIn(0f, 1f) else 0f
            out[p * 4 + 2] = if (finite) b.coerceIn(0f, 1f) else 0f
            out[p * 4 + 3] = 1f
        }
        return CpuRaster(raw.width, raw.height, out)
    }

    /**
     * What a person sees of a raw raster in the window: [displayed], then the sRGB transfer function.
     *
     * The window's swapchain is `B8G8R8A8_SRGB` (scRGB when HDR is on, which looks the same for
     * `0..1`), so the value the shader writes is treated as *linear* light and encoded for display on
     * the way to the screen: `0.5` shows as sRGB 188, a light grey, not 128. An image that showed the
     * written values directly would be markedly darker than the window, and a model reading it would
     * be judging a different picture from the person. Every study image, every export and the card's
     * WebGL preview go through this; clipping and NaN are still counted on the raw values.
     */
    fun presented(raw: CpuRaster): CpuRaster {
        val shown = displayed(raw)
        val out = shown.rgba.copyOf()
        for (p in 0 until shown.width * shown.height) for (c in 0 until 3) out[p * 4 + c] = linearToSrgb(out[p * 4 + c])
        return CpuRaster(shown.width, shown.height, out)
    }

    /** The sRGB transfer function (IEC 61966-2-1), for a linear value in `0..1`. */
    fun linearToSrgb(v: Float): Float =
        if (v <= 0.0031308f) v * 12.92f else (1.055 * Math.pow(v.toDouble(), 1.0 / 2.4) - 0.055).toFloat()

    /**
     * The raw `vec4` [program] leaves at one point, `(u, v)` in the frame's `0..1` coordinates.
     * One shader invocation — cheap enough to call once per word of a program.
     */
    fun sample(program: String, u: Double, v: Double, seconds: Double): FloatArray {
        val expanded = StackSLCpu.expandStackAuto(RAW_FRAGMENT, program)
        // A 1x1 raster would evaluate at its centre; perPixel runs after fragUV is set, so it can move the point.
        val result = StackSLCpu.renderFragment(expanded, 1, 1,
            uniforms = mapOf("pc.params" to GlslValue(GlslType.VEC4, floatArrayOf(seconds.toFloat(), 0f, 0f, 0f))),
            parallel = false,
            perPixel = { _, _, invocation -> invocation.setGlobal("fragUV", GlslValue.vec2(u.toFloat(), v.toFloat())) })
        return result.rgba.copyOf()
    }

    // ---- WebGL2 ------------------------------------------------------------------------------

    /** A WebGL2 translation of a program: shader text, and the uniforms that stand for its literals. */
    data class WebGl2Program(
        val vertex: String,
        val fragment: String,
        /** Every lifted literal as `uniform float program_wire_<i>`, with the value the program gives it. */
        val wires: List<Pair<String, Double>>
    )

    /**
     * [program] as a GLSL ES 3.00 fragment shader for a browser preview: [FRAGMENT]'s `main`, the
     * program compiled with its literals externalised, and each literal a uniform — so a slider can
     * move a number at display rate without a round trip to the JVM. The CPU reference stays the
     * evidence; this is a view.
     */
    fun webgl2(program: String): WebGl2Program {
        val wired = StackSLWires.lift(program)
        val declaration = Regex("""^float (program_wire_\d+);$""", RegexOption.MULTILINE)
        val body = declaration.replace(wired.glsl) { "uniform float ${it.groupValues[1]};" }
        val fragment = buildString {
            appendLine("#version 300 es")
            appendLine("precision highp float;")
            appendLine("precision highp int;")
            appendLine("in vec2 fragUV;")
            appendLine("out vec4 outColor;")
            appendLine("uniform float u_time;")
            appendLine()
            appendLine(FRAGMENT.substringAfter("layout(push_constant) uniform Push { vec4 params; } pc;").substringBefore("{{ stack.auto.program }}").trim())
            appendLine()
            appendLine(body.trim())
            appendLine()
            // A WebGL canvas is shown as-is, where the window's sRGB swapchain encodes on write: encode here
            // so the card's preview looks like the window.
            appendLine("vec3 study_srgb(vec3 c) { return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c)); }")
            appendLine(FRAGMENT.substringAfter("{{ stack.auto.program }}").trim().replace("pc.params.x", "u_time")
                .replace("outColor = vec4(clamp(c, 0.0, 1.0), 1.0);", "outColor = vec4(study_srgb(clamp(c, 0.0, 1.0)), 1.0);"))
        }
        val vertex = """
            #version 300 es
            out vec2 fragUV;
            void main() {
                vec2 pos = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
                fragUV = pos;
            }
        """.trimIndent()
        return WebGl2Program(vertex, fragment,
            wired.wires.map { StackSLWires.globalName(wired.prefix, it.index) to it.initial.toDouble() })
    }

    // ---- Stages ------------------------------------------------------------------------------

    /**
     * The program's stages: one per source line that holds root words, or — for a program written on a
     * single line — one per root word. Each stage names how many root words run through its end, which
     * is the `count` [StackSLOutline.rootPrefix] takes.
     */
    fun stages(program: String, outline: StackSLOutline = StackSLCompiler.outline(program)): List<StackSLStage> {
        val lines = program.lines()
        val byLine = outline.root.withIndex().groupBy { it.value.location.line }
        return if (byLine.size <= 1) {
            outline.root.mapIndexed { i, word -> StackSLStage(i + 1, word.location.line, word.text, i + 1, "word",
                splitComment(lines.getOrElse(word.location.line - 1) { "" }).second) }
        } else {
            byLine.entries.sortedBy { it.key }.mapIndexed { i, (line, words) ->
                val (code, comment) = splitComment(lines.getOrElse(line - 1) { "" })
                StackSLStage(i + 1, line, code, words.last().index + 1, "line", comment)
            }
        }
    }

    /**
     * A source line split into its words and its comment, by the tokenizer's rules: `#` and `//` run to
     * the end of the line and `( … )` is inline. Words never contain `#` or `(`, so the first one of
     * those outside a comment starts one.
     */
    fun splitComment(line: String): Pair<String, String?> {
        val cut = listOf(line.indexOf('#'), line.indexOf("//")).filter { it >= 0 }.minOrNull()
        val code = (if (cut == null) line else line.substring(0, cut))
        val inline = Regex("""\([^)]*\)""")
        val comments = inline.findAll(code).map { it.value.removeSurrounding("(", ")").trim() }.toList() +
            listOfNotNull(cut?.let { line.substring(it).trimStart('#', '/').trim() })
        return inline.replace(code, " ").trim().replace(Regex("\\s+"), " ") to comments.filter { it.isNotBlank() }.joinToString("; ").ifBlank { null }
    }

    // ---- Knobs -------------------------------------------------------------------------------

    private val numberInWord = Regex("""-?(?:\d+\.?\d*|\.\d+)""")

    /** The literals a person typed in [program], each located to its digits. See the object docs. */
    fun knobs(program: String): List<StackSLKnob> {
        val wired = StackSLWires.lift(program)
        val outline = StackSLCompiler.outline(program)
        val lines = program.lines()
        val rootWords = outline.root
        val typed = wired.wires.filter { w ->
            w.token != null && w.line != null && w.column != null && !w.note.startsWith("optional default") &&
                w.token.firstOrNull()?.isLetter() == false
        }
        val knobs = mutableListOf<StackSLKnob>()
        typed.groupBy { it.line!! to it.column!! }.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (at, wires) ->
            val (line, column) = at
            val word = wires.first().token!!
            val unmatched = wires.sortedBy { it.index }.toMutableList()
            val matched = mutableListOf<Int>() // indices into knobs, for this word
            fun same(a: Double, b: Double) = kotlin.math.abs(a - b) <= 1e-6 * maxOf(1.0, kotlin.math.abs(b))
            numberInWord.findAll(word).forEach { match ->
                val value = match.value.toDoubleOrNull() ?: return@forEach
                val span = StackSLSpan(column + match.range.first, match.value.length)
                val wire = unmatched.firstOrNull { same(it.initial.toDouble(), value) }
                if (wire == null) {
                    // The compiler keys a wire by word, value and role, so equal components of one
                    // vector literal share a wire: this span belongs to that knob as well.
                    val shared = matched.lastOrNull { same(knobs[it].value, value) } ?: return@forEach
                    knobs[shared] = knobs[shared].copy(spans = knobs[shared].spans + span)
                    return@forEach
                }
                unmatched -= wire
                // The next word that is not itself a number, in the same body — a definition's or the root's.
                val body = (listOf(rootWords) + outline.definitions.map { it.body }).firstOrNull { words ->
                    words.any { it.location.line == line && it.location.column == column }
                }.orEmpty()
                val wordIndex = body.indexOfFirst { it.location.line == line && it.location.column == column }
                val feeds = if (wordIndex >= 0 && match.value == word) {
                    body.drop(wordIndex + 1).firstOrNull { it.text.toDoubleOrNull() == null && !it.text.startsWith("[") }?.text
                } else null
                matched += knobs.size
                knobs += StackSLKnob(
                    knob = knobs.size, wire = wire.index, value = value, token = word,
                    line = line, spans = listOf(span),
                    role = if (wire.note == "number") "number" else wire.note,
                    feeds = feeds, context = lines.getOrElse(line - 1) { "" }.trim(), takenAs = wire.consumedAs
                )
            }
        }
        return knobs
    }

    /**
     * The vector literals in [program] that look like colours — three components, or four with the
     * last `1`, the first three in `0..1` — each with the three knobs that hold its red, green and blue.
     * A literal whose components share a knob (equal values share a compiler wire) is left out, since
     * a colour control could not set them apart.
     */
    fun colourKnobs(program: String, knobs: List<StackSLKnob> = knobs(program)): List<StackSLColourKnobs> {
        val found = mutableListOf<StackSLColourKnobs>()
        var i = 0
        while (i < knobs.size) {
            val first = knobs[i]
            var j = i
            while (j < knobs.size && knobs[j].line == first.line && knobs[j].token == first.token) j++
            val ofWord = knobs.subList(i, j)
            i = j
            val word = first.token
            if (!word.startsWith("[") || !word.endsWith("]")) continue
            val matches = numberInWord.findAll(word).toList()
            val values = matches.mapNotNull { it.value.toDoubleOrNull() }
            if (values.size != matches.size || !(values.size == 3 || (values.size == 4 && values[3] == 1.0))) continue
            if (values.take(3).any { it < 0.0 || it > 1.0 }) continue
            val wordColumn = first.spans.first().column - matches.first().range.first
            val owners = matches.take(3).map { m ->
                val column = wordColumn + m.range.first
                ofWord.firstOrNull { k -> k.spans.any { it.column == column } }?.knob
            }
            if (owners.any { it == null } || owners.toSet().size != 3) continue
            found += StackSLColourKnobs(owners.map { it!! }, values.take(3))
        }
        return found
    }

    /** A knob value written the way a person would: no float noise, and integer-looking where the original was. */
    fun formatKnob(value: Double, original: String): String {
        require(value.isFinite()) { "A knob value must be finite" }
        val integral = !original.contains('.') && value == Math.rint(value) && kotlin.math.abs(value) < 1e9
        if (integral) return value.toLong().toString()
        val text = BigDecimal(value).setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (text.contains('.')) text else "$text.0"
    }

    /**
     * [program] with each knob in [values] (knob index to new value) rewritten in place, re-read and
     * checked: the same knobs must exist afterwards and each must now hold its requested value. A
     * value a word cannot hold — a negative frequency after a modifier letter, say — is refused with
     * the reason, never written half-way.
     */
    fun setKnobs(program: String, values: Map<Int, Double>): String {
        val knobs = knobs(program)
        values.keys.forEach { require(it in knobs.indices) { "No knob $it; this program has ${knobs.size} (0..${knobs.size - 1})" } }
        val lines = program.lines().toMutableList()
        values.entries.flatMap { (index, value) -> knobs[index].spans.map { Triple(knobs[index].line, it, value) } }
            .sortedWith(compareByDescending<Triple<Int, StackSLSpan, Double>> { it.first }.thenByDescending { it.second.column })
            .forEach { (lineNumber, span, value) ->
                val row = lineNumber - 1
                val col = span.column - 1
                val line = lines[row]
                val original = line.substring(col, col + span.length)
                lines[row] = line.take(col) + formatKnob(value, original) + line.drop(col + span.length)
            }
        val rewritten = lines.joinToString("\n")
        val compiled = StackSLCpu.compile(rewritten)
        require(compiled.ok) {
            "Writing those values breaks the program: " + compiled.diagnostics.joinToString("; ") { it.toString() }
        }
        val after = knobs(rewritten)
        require(after.size == knobs.size) {
            "Writing those values changed which numbers the program has (${knobs.size} before, ${after.size} after); a value the word cannot hold, such as a negative number after a modifier letter, does this"
        }
        values.forEach { (index, value) ->
            val got = after[index].value
            require(kotlin.math.abs(got - value) <= 1e-4 * maxOf(1.0, kotlin.math.abs(value))) {
                "Knob $index reads back as $got, not $value"
            }
        }
        return rewritten
    }

    /** The word that starts at [line]:[column], for diagnostics. */
    internal fun wordAt(outline: StackSLOutline, line: Int, column: Int): StackSLWord? =
        outline.root.firstOrNull { it.location.line == line && it.location.column == column }
}

/** One stage of a program: a line of root words, or a single word of a one-line program. */
data class StackSLStage(
    /** 1-based stage number. */
    val stage: Int,
    /** The source line the stage ends on. */
    val line: Int,
    /** The line's text, or the word's. */
    val text: String,
    /** How many root words have run by the end of this stage. */
    val wordsThrough: Int,
    /** `"line"` or `"word"`. */
    val granularity: String,
    /** The comment on that line, if any — what the author said the stage is for. */
    val comment: String? = null
)

/**
 * A number a person typed, located to its digits so it can be rewritten without changing its word.
 *
 * `0.2t` has one knob, at the `0.2`; `0.5pulse4d0.2` has three; `[7,7,7,0.048]` has two, because
 * the compiler lifts equal components of one literal into one wire and the knob follows the wire.
 */
data class StackSLKnob(
    /** Index among this program's knobs, in source order — the number a tool call names. */
    val knob: Int,
    /** The compiler's wire index for this literal, which the WebGL preview's uniforms are named by. */
    val wire: Int,
    val value: Double,
    /** The whole word the number is part of. */
    val token: String,
    /** 1-based line of the digits. */
    val line: Int,
    /**
     * Where the digits are on that line — usually one span, several when equal components of a vector
     * literal share one compiler wire (`[3,3,3,0.08]` has a knob for the three 3s together).
     */
    val spans: List<StackSLSpan>,
    /** `"number"` for a plain literal, else what the compiler says the number does (`"t amount"`, `"wr frequency"`). */
    val role: String,
    /** For a plain literal, the next word — usually the one that consumes it. */
    val feeds: String?,
    /** The source line, trimmed. */
    val context: String,
    /**
     * The operand the compiler took the number as, in its own words — `"mix amount"`, `"smoothstep high"`,
     * `"multiply b"` — or null when nothing took it ([afield.stacksl.StackSLDanglingWire.consumedAs]).
     */
    val takenAs: String? = null
) {
    /** 1-based column of the first digit. */
    val column: Int get() = spans.first().column
}

/** A run of digits within a source line: 1-based [column] and [length] in characters. */
data class StackSLSpan(val column: Int, val length: Int)

/** A colour literal's red, green and blue knobs, and its colour as linear light: see [StackSLStudyShader.colourKnobs]. */
data class StackSLColourKnobs(val knobs: List<Int>, val linear: List<Double>) {
    /** The colour as the window shows it: sRGB-encoded, `#rrggbb`. */
    val hex: String get() = "#" + linear.joinToString("") { c ->
        val encoded = if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1 / 2.4) - 0.055
        "%02x".format(java.util.Locale.US, Math.round(encoded.coerceIn(0.0, 1.0) * 255).toInt())
    }
}
