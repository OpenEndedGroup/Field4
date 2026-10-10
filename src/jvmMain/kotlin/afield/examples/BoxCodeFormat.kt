package afield.examples

import afield.formatBoxKotlin
import afield.formatCodeProperty
import java.io.File
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
 */
object BoxCodeFormat {
    private var imports = false
    private var k2 = false

    private val BLOCK_KEY = Regex("""^(\s*)(?:-\s+)?(\w+):\s*\|[-+]?\s*$""")

    @JvmStatic
    fun main(args: Array<String>) {
        val check = "--check" in args
        imports = "--imports" in args
        k2 = "--k2" in args
        val files = args.filter { !it.startsWith("--") }.map(::File).ifEmpty {
            val dir = DemoTranscriptExecSmokeTest.locateTranscriptsDir()
            (dir.listFiles { f -> f.isFile && f.extension == "yaml" } ?: emptyArray()).sortedBy { it.name }
        }
        var changed = 0
        for (file in files) {
            val before = file.readText()
            val after = when {
                file.name.endsWith("_code_.kt") -> formatOrNull("code", before, file.path) ?: before
                file.extension == "yaml" || file.extension == "yml" -> formatYaml(before, file.path)
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

    fun formatYaml(text: String, label: String): String {
        val lines = text.split("\n")
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
            val formatted = formatOrNull(key.groupValues[2], body, "$label:${start + 1}")
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

    private fun formatOrNull(property: String, code: String, label: String): String? = try {
        if (imports && property == "code") formatBoxKotlin(code, useK2 = k2) else formatCodeProperty(property, code)
    } catch (t: Throwable) {
        println("skipped $label ($property): ${t.javaClass.simpleName}: ${t.message?.lineSequence()?.firstOrNull()}")
        null
    }

    private fun indentOf(line: String) = line.length - line.trimStart().length
}
