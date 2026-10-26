package afield.ai.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Eval tasks mined from the operation transcripts under `dev_docs/agents/transcripts/`, rather than written by hand.
 *
 * [InFieldAgentEvals] is seventeen tasks; a pass rate over seventeen moves 6% per task. The transcripts hold
 * about a thousand boxes whose code somebody wrote and ran on purpose, so each runnable one is a task:
 *
 * - **goal**: make a box of this name, under these parents, that does what the box's own leading comment
 *   says, and whose output includes what the real box printed or displayed;
 * - **setup**: the box's ancestors in its transcript, created with their code and run in order;
 * - **solution**: the box's real code (`NewBoxRequest` + `RunBoxRequest`/`LaunchBoxRequest`), so the corpus
 *   is its own scripted floor;
 * - **checks**: derived from what the solution actually said when run, twice ([calibrate]): only output
 *   that came out the same both times becomes a check.
 *
 * Mining is two stages. [mine] is static — YAML only — and rejects a box with a reason (never executed by its
 * transcript, needs a renderer / audio / the clock / files, prints nothing). [calibrate] needs a live Field
 * and turns each surviving candidate into a [CorpusTask], or rejects it (`solution-fails`, `no-stable-output`).
 * The calibrated corpus is checked in as [CHECKOUT_PATH], so an eval run does not re-mine.
 *
 * The split is fixed by the task id ([splitOf]): about a third of the tasks are `heldout`, never shown to
 * [AgentLessons.distil] when the protocol is followed (distil from a `train` run, measure on `heldout`).
 */
object AgentEvalCorpus {

    const val CHECKOUT_PATH = "src/jvmMain/resources/ai4/agent_eval_corpus.json"
    const val TRANSCRIPTS_PATH = "dev_docs/agents/transcripts"

    /** A live corpus run may sample at most this many tasks (the hand suite's cap is [AgentEvalRunner.MAX_LIVE_TASKS]). */
    const val MAX_LIVE_SAMPLE = 25

    /**
     * Code that needs something a headless scratch document does not give it, or whose output cannot be
     * the same twice. Matched as a regex anywhere in the box's code or its ancestors'.
     */
    val DENY: List<Pair<String, Regex>> = listOf(
        "renderer" to Regex("""\brenderer\b|Renderer\b|moduleHere|computeModuleHere|RenderToTexture|\bMesh\b|ComputeMesh|Vulkan|frames\s*\{"""),
        "shader" to Regex("""\bshader\b|fragment\s*=|vertex\s*=|#version"""),
        "audio" to Regex("""Audio|Synth|\bsignal\(|Dough|SonicPi|\bsound\("""),
        "python" to Regex("""(?i)\bpython\b|\bjep\b"""),
        "network" to Regex("""https?://|Socket|\bmqtt\b|(?i)websocket"""),
        "files" to Regex("""File\(|readText\(|readBytes\(|Paths\.get|\.toPath\(\)"""),
        "camera" to Regex("""(?i)\bwebcam\b|\bcamera\b"""),
        "neural" to Regex("""(?i)\bonnx\b|\bllm\b|\bai4\b"""),
        "clock" to Regex("""System\.nanoTime|currentTimeMillis|Instant\.now|LocalDateTime\.now|\bnow\(\)"""),
        "unseeded-random" to Regex("""Random\(\)|Math\.random|\brandom\(\)"""),
        "threads" to Regex("""Thread\b|\bsleep\(|delay\("""),
        "forever" to Regex("""\bloop\s*\{|\bstart\s*\{|\bforever\s*\{"""),
    )

    private val OUTPUT_CALL = Regex("""\bprintln\(|\bprint\(|\bdisplay\(|\blines\(|\btable\(|\bhtml\(""")
    const val MAX_CODE_CHARS = 6000

    @Serializable
    data class Candidate(
        val id: String,
        val source: String,
        val box: String,
        val title: String,
        val code: String,
        /** `run` or `launch`, as the transcript executed it. */
        val verb: String,
        val parents: List<String>,
        /** Ancestors in transcript order: (box id, code, verb). */
        val setup: List<SetupBox>,
        val prose: String,
    )

