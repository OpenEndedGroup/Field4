@file:afield.runtime.completion.CompletionCategory("Synthesis")

package afield.audio.synth

import afield.ai.inference.onnx.OnnxModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.text.Normalizer
import java.util.zip.ZipFile
import kotlin.math.exp
import kotlin.math.tanh

/**
 * English text to the phoneme tokens [TinyVoca] reads: a JVM port of the core of
 * [dizione](https://github.com/n4ze3m/dizione) (Apache-2.0), the phonemizer the model was trained on.
 *
 * What is ported, in dizione's order of authority for one word:
 *
 *  1. the built-in exception list (`exceptions.txt`);
 *  2. function words with their weak forms and stress classes (`function_words.txt`), resolved
 *     right to left across each clause exactly as `sentence.stress_clause` does;
 *  3. the 125k-word dictionary built from CMUdict (`lexicon.tsv`, already in the model's phones);
 *  4. a regular ending (`-s`, `-'s`) on a dictionary word;
 *  5. dizione's unknown-word model, a 459k-parameter bidirectional GRU read greedily as CTC,
 *     re-implemented here from its `g2p.npz` weights.
 *
 * Then the clause rules of `sentence.join_clause` ("the" and "to" before a vowel, linking r, the flap
 * in "it"/"at"), and `symbols.tokenize`.
 *
 * What is **not** ported, so differs from the Python phonemizer: homograph disambiguation (`read`,
 * `record`, `live` take the dictionary's first reading), multi-word phrases and names, the full text
 * normaliser (money, dates, units, URLs; only plain integers, decimals, ordinals and clock times are
 * read out here), compound and prefix derivation, and dizione's ARPAbet-to-phone rules that depend on
 * spelling alignment. The unknown-word model's ARPAbet output is mapped by a context-free table
 * instead, which is right for most phones and loses the flap and the syllabic n.
 *
 * Data files are fetched on first use into the ONNX model cache (`~/.afield/onnx/models/tiny-voca/`),
 * pinned by sha256, like every other model file in Field.
 */
object SpeechPhonemizer {

    /** One word as spoken: its spelling and its `(phone, stress)` pairs. */
    data class Word(val text: String, val phones: List<Pair<String, Int>>, val source: String)

    /** A clause of words, or a punctuation mark between clauses. */
    sealed class Piece {
        /** Words between two marks. */
        data class Clause(val words: List<Word>) : Piece()

        /** A mark the model reads as a pause or an intonation cue. */
        data class Mark(val mark: String) : Piece()
    }

    /**
     * The token sequence for one utterance, with the word each token belongs to.
     *
     * @param symbols phones, marks and the `<bos>`/`<sp>`/`<eos>` specials, in order
     * @param ids positions of [symbols] in the model's vocabulary
     * @param stress 0 none, 1 primary, 2 secondary, per token
     * @param wordOf index into [words] for each phone token, -1 for specials and marks
     */
    class Tokens(
        val symbols: List<String>,
        val ids: LongArray,
        val stress: LongArray,
        val wordOf: IntArray,
        val words: List<Word>
    ) {
        override fun toString(): String =
            symbols.indices.joinToString(" ") { (if (stress[it] == 1L) "ˈ" else if (stress[it] == 2L) "ˌ" else "") + symbols[it] }
    }

    private const val REVISION = "1e73262226dbf4ef02e9df277e81aaa1db5ce16c"
    private const val DIZIONE = "8cd889e4de7be6bd1dd2e6891344a0729fa14dd4"
    private fun hf(path: String) = "https://huggingface.co/n4ze3m/tiny-voca/resolve/$REVISION/$path"
    private fun gh(path: String) = "https://raw.githubusercontent.com/n4ze3m/dizione/$DIZIONE/src/dizione/data/$path"

    /** CMUdict-derived dictionary in the model's phones, 3.4 MB. */
    val LEXICON = OnnxModel("tiny-voca-lexicon", hf("phonemizer/lexicon.tsv"), "tiny-voca/phonemizer/lexicon.tsv",
        "3fc930345c6b3cabc533981c4b212fa309eec83913fcdbd42919338270d64b06", "dizione lexicon, 124,926 words")

