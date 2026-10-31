package afield.examples

import afield.runtime.kotlin.formatBoxKotlin
import afield.runtime.kotlin.formatCodeProperty
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.File
import java.io.StringReader
import kotlin.system.exitProcess

/**
 * Formats the box code in transcripts and `*_code_.kt` sidecars with the formatter the editor uses
 * ([formatCodeProperty]: ktfmt for `code`, Field's GLSL formatter for shader properties), in place.
 *
 * A transcript holds code in YAML block scalars (`code: |`, `fragment: |`, ...). Each block is
 * de-indented, formatted, and re-indented to the column it had, so nothing else in the file moves.
 * A block the formatter cannot parse is left exactly as it was and named on stdout: a format pass
 * that guessed would be worse than one that skipped.
 *
 * `./gradlew formatBoxCode --args="<file>..."`, or with no arguments every top-level transcript.
 * `--check` reports what would change and exits 1 if anything would. `--imports` also removes each
 * `code` block's redundant and unused imports ([formatBoxKotlin]); `--k2` adds the code-use sidecar's
 * resolution evidence to that. Follow a real run with `./gradlew checkBoxCodeCompiles`.
 *
 * A `.md` argument formats the Kotlin in its `diffstatic` / `diffstatic_runner` fences ([formatMarkdown]);
 * `--docs` adds every `.md` page directly under `public/docs`. Follow a docs run with a docs build.
 * `--all` takes every corpus at once ([allCorpora]); `--guard --check` is `./gradlew checkBoxCodeFormat`,
 * which landing runs on the files a branch touches.
 */
object BoxCodeFormat {
    private var imports = false
    private var k2 = false

    private val BLOCK_KEY = Regex("""^(\s*)(?:-\s+)?(["']?)(\w+)\2:\s*\|[-+]?\s*(?:#.*)?$""")

    @JvmStatic
    fun main(args: Array<String>) {
        val check = "--check" in args
        imports = "--imports" in args
        k2 = "--k2" in args
        val docs = if ("--docs" in args) docsPages() else emptyList()
        val explicit = args.filter { !it.startsWith("--") }.map(::File)
        val files = when {
            "--all" in args || "--guard" in args -> guardScope(allCorpora(), "--guard" in args)
            explicit.isNotEmpty() -> explicit + docs
            docs.isNotEmpty() -> docs
            else -> transcripts()
        }
        var changed = 0
        for (file in files) {
            val before = file.readText()
            val after = when {
                file.name.endsWith("_code_.kt") -> formatOrNull("code", before, file.path) ?: before
                file.extension == "yaml" || file.extension == "yml" -> formatYaml(before, file.path)
                file.extension == "md" -> formatMarkdown(before, file.path)
                else -> before
            }
            if (after != before) {
                changed++
                println("${if (check) "would format" else "formatted"} ${file.path}")
                if (!check) file.writeText(after)
            }
        }
        println("BoxCodeFormat: ${files.size} file(s), $changed ${if (check) "need formatting" else "formatted"}")
        exitProcess(if (check && changed > 0) 1 else 0)
    }

    private fun transcripts(): List<File> {
        val dir = DemoTranscriptExecSmokeTest.locateTranscriptsDir()
        return (dir.listFiles { f -> f.isFile && f.extension == "yaml" } ?: emptyArray()).sortedBy { it.name }
    }

    private fun docsPages(): List<File> =
        (File("public/docs").listFiles { f -> f.isFile && f.extension == "md" } ?: emptyArray())
            .filter { f -> RECORDED.none { "public/docs/${f.name}".startsWith(it) } }
            .sortedBy { it.name }

    // Copies regenerated from the transcripts (regenerateAgentTranscriptStartingPoints); formatting
    // them by hand would only be overwritten, and a guard over them would blame the wrong file.
    private const val GENERATED_ROOT = "public/docs/assets/agent_transcripts/"

    // Recorded artifacts whose bytes are pinned to a saved revision (package_function_conversation.py
    // asserts the final source equals studies/function_source/r9-*.kt), so a format would break the record.
    // The page's listings are those same bytes, written by that packager.
    private val RECORDED = listOf("dev_docs/agents/demos/function_conversation_example/", "public/docs/function_conversation.md")

