package afield.csound

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
 * which is committed so the editor has it on a machine with no libcsound. `CsoundVocabularyTest`
 * regenerates it in memory, when a libcsound is built, and fails if the committed file differs.
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

    @Serializable
    data class Opcode(val name: String, val signatures: List<Signature>)

    @Serializable
    data class Vocabulary(val csoundVersion: Int, val opcodes: List<Opcode>)

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

    /** Write the generated vocabulary to [RESOURCE_PATH]: `./gradlew generateCsoundVocabulary`. */
    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: RESOURCE_PATH)
        val v = generate()
        target.writeText(toJson(v))
        println("wrote ${v.opcodes.size} opcodes (${v.opcodes.sumOf { it.signatures.size }} signatures) from Csound ${v.csoundVersion} to $target")
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
