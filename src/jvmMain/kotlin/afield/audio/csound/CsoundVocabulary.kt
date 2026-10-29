package afield.audio.csound
/**
 * The opcode list a Csound box completes against, generated from libcsound and joined with the
 * Csound Reference Manual's descriptions.
 */


import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The opcodes a Csound box can complete, with their signatures, as libcsound itself lists them.
 *
 * Nothing here is hand-typed. [generate] asks a running Csound for `csoundNewOpcodeList`, which
 * returns every opcode the library has compiled in — name, output types, input types — and
 * `./gradlew generateCsoundVocabulary` writes that to `src/commonMain/resources/csound-vocabulary.json`,
 * which is committed so the editor has it on a machine with no libcsound. `CsoundLanguageTest`
 * regenerates it in memory, when a libcsound is built, and fails if the committed file differs.
 *
 * The descriptions, syntax lines and arguments are joined on from the Csound Reference Manual's
 * DocBook source, pinned to release 6.18.0 ([readManual], `tools/fetch_csound_manual.py`), under the
 * manual's GNU Free Documentation License; see `csound-manual-COPYING.txt` beside the JSON.
 *
 * Csound's type letters are its own and are kept: `a` audio, `k` control, `i` init, `S` string,
 * `f` frequency-domain, `x` audio or control, `T` string or init, `U` init or control, `w`
 * spectral; lower-case `o p j v h` are optional init arguments (defaults 0, 1, -1, 0.5, 127), upper
 * `O P V J` optional control ones; `m M N n y z Z *` are variable-length lists. [describe] spells
 * one signature out for a completion row.
 */
object CsoundVocabulary {

    @Serializable
    data class Signature(val outypes: String, val intypes: String)

    /** One argument as the manual describes it: its name and the first sentence about it. */
    @Serializable
    data class Argument(val name: String, val text: String)

    /**
     * An opcode: [name] and [signatures] from libcsound; [description], [syntax], [arguments] and
     * [page] from the Csound Reference Manual, when it has an entry for the name.
     */
    @Serializable
    data class Opcode(
        val name: String,
        val signatures: List<Signature>,
        val description: String? = null,
        val syntax: List<String> = emptyList(),
        val arguments: List<Argument> = emptyList(),
        val page: String? = null,
        val category: String? = null,
    )

    /** Where the descriptions came from, and under what terms they are shipped. */
    @Serializable
    data class ManualSource(val tag: String, val sha256: String, val licence: String, val described: Int, val undescribed: List<String>)

    @Serializable
    data class Vocabulary(val csoundVersion: Int, val opcodes: List<Opcode>, val manual: ManualSource? = null)

    /** The manual release and checksum `tools/fetch_csound_manual.py` pins. */
    const val MANUAL_TAG = "6.18.0"
    const val MANUAL_SHA256 = "260be373a943b6b81b255317d3033275dfbee249aa7bcc83e009896c03283eb5"
    const val MANUAL_LICENCE = "GNU Free Documentation License 1.2 or later; Csound Reference Manual, " +
        "(c) 1986, 1992 MIT (Barry L. Vercoe) and its contributors. See csound-manual-COPYING.txt."

    /** A manual page URL for an opcode with a [Opcode.page]. */
    fun manualUrl(page: String): String = "https://csound.com/docs/manual/$page.html"

    /** What the manual says about one name. */
    data class ManualEntry(val page: String, val description: String?, val syntax: List<String>, val arguments: List<Argument>, val category: String? = null)

