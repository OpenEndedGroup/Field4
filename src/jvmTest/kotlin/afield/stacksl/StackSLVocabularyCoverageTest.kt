package afield.stacksl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Does the documentation actually describe the language?
 *
 * Every other small language in Field has a guard like this — RectSL's
 * `RectSLExamples.checkCheatsheetCoverage`, `PetriGallery`'s equivalent,
 * `FieldMLCheatsheetCoverageTest`. StackSL had none, which meant an entire family of words could
 * be added, documented nowhere, and no gate would notice. That is the failure shape this project
 * keeps rediscovering: not a gate that goes red for the wrong reason, but a gate that does not
 * exist for the thing it is named after.
 *
 * WHAT THIS CAN CATCH
 *  - a word in the compiler with no cheatsheet row and no vocabulary entry (forward direction);
 *  - a vocabulary entry or cheatsheet row that names nothing the compiler accepts, which is how a
 *    typo or a renamed-but-not-removed word survives (reverse direction).
 *
 * Both directions matter. A one-directional set difference lets a *deleted* table pass on an
 * empty list, and lets a misspelled row sit in the page forever looking like documentation.
 *
 * WHAT IT CANNOT CATCH
 *  - whether the prose is correct, or even about the right word;
 *  - whether a word has a *worked, rendered example*. RectSL's guard goes that far because its
 *    gallery is a directory of runnable programs; StackSL's rendered gallery is smaller than its
 *    vocabulary, so requiring an example per word would be a ratchet nobody could pay. Presence in
 *    the reference is the bar here, deliberately, and it is the bar the six words below failed.
 *  - the modifier alphabet, number suffixes and mark syntax, which are forms rather than words.
 */
class StackSLVocabularyCoverageTest {

    private val root = File(System.getProperty("user.dir"))
    private val cheatsheetFile = File(root, "public/docs/stacksl_cheatsheet.md")
    private val examplesFile = File(root, "public/docs/stacksl_examples.md")

    /**
     * Words that are mechanically generated spellings of a documented base. Documenting
     * `crosssample.r` and `texelsize` is enough; requiring sixteen numbered sampler variants and
     * every channel suffix would be paperwork, not documentation.
     */
    private fun collapse(word: String): String {
        var w = word
        // `foo.r` / `foo.g` / `foo.b` / `foo.a` channel pickers collapse onto their base word.
        Regex("^(.*)\\.[rgba]$").find(w)?.let { if (it.groupValues[1].isNotEmpty()) w = it.groupValues[1] }
        // A SAMPLER index is not a separate word: `texelsize2`, `sample3-abs`, `gather1.w`.
        //
        // Restricted to the sampler families on purpose. The first draft of this stripped a
        // trailing digit from ANY word, which quietly turned `noise3` into `noise` and `fbm3`
        // into `fbm` — so the guard reported the volumetric family as fully documented before a
        // single line of documentation existed for it. A coverage guard that cannot fail for the
        // words it was written for is worse than no guard, and only the family-specific test
        // below caught it.
        if (INDEXED_SAMPLER_FAMILIES.any { w.startsWith(it) }) {
            w = w.replace(Regex("[0-3](?=$|[.-])"), "").removeSuffix(".w")
        }
        return ALIASES[w] ?: w
    }

    /** The word families that carry a `0..3` sampler index. Nothing else may lose a digit. */
    private val INDEXED_SAMPLER_FAMILIES = listOf(
        "sample", "gather", "texelsize", "texel-size", "texturesize", "texture-size",
        "crosssample", "cross-sample", "rcrosssample", "r-cross-sample"
    )

    /** Alternate spellings that the documentation covers under one member of the pair. */
    private val ALIASES = mapOf(
        "directionalnoise" to "directional-noise",
        "graycode" to "gray-code",
        "texelsize" to "texel-size",
        "texturesize" to "texture-size",
        "crosssample" to "cross-sample",
        "rcrosssample" to "r-cross-sample",
        "voronoi3" to "worley3"
    )