    @Serializable
    data class SetupBox(val box: String, val code: String, val verb: String, val parents: List<String>)

    @Serializable
    data class Rejection(val source: String, val box: String, val reason: String)

    @Serializable
    data class Mined(val candidates: List<Candidate>, val rejections: List<Rejection>, val transcripts: Int, val codeBoxes: Int)

    @Serializable
    data class CorpusTask(
        val id: String,
        val source: String,
        val split: String,
        /** `stdout` (checks on printed lines), `display` (checks on a displayed text node), `display-shape` (only a tag). */
        val kind: String,
        val request: AgentTaskRequest,
        val solution: String,
        val hasProse: Boolean,
    )

    @Serializable
    data class Corpus(
        val version: Int = 1,
        val minedFrom: String = TRANSCRIPTS_PATH,
        val transcripts: Int = 0,
        val codeBoxes: Int = 0,
        val tasks: List<CorpusTask> = emptyList(),
        val rejections: List<Rejection> = emptyList(),
    ) {
        /** Rejection counts by reason prefix (`deny:renderer`, `not-executed`, ...). */
        fun rejectionCounts(): Map<String, Int> =
            rejections.groupingBy { it.reason.substringBefore(" ") }.eachCount().toList().sortedByDescending { it.second }.toMap()
    }

