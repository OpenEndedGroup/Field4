package afield.docs

import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.system.exitProcess

/**
 * Keeps OperationTranscript boxes visible and compact without outlawing deliberately composed boards.
 *
 * A Field box frame is the labelled canvas rectangle, not its Monaco editor or output viewport. Ordinary
 * boxes therefore start near the positive canvas origin at roughly `300 x 100`; see
 * `dev_docs/agents/OPERATION_TRANSCRIPT.md`. A larger visual or capture card is allowed only when its
 * `file#box` key has a concrete reason in `dev_docs/agents/transcript_layout_exceptions.yaml`.
 *
 * ### The corpus this ceiling is stated over
 *
 * Both halves: every `create` op under `dev_docs/agents/transcripts`, *and* every box in every
 * `metadata.edn` under `dev_docs/agents/demos`. The two are named here because the failure message names
 * a ceiling and not a subset, and a message that says "the ordinary ceiling" while enforcing over
 * half the boxes is the defect this guard shipped with: on 2026-09-18 a demo document went out at
 * `700 x 900` and twice `1180 x 900` — 6.6x the demo corpus's median box height and 1.9x this
 * width ceiling — and everything was green. [AuditResult] therefore reports the two populations
 * separately and [main] prints both, so "no violations" can never be read off a corpus of zero.
 *
 * The ceiling was `520 x 120` until 2026-09-13, when the whole corpus grew by `+100 w` / `+50 h`: the
 * compaction that brought these boxes down to `200 x 50` had overshot, leaving labels cramped. Growing
 * the boxes without growing the ceiling would have turned every name-widened box into a violation, so
 * the two move together and the ratio between an ordinary box and the ceiling is unchanged.
 *
 * Run with `./gradlew checkAgentTranscriptLayouts` (also part of `check` and `docsGates`).
 */
object AgentTranscriptLayoutGuard {
    const val MAX_ORDINARY_WIDTH = 620.0
    const val MAX_ORDINARY_HEIGHT = 170.0

    private const val CORPUS_REL = "dev_docs/agents/transcripts"
    private const val DEMOS_REL = "dev_docs/agents/demos"
    private const val EXCEPTIONS_REL = "dev_docs/agents/transcript_layout_exceptions.yaml"
    private val CREATE_VERBS = setOf("create", "new-box", "box")

    private data class Frame(
        val key: String,
        val source: String,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    ) {
        val isLarge: Boolean
            get() = width > MAX_ORDINARY_WIDTH || height > MAX_ORDINARY_HEIGHT
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = repoRoot()
        val result = audit(repo)
        if (result.problems.isNotEmpty()) {
            System.err.println("AgentTranscriptLayoutGuard FAILED with ${result.problems.size} problem(s):")
            result.problems.forEach { System.err.println("  - $it") }
            System.err.println(
                "\nUse compact positive create.frame geometry for ordinary boxes. If a large frame is part of " +
                    "the document or capture composition, add a specific file#box reason to $EXCEPTIONS_REL."
            )
            exitProcess(1)
        }
        println(
            "AgentTranscriptLayoutGuard ok — population: ${result.transcripts} transcript(s) under $CORPUS_REL " +
                "+ ${result.demos} demo document(s) under $DEMOS_REL, ${result.frames} create frame(s), " +
                "${result.largeFrames} reviewed large frame(s)"
        )
    }

    /**
     * The population, stated as two numbers rather than one.
     *
     * [transcripts] and [demos] are separate fields because the ceiling in the failure message names
     * *every* box in the corpus, and a single total cannot say that half of it went to zero. The
     * demo half was outside this guard until 2026-09-05 and had drifted entirely over the ceiling
     * while the guard reported success; a count that cannot distinguish "no demos" from "no demo
     * violations" would let that happen again silently.
     */
    data class AuditResult(
        val transcripts: Int,
        val demos: Int,
        val frames: Int,
        val largeFrames: Int,
        val problems: List<String>,
    )

