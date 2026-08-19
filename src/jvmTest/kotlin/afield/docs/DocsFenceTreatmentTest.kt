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
 * `docs_hooks.py` does rewrite plain fences, but only for workspace guides (`rewrite_guide_fences`
 * is gated behind `is_workspace_guide`), so a hand-written page was never covered. The failure is
 * silent and purely visual, which is why it kept coming back: on 2026-08-19 a new page shipped six
 * of them, and a sweep then found 26 more across ten pages that had been wrong for months.
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
            // Workspace guides are copied verbatim into every workspace and must stay ordinary
            // markdown on disk; docs_hooks.rewrite_guide_fences gives them the treatment at build
            // time. They are the one case the build already handles.
            if (file.name in setOf("AGENTS.md", "README.md") && "assets" in relative.split(File.separator)) return@forEach

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
     * If `docs_hooks.py` ever learns to rewrite every page's plain fences, this guard becomes
     * belt-and-braces rather than the only thing standing between a plain fence and a wrong render,
     * and this test should be the one that says so.
     */
    @Test
    fun theBuildStillOnlyRewritesFencesForWorkspaceGuides() {
        val hooks = File(CitationGuard.repoRoot(), "public/docs_hooks.py").readText()
        assertTrue(
            "def rewrite_guide_fences" in hooks,
            "docs_hooks.py no longer has rewrite_guide_fences; re-derive what this guard is for",
        )
        assertTrue(
            "def is_workspace_guide" in hooks && "GUIDE_FILENAMES" in hooks,
            "the workspace-guide gate is gone — if plain fences are now rewritten site-wide, this " +
                "guard is no longer load-bearing and its KDoc needs saying so",
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