    /** Weights of dizione's unknown-word model, 1.8 MB. */
    val G2P_WEIGHTS = OnnxModel("tiny-voca-g2p", hf("phonemizer/g2p.npz"), "tiny-voca/phonemizer/g2p.npz",
        "eb3fd519ff00e72d850928d2fccb968adc36134bca2f3bda09de7e794c2ed01b", "letters to ARPAbet, BiGRU + CTC")

    /** The unknown-word model's settings and output symbols. */
    val G2P_SETTINGS = OnnxModel("tiny-voca-g2p-settings", hf("phonemizer/g2p.json"), "tiny-voca/phonemizer/g2p.json",
        "c518fd839ea0109c0b2b4f778c17506bf0d7653557b8b8c97a4c8dd9883d4432")

    /** Weak forms and stress classes of function words. */
    val FUNCTION_WORDS = OnnxModel("dizione-function-words", gh("function_words.txt"), "tiny-voca/dizione/function_words.txt",
        "d74956771ca5dcd8247b2eb9c2ceadcf525ad3e6df621361071a3e1ee6f9e148")

    /** Hand-written pronunciations that override the dictionary. */
    val EXCEPTIONS = OnnxModel("dizione-exceptions", gh("exceptions.txt"), "tiny-voca/dizione/exceptions.txt",
        "978f6687c0c919b59ef391f29d64d2f82ef43d37150ef4b5a88a6c9394c1fcbd")

    /** Names of the letters, for initialisms. */
    val LETTERS = OnnxModel("dizione-letters", gh("letters.txt"), "tiny-voca/dizione/letters.txt",
        "1cb533378c70c437b952b3938df48f5e94e18187bf612f4881b339ccf5b06882")

    /** Every file this phonemizer needs, for a prefetch. */
    val FILES: List<OnnxModel> get() = listOf(LEXICON, G2P_WEIGHTS, G2P_SETTINGS, FUNCTION_WORDS, EXCEPTIONS, LETTERS)

    // ── the inventory (dizione symbols.py; the order is the model's vocabulary) ──────────────────

    private val SPECIALS = listOf("<pad>", "<bos>", "<eos>", "<sp>", "<unk>")
    private val PUNCTUATION = listOf(",", ".", "?", "!", ";", ":", "\"", "'", "(", ")", "—", "…")
    private val CONSONANTS = listOf("p", "b", "t", "d", "k", "ɡ", "f", "v", "θ", "ð", "s", "z", "ʃ", "ʒ", "h",
        "m", "n", "ŋ", "l", "ɹ", "j", "w", "tʃ", "dʒ", "ɾ", "ʔ")
    private val VOWELS = listOf("ə", "ɐ", "ɚ", "ᵻ", "ɪ", "i", "iː", "ɛ", "æ", "ʌ", "ɑː", "ɔ", "ɔː", "ʊ", "uː", "ɜː",
        "eɪ", "aɪ", "ɔɪ", "oʊ", "aʊ", "iə", "oː", "ɑːɹ", "ɔːɹ", "oːɹ", "ɪɹ", "ɛɹ", "ʊɹ", "aɪɚ", "aɪə", "əl", "n̩")

    /** Vocabulary: symbol to model id. */
    val VOCAB: Map<String, Int> = (SPECIALS + PUNCTUATION + CONSONANTS + VOWELS).withIndex().associate { it.value to it.index }
    private val PHONES = (CONSONANTS + VOWELS).toSet()

    /** The vowel phones; a word with none of these is unpronounceable. */
    val VOWEL_SET: Set<String> = VOWELS.toSet()

    // ── data, loaded once ────────────────────────────────────────────────────────────────────────

    private class FunctionWord(val kind: String, val form: List<Pair<String, Int>>, val final: List<Pair<String, Int>>?)

    private class Data(
        val lexicon: Map<String, String>,
        val exceptions: Map<String, List<Pair<String, Int>>>,
        val functions: Map<String, FunctionWord>,
        val letters: Map<Char, List<Pair<String, Int>>>,
        val g2p: G2p
    )

