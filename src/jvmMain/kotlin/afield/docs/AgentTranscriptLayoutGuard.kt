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
            "AgentTranscriptLayoutGuard ok — ${result.transcripts} transcript(s), ${result.frames} create frame(s), " +
                "${result.largeFrames} reviewed large frame(s)"
        )
    }

    data class AuditResult(
        val transcripts: Int,
        val frames: Int,
        val largeFrames: Int,
        val problems: List<String>,
    )

    fun audit(repo: File): AuditResult {
        val corpus = File(repo, CORPUS_REL)
        val problems = mutableListOf<String>()
        if (!corpus.isDirectory) {
            return AuditResult(0, 0, 0, listOf("no transcript corpus at ${corpus.absolutePath}"))
        }

        val files = corpus.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("yaml", "yml") }
            .sortedBy { it.relativeTo(corpus).invariantSeparatorsPath }
            .toList()
        val demoFrames = parseDemoFrames(File(repo, DEMOS_REL), problems)
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

        return AuditResult(files.size, frames.size, large.size, problems.sorted())
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
 * Frames from the loadable demo documents under `dev_docs/agents/demos`.
 *
 * These carry the *same boxes* as a transcript — often literally the same box, since a demo and its
 * transcript are generated from one set of `*_code_.kt` files — but they were outside this guard's
 * population until 2026-09-05, and every one of them had drifted over the ceiling as a result: 24 of
 * 26 boxes, up to 760 x 600. The ceiling was never the missing part; the population was. A `metadata.edn`
 * is EDN rather than YAML, so the frames are read with a regex over `"id" … #afield/rect [x y w h]`
 * rather than through a parser.
 */
private fun parseDemoFrames(demosDir: File, problems: MutableList<String>): List<Frame> {
    if (!demosDir.isDirectory) return emptyList()
    val rectRe = Regex("""\"id\"\s+\"([^\"]+)\"(.*?)#afield/rect\s+\[([^\]]+)\]""", RegexOption.DOT_MATCHES_ALL)
    val result = mutableListOf<Frame>()
    demosDir.listFiles()?.sortedBy { it.name }?.forEach { dir ->
        val edn = File(dir, "metadata.edn")
        if (!edn.isFile) return@forEach
        val text = edn.readText()
        val relative = "${dir.name}/metadata.edn"
        for (match in rectRe.findAll(text)) {
            val box = match.groupValues[1]
            val parts = match.groupValues[3].trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
            if (parts.size != 4) {
                problems.add("$relative: box '$box' has an unreadable frame '${match.groupValues[3]}'")
                continue
            }
            result.add(Frame("$relative#$box", "$relative box '$box'", parts[0], parts[1], parts[2], parts[3]))
        }
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
