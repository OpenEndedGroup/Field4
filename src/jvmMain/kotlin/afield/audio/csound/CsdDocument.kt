package afield.audio.csound

/**
 * A Csound box's text, read as a `.csd` — the unified file format Csound has used since the late
 * 1990s: `<CsOptions>`, `<CsInstruments>` and `<CsScore>` inside `<CsoundSynthesizer>`.
 *
 * Text with no `<CsInstruments>` tag is read as a bare orchestra with no score, so a box can be
 * five lines of `instr … endin` and nothing else.
 *
 * Every section records the 0-based line of the box text its body starts on, which is what turns
 * a line Csound reports, or a line the cursor is on, into a place in the box.
 */
class CsdDocument private constructor(
    val text: String,
    val options: Section?,
    val orchestra: Section,
    val score: Section?,
) {
    /** One section's body and where it starts. [firstLine] is 0-based in [text]; [lineCount] its lines. */
    data class Section(val body: String, val firstLine: Int) {
        val lineCount: Int get() = body.split("\n").size
        /** True when 0-based box line [line] is inside this section. */
        operator fun contains(line: Int): Boolean = line >= firstLine && line < firstLine + lineCount
    }

    /** What part of the document 0-based box line [line] is in. */
    enum class Part { OPTIONS, ORCHESTRA, SCORE, OUTSIDE }

    fun partAt(line: Int): Part = when {
        options != null && line in options -> Part.OPTIONS
        line in orchestra -> Part.ORCHESTRA
        score != null && line in score -> Part.SCORE
        else -> Part.OUTSIDE
    }

    /** `<CsOptions>` flags, one per whitespace-separated word. */
    val optionFlags: List<String>
        get() = options?.body?.lines()?.map { it.substringBefore(';').trim() }?.flatMap { it.split(Regex("\\s+")) }?.filter { it.isNotEmpty() }.orEmpty()

    /** An `instr` block of the orchestra: its names (numbers or names), and 0-based box lines. */
    data class Instrument(val names: List<String>, val startLine: Int, val endLine: Int)

    /** Every `instr … endin` block, in order. */
    val instruments: List<Instrument> by lazy {
        val out = ArrayList<Instrument>()
        val lines = orchestra.body.split("\n")
        var open: Pair<List<String>, Int>? = null
        for ((k, raw) in lines.withIndex()) {
            val t = stripComment(raw).trim()
            val m = INSTR.matchEntire(t)
            if (m != null) {
                open = m.groupValues[1].split(',').map { it.trim().removePrefix("+") }.filter { it.isNotEmpty() } to k
            } else if (ENDIN.matches(t) && open != null) {
                out += Instrument(open.first, orchestra.firstLine + open.second, orchestra.firstLine + k)
                open = null
            }
        }
        out
    }

    /** The instrument whose block contains 0-based box line [line], or null. */
    fun instrumentAt(line: Int): Instrument? = instruments.firstOrNull { line in it.startLine..it.endLine }

    /** The orchestra text of [instrument], `instr` through `endin`. */
    fun instrumentText(instrument: Instrument): String =
        text.split("\n").subList(instrument.startLine, instrument.endLine + 1).joinToString("\n")

    /** A score `i` statement: what it plays and its 0-based box line. `.` carries the last p1, as Csound does. */
    data class Event(val instrument: String, val line: Int)

    /** Every `i` statement in the score, with carried p1s resolved. Text inside `{ }` loops is counted once. */
    val events: List<Event> by lazy {
        val sco = score ?: return@lazy emptyList()
        val out = ArrayList<Event>()
        var last: String? = null
        for ((k, raw) in sco.body.split("\n").withIndex()) {
            val t = stripComment(raw).trim()
            if (!t.startsWith("i")) continue
            val rest = t.removePrefix("i").trimStart()
            if (rest.isEmpty() || !(rest[0].isDigit() || rest[0] == '"' || rest[0] == '.')) continue
            val p1 = when {
                rest[0] == '"' -> rest.substring(1).substringBefore('"')
                rest[0] == '.' -> last ?: continue
                else -> rest.takeWhile { it.isDigit() || it == '.' }.substringBefore('.')
            }
            last = p1
            out += Event(p1, sco.firstLine + k)
        }
        out
    }

    /** `chn_k`/`chn_a`/`chn_S` declarations: channel name to its 0-based box line. */
    val declaredChannels: Map<String, Int> by lazy {
        val out = LinkedHashMap<String, Int>()
        for ((k, raw) in orchestra.body.split("\n").withIndex()) {
            CHN_DECL.find(stripComment(raw))?.let { out.putIfAbsent(it.groupValues[1], orchestra.firstLine + k) }
        }
        out
    }

    /** Every channel name the orchestra uses: declared ones, and the first argument of the bus opcodes. */
    val channelNames: Set<String> by lazy {
        val out = LinkedHashSet(declaredChannels.keys)
        for (raw in orchestra.body.split("\n")) CHN_USE.findAll(stripComment(raw)).forEach { out += it.groupValues[1] }
        out
    }

    companion object {
        private val INSTR = Regex("""instr\s+(.+)""")
        private val ENDIN = Regex("""endin\b.*""")
        private val CHN_DECL = Regex("""\bchn_[kaS]\s+"([^"]+)"""")
        private val CHN_USE = Regex("""\b(?:chnget|chnset|chnmix|chnclear|invalue|outvalue|chn_[kaS])\b[^"]*"([^"]+)"""")

        /** `;` and `//` comments end a line; a `;` inside a string does not. */
        fun stripComment(line: String): String {
            var inString = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '"') inString = !inString
                else if (!inString && c == ';') return line.substring(0, i)
                else if (!inString && c == '/' && i + 1 < line.length && line[i + 1] == '/') return line.substring(0, i)
                i++
            }
            return line
        }

        /** Read [text] as a CSD, or as a bare orchestra when it has no `<CsInstruments>`. */
        fun parse(text: String): CsdDocument {
            fun section(tag: String): Section? {
                val open = Regex("<$tag>", RegexOption.IGNORE_CASE).find(text) ?: return null
                val close = Regex("</$tag>", RegexOption.IGNORE_CASE).find(text, open.range.last + 1)
                val bodyStart = text.indexOf('\n', open.range.last).let { if (it < 0) text.length else it + 1 }
                val end = close?.range?.first ?: text.length
                // The closing tag's own line is not body.
                val bodyEnd = text.lastIndexOf('\n', (end - 1).coerceAtLeast(0)).let { if (it < bodyStart) bodyStart else it }
                val body = if (bodyEnd > bodyStart) text.substring(bodyStart, bodyEnd) else ""
                return Section(body, text.substring(0, bodyStart).count { it == '\n' })
            }
            val orc = section("CsInstruments")
            return if (orc == null) CsdDocument(text, null, Section(text, 0), null)
            else CsdDocument(text, section("CsOptions"), orc, section("CsScore"))
        }
    }
}
