package afield.brief

import java.io.File

/**
 * The Brief editor extension, as bytes.
 *
 * The single source of truth is the static resource `src/commonMain/resources/brief.js`, served to the
 * browser as the AMD module `bithero/brief` (see `bootstrapMonaco.js`) and read verbatim here, so the
 * node probe in `afield.examples.brief.BriefEditorExtensionWorkout` exercises the exact code that
 * ships. The file self-installs under bare `eval` and registers an AMD module when a Monaco loader is
 * present, which is what makes it testable outside a browser at all — a browser-side language you can
 * only test in a browser does not stay correct.
 */
object BriefEditorExtension {
    const val id: String = Brief.ID

    fun javascript(): String {
        BriefEditorExtension::class.java.getResourceAsStream("/brief.js")?.use { stream ->
            return stream.readBytes().toString(Charsets.UTF_8)
        }
        val onDisk = File("./src/commonMain/resources/brief.js")
        require(onDisk.isFile) { "brief.js not found on the classpath or at ${onDisk.absolutePath}" }
        return onDisk.readText(Charsets.UTF_8)
    }

    /**
     * The vocabulary the `.js` declares, scraped out of it.
     *
     * A Route A language keeps its word list twice — once on the JVM for the compiler, once in the
     * browser for the editor — and that is the price of the split, not a defect. What *is* a defect is
     * the two drifting, so `BriefEditorExtensionTest` compares this to [BriefVerbs] in both directions.
     * Keyed by the `const` name in `brief.js`.
     */
    fun declaredVocabulary(): Map<String, Set<String>> {
        val js = javascript()
        val out = linkedMapOf<String, Set<String>>()
        Regex("""const (\w+) = new Set\(\[(.*?)]\);""", RegexOption.DOT_MATCHES_ALL)
            .findAll(js)
            .forEach { match ->
                val words = Regex(""""([^"]+)"""").findAll(match.groupValues[2]).map { it.groupValues[1] }.toSet()
                if (words.isNotEmpty()) out[match.groupValues[1]] = words
            }
        return out
    }
}

fun briefEditorExtensionJavascript(): String = BriefEditorExtension.javascript()