    val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = false }

    // ---- stage 1: static mining ---------------------------------------------------------------

    fun transcriptsDir(): File = File(TRANSCRIPTS_PATH).absoluteFile

    fun mine(dir: File = transcriptsDir()): Mined {
        val files = dir.listFiles { f -> f.isFile && f.extension in setOf("yaml", "yml") }?.sortedBy { it.name }.orEmpty()
        val candidates = ArrayList<Candidate>()
        val rejections = ArrayList<Rejection>()
        var codeBoxes = 0
        for (file in files) {
            val result = runCatching { mineTranscript(file.nameWithoutExtension, file.readText()) }
                .getOrElse { rejections += Rejection(file.name, "", "unparseable ${it.javaClass.simpleName}"); continue }
            codeBoxes += result.codeBoxes
            candidates += result.candidates
            rejections += result.rejections
        }
        return Mined(candidates, rejections, files.size, codeBoxes)
    }

    @Suppress("UNCHECKED_CAST")
    fun mineTranscript(source: String, yamlText: String): Mined {
        val root = Yaml().load<Any?>(yamlText) as? Map<String, Any?> ?: return Mined(emptyList(), emptyList(), 1, 0)
        val ops = (root["operations"] as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> }
        data class Made(val box: String, val title: String, val code: String, val order: Int)
        val made = LinkedHashMap<String, Made>()
        val parentsOf = LinkedHashMap<String, MutableList<String>>()
        val verbOf = HashMap<String, String>()
        ops.forEachIndexed { index, op ->
            val (kind, body) = op.entries.firstOrNull() ?: return@forEachIndexed
            val args = body as? Map<String, Any?> ?: emptyMap()
            val box = args["box"]?.toString().orEmpty()
            when (kind) {
                "create" -> {
                    val props = args["properties"] as? Map<String, Any?> ?: emptyMap()
                    val code = props["code"] as? String
                    if (box.isNotBlank() && code != null) made[box] = Made(box, args["name"]?.toString() ?: box, code, index)
                    (args["parent"] ?: args["parents"])?.let { p ->
                        val list = (p as? List<Any?>)?.map { it.toString() } ?: listOf(p.toString())
                        parentsOf.getOrPut(box) { ArrayList() } += list
                    }
                }
                "connect" -> {
                    val parent = args["parent"]?.toString().orEmpty()
                    val child = args["child"]?.toString().orEmpty()
                    if (parent.isNotBlank() && child.isNotBlank()) parentsOf.getOrPut(child) { ArrayList() } += parent
                }
                // A fragment execute runs a selection, not the box; it does not make the box a runnable whole.
                "execute" -> if ((args["type"]?.toString() ?: "code") != "fragment" && box.isNotBlank()) verbOf.putIfAbsent(box, "run")
                "launch" -> if (box.isNotBlank()) verbOf[box] = "launch"
            }
        }
        val candidates = ArrayList<Candidate>()
        val rejections = ArrayList<Rejection>()
        fun ancestors(box: String, seen: MutableSet<String> = LinkedHashSet()): Set<String> {
            for (p in parentsOf[box].orEmpty()) if (seen.add(p)) ancestors(p, seen)
            return seen
        }
        for (m in made.values) {
            fun reject(reason: String) { rejections += Rejection(source, m.box, reason) }
            val verb = verbOf[m.box] ?: run { reject("not-executed"); null } ?: continue
            denyReason(m.code)?.let { reject("deny:$it"); continue }
            if (!OUTPUT_CALL.containsMatchIn(m.code)) { reject("no-output-call"); continue }
            if (m.code.length > MAX_CODE_CHARS) { reject("too-long"); continue }
            val up = ancestors(m.box)
            if (m.box in up) { reject("cycle"); continue }
            val missing = up.filter { it !in made }
            // A parent the transcript did not create with code (a guide, the renderer context) cannot be set up here.
            if (missing.isNotEmpty()) { reject("ancestor-unknown ${missing.first()}"); continue }
            val ancestorDenied = up.firstNotNullOfOrNull { a -> denyReason(made[a]!!.code)?.let { "$a:$it" } }
            if (ancestorDenied != null) { reject("ancestor-deny:${ancestorDenied.substringAfter(':')}"); continue }
            val setup = up.map { made[it]!! }.sortedBy { it.order }
                .map { SetupBox(it.box, it.code, verbOf[it.box] ?: "run", parentsOf[it.box].orEmpty().distinct()) }
            candidates += Candidate(
                id = "$source/${m.box}", source = source, box = m.box, title = m.title, code = m.code, verb = verb,
                parents = parentsOf[m.box].orEmpty().distinct(), setup = setup, prose = prose(m.code),
            )
        }
        return Mined(candidates, rejections, 1, made.size)
    }

    fun denyReason(code: String): String? {
        val stripped = stripComments(code)
        return DENY.firstOrNull { (_, re) -> re.containsMatchIn(stripped) }?.first
    }

    /** Code without its comments: a comment that *mentions* the renderer should not deny a box that does not use it. */
    fun stripComments(code: String): String =
        code.replace(Regex("""/\*[\s\S]*?\*/"""), " ").lines().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0 && !line.substring(0, i).contains('"')) line.substring(0, i) else line
        }

    /** The box's own description of itself: its leading comment, first two sentences, plain. */
    fun prose(code: String): String {
        val t = code.trimStart()
        val raw = when {
            t.startsWith("/*") -> t.substringAfter("/*").substringBefore("*/")
            t.startsWith("//") -> t.lines().takeWhile { it.trimStart().startsWith("//") }.joinToString(" ") { it.trimStart().removePrefix("//") }
            else -> return ""
        }
        val flat = raw.lines().joinToString(" ") { it.trim().removePrefix("*").trim() }.replace(Regex("\\s+"), " ").trim()
        val sentences = Regex("""(?<=[.!?])\s+""").split(flat).filter { it.isNotBlank() }
        return sentences.take(2).joinToString(" ").take(400)
    }

    // ---- stage 2: calibration against real runs ----------------------------------------------

    fun splitOf(id: String): String = if (Math.floorMod(id.hashCode(), 3) == 0) "heldout" else "train"

    /** The scripted solution: the box made with its real code under its parents, then run as the transcript did. */
    fun solution(c: Candidate): String {
        val first = c.parents.firstOrNull().orEmpty()
        val calls = ArrayList<Any>()
        calls += InFieldAgentEvals.call("NewBoxRequest", "name" to c.box, "code" to c.code, "parent" to first)
        c.parents.drop(1).forEach { calls += InFieldAgentEvals.call("ConnectBoxesRequest", "parent" to it, "child" to c.box) }
        calls += runCall(c.box, c.verb)
        calls += "Made ${c.box} from its transcript code and ran it."
        return InFieldAgentEvals.steps(*calls.toTypedArray())
    }

    private fun runCall(box: String, verb: String) =
        if (verb == "launch") InFieldAgentEvals.call("LaunchBoxRequest", "box" to box, "timeoutSeconds" to 90.0, "settleSeconds" to 1.0)
        else InFieldAgentEvals.call("RunBoxRequest", "box" to box, "timeoutSeconds" to 90.0)

    fun setup(c: Candidate): String {
        if (c.setup.isEmpty()) return ""
        val calls = ArrayList<Any>()
        for (s in c.setup) {
            calls += InFieldAgentEvals.call("NewBoxRequest", "name" to s.box, "code" to s.code, "parent" to s.parents.firstOrNull().orEmpty())
            s.parents.drop(1).forEach { calls += InFieldAgentEvals.call("ConnectBoxesRequest", "parent" to it, "child" to s.box) }
            calls += runCall(s.box, s.verb)
        }
        return InFieldAgentEvals.steps(*calls.toTypedArray())
    }

    /** A calibration request: setup and solution, no checks (verdict `unchecked`); the tool log carries the output. */
    fun calibrationRequest(c: Candidate) = AgentTaskRequest(
        goal = "calibrate ${c.id}", setup = setup(c), mode = "scripted", script = solution(c), taskId = c.id, maxToolRounds = 8,
    )

    /** What one run of the target box said, read from its Run/Launch tool record. */
    data class Observed(val success: Boolean, val stage: String, val stdout: List<String>, val displays: Map<String, String>, val error: String)

    fun observed(result: AgentTaskResult, box: String): Observed? {
        val record = result.toolCalls.lastOrNull { (it.tool == "RunBoxRequest" || it.tool == "LaunchBoxRequest") && it.arguments.contains(box) }
            ?: return null
        val obj = runCatching { Json.parseToJsonElement(record.result) as? JsonObject }.getOrNull() ?: return Observed(record.ok, "", emptyList(), emptyMap(), record.result.take(300))
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val stdout = (obj["stdout"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val displays = (obj["displays"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
        val success = (obj["success"] as? JsonPrimitive)?.contentOrNull == "true"
        return Observed(success, str("stage"), stdout, displays, str("error"))
    }

    private val CHECKABLE_LINE = Regex("""[A-Za-z]""")

    /** Lines that came out both times, in first-run order, trimmed; long ones cut to 100 chars. */
    fun stableLines(a: List<String>, b: List<String>): List<String> {
        fun split(xs: List<String>) = xs.flatMap { it.lines() }.map { it.trim() }.filter { it.length >= 3 }
        val second = split(b).toSet()
        return split(a).filter { it in second }.distinct()
    }

    /** Text nodes (between tags) that appeared in both displays. */
    fun stableTextNodes(a: Map<String, String>, b: Map<String, String>): List<String> {
        fun nodes(m: Map<String, String>) = m.values.flatMap { html ->
            Regex(""">([^<>]{4,200})<""").findAll(html).map { it.groupValues[1].trim() }.filter { it.length >= 4 && CHECKABLE_LINE.containsMatchIn(it) }.toList()
        }
        val second = nodes(b).toSet()
        return nodes(a).filter { it in second }.distinct()
    }

    sealed interface Calibrated
    data class Accepted(val task: CorpusTask) : Calibrated
    data class Refused(val rejection: Rejection) : Calibrated

    /**
     * Turn two scripted runs of [c]'s solution into a task: checks on the output that was the same both times.
     * Up to two printed lines (first and last checkable), else one displayed text node, else — weakest — the
     * first tag of the display.
     */
    fun calibrate(c: Candidate, first: AgentTaskResult, second: AgentTaskResult): Calibrated {
        fun refuse(reason: String) = Refused(Rejection(c.source, c.box, reason))
        val a = observed(first, c.box) ?: return refuse("no-run-record ${first.error.take(120)}")
        val b = observed(second, c.box) ?: return refuse("no-run-record ${second.error.take(120)}")
        if (!a.success || !b.success) {
            val bad = if (!a.success) a else b
            return refuse("solution-fails ${bad.stage.ifBlank { "?" }}: ${bad.error.lines().firstOrNull().orEmpty().take(160)}")
        }
        val base = mutableListOf(AgentCheck.parse("box-exists:${c.box}"), AgentCheck.parse("last-run-ok:${c.box}"))
        c.parents.forEach { base += AgentCheck.parse("connected:$it:${c.box}") }
        val lines = stableLines(a.stdout, b.stdout).filter { CHECKABLE_LINE.containsMatchIn(it) }
        val texts = stableTextNodes(a.displays, b.displays)
        val (kind, expect) = when {
            lines.isNotEmpty() -> "stdout" to listOf(lines.first(), lines.last()).distinct().map { it.take(100) }
            texts.isNotEmpty() -> "display" to listOf(texts.first().take(100))
            a.displays.isNotEmpty() && a.displays.keys == b.displays.keys -> {
                val tag = Regex("""<([a-zA-Z][a-zA-Z0-9]*)""").find(a.displays.values.first())?.groupValues?.get(1)
                    ?: return refuse("no-stable-output display without a tag")
                "display-shape" to listOf("<$tag")
            }
            a.stdout.isNotEmpty() || a.displays.isNotEmpty() -> return refuse("no-stable-output differs between runs")
            else -> return refuse("no-stable-output said nothing")
        }
        val checks = base + expect.map { AgentCheck(kind = "output-contains", box = c.box, text = it) }
        val goal = goal(c, kind, expect)
        return Accepted(CorpusTask(
            id = c.id, source = c.source, split = splitOf(c.id), kind = kind,
            request = AgentTaskRequest(goal = goal, checks = checks, setup = setup(c), taskId = c.id),
            solution = solution(c), hasProse = c.prose.isNotBlank(),
        ))
    }

    fun goal(c: Candidate, kind: String, expect: List<String>): String = buildString {
        val verb = if (c.verb == "launch") "launch" else "run"
        append("Make a box named ${c.box}")
        if (c.title != c.box) append(" (titled \"${c.title}\")")
        if (c.parents.isNotEmpty()) append(" as a child of ${c.parents.joinToString(" and ")}, which already exist and have been run; it may use what they share")
        append(", and $verb it.")
        if (c.prose.isNotBlank()) append(" What it is for, in its author's words: ${c.prose}")
        append(when (kind) {
            "stdout" -> " When it runs, what it prints must include " + expect.joinToString(" and ") { "the text «$it»" } + "."
            "display" -> " It must display (with display(...) or a drawing helper) output that includes the text «${expect.first()}»."
            else -> " It must display output (with display(...) or a drawing helper such as lines(...)) containing a ${expect.first()}> element."
        })
    }

    // ---- the checked-in corpus -----------------------------------------------------------------

    fun sourceFile(): File? = File(CHECKOUT_PATH).absoluteFile.takeIf { it.isFile }

    fun load(): Corpus {
        val text = sourceFile()?.readText()
            ?: AgentEvalCorpus::class.java.getResource("/ai4/agent_eval_corpus.json")?.readText()
            ?: return Corpus()
        return json.decodeFromString(Corpus.serializer(), text)
    }

    fun write(corpus: Corpus, target: File = File(CHECKOUT_PATH).absoluteFile) {
        target.parentFile?.mkdirs()
        target.writeText(json.encodeToString(Corpus.serializer(), corpus) + "\n")
    }

    /**
     * The tasks a run asks for: [split] `all` / `train` / `heldout`, then [sample] of them (0 = all) drawn by [seed].
     * A seeded shuffle, so `--sample 20 --seed 1` names the same tasks on every machine.
     */
    fun select(corpus: Corpus, split: String, sample: Int, seed: Long, ids: List<String> = emptyList()): List<CorpusTask> {
        var pool = when (split) {
            "", "all" -> corpus.tasks
            "train", "heldout" -> corpus.tasks.filter { it.split == split }
            else -> throw IllegalArgumentException("corpus split must be all, train or heldout, not '$split'")
        }
        if (ids.isNotEmpty()) pool = pool.filter { it.id in ids }
        if (sample <= 0 || sample >= pool.size) return pool
        return pool.sortedBy { it.id }.shuffled(java.util.Random(seed)).take(sample)
    }
}
