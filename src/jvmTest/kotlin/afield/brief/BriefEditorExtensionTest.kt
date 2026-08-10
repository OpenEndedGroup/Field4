package afield.brief

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `brief.js`'s vocabulary against `BriefVerbs`, in both directions.
 *
 * A Route A language keeps its word list twice — the compiler's on the JVM, the editor's in a browser
 * `.js` — and there is no way around that, because the browser cannot call the compiler to find out
 * what a word is. So the mirror has to be *asserted*, which is shape 4 of
 * `dev_docs/small_language_anatomy.md`'s coverage-guard patterns and the shape it recommends for
 * exactly this situation: read the `.js` from Kotlin, scrape the list, and check forward *and* reverse.
 *
 * The failure this catches is quiet and specific. A verb added to `BriefVerbs` and not to `brief.js`
 * parses and solves correctly and shows up in the editor as an unhighlighted identifier with no
 * completion — so it works, and looks like it does not. The reverse — a word in `brief.js` that the
 * compiler does not have — is worse: the editor advertises it, and the program fails to compile.
 *
 * This test also holds the two registration lists, because a language module that is not in
 * `FIELD_LANGUAGE_MODULES` never loads and one that is not in `DEFAULT_LANGUAGES` gets no completion,
 * and neither omission produces an error anywhere.
 */
class BriefEditorExtensionTest {

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, "settings.gradle.kts").isFile) {
            d = d.parentFile ?: error("no repo root above ${System.getProperty("user.dir")}")
        }
        return d
    }

    private val root = repoRoot()

    @Test
    fun theEditorVocabularyMirrorsBriefVerbsInBothDirections() {
        val declared = BriefEditorExtension.declaredVocabulary()
        assertTrue(
            declared.size >= 8,
            "the brief.js vocabulary scrape looks broken — found ${declared.keys}"
        )
        val pairs = listOf(
            Triple("strengths", BriefVerbs.strengths, "the strength ladder"),
            Triple("declarations", BriefVerbs.declarations, "the declaration keywords"),
            Triple("directives", BriefVerbs.directives, "the directives"),
            Triple("infixVerbs", BriefVerbs.infix, "the infix relations"),
            Triple("prefixVerbs", BriefVerbs.prefix, "the prefix relations"),
            Triple("functions", BriefVerbs.functions, "the term functions"),
            Triple("properties", BriefVerbs.properties, "the properties"),
            Triple("losses", BriefVerbs.losses, "the robust losses"),
            Triple("glue", BriefVerbs.glue, "the glue words"),
        )
        pairs.forEach { (jsName, kotlinSet, what) ->
            val js = declared[jsName]
                ?: error("brief.js has no `const $jsName = new Set([…])`; it declares ${declared.keys}")
            val onlyInKotlin = (kotlinSet - js).sorted()
            val onlyInJs = (js - kotlinSet).sorted()
            assertTrue(
                onlyInKotlin.isEmpty(),
                "$what: BriefVerbs has $onlyInKotlin, brief.js does not. The notation would accept " +
                    "them and the editor would show them as plain identifiers with no completion — " +
                    "working, and looking like they do not."
            )
            assertTrue(
                onlyInJs.isEmpty(),
                "$what: brief.js has $onlyInJs, BriefVerbs does not. The editor would advertise a word " +
                    "the compiler refuses, which is the worse direction."
            )
            assertEquals(kotlinSet, js, "$what must match exactly")
        }
    }

    @Test
    fun everyDocumentedWordIsAWord() {
        // The `docs` map in brief.js is what the context badge and the completion detail read. A key
        // that is not a word is dead text nobody will ever see, which is how a hover ends up describing
        // a verb that was renamed.
        val js = BriefEditorExtension.javascript()
        // Scoped to the `docs` map. A repo-wide scrape for `["a", "b"]` also picks up the AMD
        // dependency array and the Monaco alias list, which is how a guard ends up complaining about
        // the word "require".
        val block = Regex("""const docs = new Map\(\[(.*?)]\);""", RegexOption.DOT_MATCHES_ALL)
            .find(js)?.groupValues?.get(1)
            ?: error("brief.js has no `const docs = new Map([…])`")
        val documented = Regex("""\["([\w-]+)",""").findAll(block).map { it.groupValues[1] }.toSet()
        assertTrue(documented.size >= 20, "the brief.js docs scrape looks broken — found $documented")
        val orphans = (documented - BriefVerbs.all).sorted()
        assertTrue(orphans.isEmpty(), "brief.js documents words the notation does not have: $orphans")
    }

    @Test
    fun theModuleIsLoadedAtBootAndServedBySuggest() {
        val bootstrap = File(root, "src/commonMain/resources/bootstrapMonaco.js").readText()
        assertTrue(
            "\"bithero/brief\"" in
                (Regex("""const FIELD_LANGUAGE_MODULES\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
                    .find(bootstrap)?.groupValues?.get(1) ?: ""),
            "brief.js is not in FIELD_LANGUAGE_MODULES, so it never loads and nothing says so"
        )
        val suggest = File(root, "src/commonMain/resources/fieldSuggest.js").readText()
        assertTrue(
            "\"brief\"" in
                (Regex("""const DEFAULT_LANGUAGES\s*=\s*new Set\(\[(.*?)]\)""", RegexOption.DOT_MATCHES_ALL)
                    .find(suggest)?.groupValues?.get(1) ?: ""),
            "brief is not in DEFAULT_LANGUAGES, so Field's suggest widget ignores the caret " +
                "(fieldSuggest.js's gate returns false) and completion silently does nothing"
        )
    }

    @Test
    fun theResourceIsReadableTheWayTheServerWillReadIt() {
        // `javascript()` prefers the classpath and falls back to disk. If the resource is missing from
        // the jar the browser gets a 404 and the editor comes up with no highlighting and no error, so
        // check the classpath copy specifically rather than the file.
        val fromClasspath = BriefEditorExtension::class.java.getResourceAsStream("/brief.js")
            ?: error("brief.js is not on the classpath; the server would serve a 404 and the editor " +
                "would come up with no highlighting and nothing in the console")
        fromClasspath.use {
            val text = it.readBytes().toString(Charsets.UTF_8)
            assertEquals(
                File(root, "src/commonMain/resources/brief.js").readText(), text,
                "the classpath copy of brief.js differs from the one on disk"
            )
            assertTrue("define(\"bithero/brief\"" in text, "the AMD module id has to match the list entry")
        }
    }
}