    private val data: Data by lazy { load() }

    /** Load the data now (downloading on a fresh machine) rather than on the first word. */
    fun warm() { data }

    private fun rows(model: OnnxModel): List<List<String>> =
        Files.readAllLines(model.ensureLocal()).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> line.split(Regex("\t+| {2,}")).map { it.trim() } }

    private fun load(): Data {
        val lexicon = HashMap<String, String>(200_000)
        Files.newBufferedReader(LEXICON.ensureLocal()).useLines { lines ->
            lines.forEach { line -> val tab = line.indexOf('\t'); if (tab > 0) lexicon[line.substring(0, tab)] = line.substring(tab + 1) }
        }
        val exceptions = rows(EXCEPTIONS).filter { it.size == 2 && !it[0].contains(' ') }
            .associate { it[0].lowercase() to pronunciation(it[1]) }
        val functions = rows(FUNCTION_WORDS).filter { it.size in 3..4 }
            .associate { it[0] to FunctionWord(it[1], fromText(it[2]), it.getOrNull(3)?.let(::fromText)) }
        val letters = rows(LETTERS).filter { it.size == 2 && it[0].length == 1 }.associate { it[0][0] to fromText(it[1]) }
        return Data(lexicon, exceptions, functions, letters, G2p.load())
    }

    /** `"h ə l ˈoʊ"` to `(phone, stress)` pairs. */
    fun fromText(text: String): List<Pair<String, Int>> = text.split(' ').filter { it.isNotEmpty() }.map { unit ->
        when (unit[0]) {
            'ˈ' -> unit.substring(1) to 1
            'ˌ' -> unit.substring(1) to 2
            else -> unit to 0
        }
    }

    private fun pronunciation(text: String): List<Pair<String, Int>> {
        val symbols = text.split(' ').filter { it.isNotEmpty() }
        return if (symbols.all { it.trimEnd('0', '1', '2') in ARPA_CONSONANTS || it.trimEnd('0', '1', '2') in ARPA_VOWELS })
            arpabetToPhones(symbols) else fromText(text)
    }

    // ── ARPAbet to phones (context-free; dizione's convert.py does more, see the KDoc above) ──────

    private val ARPA_CONSONANTS = mapOf("B" to "b", "CH" to "tʃ", "D" to "d", "DH" to "ð", "F" to "f", "G" to "ɡ", "HH" to "h",
        "JH" to "dʒ", "K" to "k", "L" to "l", "M" to "m", "N" to "n", "NG" to "ŋ", "P" to "p", "R" to "ɹ", "S" to "s",
        "SH" to "ʃ", "T" to "t", "TH" to "θ", "V" to "v", "W" to "w", "Y" to "j", "Z" to "z", "ZH" to "ʒ")
    private val ARPA_VOWELS = mapOf("AA" to "ɑː", "AE" to "æ", "AH" to "ʌ", "AO" to "ɔː", "AW" to "aʊ", "AY" to "aɪ",
        "EH" to "ɛ", "ER" to "ɜː", "EY" to "eɪ", "IH" to "ɪ", "IY" to "iː", "OW" to "oʊ", "OY" to "ɔɪ", "UH" to "ʊ", "UW" to "uː")
    private val REDUCED = mapOf("AH" to "ə", "IH" to "ɪ", "ER" to "ɚ", "IY" to "i")
    private val RHOTIC = mapOf("AA" to "ɑːɹ", "AO" to "ɔːɹ", "IH" to "ɪɹ", "IY" to "ɪɹ", "EH" to "ɛɹ", "UH" to "ʊɹ")

    /** CMUdict symbols with stress digits to the model's phones. */
    fun arpabetToPhones(symbols: List<String>): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var i = 0
        while (i < symbols.size) {
            val s = symbols[i]
            val base = s.trimEnd('0', '1', '2')
            val stress = s.lastOrNull()?.digitToIntOrNull() ?: 0
            if (base in ARPA_VOWELS) {
                val next = symbols.getOrNull(i + 1)
                if (next == "R" && base in RHOTIC && stress != 0) {
                    out += RHOTIC.getValue(base) to stress; i += 2; continue
                }
                val phone = if (stress == 0) REDUCED[base] ?: ARPA_VOWELS.getValue(base) else ARPA_VOWELS.getValue(base)
                out += phone to stress
            } else {
                out += (ARPA_CONSONANTS[base] ?: return out) to 0
            }
            i++
        }
        return out
    }

    // ── the unknown-word model (dizione g2p.py) ─────────────────────────────────────────────────

    /** Letters to ARPAbet: embeddings with `slots` positions per letter, two BiGRU layers, greedy CTC. */
    class G2p internal constructor(
        private val letters: String,
        private val symbols: List<String>,
        private val slots: Int,
        private val layers: Int,
        private val w: Map<String, Pair<IntArray, FloatArray>>
    ) {
        private fun m(name: String) = w.getValue(name)

        /** The word reduced to letters the model knows, accents removed. */
        fun spellable(word: String): String =
            Normalizer.normalize(word.lowercase(), Normalizer.Form.NFKD).filter { it in letters }.trim('\'')

        /** CMUdict symbols for [word], e.g. `HH AH0 L OW1`. */
        fun predict(word: String): List<String> {
            val spelling = spellable(word)
            if (spelling.isEmpty()) return emptyList()
            val (es, embed) = m("embed__weight")
            val (_, slot) = m("slot")
            val width = es[1]
            val frames = spelling.length * slots
            var x = Array(frames) { FloatArray(width) }
            spelling.forEachIndexed { li, c ->
                val id = letters.indexOf(c) + 1
                for (s in 0 until slots) for (j in 0 until width) x[li * slots + s][j] = embed[id * width + j] + slot[s * width + j]
            }
            for (layer in 0 until layers) {
                val f = gru(x, "l$layer", false)
                val b = gru(x, "l${layer}_reverse", true)
                x = Array(frames) { t -> f[t] + b[t] }
            }
            val (os, ow) = m("out__weight")
            val (_, ob) = m("out__bias")
            val out = ArrayList<String>()
            var previous = 0
            for (t in 0 until frames) {
                var best = 0
                var bestV = Float.NEGATIVE_INFINITY
                for (o in 0 until os[0]) {
                    var v = ob[o]
                    val row = o * os[1]
                    for (j in 0 until os[1]) v += ow[row + j] * x[t][j]
                    if (v > bestV) { bestV = v; best = o }
                }
                if (best != previous && best != 0) out += symbols[best - 1]
                previous = best
            }
            return out
        }

        private fun gru(x: Array<FloatArray>, suffix: String, reverse: Boolean): Array<FloatArray> {
            val (ihs, wih) = m("gru__weight_ih_$suffix")
            val (hhs, whh) = m("gru__weight_hh_$suffix")
            val (_, bih) = m("gru__bias_ih_$suffix")
            val (_, bhh) = m("gru__bias_hh_$suffix")
            val size = hhs[1]
            val inSize = ihs[1]
            val state = FloatArray(size)
            val out = Array(x.size) { FloatArray(size) }
            val gi = FloatArray(3 * size)
            val gh = FloatArray(3 * size)
            val order = if (reverse) x.indices.reversed() else x.indices
            for (t in order) {
                for (r in 0 until 3 * size) {
                    var a = bih[r]; val ri = r * inSize
                    for (j in 0 until inSize) a += wih[ri + j] * x[t][j]
                    gi[r] = a
                    var h = bhh[r]; val rh = r * size
                    for (j in 0 until size) h += whh[rh + j] * state[j]
                    gh[r] = h
                }
                for (j in 0 until size) {
                    val reset = sigmoid(gi[j] + gh[j])
                    val update = sigmoid(gi[size + j] + gh[size + j])
                    val candidate = tanh(gi[2 * size + j] + reset * gh[2 * size + j])
                    state[j] = (1f - update) * candidate + update * state[j]
                }
                state.copyInto(out[t])
            }
            return out
        }

        private fun sigmoid(v: Float) = 1f / (1f + exp(-v))

        companion object {
            internal fun load(): G2p {
                val settings = String(Files.readAllBytes(G2P_SETTINGS.ensureLocal()))
                fun intField(name: String) = Regex("\"$name\"\\s*:\\s*(\\d+)").find(settings)!!.groupValues[1].toInt()
                val letters = Regex("\"letters\"\\s*:\\s*\"([^\"]*)\"").find(settings)!!.groupValues[1]
                val symbolBlock = settings.substringAfter("\"symbols\"").substringAfter('[').substringBefore(']')
                val symbols = Regex("\"([^\"]+)\"").findAll(symbolBlock).map { it.groupValues[1] }.toList()
                return G2p(letters, symbols, intField("slots"), intField("layers"), readNpz(G2P_WEIGHTS.ensureLocal().toFile()))
            }

            /** A NumPy `.npz` of little-endian float32 arrays: name to (shape, data). */
            internal fun readNpz(file: java.io.File): Map<String, Pair<IntArray, FloatArray>> = ZipFile(file).use { zip ->
                zip.entries().asSequence().associate { entry ->
                    val bytes = zip.getInputStream(entry).readBytes()
                    val headerLength = (bytes[8].toInt() and 0xff) or ((bytes[9].toInt() and 0xff) shl 8)
                    val header = String(bytes, 10, headerLength)
                    require("'<f4'" in header && "'fortran_order': False" in header) { "unsupported array ${entry.name}: $header" }
                    val shape = header.substringAfter("'shape': (").substringBefore(')').split(',')
                        .mapNotNull { it.trim().toIntOrNull() }.toIntArray()
                    val buffer = ByteBuffer.wrap(bytes, 10 + headerLength, bytes.size - 10 - headerLength).order(ByteOrder.LITTLE_ENDIAN)
                    val floats = FloatArray(buffer.remaining() / 4).also { buffer.asFloatBuffer().get(it) }
                    entry.name.removeSuffix(".npy") to (shape to floats)
                }
            }
        }
    }

    // ── one word ─────────────────────────────────────────────────────────────────────────────────

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Word>()

    /** Pronunciation of one lower-case spelling, and where it came from. */
    fun word(text: String): Word = cache.getOrPut(text) { lookup(text) }

    private fun lookup(text: String): Word {
        val d = data
        d.exceptions[text]?.let { return Word(text, it, "exception") }
        d.lexicon[text]?.let { return Word(text, fromText(it), "dictionary") }
        if (text.endsWith("'s") && text.length > 3) d.lexicon[text.dropLast(2)]?.let { return Word(text, plural(fromText(it)), "ending") }
        if (text.endsWith("s") && !text.endsWith("ss") && text.length > 3) d.lexicon[text.dropLast(1)]?.let { return Word(text, plural(fromText(it)), "ending") }
        val guessed = promote(arpabetToPhones(d.g2p.predict(text)))
        return if (guessed.any { it.first in VOWEL_SET }) Word(text, guessed, "model") else Word(text, spell(text), "letters")
    }

    /** Letter names, joined; the last letter carries the main stress. */
    fun spell(letters: String): List<Pair<String, Int>> {
        val names = letters.lowercase().mapNotNull { data.letters[it] }
        return names.flatMapIndexed { index, name -> name.map { (p, s) -> p to (if (s != 0 && index < names.size - 1) 2 else s) } }
    }

    private fun plural(phones: List<Pair<String, Int>>): List<Pair<String, Int>> {
        val last = phones.lastOrNull()?.first ?: return phones
        return when (last) {
            "s", "z", "ʃ", "ʒ", "tʃ", "dʒ" -> phones + ("ɪ" to 0) + ("z" to 0)
            "p", "t", "k", "f", "θ" -> phones + ("s" to 0)
            else -> phones + ("z" to 0)
        }
    }

    private fun promote(phones: List<Pair<String, Int>>): List<Pair<String, Int>> {
        val marks = phones.map { it.second }
        if (1 in marks) return phones
        val target = if (2 in marks) marks.lastIndexOf(2) else phones.indexOfFirst { it.first in VOWEL_SET }
        return phones.mapIndexed { i, (p, s) -> p to (if (i == target) 1 else s) }
    }

    // ── text normalisation (a small subset of dizione normalize.py) ────────────────────────────────

    private val ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** An integer read out in words, e.g. `342` → `three hundred forty two`. */
    fun numberWords(n: Long): String = when {
        n < 0 -> "minus " + numberWords(-n)
        n < 20 -> ONES[n.toInt()]
        n < 100 -> TENS[(n / 10).toInt()] + if (n % 10 != 0L) " " + ONES[(n % 10).toInt()] else ""
        n < 1000 -> ONES[(n / 100).toInt()] + " hundred" + if (n % 100 != 0L) " " + numberWords(n % 100) else ""
        else -> listOf(1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million", 1000L to "thousand")
            .first { n >= it.first }.let { (unit, name) ->
                numberWords(n / unit) + " $name" + if (n % unit != 0L) " " + numberWords(n % unit) else ""
            }
    }

    private fun ordinal(words: String): String {
        val last = words.substringAfterLast(' ')
        val head = words.dropLast(last.length)
        val spoken = when (last) {
            "one" -> "first"; "two" -> "second"; "three" -> "third"; "five" -> "fifth"; "eight" -> "eighth"
            "nine" -> "ninth"; "twelve" -> "twelfth"
            else -> if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        }
        return head + spoken
    }

    private fun normalizeNumbers(text: String): String = text
        .replace(Regex("\\b(\\d{1,2}):(\\d{2})\\b")) { m ->
            val minutes = m.groupValues[2].toInt()
            numberWords(m.groupValues[1].toLong()) + when {
                minutes == 0 -> " o'clock"
                minutes < 10 -> " oh " + numberWords(minutes.toLong())
                else -> " " + numberWords(minutes.toLong())
            }
        }
        .replace(Regex("\\b(\\d+)(st|nd|rd|th)\\b")) { ordinal(numberWords(it.groupValues[1].toLong())) }
        .replace(Regex("\\b(\\d+)\\.(\\d+)\\b")) { m ->
            numberWords(m.groupValues[1].toLong()) + " point " + m.groupValues[2].map { ONES[it - '0'] }.joinToString(" ")
        }
        .replace(Regex("\\d[\\d,]*")) { numberWords(it.value.replace(",", "").toLongOrNull() ?: 0) }

    // ── sentences ────────────────────────────────────────────────────────────────────────────────

    private val TOKEN = Regex("[A-Za-zÀ-ɏ]+(?:'[A-Za-z]+)*|[,.?!;:\"()…—]|--")

    /** Clauses and marks, with every word pronounced and the clause rules applied. */
    fun pieces(text: String): List<Piece> {
        val d = data
        val out = ArrayList<Piece>()
        val raw = ArrayList<String>()
        fun flush() {
            if (raw.isEmpty()) return
            out += Piece.Clause(clause(raw, d))
            raw.clear()
        }
        for (m in TOKEN.findAll(normalizeNumbers(text))) {
            val t = m.value
            if (t[0].isLetter()) raw += t
            else {
                flush()
                out += Piece.Mark(if (t == "--") "—" else t)
            }
        }
        flush()
        return out
    }

    private class Unit(val text: String, var phones: List<Pair<String, Int>>, val kind: String, val final: List<Pair<String, Int>>?, val source: String)

    private fun clause(raw: List<String>, d: Data): List<Word> {
        val units = raw.map { original ->
            val lower = original.lowercase()
            val fn = d.functions[lower]
            when {
                lower in d.exceptions -> Unit(lower, d.exceptions.getValue(lower), "c", null, "exception")
                fn != null -> Unit(lower, fn.form, fn.kind, fn.final, "function")
                original.length in 2..5 && original.all { it.isUpperCase() } && lower !in d.lexicon ->
                    Unit(lower, spell(lower), "p", null, "letters")
                else -> word(lower).let { Unit(lower, it.phones, "c", null, it.source) }
            }
        }
        stressClause(units)
        joinClause(units)
        return units.filter { it.phones.isNotEmpty() }.map { Word(it.text, it.phones, it.source) }
    }

    private fun unstress(p: List<Pair<String, Int>>) = p.map { it.first to 0 }

    private fun stressClause(units: List<Unit>) {
        var primaryFollows = false
        for (index in units.indices.reversed()) {
            val u = units[index]
            fun promoted() = u.final ?: promote(u.phones)
            val last = index == units.size - 1
            u.phones = when (u.kind) {
                "u" -> unstress(u.phones)
                "uf" -> if (last) promoted() else unstress(u.phones)
                "ur" -> if (primaryFollows) unstress(u.phones) else promoted()
                "s" -> if (primaryFollows) u.phones else promoted()
                "si" -> if (!primaryFollows) promoted() else if (index == 0) u.phones else unstress(u.phones)
                "pf" -> if (last) promoted() else u.phones
                else -> u.phones
            }
            primaryFollows = primaryFollows || u.phones.any { it.second == 1 }
        }
        if (units.isNotEmpty() && units.all { u -> u.phones.all { it.second == 0 } }) {
            units.lastOrNull { u -> u.phones.any { it.first in VOWEL_SET } }?.let { it.phones = promote(it.phones) }
        }
    }

    private val SPLIT = mapOf("ɑːɹ" to "ɑː", "ɔːɹ" to "ɔː", "oːɹ" to "oː", "ɪɹ" to "ɪ", "ɛɹ" to "ɛ", "ʊɹ" to "ʊ")
    private val RHOTIC_END = setOf("ɚ", "ɜː", "aɪɚ")

    private fun joinClause(units: List<Unit>) {
        for (i in 0 until units.size - 1) {
            val u = units[i]
            val next = units[i + 1]
            if (u.phones.isEmpty() || next.phones.isEmpty() || next.phones[0].first !in VOWEL_SET) continue
            val (last, stress) = u.phones.last()
            when {
                u.text == "the" && last == "ə" -> u.phones = u.phones.dropLast(1) + ("ɪ" to stress)
                u.text == "to" && last == "ə" -> u.phones = u.phones.dropLast(1) + ("ʊ" to stress)
                next.text in setOf("and", "or", "if") -> {}
                last in SPLIT -> u.phones = u.phones.dropLast(1) + (SPLIT.getValue(last) to stress) + ("ɹ" to 0)
                last in RHOTIC_END -> u.phones = u.phones + ("ɹ" to 0)
                last == "t" && u.text in setOf("it", "at") && u.phones.size > 1 && u.phones[u.phones.size - 2].first in VOWEL_SET ->
                    u.phones = u.phones.dropLast(1) + ("ɾ" to 0)
            }
        }
    }

    /** Text to the model's tokens; a text that ends without a mark gets a full stop. */
    fun tokens(text: String): Tokens {
        val pieces = pieces(text).toMutableList()
        if (pieces.lastOrNull() !is Piece.Mark) pieces += Piece.Mark(".")
        val symbols = arrayListOf("<bos>")
        val stress = arrayListOf(0L)
        val wordOf = arrayListOf(-1)
        val words = ArrayList<Word>()
        for (piece in pieces) when (piece) {
            is Piece.Mark -> if (symbols.last() != piece.mark) { symbols += piece.mark; stress += 0L; wordOf += -1 }
            is Piece.Clause -> for (w in piece.words) {
                if (symbols.last() in PHONES || symbols.last() == "<unk>") { symbols += "<sp>"; stress += 0L; wordOf += -1 }
                words += w
                for ((p, s) in w.phones) {
                    symbols += if (p in PHONES) p else "<unk>"
                    stress += s.toLong()
                    wordOf += words.size - 1
                }
            }
        }
        symbols += "<eos>"; stress += 0L; wordOf += -1
        require(words.isNotEmpty()) { "the text has nothing to pronounce: \"$text\"" }
        return Tokens(symbols, LongArray(symbols.size) { VOCAB.getValue(symbols[it]).toLong() }, stress.toLongArray(), wordOf.toIntArray(), words)
    }
}