    fun audit(repo: File): AuditResult {
        val corpus = File(repo, CORPUS_REL)
        val demosDir = File(repo, DEMOS_REL)
        val problems = mutableListOf<String>()
        if (!corpus.isDirectory) {
            return AuditResult(0, 0, 0, 0, listOf("no transcript corpus at ${corpus.absolutePath}"))
        }
        // Half a corpus is not a smaller corpus, it is a different guard. The ceiling this reports
        // is stated over "every shipped box", so a demos directory that has moved or been renamed
        // must stop the run rather than shrink it.
        if (!demosDir.isDirectory) {
            return AuditResult(0, 0, 0, 0, listOf("no demo corpus at ${demosDir.absolutePath}"))
        }

        val files = corpus.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("yaml", "yml") }
            .sortedBy { it.relativeTo(corpus).invariantSeparatorsPath }
            .toList()
        val demoDocuments = demosDir.listFiles()
            ?.filter { it.isDirectory && File(it, "metadata.edn").isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        val demoFrames = demoDocuments.flatMap { parseDemoFrames(it, problems) }
        val frames = files.flatMap { parseFrames(corpus, it, problems) } + demoFrames
        val byKey = frames.groupBy { it.key }
        byKey.filterValues { it.size > 1 }.forEach { (key, duplicates) ->
            problems.add("$key identifies ${duplicates.size} create frames; box ids must be unique within a transcript")
        }

        for (frame in frames) {
            if (!frame.x.isFinite() || !frame.y.isFinite() || !frame.width.isFinite() || !frame.height.isFinite()) {
                problems.add("${frame.source}: frame values must be finite")
                continue
            }
            if (frame.x < 0.0 || frame.y < 0.0) {
                problems.add(
                    "${frame.source}: negative position (${frame.x}, ${frame.y}); ordinary transcripts begin in " +
                        "the positive canvas near (40, 40)"
                )
            }
            if (frame.width <= 0.0 || frame.height <= 0.0) {
                problems.add("${frame.source}: frame dimensions must be positive, got ${frame.width} x ${frame.height}")
            }
        }

        val exceptions = parseExceptions(File(repo, EXCEPTIONS_REL), problems)
        val large = frames.filter { it.isLarge }
        for (frame in large) {
            if (frame.key !in exceptions) {
                problems.add(
                    "${frame.source}: ${frame.width} x ${frame.height} exceeds the ordinary " +
                        "${MAX_ORDINARY_WIDTH.toInt()} x ${MAX_ORDINARY_HEIGHT.toInt()} ceiling; compact it or add " +
                        "a reviewed `${frame.key}` reason to $EXCEPTIONS_REL"
                )
            }
        }
        for ((key, reason) in exceptions) {
            val matched = byKey[key]
            when {
                reason.length < 20 -> problems.add("$EXCEPTIONS_REL: `$key` needs a specific reason of at least 20 characters")
                matched == null -> problems.add("$EXCEPTIONS_REL: `$key` names no create frame")
                matched.none { it.isLarge } -> problems.add("$EXCEPTIONS_REL: `$key` is no longer large; remove its stale exception")
            }
        }

        return AuditResult(files.size, demoDocuments.size, frames.size, large.size, problems.sorted())
    }

    private fun parseFrames(corpus: File, file: File, problems: MutableList<String>): List<Frame> {
        val relative = file.relativeTo(corpus).invariantSeparatorsPath
        val loaded = runCatching { Yaml().load<Any?>(file.readText()) }.getOrElse { error ->
            problems.add("$relative: invalid YAML: ${error.message}")
            return emptyList()
        }
        val operations = when (loaded) {
            is List<*> -> loaded
            is Map<*, *> -> (loaded["operations"] as? List<*>) ?: (loaded["ops"] as? List<*>) ?: emptyList<Any?>()
            else -> emptyList()
        }
        val result = mutableListOf<Frame>()
        for ((index, rawOperation) in operations.withIndex()) {
            val operation = rawOperation as? Map<*, *> ?: continue
            val verb = operation.keys.firstOrNull()?.toString()?.lowercase() ?: continue
            if (verb !in CREATE_VERBS) continue
            val payload = operation.values.firstOrNull() as? Map<*, *>
            if (payload == null) {
                problems.add("$relative operation ${index + 1}: $verb payload is not a map")
                continue
            }
            val box = (payload["box"] ?: payload["uid"] ?: payload["id"] ?: payload["target"])
                ?.toString()?.takeIf { it.isNotBlank() }
            if (box == null) {
                problems.add("$relative operation ${index + 1}: $verb has no box id")
                continue
            }
            val source = "$relative#$box"
            val rawFrame = (payload["frame"] ?: payload["rect"]) as? Map<*, *>
            if (rawFrame == null) {
                problems.add("$source: create has no frame map; an omitted frame is an invisible Rect(0,0,0,0)")
                continue
            }
            val values = listOf("x", "y", "w", "h").associateWith { field ->
                val value = rawFrame[field]
                if (value !is Number) problems.add("$source: frame.$field must be numeric, got ${value ?: "missing"}")
                (value as? Number)?.toDouble() ?: Double.NaN
            }
            result.add(
                Frame(
                    key = source,
                    source = source,
                    x = values.getValue("x"),
                    y = values.getValue("y"),
                    width = values.getValue("w"),
                    height = values.getValue("h"),
                )
            )
        }
        return result
    }

    private fun parseExceptions(file: File, problems: MutableList<String>): Map<String, String> {
        if (!file.isFile) {
            problems.add("no layout-exception registry at ${file.absolutePath}")
            return emptyMap()
        }
        val loaded = runCatching { Yaml().load<Any?>(file.readText()) }.getOrElse { error ->
            problems.add("$EXCEPTIONS_REL: invalid YAML: ${error.message}")
            return emptyMap()
        }
        val root = loaded as? Map<*, *>
        val raw = root?.get("largeFrames") as? Map<*, *>
        if (raw == null) {
            problems.add("$EXCEPTIONS_REL: expected a `largeFrames` map")
            return emptyMap()
        }
        val result = linkedMapOf<String, String>()
        raw.forEach { (rawKey, rawReason) ->
            val key = rawKey?.toString()?.trim().orEmpty()
            val reason = rawReason?.toString()?.trim().orEmpty()
            if (key.isEmpty()) problems.add("$EXCEPTIONS_REL: empty largeFrames key")
            else result[key] = reason
        }
        return result
    }

