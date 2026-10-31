package afield.lang.brief

import java.io.File

/**
 * The Brief editor extension's JavaScript, read from the same resource the browser loads.
 *
 * The single source is the static resource `src/commonMain/resources/brief.js`, served to the browser
 * as the AMD module `bithero/brief` (see `bootstrapMonaco.js`) and read verbatim here, so the node
 * probe in `afield.examples.brief.BriefEditorExtensionWorkout` exercises the code that ships. The file
 * installs itself under bare `eval` and registers an AMD module when a Monaco loader is present, which
 * is what lets it be tested outside a browser.
 */

/** Access to `brief.js` and the vocabulary it declares. */
object BriefEditorExtension {
    /** The language id the editor registers under, which is [Brief.ID]. */
    const val id: String = Brief.ID

    /**
     * The text of `brief.js`, from the classpath if it is there and from the source tree otherwise.
     *
     * The on-disk fallback is relative to the working directory, so it answers for a run whose
     * classpath has no resources; it throws rather than returning an empty extension when neither
     * is reachable.
     */
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

/** [BriefEditorExtension.javascript] as a top-level function, for a caller holding no receiver. */
fun briefEditorExtensionJavascript(): String = BriefEditorExtension.javascript()