    private fun clean(xml: String): String = xml
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&ndash;", "–").replace("&mdash;", "—").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ").trim()

    private fun firstSentence(text: String, max: Int = 180): String {
        val t = text.trim().removePrefix("--").removePrefix("–").removePrefix("-").trim()
        val end = Regex("""\.(\s|$)""").find(t)?.range?.first?.let { it + 1 } ?: t.length
        val s = t.substring(0, end).trim()
        return if (s.length <= max) s else s.substring(0, max).trimEnd() + "…"
    }

    /**
     * Read every refentry under `opcodes/` of the manual at [manualDir] into entries by refname.
     * Pure text processing in sorted file order, so the same manual gives the same result.
     */
    fun readManual(manualDir: File): Map<String, ManualEntry> {
        val out = java.util.TreeMap<String, ManualEntry>()
        val secondary = java.util.TreeMap<String, ManualEntry>()
        val files = File(manualDir, "opcodes").listFiles { f -> f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
        for (f in files) {
            val xml = f.readText()
            val id = Regex("""<refentry\s+id="([^"]+)"""").find(xml)?.groupValues?.get(1) ?: continue
            val names = Regex("""<refname>(.*?)</refname>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { clean(it.groupValues[1]) }.filter { it.isNotEmpty() }.toList()
            val purpose = Regex("""<refpurpose>(.*?)</refpurpose>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.let { clean(it.groupValues[1]) }?.takeIf { it.isNotEmpty() }
            val syntax = Regex("""<synopsis>(.*?)</synopsis>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { clean(it.groupValues[1]) }.filter { it.isNotEmpty() }.distinct().toList()
            val args = ArrayList<Argument>()
            val sections = Regex("""<refsect1>\s*<title>(Initialization|Performance)</title>(.*?)</refsect1>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            for (sec in sections) {
                for (para in Regex("""<para>\s*<emphasis>([^<]+)</emphasis>(.*?)</para>""", RegexOption.DOT_MATCHES_ALL).findAll(sec.groupValues[2])) {
                    val name = clean(para.groupValues[1])
                    val rest = clean(para.groupValues[2])
                    if (name.isEmpty() || name.contains(' ') || name in names || name == id || args.any { it.name == name }) continue  // the opcode itself is not an argument
                    // "-- text", or ", ifoo -- text" when several are described together.
                    val text = rest.substringAfter("--", rest).substringAfter("–", rest.substringAfter("--", rest))
                    val sentence = firstSentence(text)
                    if (sentence.isNotEmpty()) args += Argument(name, sentence)
                    if (args.size >= 8) break
                }
            }
            val category = Regex("""<refentryinfo>\s*<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL).find(xml)
                ?.let { clean(it.groupValues[1]).replace(":", ": ").replace(Regex("\\s+"), " ").trim() }?.takeIf { it.isNotEmpty() }
            val entry = ManualEntry(id, purpose, syntax.take(6), args, category)
            for (n in names) out.putIfAbsent(n, entry)
            // A page documents more names than its refname says: `chn` covers chn_k, chn_a and chn_S,
            // and some pages carry a stale refname. The `<command>` of each syntax line is the name
            // Csound users type; those are second-choice keys, after every page's refnames.
            val commands = Regex("""<synopsis>.*?<command>([^<]+)</command>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).map { clean(it.groupValues[1]) }.filter { it.isNotEmpty() }.distinct()
            for (c in commands) secondary.putIfAbsent(c, entry.copy(syntax = syntax.filter { line -> Regex("(^|\\s)" + Regex.escape(c) + "(\\s|$)").containsMatchIn(line) }.ifEmpty { syntax }.take(6)))
        }
        for ((k, v) in secondary) out.putIfAbsent(k, v)
        return out
    }

    /** [library] with the manual's text joined on by opcode name. */
    fun describe(library: Vocabulary, manual: Map<String, ManualEntry>): Vocabulary {
        val opcodes = library.opcodes.map { op ->
            val m = manual[op.name] ?: return@map op
            op.copy(description = m.description, syntax = m.syntax, arguments = m.arguments, page = m.page, category = m.category)
        }
        val undescribed = opcodes.filter { it.description == null }.map { it.name }
        return Vocabulary(library.csoundVersion, opcodes,
            ManualSource(MANUAL_TAG, MANUAL_SHA256, MANUAL_LICENCE, opcodes.size - undescribed.size, undescribed))
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** The committed vocabulary's path in a checkout. */
    const val RESOURCE_PATH = "src/commonMain/resources/csound-vocabulary.json"

    /** Ask libcsound for its opcodes. Needs a built libcsound ([CsoundLibrary]). */
    fun generate(): Vocabulary {
        CsoundInstance("instr 1\nendin", "f 0 0.01").use { cs ->
            val entries = cs.opcodeList()
            val byName = entries.groupBy({ it.first }, { Signature(it.second, it.third) })
            val opcodes = byName.entries
                // Csound lists its internal helpers too: names starting with a non-letter, and the
                // `.`-suffixed polymorphic variants, which the parser resolves from the base name.
                .filter { (name, _) -> name.firstOrNull()?.isLetter() == true }
                .map { (name, sigs) -> Opcode(name, sigs.distinct().sortedWith(compareBy({ it.outypes }, { it.intypes }))) }
                .sortedBy { it.name }
            return Vocabulary(cs.version, opcodes)
        }
    }

    fun toJson(v: Vocabulary): String = json.encodeToString(v) + "\n"

    fun fromJson(text: String): Vocabulary = json.decodeFromString(Vocabulary.serializer(), text)

    /** The committed vocabulary, from the classpath or the checkout. Cached after the first read. */
    val committed: Vocabulary by lazy {
        val text = CsoundVocabulary::class.java.getResourceAsStream("/csound-vocabulary.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: File(RESOURCE_PATH).takeIf { it.isFile }?.readText()
            ?: return@lazy Vocabulary(0, emptyList())
        fromJson(text)
    }

    /** Opcodes by name. */
    val byName: Map<String, Opcode> by lazy { committed.opcodes.associateBy { it.name } }

    private val argName = mapOf(
        'a' to "aSig", 'k' to "kVal", 'i' to "iVal", 'S' to "Sstr", 'f' to "fSig", 'w' to "wSig",
        'x' to "xVal", 'T' to "Sfn|iVal", 'U' to "iVal|kVal", 'B' to "bool", 'l' to "label",
        'o' to "[io=0]", 'p' to "[ip=1]", 'j' to "[ij=-1]", 'v' to "[iv=.5]", 'h' to "[ih=127]",
        'O' to "[kO=0]", 'P' to "[kP=1]", 'V' to "[kV=.5]", 'J' to "[kJ=-1]",
        'm' to "[i…]", 'M' to "[x…]", 'N' to "[x…]", 'n' to "[i…]", 'y' to "[a…]", 'z' to "[k…]",
        'Z' to "[k, a…]", '*' to "[…]", '.' to "any",
    )

    /**
     * One signature as Csound users read one: `aSig poscil xVal, xVal, [ij=-1], [io=0]`. Array types
     * (`k[]`) keep their brackets; a type letter this does not know is shown as itself.
     */
    fun describe(name: String, sig: Signature): String {
        fun spell(types: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < types.length) {
                val c = types[i]
                if (c == '[') {          // `[k]` style array
                    val end = types.indexOf(']', i).takeIf { it > i } ?: (types.length - 1)
                    out += types.substring(i, end + 1)
                    i = end + 1
                    continue
                }
                if (i + 2 < types.length + 1 && types.startsWith("[]", i + 1)) {
                    out += "${c}Arr[]"; i += 3; continue
                }
                out += argName[c] ?: c.toString()
                i++
            }
            return out
        }
        val outs = spell(sig.outypes.removePrefix("(").removeSuffix(")")).filter { it != "any" || sig.outypes.isNotEmpty() }
        val ins = spell(sig.intypes)
        return (if (outs.isEmpty()) "" else outs.joinToString(", ") + " ") + name + (if (ins.isEmpty()) "" else " " + ins.joinToString(", "))
    }

    /** The pinned manual's directory, fetched (checksummed, cached) by `tools/fetch_csound_manual.py`. */
    fun fetchManual(): File {
        val p = ProcessBuilder("python3", "tools/fetch_csound_manual.py").redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        check(p.waitFor() == 0 && out.isNotEmpty()) { "tools/fetch_csound_manual.py failed" }
        return File(out.lines().last())
    }

    /**
     * Write the generated vocabulary to [RESOURCE_PATH]: `./gradlew generateCsoundVocabulary`.
     * Names and signatures come from libcsound; descriptions from the pinned manual.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: RESOURCE_PATH)
        val manualDir = args.getOrNull(1)?.let(::File) ?: fetchManual()
        val v = describe(generate(), readManual(manualDir))
        target.writeText(toJson(v))
        File(manualDir, "copying.txt").copyTo(File(target.parentFile, "csound-manual-COPYING.txt"), overwrite = true)
        println("wrote ${v.opcodes.size} opcodes (${v.opcodes.sumOf { it.signatures.size }} signatures) from Csound ${v.csoundVersion} " +
            "to $target; ${v.manual!!.described} described from manual ${v.manual.tag}, ${v.manual.undescribed.size} not")
    }
}

/** `csoundNewOpcodeList`: (name, outypes, intypes) for every opcode entry the library has. */
internal fun CsoundInstance.opcodeList(): List<Triple<String, String, String>> = withHandle { cs ->
    Arena.ofConfined().use { a ->
        val ptr = a.allocate(ADDRESS)
        val n = CsoundNative.newOpcodeList(cs, ptr)
        if (n <= 0) return@withHandle emptyList()
        val list = ptr.get(ADDRESS, 0)
        // opcodeListEntry: char *opname, *outypes, *intypes; int flags — 32 bytes with padding.
        val entries = list.reinterpret(32L * n)
        val out = ArrayList<Triple<String, String, String>>(n)
        for (k in 0 until n) {
            val base = 32L * k
            out += Triple(
                CsoundNative.cString(entries.get(ADDRESS, base)),
                CsoundNative.cString(entries.get(ADDRESS, base + 8)),
                CsoundNative.cString(entries.get(ADDRESS, base + 16)),
            )
            entries.get(JAVA_INT, base + 24)
        }
        CsoundNative.disposeOpcodeList(cs, list)
        out
    }
}
