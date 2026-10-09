package afield.examples

import afield.Box
import afield.METAROOT_ID
import afield.OperationTranscript
import afield.Rect
import afield.SavingContext
import afield.isTrue
import afield.nestedIn
import afield.thisSessionInfo
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.system.exitProcess

/*
FIELD_TEST
id: examples.document-to-transcript.round-trip
runner: jvm-main
entrypoint: afield.examples.DocumentToTranscript
tags: [smoke, agents, transcript, verification]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "DocumentToTranscript ok"
*/
/**
 * Writes an OperationTranscript yaml from a saved Field document — the reverse of
 * [AgentTranscriptMaterializer]. It is how a document built by hand, or a nightly demo under
 * `dev_docs/agents/demos/`, becomes a transcript in `dev_docs/agents/transcripts/` without anyone
 * retyping its boxes: the document is what the boxes currently contain, so the transcript is
 * generated from it, never paraphrased.
 *
 * The document is read through [SavingContext.load] — the same reader Field opens it with — so
 * sidecars, `#afield/file-inline` and `:persistentProperties` mean here what they mean in Field.
 * Each box becomes one `create` op carrying its name, frame and every persisted property verbatim;
 * each parent edge other than the root's becomes a `connect`. No `launch`, `execute` or `save` op
 * is written: what runs, and when, is the author's to add, and a transcript never saves.
 *
 * Usage:
 *  - `run-jvm-main afield.examples.DocumentToTranscript --arg <document dir> [--arg <out.yaml>]`
 *    writes `<out.yaml>` (default: stdout). The description comes from the document's `build.yaml`
 *    when it has one.
 *  - `--arg=--all-demos --arg <dir>` writes one transcript per demo document into `<dir>`.
 *  - With no arguments (the FIELD_TEST) it round-trips every document under
 *    `dev_docs/agents/demos/`: generate the transcript, rebuild its graph with the materializer's
 *    structural ops, and fail on any box, property or edge that did not come back identical.
 *
 * Anything the transcript language cannot carry is reported rather than dropped silently: the root
 * box's own properties (a `create` cannot target root) and values of a type this writer does not
 * know how to spell in yaml.
 */
object DocumentToTranscript {

    /** Properties a `create` op carries as fields of its own, or that the graph rebuilds. */
    private val STRUCTURAL = setOf("name", "frame", "children", "parents", "_tombstone", "nested", "nestedIn")

    /** Root properties that are session state rather than part of the scene. */
    private val ROOT_SESSION_STATE = setOf("fileManagerAccessHistory")

    /**
     * A property larger than this is left out and declared, not written: the case that exists is a
     * 5.7 MB AI chat history, which is a record of how a document was made rather than the scene,
     * and is past SnakeYAML's 3 MB input limit anyway.
     */
    private const val MAX_PROPERTY_CHARS = 256 * 1024

    /**
     * What one conversion produced. [omitted] are things the transcript leaves out on purpose and
     * says so in its header; [notCarried] are values this writer cannot spell, which the round trip
     * treats as failures.
     */
    class Result(val yaml: String, val boxes: Int, val omitted: List<String>, val notCarried: List<String>)

