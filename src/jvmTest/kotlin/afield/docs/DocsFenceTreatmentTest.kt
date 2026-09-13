package afield.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every code fence on a published page must carry the `diffstatic` treatment.
 *
 * A fence written as a bare language word — ` ```kotlin ` — renders as ordinary highlighted
 * Markdown while its neighbours get the code-diff chrome, and **nothing in the build noticed**.
 * Nothing in the build rewrites a page's plain fences: there was one exception until 2026-09-13,
 * `docs_hooks.rewrite_guide_fences`, and it only ever covered the workspace guides under
 * `public/docs/assets` — which are no longer built as pages at all. The failure is silent and
 * purely visual, which is why it kept coming back: on 2026-08-19 a new page shipped six of them,
 * and a sweep then found 26 more across ten pages that had been wrong for months.
 *
 * The fix a failure wants is mechanical — ` ```kotlin ` becomes ` ```diffstatic {data-lang=kotlin} `
 * — and the message below says so with the exact replacement text.
 *
 * Fences that already carry attributes are left alone: those are runner fences
 * (`diffstatic_runner`, `diff_runner`), the plain `diff` shorthand, or Field's own document format
 * (`kotlin [field-property …]`), and rewriting any of them would break it.
 */
class DocsFenceTreatmentTest {

    /**
     * Pages allowed to keep a bare fence, each for a reason that is checked rather than trusted.
     *
     * `diff-player.md` documents the fence syntax itself, so its examples *contain* `:::static` /
     * `:::before` / `:::after`. Those are [markerPrefixes] — the diffstatic processor consumes them
     * — so giving these fences the treatment would silently eat the syntax being documented. They
     * are deliberately raw, and [theAllowlistIsStillJustified] proves each one still looks like a
     * fence-syntax example rather than an ordinary snippet someone forgot.
     */
    private val allowlist = mapOf(
        "public/docs/diff-player.md" to "documents fence syntax; its examples contain ::: markers",
    )

    private val markerPrefixes = listOf(":::static", ":::before", ":::after", ":::")

    /** Read off `build_zensical_docs.WORKSPACE_MARKDOWN_FILENAMES` by [theWorkspaceSkipMatchesTheBuild]. */
    private val workspaceMarkdown = setOf("AGENTS.md", "README.md", "document.md")

    private data class Fence(val file: String, val line: Int, val lang: String, val body: List<String>)

    private val fenceOpen = Regex("""^( {0,3})(`{3,}|~{3,})(.*?)\s*$""")
    private val bareLang = Regex("""^[A-Za-z0-9_+#-]*$""")

    /**
     * Bare-language opening fences on published pages.
     *
     * Fence *state* is tracked rather than matched line by line, because a closing ``` would
     * otherwise read as an opener, and because a four-backtick fence can legally contain
     * three-backtick lines — which is exactly the shape `diff-player.md` uses.
     */
    private fun bareFences(root: File): List<Fence> {
        val docs = File(root, "public/docs")
        val found = mutableListOf<Fence>()
        docs.walkTopDown().filter { it.isFile && it.extension == "md" }.sorted().forEach { file ->
            val relative = file.relativeTo(root).path
            // The markdown Field writes into a workspace under public/docs/assets is not a
            // published page: build_zensical_docs.is_workspace_markdown keeps all three files out
            // of the page pipeline and ships them raw, so their fences are never rendered by the
            // site and must stay ordinary markdown on disk for the copy in a user's workspace.
            if (file.name in workspaceMarkdown && "assets" in relative.split(File.separator)) return@forEach

            var closing: Regex? = null
            var openedAt = 0
            var lang: String? = null
            val body = mutableListOf<String>()
            file.readLines().forEachIndexed { index, line ->
                val active = closing
                if (active != null) {
                    if (active.matches(line)) {
                        lang?.let { found += Fence(relative, openedAt, it, body.toList()) }
                        closing = null
                        lang = null
                        body.clear()
                    } else {
                        body += line
                    }
                    return@forEachIndexed
                }
                val match = fenceOpen.find(line) ?: return@forEachIndexed
                val ticks = match.groupValues[2]
                closing = Regex("""^ {0,3}${Regex.escape(ticks)}\s*$""")
                openedAt = index + 1
                val info = match.groupValues[3].trim()
                // Two shapes go untreated, and missing the second is why this kept coming back:
                // a bare language word (```kotlin) *and* an opening fence with no info string at
                // all, which renders as a line-numbered highlighttable. `docs_hooks` aliases the
                // empty info string to `text`, so that is what it should have been written as.
                lang = when {
                    info.isEmpty() -> "text"
                    bareLang.matches(info) -> info
                    else -> null
                }
                body.clear()
            }
        }
        return found
    }

    @Test
    fun everyPublishedFenceCarriesTheDiffstaticTreatment() {
        val root = CitationGuard.repoRoot()
        val offenders = bareFences(root).filterNot { it.file in allowlist }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("${offenders.size} code fence(s) render as raw Markdown instead of diffstatic.")
                appendLine("Replace the opening fence; the content does not change:")
                offenders.forEach {
                    val wrote = if (it.lang == "text") "``` (no language)" else "```${it.lang}"
                    appendLine("  ${it.file}:${it.line}   $wrote  ->  ```diffstatic {data-lang=${it.lang}}")
                }
                appendLine("If a fence must stay raw — it documents fence syntax, say — add its page to")
                appendLine("DocsFenceTreatmentTest.allowlist with the reason.")
            },
        )
    }

    /**
     * The allowlist must not become a place things are quietly parked.
     *
     * Every allowed page has to still contain at least one bare fence whose body carries a `:::`
     * marker — the property that makes converting it destructive. An entry that no longer earns
     * its exemption fails here rather than sitting there forever.
     */
    @Test
    fun theAllowlistIsStillJustified() {
        val root = CitationGuard.repoRoot()
        val byFile = bareFences(root).groupBy { it.file }

        allowlist.forEach { (path, reason) ->
            val fences = byFile[path]
            assertTrue(
                fences != null && fences.isNotEmpty(),
                "$path is allowlisted (\"$reason\") but has no bare fences left — remove the entry",
            )
            val destructive = fences.filter { fence ->
                fence.body.any { line -> markerPrefixes.any { line.trim().startsWith(it) } }
            }
            assertTrue(
                destructive.isNotEmpty(),
                "$path is allowlisted (\"$reason\") but none of its ${fences.size} bare fence(s) " +
                    "contain a ::: marker, so converting them would not be destructive — either " +
                    "convert them or update the reason",
            )
        }
    }

    /**
     * Pins the premise the whole guard rests on: the build does *not* rescue a hand-written page.
     *
     * It used to have one exception, `docs_hooks.rewrite_guide_fences` behind `is_workspace_guide`,
     * which is why [bareFences] skips the workspace markdown under `public/docs/assets`. That
     * markdown stopped being built as pages on 2026-09-13 — 312 orphans whose only reader was the
     * search index — so the rewriting went with it and no page's fences are touched on the way in.
     * If `docs_hooks.py` ever learns to rewrite every page's plain fences, this guard becomes
     * belt-and-braces rather than the only thing standing between a plain fence and a wrong render,
     * and this test should be the one that says so.
     */
    @Test
    fun theBuildStillDoesNotRewriteAnyPagesFences() {
        val hooks = File(CitationGuard.repoRoot(), "public/docs_hooks.py").readText()
        assertTrue(
            "def rewrite_guide_fences" !in hooks && "def is_workspace_guide" !in hooks,
            "docs_hooks.py rewrites fences on the way in again — if that is now site-wide this " +
                "guard is no longer load-bearing and its KDoc needs saying so",
        )
    }

    /**
     * The skip in [bareFences] must name the same files the docs build keeps out of the site.
     *
     * Read off `WORKSPACE_MARKDOWN_FILENAMES` rather than restated, because the two lists failing
     * apart is silent in both directions: a file the build still renders would go unchecked, and a
     * file it no longer renders would be held to a contract about published pages.
     */
    @Test
    fun theWorkspaceSkipMatchesTheBuild() {
        val build = File(CitationGuard.repoRoot(), "tools/build_zensical_docs.py").readText()
        val declared = Regex("""WORKSPACE_MARKDOWN_FILENAMES\s*=\s*frozenset\(\{([^}]*)\}\)""").find(build)
        assertTrue(
            declared != null,
            "build_zensical_docs.py no longer declares WORKSPACE_MARKDOWN_FILENAMES; if workspace " +
                "markdown is a published page again, bareFences() must stop skipping it",
        )
        assertEquals(
            workspaceMarkdown,
            Regex(""""([^"]+)"""").findAll(declared.groupValues[1]).map { it.groupValues[1] }.toSet(),
            "the docs build and this guard disagree about which markdown is workspace furniture",
        )
    }

    /** The treated spellings, so a typo like `diffstatik` cannot pass as "has attributes". */
    @Test
    fun theTreatedFenceSpellingsAreTheOnesTheHooksKnow() {
        val hooks = File(CitationGuard.repoRoot(), "public/docs_hooks.py").readText()
        val known = listOf("diffstatic_runner", "diffstatic", "diff_runner")
        known.forEach {
            assertTrue("\"$it\"" in hooks, "docs_hooks.py no longer knows the $it fence")
        }
    }

    /**
     * [markerPrefixes] must stay a superset of what the hooks actually strip.
     *
     * Read off `MARKER_PREFIXES` rather than counting occurrences of the strings — they appear in
     * several code paths, and an occurrence count says nothing about the tuple's contents.
     */
    @Test
    fun theMarkerSetMatchesTheHooks() {
        val hooks = File(CitationGuard.repoRoot(), "public/docs_hooks.py").readText()
        val tuple = Regex("""MARKER_PREFIXES\s*=\s*\(([^)]*)\)""").find(hooks)
        assertTrue(tuple != null, "docs_hooks.py no longer defines MARKER_PREFIXES")
        val declared = Regex(""""([^"]+)"""").findAll(tuple.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals(
            emptyList(),
            declared.filterNot { declared -> markerPrefixes.any { declared.startsWith(it) } },
            "docs_hooks.py strips a marker this guard does not know about, so a fence containing " +
                "it could be judged safe to convert when it is not",
        )
    }
}