    /**
     * Frames from one loadable demo document under `dev_docs/agents/demos`.
     *
     * These carry the *same boxes* as a transcript — often literally the same box, since a demo and
     * its transcript are generated from one set of `*_code_.kt` files — but they were outside this
     * guard's population until 2026-09-05, and every one of them had drifted over the ceiling as a
     * result: 24 of 26 boxes, up to 760 x 600. The ceiling was never the missing part; the
     * population was. A `metadata.edn` is EDN rather than YAML, so the frames are read with a regex
     * rather than through a parser.
     *
     * **The scan is per box, not one spanning regex, and that is the whole point of this function.**
     * The original form matched `"id" "…" (.*?) #afield/rect [x y w h]` across the document, so a
     * box with no frame silently absorbed the *next* box's rectangle and hid that box from the
     * guard entirely — one omission, two boxes unchecked, no message. `parseFrames` rejects exactly
     * that omission on the YAML side ("an omitted frame is an invisible Rect(0,0,0,0)"); the EDN
     * side swallowed it. So the document is cut into one segment per `"id"`, each segment must
     * carry exactly one `"frame" #afield/rect`, and a segment that does not is a problem rather
     * than a gap. Re-measured 2026-09-20: identical attribution to the spanning form over all 20
     * demos, 72 frames, so the change adds a failure mode and removes none.
     *
     * That corpus is **21 demos and 77 demo frames** as of 2026-09-21, when `marf_demo` landed. The
     * sentence above is deliberately not edited to match: what it claims is that the two parsers
     * agreed *over the corpus they were compared on*, and retyping the numbers would assert a
     * comparison nobody re-ran. [main] prints the live population on every run, which is the
     * number to believe. To recount the demo half on its own:
     *
     * ```
     * grep -roh '"frame" #afield.rect' dev_docs/agents/demos --include=metadata.edn | wc -l
     * ```
     *
     * Two traps in that one line, both hit while writing it. `--include=metadata.edn` is
     * load-bearing: without it the `document.md` mirrors are counted too and the answer is 120.
     * And it is `grep -oh … | wc -l`, not `grep -c`, because `-c` counts matching *lines* and a
     * `metadata.edn` is one enormous line — that spelling answers 64. The glob you would reach for
     * first cannot be written in this KDoc at all: an adjacent `*` and `/` close the comment and
     * swallow the rest of the file. `dev_docs/GUARD_CORPORA.md` is the argument for why a dated
     * measurement in prose has to say that it is one.
     *
     * `root` is the document's implicit container box and legitimately has no frame; it is the only
     * id exempted, and it is exempted by name so that a *box* called anything else cannot slip
     * through as one.
     */
    private fun parseDemoFrames(dir: File, problems: MutableList<String>): List<Frame> {
        val edn = File(dir, "metadata.edn")
        if (!edn.isFile) return emptyList()
        val text = edn.readText()
        val relative = "${dir.name}/metadata.edn"
        val idRe = Regex("""\"id\"\s+\"([^\"]+)\"""")
        val frameRe = Regex("""\"frame\"\s+#afield/rect\s+\[([^\]]+)\]""")
        val ids = idRe.findAll(text).toList()
        val result = mutableListOf<Frame>()
        for ((index, id) in ids.withIndex()) {
            val box = id.groupValues[1]
            val end = if (index + 1 < ids.size) ids[index + 1].range.first else text.length
            val segment = text.substring(id.range.last + 1, end)
            val rects = frameRe.findAll(segment).toList()
            if (rects.isEmpty()) {
                if (box != "root") {
                    problems.add(
                        "$relative: box '$box' has no `\"frame\" #afield/rect`; a frameless box is an " +
                            "invisible Rect(0,0,0,0), and it used to make the next box invisible to this guard too"
                    )
                }
                continue
            }
            if (rects.size > 1) {
                problems.add("$relative: box '$box' declares ${rects.size} frames; exactly one is expected")
                continue
            }
            val raw = rects.single().groupValues[1]
            val parts = raw.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
            if (parts.size != 4) {
                problems.add("$relative: box '$box' has an unreadable frame '$raw'")
                continue
            }
            result.add(Frame("$relative#$box", "$relative box '$box'", parts[0], parts[1], parts[2], parts[3]))
        }
        return result
    }

    private fun repoRoot(): File {
        var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
        while (candidate != null) {
            if (File(candidate, "public/docs").isDirectory && File(candidate, "src/jvmMain/kotlin").isDirectory) {
                return candidate
            }
            candidate = candidate.parentFile
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }
}