    /** Every corpus the formatter owns: transcripts, tracked `*_code_.kt` sidecars outside [GENERATED_ROOT], docs pages. */
    fun allCorpora(): List<File> {
        val tracked = ProcessBuilder("git", "ls-files", "*_code_.kt").redirectErrorStream(true).start()
            .inputStream.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith(GENERATED_ROOT) && RECORDED.none(it::startsWith) }
            .map(::File)
        return transcripts() + tracked + docsPages()
    }

    // `--guard` limits the corpus to the paths the lander says a branch touched, the same
    // AFIELD_GUARD_CHANGED_PATHS_FILE listing checkBoxIdioms reads. Without it the whole corpus is checked.
    private fun guardScope(corpus: List<File>, guard: Boolean): List<File> {
        val listing = System.getenv("AFIELD_GUARD_CHANGED_PATHS_FILE")?.takeIf { guard && it.isNotBlank() } ?: return corpus
        val changed = File(listing).readLines().map(String::trim).filter(String::isNotEmpty).toSet()
        val root = File("").absoluteFile
        return corpus.filter { it.absoluteFile.relativeTo(root).invariantSeparatorsPath in changed }
    }

    fun formatYaml(text: String, label: String): String {
        val lines = expandQuotedCode(text, label).split("\n")
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val key = BLOCK_KEY.matchEntire(lines[i])
            out += lines[i]
            i++
            if (key == null) continue
            val keyIndent = key.groupValues[1].length
            val start = i
            while (i < lines.size && (lines[i].isBlank() || indentOf(lines[i]) > keyIndent)) i++
            // Trailing blank lines belong to the YAML between entries, not to the block.
            var end = i
            while (end > start && lines[end - 1].isBlank()) end--
            val block = lines.subList(start, end)
            if (block.isEmpty()) { out += lines.subList(end, i); continue }
            val indent = block.filter(String::isNotBlank).minOf(::indentOf)
            val body = block.joinToString("\n") { if (it.isBlank()) "" else it.substring(indent) }
            val formatted = formatOrNull(key.groupValues[3], body, "$label:${start + 1}")
            if (formatted == null) {
                out += block
            } else {
                val pad = " ".repeat(indent)
                formatted.trimEnd().split("\n").forEach { out += if (it.isBlank()) "" else pad + it }
            }
            out += lines.subList(end, i)
        }
        return out.joinToString("\n")
    }

    // Older fixtures encode complete programs as escaped YAML strings. Decode those through
    // YAML, then use the same literal-block path as authored transcripts. Metadata stays in place.
    private fun expandQuotedCode(text: String, label: String): String {
        val documents = try {
            Yaml().composeAll(StringReader(text)).toList()
        } catch (_: RuntimeException) {
            return text // Keep the existing handling of malformed YAML; never guess at its values.
        }
        data class Edit(val start: Int, val end: Int, val replacement: String)
        val edits = mutableListOf<Edit>()
        val visited = mutableSetOf<Node>()
        fun visit(node: Node, inFlow: Boolean) {
            if (!visited.add(node)) return
            when (node) {
                is MappingNode -> {
                    val flow = inFlow || node.flowStyle == DumperOptions.FlowStyle.FLOW
                    for (entry in node.value) {
                        val key = entry.keyNode as? ScalarNode
                        val value = entry.valueNode as? ScalarNode
                        if (key != null && value != null && value.tag == Tag.STR &&
                            (key.value == "code" || formatCodeProperty(key.value, "") != null) &&
                            value.scalarStyle !in setOf(DumperOptions.ScalarStyle.LITERAL, DumperOptions.ScalarStyle.FOLDED)
                        ) {
                            if (flow || value.anchor != null || value.startMark.index < key.endMark.index) {
                                println("skipped $label:${key.startMark.line + 1} (${key.value}): quoted code in a flow collection or alias")
                            } else {
                                val formatted = formatOrNull(key.value, value.value, "$label:${key.startMark.line + 1}")
                                if (formatted != null) {
                                    // SnakeYAML marks count Unicode code points; JVM string offsets
                                    // count UTF-16 units. Emoji before or inside code change the offset.
                                    val start = text.offsetByCodePoints(0, value.startMark.index)
                                    val end = text.offsetByCodePoints(0, value.endMark.index)
                                    val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
                                    val suffix = text.substring(end, lineEnd)
                                    // Keep an inline YAML comment on the new block's header.
                                    if (suffix.isBlank() || suffix.trimStart().startsWith("#")) {
                                        val comment = if (suffix.isBlank()) "" else suffix.trimEnd()
                                        val indent = " ".repeat(key.startMark.column + 2)
                                        val body = formatted.trimEnd('\n', '\r').lineSequence()
                                            .joinToString("\n") { if (it.isBlank()) "" else indent + it }
                                        edits += Edit(start, lineEnd, "|-" + comment + "\n" + body)
                                    }
                                }
                            }
                        }
                        visit(entry.valueNode, flow)
                    }
                }
                is SequenceNode -> node.value.forEach { visit(it, inFlow || node.flowStyle == DumperOptions.FlowStyle.FLOW) }
            }
        }
        documents.filterNotNull().forEach { visit(it, false) }
        var result = text
        for (edit in edits.sortedByDescending { it.start }) {
            result = result.replaceRange(edit.start, edit.end, edit.replacement)
        }
        return result
    }

    private val FENCE_OPEN = Regex("""^(`{3,})(diffstatic_runner|diffstatic)(?![\w-])(.*)$""")
    private val FENCE_LANG = Regex("""\b(?:data-lang|data-language|lang|language)\s*=\s*["']?([\w+-]+)""")

    // What the docs runner reads out of a fence body, or what a reformat would move away from the
    // line a page's prose points at: section and fold markers, fragment breaks, region comments and
    // the runner's own `FIELD_DOCS_` directives. A fence holding any of them is left as written.
    private val RUNNER_MARKER = Regex("""^\s*(:::|//\s*%-|//\s*(?:end)?region\b|//\s*#(?:end)?region\b)|FIELD_DOCS_""")

    /**
     * Format the Kotlin in a docs page's `diffstatic` / `diffstatic_runner` fences, in place.
     *
     * Only unindented fences whose language is Kotlin (no `data-lang`, or `kotlin`/`kt`/`kts`) are
     * touched; `diff` fences hold before/after sections and are never formatted. A fence that does not
     * parse, or that carries a [RUNNER_MARKER], is left as written. The fence lines stay as they are.
     */
    fun formatMarkdown(text: String, label: String): String {
        val lines = text.split("\n")
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val open = FENCE_OPEN.matchEntire(lines[i])
            out += lines[i]
            i++
            if (open == null) continue
            val ticks = open.groupValues[1]
            val start = i
            while (i < lines.size && lines[i].trimEnd() != ticks) i++
            if (i >= lines.size) { out += lines.subList(start, i); break }
            val body = lines.subList(start, i)
            val lang = FENCE_LANG.find(open.groupValues[3])?.groupValues?.get(1)?.lowercase()
            val kotlin = lang == null || lang in setOf("kotlin", "kt", "kts")
            val formatted = if (!kotlin || body.isEmpty() || body.any { RUNNER_MARKER.containsMatchIn(it) }) null
            else formatOrNull("code", body.joinToString("\n"), "$label:$start")
            if (formatted == null) out += body else out += formatted.trimEnd().split("\n")
        }
        return out.joinToString("\n")
    }

    private fun formatOrNull(property: String, code: String, label: String): String? = try {
        if (imports && property == "code") formatBoxKotlin(code, useK2 = k2) else formatCodeProperty(property, code)
    } catch (t: Throwable) {
        println("skipped $label ($property): ${t.javaClass.simpleName}: ${t.message?.lineSequence()?.firstOrNull()}")
        null
    }

    private fun indentOf(line: String) = line.length - line.trimStart().length
}