    @JvmStatic
    fun main(args: Array<String>) {
        AgentTranscriptMaterializer.startRunloopDaemon()
        if (args.size == 2 && args[0] == "--all-demos") {
            val outDir = File(args[1]).also { it.mkdirs() }
            val documents = demoDocuments()
            for (document in documents) {
                try {
                    File(outDir, "${document.name}.yaml").writeText(convert(document).yaml)
                } finally {
                    thisSessionInfo.remove()
                }
            }
            println("DocumentToTranscript: wrote ${documents.size} transcript(s) to ${outDir.absolutePath}")
            exitProcess(0)
        }
        if (args.isNotEmpty()) {
            val document = File(args[0]).absoluteFile
            val result = convert(document)
            result.omitted.forEach { System.err.println("[omitted] $it") }
            result.notCarried.forEach { System.err.println("[not carried] $it") }
            val out = args.getOrNull(1)?.let { File(it) }
            if (out == null) print(result.yaml) else {
                out.writeText(result.yaml)
                println("DocumentToTranscript: ${result.boxes} box(es) -> ${out.absolutePath}")
            }
            exitProcess(0)
        }

        val documents = demoDocuments()
        val failures = mutableListOf<String>()
        for (document in documents) {
            try {
                val drift = roundTrip(document)
                if (drift.isEmpty()) println("[round-trip] PASS ${document.name}")
                else {
                    drift.forEach { failures.add("${document.name}: $it") }
                    println("[round-trip] FAIL ${document.name} (${drift.size} difference(s))")
                }
            } catch (t: Throwable) {
                failures.add("${document.name}: threw ${t.javaClass.simpleName}: ${t.message}")
                t.printStackTrace(System.err)
            } finally {
                thisSessionInfo.remove()
            }
        }
        if (failures.isNotEmpty()) {
            System.err.println("DocumentToTranscript FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("DocumentToTranscript ok — ${documents.size} document(s) round-tripped")
        exitProcess(0)
    }

    /** Every saved document under `dev_docs/agents/demos/`. */
    private fun demoDocuments(): List<File> {
        val demos = File(AgentTranscriptMaterializer.locateTranscriptsDir().parentFile, "demos")
        return (demos.listFiles { f -> File(f, SavingContext.METADATA_EDN).isFile } ?: emptyArray())
            .sortedBy { it.name }
            .also { require(it.isNotEmpty()) { "no documents under ${demos.absolutePath}" } }
    }

    /** Load [document] in a fresh session and write its transcript. */
    fun convert(document: File, name: String = document.name): Result {
        val boxes = loadDocument(document, name)
        val omitted = mutableListOf<String>()
        val notCarried = mutableListOf<String>()
        val root = thisSessionInfo.get().root
        root.properties.entries
            .filter { (k, v) -> k in Box.ioPersistentProperties && k !in STRUCTURAL && k !in ROOT_SESSION_STATE && !isEmpty(v) }
            .map { it.key }.sorted()
            .forEach { omitted.add("root.$it (a create op cannot target the root box)") }

        val ops = StringBuilder()
        for (box in boxes) {
            ops.append("  - create:\n")
            ops.append("      box: ").append(scalar(box.id)).append('\n')
            if (box.name.isNotEmpty()) ops.append("      name: ").append(scalar(box.name)).append('\n')
            val f = box.frame
            ops.append("      frame: { x: ${f.x}, y: ${f.y}, w: ${f.w}, h: ${f.h} }\n")
            val spelled = carriedProperties(box).mapNotNull { (key, value) ->
                if (value is String && value.length > MAX_PROPERTY_CHARS) {
                    omitted.add("${box.id}.$key (${value.length} chars, over the $MAX_PROPERTY_CHARS-char limit)")
                    return@mapNotNull null
                }
                val v = yamlValue(value, 10)
                if (v == null) notCarried.add("${box.id}.$key (${value?.javaClass?.name} has no yaml spelling here)")
                v?.let { key to it }
            }
            if (spelled.isNotEmpty()) {
                ops.append("      properties:\n")
                spelled.forEach { (key, v) -> ops.append("        ").append(scalar(key)).append(": ").append(v) }
            }
        }
        for ((parent, child) in edges(boxes)) {
            val nested = if (boxes.first { it.id == child }.nestedIn?.id == parent) ", nested: true" else ""
            ops.append("  - connect: { parent: ${scalar(parent)}, child: ${scalar(child)}$nested }\n")
        }

        val out = StringBuilder()
        out.append("# Generated from ${document.name}/metadata.edn by afield.examples.DocumentToTranscript.\n")
        if (omitted.isNotEmpty()) {
            out.append("# Left out of this transcript:\n")
            omitted.forEach { out.append("#   ").append(it).append('\n') }
        }
        out.append("name: ").append(scalar(name)).append('\n')
        description(document)?.let { out.append("description: ").append(block(it, 2)) }
        out.append("operations:\n").append(ops)
        return Result(out.toString(), boxes.size, omitted, notCarried)
    }

    /**
     * Convert [document], rebuild the transcript's graph through the real apply path, and list every
     * difference between the rebuilt boxes and the loaded ones. Empty means the transcript is the
     * document.
     */
    fun roundTrip(document: File): List<String> {
        val result = convert(document)
        val expected = snapshot(liveBoxes())
        thisSessionInfo.remove()

        AgentTranscriptMaterializer.freshSession("roundtrip-${document.name}")
        val macroName = "roundtrip_${document.name}"
        @Suppress("UNCHECKED_CAST")
        val ops = (Yaml().load<Any?>(result.yaml) as Map<String, Any?>)["operations"] as List<Map<String, Any?>>
        require(ops.all { it.keys.first() in AgentTranscriptMaterializer.GRAPH_VERBS }) { "non-structural op generated" }
        val macros = File(AgentTranscriptMaterializer.currentDocumentDir(), "macros").also { it.mkdirs() }
        File(macros, "$macroName.yaml").writeText(result.yaml)
        val build = OperationTranscript.runMacro(
            AgentTranscriptMaterializer.currentDocumentDir().absolutePath,
            macroName,
            initializeCreatedBoxes = false,
        )
        val drift = mutableListOf<String>()
        if (!build.success) drift.add("transcript did not apply: ${build.message}")
        val actual = snapshot(liveBoxes())

        for (id in (expected.keys + actual.keys).sorted()) {
            val e = expected[id]
            val a = actual[id]
            when {
                e == null -> drift.add("box $id appears only in the rebuilt graph")
                a == null -> drift.add("box $id did not come back")
                else -> for (key in (e.keys + a.keys).sorted()) {
                    if (e[key] != a[key]) drift.add("$id.$key: document ${preview(e[key])} != rebuilt ${preview(a[key])}")
                }
            }
        }
        // An omitted or unspellable value is a difference too, but it is already named, so say it
        // once in those words rather than again as a property mismatch. Omissions are declared in
        // the transcript's header and are not failures; an unspellable value is.
        val named = (result.omitted + result.notCarried).map { it.substringBefore(' ') }.toSet()
        result.omitted.forEach { println("[round-trip] ${document.name}: omitted $it") }
        return drift.filterNot { d -> named.any { d.startsWith("$it:") } } + result.notCarried
    }

    private fun loadDocument(document: File, name: String): List<Box> {
        val scratch = AgentTranscriptMaterializer.freshSession("d2t-$name")
        // Load a copy: SavingContext may upgrade or touch what it reads, and the source is committed.
        document.copyRecursively(File(scratch.parentFile, "source"), overwrite = true)
        val loaded = SavingContext().load(File(scratch.parentFile, "source").absolutePath, initIfCreated = false)
        val order = loaded.map { it.id }
        return liveBoxes().sortedBy { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    private fun liveBoxes(): List<Box> = thisSessionInfo.get().boxes.values
        .filter { it.id != "root" && it.id != METAROOT_ID && !it.getPropertyRaw("_tombstone").isTrue() }

    private fun carriedProperties(box: Box): List<Pair<String, Any?>> =
        box.properties.entries
            .filter { (k, v) -> k in Box.ioPersistentProperties && k !in STRUCTURAL && !isEmpty(v) }
            // An empty preamble is regenerated on open; the materializer strips it for the same reason.
            .filterNot { (k, v) -> k == "preamble" && v == "" }
            .sortedBy { it.key }
            .map { it.key to it.value }

    private fun edges(boxes: List<Box>): List<Pair<String, String>> {
        val ids = boxes.map { it.id }.toSet()
        return boxes.flatMap { child ->
            child.parents.map { it.id }.filter { it in ids }.sorted().map { it to child.id }
        }
    }

    /** What a box is, for comparison: persisted properties, frame, name and non-root parents. */
    private fun snapshot(boxes: List<Box>): Map<String, Map<String, Any?>> {
        val ids = boxes.map { it.id }.toSet()
        return boxes.associate { box ->
            val m = sortedMapOf<String, Any?>()
            carriedProperties(box).forEach { (k, v) -> m[k] = normalise(v) }
            m["name"] = box.name
            m["frame"] = box.frame.let { listOf(it.x, it.y, it.w, it.h) }
            m["parents"] = box.parents.map { it.id }.filter { it in ids }.sorted()
            m["nestedIn"] = box.nestedIn?.id
            box.id to m
        }
    }

    /** Null and empty collections are what a box has before anything is set; a transcript leaves them out. */
    private fun isEmpty(v: Any?) = v == null || (v is Collection<*> && v.isEmpty()) || (v is Map<*, *> && v.isEmpty())

    /** Yaml reads an integer-valued double back as Double and a small long as Integer; compare numbers by value. */
    private fun normalise(v: Any?): Any? = when (v) {
        is Number -> v.toDouble()
        is Map<*, *> -> v.entries.associate { it.key.toString() to normalise(it.value) }
        is Iterable<*> -> v.map { normalise(it) }
        else -> v
    }

    private fun preview(v: Any?): String {
        val s = v.toString().replace("\n", "\\n")
        return if (s.length > 80) s.take(80) + "…(${s.length} chars)" else s
    }

    private fun description(document: File): String? {
        val build = File(document, "build.yaml").takeIf { it.isFile } ?: return null
        return (Yaml().load<Any?>(build.readText()) as? Map<*, *>)?.get("description")?.toString()
    }

    // ---- yaml spelling ----

    /** [v] spelled after `key: `, ending in a newline; null when there is no faithful spelling. */
    private fun yamlValue(v: Any?, indent: Int): String? = when (v) {
        is String -> if ('\n' in v && '\r' !in v) block(v, indent) else scalar(v) + "\n"
        is Boolean, is Int, is Long -> "$v\n"
        is Double -> if (v.isFinite()) "$v\n" else null
        is Float -> if (v.isFinite()) "${v.toDouble()}\n" else null
        is Rect -> "{ x: ${v.x}, y: ${v.y}, w: ${v.w}, h: ${v.h} }\n"
        is List<*> -> v.map { flow(it) ?: return null }.joinToString(", ", "[", "]\n")
        is Map<*, *> -> v.entries.map { (k, x) -> scalar(k.toString()) + ": " + (flow(x) ?: return null) }
            .joinToString(", ", "{ ", " }\n")
        else -> null
    }

    private fun flow(v: Any?): String? = when (v) {
        null -> "null"
        is String -> scalar(v)
        is Boolean, is Int, is Long -> v.toString()
        is Double -> v.takeIf { it.isFinite() }?.toString()
        is List<*> -> v.map { flow(it) ?: return null }.joinToString(", ", "[", "]")
        is Map<*, *> -> v.entries.map { (k, x) -> scalar(k.toString()) + ": " + (flow(x) ?: return null) }
            .joinToString(", ", "{ ", " }")
        else -> null
    }

    /** A double-quoted scalar: JSON escaping is valid yaml and survives every character. */
    private fun scalar(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\t' -> b.append("\\t")
            c == '\r' -> b.append("\\r")
            c < ' ' || c in '\u007f'..'\u009f' || c == '\u2028' || c == '\u2029' || c == '\ufeff' ->
                b.append("\\u%04x".format(c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    /**
     * A literal block scalar, exact to the byte: chomping (`|-`, `|`, `|+`) keeps the trailing
     * newlines as they were, and an explicit indentation indicator protects a first line that
     * starts with a space. Falls back to a quoted scalar for text a block cannot hold verbatim
     * (control characters).
     */
    private fun block(s: String, indent: Int): String {
        val unsafe = s.any { it < ' ' && it != '\n' && it != '\t' } || s.contains('\ufeff')
        if (unsafe) return scalar(s) + "\n"
        val trailing = s.length - s.trimEnd('\n').length
        val chomp = when (trailing) { 0 -> "-"; 1 -> ""; else -> "+" }
        val body = s.trimEnd('\n')
        // Every caller nests the block two columns under its key.
        val indicator = if (body.startsWith(" ") || body.startsWith("\n")) "2" else ""
        if (body.isEmpty()) return scalar(s) + "\n"
        val pad = " ".repeat(indent)
        val lines = body.split('\n').joinToString("\n") { if (it.isEmpty()) "" else pad + it }
        return "|$indicator$chomp\n$lines\n" + "\n".repeat(maxOf(0, trailing - 1))
    }
}
