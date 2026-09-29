@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.ai.core.Ai4ModelDefaults
import afield.ai.core.LlmReasoningEffort
import afield.ai.core.SilentLog
import afield.ai.core.createLlmBridge
import afield.logger.lprintln
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Short names for the numbers of a program — `wave drift speed`, `star brightness` — for the sliders
 * of a study card, written by Field's fast model from what the compiler already knows.
 *
 * A card can always say what reads a number: StackSL's compiler records the operand it took it as
 * (`mix amount`, `multiply b`), RectSL's knobs the word that reads them (`->xy x`). That is exact, but
 * a program whose numbers mostly scale and offset reads as a column of `multiplies` and `adds`. So the
 * card shows that at first, and asks the fast model once, for every number of the program at a time,
 * to say in a few words what turning each one changes, given the program with its comments and each
 * number's line, spelling and operand. The compiler's label stays on the slider's tooltip.
 *
 * Names are kept by the program's shape — its text with every number blanked out, and the operands —
 * so turning a number, or keeping one, never asks again; they are stored in the study's directory,
 * beside its revisions, so a document carries them. Nothing is asked for a box in no open document (a
 * test's box), nor for a document an agent opened unless `AFIELD_AI_KNOB_NAMES=1`; `0` turns asking
 * off, and names already stored are still shown.
 */
internal object KnobNames {

    private const val FILE = "knob-names.json"
    private const val KEPT_SHAPES = 32
    private const val RETRY_AFTER_MS = 10 * 60_000L
    private const val PROGRAM_CHARS = 12_000

    private val mapper = jacksonObjectMapper()
    private val known = ConcurrentHashMap<String, Map<Int, String>>()
    private val asked = ConcurrentHashMap<String, Long>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "knob-names").apply { isDaemon = true } }
    private val number = Regex("""0x[0-9a-fA-F]+|(?<![A-Za-z_\d.])-?(?:\d+\.?\d*|\.\d+)""")

    /** What the program's knobs are named by: its text with the numbers blanked, the language and the operands. */
    fun shape(language: String, source: String, knobs: List<StudyKnob>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$language\n${number.replace(source, "#")}\n${knobs.joinToString("\n") { it.operand }}".toByteArray())
        return digest.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * The names of [knobs] of [source], by knob index, if they are known; otherwise null, having — when
     * [ask] and asking is on — asked the fast model for them in the background, once per shape, with
     * [ready] called when they are stored. [directory] is the study's; [about] a sentence on what the
     * language is, for the model.
     */
    fun names(language: String, about: String, source: String, knobs: List<StudyKnob>, directory: File?, ask: Boolean, ready: () -> Unit): Map<Int, String>? {
        if (knobs.isEmpty()) return emptyMap()
        val key = shape(language, source, knobs)
        val slot = "${directory?.absolutePath}|$key"
        known[slot]?.let { return it }
        stored(directory, key)?.let { known[slot] = it; return it }
        if (!ask || !asking()) return null
        val now = System.currentTimeMillis()
        val last = asked[slot]
        if (last != null && now - last < RETRY_AFTER_MS) return null
        asked[slot] = now
        worker.execute {
            try {
                val (model, named) = request(language, about, source, knobs)
                if (named.isEmpty()) return@execute
                known[slot] = named
                store(directory, key, model, named)
                ready()
            } catch (e: Throwable) {
                lprintln("warning,study", "knob names for a $language program were not written: ${e.message}")
            }
        }
        return null
    }

    /** Whether to ask at all, by `AFIELD_AI_KNOB_NAMES` (or `afield.ai.knobNames`): `0`/`false` no. */
    private fun asking(): Boolean {
        val flag = System.getenv("AFIELD_AI_KNOB_NAMES") ?: System.getProperty("afield.ai.knobNames")
        return !(flag.equals("false", ignoreCase = true) || flag == "0")
    }

    /** Whether a card on [box] should ask: its document is open, and a person opened it or asking was forced on. */
    fun shouldAsk(box: afield.Box): Boolean {
        val document = BoxStudies.documentOf(box) ?: return false
        val flag = System.getenv("AFIELD_AI_KNOB_NAMES") ?: System.getProperty("afield.ai.knobNames")
        return !document.agentManaged || flag.equals("true", ignoreCase = true) || flag == "1"
    }

    internal fun prompt(language: String, about: String, source: String, knobs: List<StudyKnob>): String = buildString {
        appendLine("You label the sliders of a live $language program in Field4. $about")
        appendLine("Each slider turns one number typed in the program. For each knob below, write a label of 2 to 4 lowercase words")
        appendLine("saying what turning that number changes in what the program draws: \"wave drift speed\", \"star brightness\", \"horizon glow height\".")
        appendLine("Rules:")
        appendLine("- Return only JSON: an object from each knob's index (as a string, without the k) to its label, for every knob below.")
        appendLine("- Use the program's comments and its own names (marks, definitions, rectangle names) when they say what a line is for.")
        appendLine("- Say what the number controls, not the arithmetic: \"multiplies\" or \"adds\" alone says nothing.")
        appendLine("- Knobs on one line that do different jobs get different labels.")
        appendLine("- No punctuation, no quotes, no knob numbers, no line numbers.")
        appendLine()
        appendLine("Program, with line numbers:")
        source.take(PROGRAM_CHARS).lines().forEachIndexed { i, line -> appendLine("${(i + 1).toString().padStart(4)}  $line") }
        if (source.length > PROGRAM_CHARS) appendLine("     … (the rest of the program is not shown)")
        appendLine()
        appendLine("Knobs (index, line, the number as typed, and what reads it):")
        knobs.forEach { appendLine("k${it.index} · line ${it.line} · ${it.typed} · ${it.operand}") }
    }

    private fun request(language: String, about: String, source: String, knobs: List<StudyKnob>): Pair<String, Map<Int, String>> {
        val model = Ai4ModelDefaults.envOrDefault("AFIELD_AI_KNOB_NAMES_MODEL", Ai4ModelDefaults.fastModel())
        val bridge = createLlmBridge(model, SilentLog, LlmReasoningEffort.LOW)
        val state = bridge.startThread(fileKey = "ai4:knob-names", fileText = "", instructions = "Return only the requested JSON. Do not call tools.")
        val text = bridge.send(state, prompt(language, about, source, knobs), maxOutputTokens = 200L + 24L * knobs.size, maxToolRounds = 0).text
        return model to parse(text, knobs.map { it.index }.toSet())
    }

    /** The labels in a reply: its JSON object, keys `0` or `k0`, each label trimmed to a few plain words. */
    internal fun parse(text: String, indices: Set<Int>): Map<Int, String> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()
        val tree = runCatching { mapper.readTree(text.substring(start, end + 1)) }.getOrNull() ?: return emptyMap()
        return tree.properties().mapNotNull { (key, value) ->
            val index = key.trim().removePrefix("k").toIntOrNull()?.takeIf { it in indices } ?: return@mapNotNull null
            val label = value.asText().replace(Regex("[\"'`.]"), "").replace(Regex("\\s+"), " ").trim().take(40)
            label.takeIf { it.isNotBlank() }?.let { index to it }
        }.toMap()
    }

    private fun stored(directory: File?, key: String): Map<Int, String>? = runCatching {
        val file = File(directory ?: return null, FILE).takeIf { it.isFile } ?: return null
        mapper.readTree(file)[key]?.get("names")?.properties()?.associate { (k, v) -> k.toInt() to v.asText() }
    }.getOrNull()

    @Synchronized
    private fun store(directory: File?, key: String, model: String, names: Map<Int, String>) {
        directory ?: return
        val file = File(directory, FILE)
        @Suppress("UNCHECKED_CAST")
        val all = (runCatching { mapper.readValue(file, LinkedHashMap::class.java) }.getOrNull() as? LinkedHashMap<String, Any?>) ?: linkedMapOf()
        all.remove(key)
        all[key] = linkedMapOf("model" to model, "names" to names.toSortedMap().mapKeys { it.key.toString() })
        while (all.size > KEPT_SHAPES) all.remove(all.keys.first())
        directory.mkdirs()
        val temporary = File(directory, "$FILE.tmp")
        mapper.writerWithDefaultPrettyPrinter().writeValue(temporary, all)
        temporary.renameTo(file)
    }
}