    /** First-column backticked tokens of every markdown table row in the cheatsheet. */
    private fun cheatsheetTokens(): Set<String> =
        cheatsheetFile.readText().lines()
            .filter { it.trimStart().startsWith("|") }
            .flatMap { line ->
                val firstColumn = line.split("|").getOrElse(1) { "" }
                Regex("`([^`]+)`").findAll(firstColumn).map { it.groupValues[1].trim().lowercase() }
            }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Whitespace-separated tokens of the fenced blocks under "## Built-in vocabulary" in
     * `stacksl_examples.md`. Unlike RectSL's cheatsheet, this section is prose fences rather than
     * tables, so the RectSL scraper does not port; this is the StackSL-shaped equivalent.
     */
    private fun vocabularyTokens(): Set<String> {
        val section = examplesFile.readText()
            .substringAfter("## Built-in vocabulary")
            .substringBefore("\n## ")
        return Regex("```[^\n]*\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .findAll(section)
            .flatMap { it.groupValues[1].split(Regex("\\s+")) }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * Cheatsheet first-column entries that are syntax or Kotlin API rather than stack words.
     * Pattern-matched rather than listed, so the allowlist cannot quietly absorb a real typo.
     */
    private fun isSyntaxForm(token: String): Boolean =
        token.contains("(") ||          // `slEval("uv length", …)` — Kotlin entry points
            token.startsWith("[") ||    // `[x,y,z]`, `[3x,2y,2z,0x]` — literal and selector forms
            token.contains("?") ||      // `?name`, `->?name` — host GLSL values
            token.startsWith(".") ||    // `.xy`, `.rgb` — swizzle notation shown with the dot
            token.contains("name")      // `=name`, `>name`, `<name` — marks and definitions

    @Test
    fun `every StackSL word is documented`() {
        if (!cheatsheetFile.exists() || !examplesFile.exists()) return // partial checkout

        val documented = (cheatsheetTokens() + vocabularyTokens()).map(::collapse).toSet()
        check(documented.size > 200) { "the documentation scrape looks broken: only ${documented.size} tokens" }

        val undocumented = stackSLDocumentableWords
            .filter { collapse(it) !in documented }
            .sorted()

        assertTrue(
            undocumented.isEmpty(),
            "these StackSL words appear in no cheatsheet table and in no 'Built-in vocabulary' fence — " +
                "add a row to public/docs/stacksl_cheatsheet.md or an entry to the vocabulary section of " +
                "public/docs/stacksl_examples.md:\n  " + undocumented.joinToString(" ")
        )
        println("stacksl vocabulary coverage: all ${stackSLDocumentableWords.size} documentable words are documented")
    }

    @Test
    fun `the vocabulary section names only real words`() {
        if (!examplesFile.exists()) return
        val real = stackSLAllWords
        val bogus = vocabularyTokens()
            .filter { it !in real && !isSyntaxForm(it) }
            .sorted()
        assertTrue(
            bogus.isEmpty(),
            "the 'Built-in vocabulary' section of stacksl_examples.md lists tokens the compiler does not " +
                "accept — a typo, or a word that was renamed without updating the page:\n  " +
                bogus.joinToString(" ")
        )
    }

    @Test
    fun `every cheatsheet row names a real word or a documented syntax form`() {
        if (!cheatsheetFile.exists()) return
        val real = stackSLAllWords
        val bogus = cheatsheetTokens()
            .filter { it !in real && !isSyntaxForm(it) }
            .sorted()
        assertTrue(
            bogus.isEmpty(),
            "these cheatsheet rows name nothing the compiler accepts:\n  " + bogus.joinToString(" ")
        )
    }

    @Test
    fun `the collapse rule does not merge the 3D words into the 2D ones`() {
        // Pinning the bug described in `collapse`. If these ever collapse together again, the
        // coverage guard silently stops guarding the family it exists for.
        listOf("noise3" to "noise", "fbm3" to "fbm", "curl3" to "curl", "worley3" to "worley").forEach { (a, b) ->
            assertTrue(collapse(a) != collapse(b), "collapse() merged `$a` into `$b`")
        }
        assertTrue(collapse("texelsize2") == collapse("texel-size"), "sampler indices should still collapse")
        assertTrue(collapse("gradient.a") == collapse("gradient"), "channel pickers should still collapse")
    }

    @Test
    fun `the volumetric noise family is documented`() {
        // The specific reason this guard was written. Named explicitly so that deleting the
        // general check does not silently take this with it.
        if (!cheatsheetFile.exists() || !examplesFile.exists()) return
        val documented = (cheatsheetTokens() + vocabularyTokens()).map(::collapse).toSet()
        val family = listOf("noise3", "noise4", "fbm3", "fbm4", "curl3", "fbmd3", "warp3", "worley3")
        val missing = family.filter { collapse(it) !in documented }
        assertTrue(missing.isEmpty(), "the volumetric noise words are undocumented: $missing")
    }
}
