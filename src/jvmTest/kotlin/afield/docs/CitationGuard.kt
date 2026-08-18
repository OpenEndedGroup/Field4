package afield.docs

import java.io.File

/**
 * Resolving `dev_docs` citations by **symbol** rather than by counting lines.
 *
 * ## Why this exists
 *
 * Field's dev_docs cite source as `` `path/to/File.kt:1234` ``. A line number is a genuinely useful
 * thing to hand a reader — it puts them on the spot in one keystroke — and it is also the single most
 * perishable fact a document can contain, because *anyone* who inserts a line above it invalidates it.
 *
 * The guards that watched those numbers used a slack window: the anchor had to appear within ±N lines
 * of the cited number. That works right up until it doesn't, and it fails in a specific, expensive way:
 *
 * - **2026-08-10.** Two rows of `small_language_anatomy.md` cited `Server.kt:4632`/`:4612` for anchors
 *   really at 4634/4614 — surviving at *exactly* the full ±2 budget, which nothing reported. A merge
 *   moved two lines and both detonated at once.
 * - **2026-08-07.** The lesson was written down as: *a line-number citation guard is never
 *   path-disjoint.* Two branches touched perfectly disjoint file sets and still collided, because the
 *   guard reaches into every source file any branch happens to touch.
 *
 * A slack window cannot fix this, because the failure is not caused by the window being too small. It
 * is caused by the number being load-bearing at all.
 *
 * ## What this does instead
 *
 * The number stops being load-bearing. Every citation carries an **anchor** — a substring that must
 * still be present at the cited site — and the anchor is resolved by *searching the whole file* for
 * it. Where the symbol turns out to be is the answer. A sibling inserting twenty lines at the top of
 * the file moves the symbol and the guard follows it, silently and correctly.
 *
 * The line number survives as **advisory**: it is what the doc shows the reader, it is used to
 * disambiguate an anchor that occurs more than once, and when it has drifted this reports the line the
 * symbol is *actually* on — but a stale advisory line is not a build failure. That last part is the
 * whole design. If a stale advisory number could fail the build, a sibling's insertion would still
 * turn the guard red, and nothing would have been fixed. See [Resolution.Resolved.advisoryDrift] and
 * `dev_docs/citation_contracts.md` for the trade this makes.
 *
 * ## Ambiguity, and the honest limit of the idea
 *
 * Symbol anchors are only unbreakable when the symbol is **unique in its file**. 23 of the 134 anchors
 * in the migrated corpus are not: `` `runNodeProbe` `` occurs twice, `` `builtinWords` `` four times.
 * For those, the advisory line is used to pick the nearest occurrence, which restores a *bounded*
 * fragility — insert enough lines and the nearest occurrence becomes a different one.
 *
 * Rather than wave at that, [Resolution.Resolved.headroom] measures it: the number of lines that can be
 * inserted above the cited site before the anchor would resolve to the wrong occurrence. A unique
 * anchor reports [UNBOUNDED]. This is the honest replacement for "slack", and it is a much better
 * number, because it is a property of the citation rather than a constant in the guard.
 */
object CitationGuard {

    /** Reported as the headroom of an anchor that is unique in its file and so cannot be confused. */
    const val UNBOUNDED = Int.MAX_VALUE

    /** How far a simulated insertion is pushed before an anchor is called effectively unbreakable. */
    private const val HEADROOM_PROBE_LIMIT = 2000

    /** Extensions a citation may point at. Kept in sync with the parsing regexes below. */
    private const val EXT = "kt|js|md|py|kts|html|swift|glsl|json|yml|yaml"

    /**
     * The shape a citation was written in. This matters for error messages — telling an author to fix
     * "the ledger row" or "the prose citation" is worth more than a line number in a 700-line page.
     */
    enum class Form {
        /** `` | n | description | `path:line` | `anchor` | `` — the ledger tables. */
        LEDGER_ROW,

        /** `` `path#anchor` `` — no line number at all, the most robust form. */
        PROSE_SYMBOL,

        /** `` `path:line#anchor` `` — advisory line for the reader, symbol for the guard. */
        PROSE_LINE_SYMBOL,
    }

    /**
     * One citation, as written on a page.
     *
     * @param page repo-relative path of the citing markdown page.
     * @param pageLine 1-based line within that page, so a failure names where to go and edit.
     * @param path repo-relative path of the cited source file.
     * @param advisoryLine the number written in the doc, or null for a pure [Form.PROSE_SYMBOL].
     * @param anchor the substring that must still be present at the cited site.
     */
    data class Citation(
        val page: String,
        val pageLine: Int,
        val path: String,
        val advisoryLine: Int?,
        val anchor: String,
        val form: Form,
    ) {
        /** How this reads in a failure message: `dev_docs/x.md:12 → Foo.kt:34 (`bar`)`. */
        fun describe(): String =
            "$page:$pageLine → $path:${advisoryLine ?: "?"} (`$anchor`)"
    }

    sealed interface Resolution {
        /**
         * The anchor was found. [line] is where it *actually* is, which may differ from the advisory
         * number in the doc.
         *
         * @param hits every line in the file containing the anchor, 1-based.
         * @param headroom lines insertable above the site before this would resolve elsewhere;
         *   [UNBOUNDED] when the anchor is unique.
         */
        data class Resolved(
            val citation: Citation,
            val line: Int,
            val hits: List<Int>,
            val headroom: Int,
        ) : Resolution {
            /** Signed distance from the advisory number to the truth; null when there is no number. */
            val advisoryDrift: Int? get() = citation.advisoryLine?.let { line - it }

            /** True when the doc's number is exactly right and needs no repair. */
            val advisoryIsCorrect: Boolean get() = advisoryDrift == 0

            /** True when the anchor occurs exactly once in its file, and so cannot be confused. */
            val isUnique: Boolean get() = hits.size == 1
        }

        /** The anchor is not in the file at all, or the file is gone. Always a build failure. */
        data class Broken(val citation: Citation, val reason: String) : Resolution
    }

    // ---------------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------------

    /**
     * Ledger row: `` | … | `path:line` | `anchor` | ``.
     *
     * This is the shape `SmallLanguageAnatomyTest` has always recognised, kept byte-compatible so the
     * migration did not have to rewrite 134 rows. The fourth cell was always a symbol; only the way it
     * gets resolved has changed.
     */
    private val LEDGER_ROW = Regex(
        """^\|.*\|\s*`([^`]+\.(?:$EXT)):(\d+)`\s*\|\s*`([^`]+)`\s*\|\s*$"""
    )

    /** `` `path:line#anchor` `` — advisory line plus authoritative symbol. */
    private val PROSE_LINE_SYMBOL = Regex("""`([A-Za-z0-9_./-]+\.(?:$EXT)):(\d+)#([^`]+)`""")

    /** `` `path#anchor` `` — no number at all. */
    private val PROSE_SYMBOL = Regex("""`([A-Za-z0-9_./-]+\.(?:$EXT))#([^`]+)`""")

    /**
     * Bare `` `path:line` `` with no anchor of any kind — an *unguarded* citation. Not a citation this
     * can check; counted so that a page's unguarded remainder is a number rather than a vibe.
     */
    private val BARE = Regex("""`([A-Za-z0-9_./-]+\.(?:$EXT)):(\d+)(?:-\d+)?`""")

    /**
     * A `|` inside a markdown table cell has to be written `\|`, so an anchor containing a pipe —
     * FieldML's `\|>` pipeline operator, say — arrives escaped. Unescape it, or the anchor can never
     * match and the page is forced to avoid citing the very operators it documents.
     */
    private fun unescapeCell(s: String) = s.replace("\\|", "|")

    /** Every checkable citation on [pageFile], in page order. */
    fun parse(pageFile: File, pageName: String = pageFile.name): List<Citation> {
        val out = mutableListOf<Citation>()
        pageFile.readLines().forEachIndexed { idx, text ->
            val n = idx + 1
            LEDGER_ROW.find(text)?.let { m ->
                out += Citation(
                    pageName, n, m.groupValues[1], m.groupValues[2].toInt(),
                    unescapeCell(m.groupValues[3]), Form.LEDGER_ROW
                )
            }
            PROSE_LINE_SYMBOL.findAll(text).forEach { m ->
                out += Citation(
                    pageName, n, m.groupValues[1], m.groupValues[2].toInt(),
                    unescapeCell(m.groupValues[3]), Form.PROSE_LINE_SYMBOL
                )
            }
            // A `path:line#anchor` also matches `path#anchor` on the `path:line` prefix in some
            // readings; anchor the pure form by requiring no `:` before the `#`.
            PROSE_SYMBOL.findAll(text).forEach { m ->
                if (':' in m.groupValues[1]) return@forEach
                out += Citation(
                    pageName, n, m.groupValues[1], null,
                    unescapeCell(m.groupValues[2]), Form.PROSE_SYMBOL
                )
            }
        }
        return out
    }

    /**
     * Bare `path:line` citations on [pageFile] that carry no anchor and therefore nothing checks.
     * Ledger rows are excluded — their number is bare but their fourth cell is the anchor.
     */
    fun unguarded(pageFile: File): List<Pair<String, Int>> =
        pageFile.readLines().flatMap { text ->
            if (LEDGER_ROW.containsMatchIn(text)) return@flatMap emptyList()
            val symbolic = (PROSE_LINE_SYMBOL.findAll(text) + PROSE_SYMBOL.findAll(text))
                .map { it.range }.toList()
            BARE.findAll(text)
                .filterNot { b -> symbolic.any { it.first <= b.range.first && b.range.last <= it.last } }
                .map { it.groupValues[1] to it.groupValues[2].toInt() }
                .toList()
        }

    // ---------------------------------------------------------------------------------------------
    // Resolution
    // ---------------------------------------------------------------------------------------------

    /**
     * Source files by name, built once, used to resolve abbreviated citation paths.
     *
     * Prose cites `` `FloatEarcut.kt:705#linkedList` ``, not the full
     * `src/jvmMain/kotlin/afield/graphics/FloatEarcut.kt`, and it should keep being able to: a
     * sentence carrying a forty-character path is a sentence nobody reads. So a path that is not
     * repo-relative is matched as a **path suffix**, and is only accepted if exactly one file matches.
     */
    private val fileIndex: Map<String, List<File>> by lazy { indexFilesUnder(repoRoot()) }

    /** Directory names never worth walking, whichever tree they turn up in. */
    private val SKIP_DIRS = setOf(".git", "build", "node_modules", ".gradle", "out", ".idea")

    /**
     * True when [dir] is the root of a **different checkout** than [root] — a nested clone or git
     * worktree that happens to live inside this one.
     *
     * ## Why this test exists
     *
     * [locate] resolves an abbreviated citation by requiring that exactly one file match it. A nested
     * checkout makes *every* filename in the tree match one extra time, so on a machine where agents
     * routinely leave complete checkouts under `.claude/worktrees/` — three of them, today — every
     * abbreviated citation in the corpus becomes unresolvable at once.
     *
     * That was not a hypothetical. Measured 2026-08-17 in `/Users/marc/field4`: **83 of 222 citations
     * broken, which is exactly the 83 written in abbreviated form**, while the identical corpus in a
     * clean worktree broke none. The nightly journal recorded 81 on 08-14 and 82 on 08-16; those are
     * the same measurement, and the count rose because the corpus gained one abbreviated citation on
     * each of those dates, not because anything drifted.
     *
     * A permanent false red in the one tree gates are actually run in is worse than having no guard,
     * because it teaches a reader to skip the output — and it had already produced one wrong diagnosis
     * that then had to be retracted.
     *
     * ## Why the marker is `.git` and not `settings.gradle.kts`
     *
     * [repoRoot] finds a root by looking for `settings.gradle.kts`, so that reads like the natural
     * marker here too. It is the wrong one, and measurably so: `tools/code-use-semantic-sidecar` is an
     * in-repo Gradle build with its own `settings.gradle.kts`, its sources genuinely *are* part of this
     * checkout, and the guarded page `dev_docs/completion_aa_sidecar.md` cites two of them
     * (`AaCompletion.kt`, `CodeUseSemanticSidecar.kt`). Skipping on `settings.gradle.kts` repairs the 83
     * and breaks those 4 — a fix that trades one false red for a smaller one.
     *
     * `.git` separates the two cases exactly, on the real inputs: a nested clone or worktree always has
     * one (a directory, or a file for a worktree), an in-repo subproject never does. See
     * [CitationContractTest.anInRepoGradleSubprojectIsNotMistakenForANestedCheckout], which pins the
     * distinction, and `dev_docs/citation_contracts.md` for the whole story.
     *
     * The residual cost is narrow and deliberate: a git *submodule* also carries a `.git` file, so an
     * *abbreviated* citation into `services/field-audio-ai` would not resolve. A full repo-relative path
     * still does, because [locate] tries that before it ever consults the index.
     */
    fun isForeignCheckout(root: File, dir: File): Boolean =
        dir.absolutePath != root.absolutePath && File(dir, ".git").exists()

    /**
     * [fileIndex]'s walk over an arbitrary [root] — exposed so a test can build an index over a tree it
     * planted, which is the only way to check the nested-checkout rule without contaminating this one.
     */
    fun indexFilesUnder(root: File): Map<String, List<File>> =
        root.walkTopDown()
            .onEnter { it.name !in SKIP_DIRS && !isForeignCheckout(root, it) }
            .filter { it.isFile && it.extension in EXT.split("|") }
            .toList()
            .groupBy { it.name }

    /**
     * The file a citation path points at, or null when it is missing or ambiguous.
     *
     * Exact repo-relative paths win outright, so an unambiguous citation never pays for the index.
     *
     * A match must agree with the **whole** cited path suffix. There used to be a
     * `?: byName.singleOrNull()` fallback here, which accepted a uniquely-named file even when the
     * *directory* the citation named did not exist — so `wrong/dir/FLine.kt` resolved happily to
     * `graphics/FLine.kt` and a misleading path could sit in a doc indefinitely with nothing to say so.
     * It is written in the shape this repo has learned to distrust: a `single…() ?: first…()`-style pair
     * where the second half quietly undoes the first half's insistence on being unambiguous.
     *
     * Measured before removing it: it resolved **0** of the corpus's 222 citations, every one of which
     * matched on the full suffix instead. So it was dead code whose only reachable effect was to launder
     * an error, and [CitationContractTest.everyCitationResolvesByPathSuffixNotByBareName] asserts the
     * corpus still does not need it.
     */
    fun locate(root: File, path: String, index: Map<String, List<File>> = fileIndex): File? {
        File(root, path).takeIf { it.isFile }?.let { return it }
        return index[path.substringAfterLast('/')].orEmpty()
            .filter { it.path.endsWith("/$path") }
            .singleOrNull()
    }

    /** How many candidate files [locate] is naming at most, before a message stops being readable. */
    private const val MAX_LISTED_CANDIDATES = 8

    /**
     * Why [locate] returned null for [path], said precisely.
     *
     * The message this replaces was *"cited file X does not exist, or the abbreviated path matches more
     * than one file"*. Those two causes need opposite repairs — restore/rename the file, versus lengthen
     * the path — and a reader handed both at once has to go and find out which happened. That is not a
     * theoretical cost: the conflation is exactly why the nested-checkout failure above was read as 83
     * deleted files and mis-diagnosed twice. So ambiguity now *names the files it matched*, which makes
     * a nested checkout self-evident in the failure text rather than a puzzle.
     */
    fun explainUnresolvable(
        root: File,
        path: String,
        index: Map<String, List<File>> = fileIndex,
    ): String {
        val byName = index[path.substringAfterLast('/')].orEmpty()
        val matches = byName.filter { it.path.endsWith("/$path") }
        if (matches.isEmpty()) {
            val elsewhere = if (byName.isEmpty()) ""
            else " — a file of that name exists at " +
                byName.take(MAX_LISTED_CANDIDATES).joinToString(", ") { it.relativeToOrSelf(root).path } +
                ", so the directory in the citation is wrong"
            return "cited file `$path` does not exist$elsewhere"
        }
        val shown = matches.take(MAX_LISTED_CANDIDATES).joinToString(", ") {
            it.relativeToOrSelf(root).path
        }
        val more = (matches.size - MAX_LISTED_CANDIDATES).let { if (it > 0) ", +$it more" else "" }
        return "cited path `$path` is ambiguous — ${matches.size} files match it ($shown$more). " +
            "Write enough of the path to pick one."
    }

    /**
     * Find [Citation.anchor] in [Citation.path] and report where it really is.
     *
     * When the anchor occurs more than once the advisory line picks the nearest occurrence; with no
     * advisory line an ambiguous anchor is [Resolution.Broken], which is deliberate — it pushes the
     * author toward an anchor specific enough to stand on its own.
     */
    fun resolve(root: File, c: Citation): Resolution {
        val f = locate(root, c.path) ?: return Resolution.Broken(c, explainUnresolvable(root, c.path))
        val lines = f.readLines()
        val hits = lines.mapIndexedNotNull { i, l -> if (c.anchor in l) i + 1 else null }
        if (hits.isEmpty()) {
            val at = c.advisoryLine?.let { n ->
                if (n in 1..lines.size) " — line $n now reads: ${lines[n - 1].trim()}"
                else " — the file has only ${lines.size} lines"
            } ?: ""
            return Resolution.Broken(c, "anchor `${c.anchor}` occurs nowhere in ${c.path}$at")
        }
        val advisory = c.advisoryLine
        if (hits.size > 1 && advisory == null) {
            return Resolution.Broken(
                c,
                "anchor `${c.anchor}` is ambiguous — ${hits.size} occurrences in ${c.path} " +
                    "(lines ${hits.joinToString(", ")}) and no line number to disambiguate. " +
                    "Use a longer anchor, or write `${c.path}:${hits.first()}#${c.anchor}`."
            )
        }
        val line = if (advisory == null) hits.single() else pick(hits, advisory)
        return Resolution.Resolved(c, line, hits, headroom(hits, line, advisory))
    }

    /** The occurrence nearest the advisory number; ties break low, so the choice is deterministic. */
    private fun pick(hits: List<Int>, advisory: Int): Int =
        hits.minWithOrNull(compareBy({ Math.abs(it - advisory) }, { it }))!!

    /**
     * How many lines can be inserted *above* the cited site before [pick] would choose a different
     * occurrence — the honest, per-citation replacement for a global slack constant.
     *
     * Measured by simulation rather than derived in closed form: shift every occurrence down by `s`
     * (which is what a sibling inserting `s` lines at the top of the file does), leave the doc's
     * advisory number where it is, and find the first `s` at which the choice changes. A unique anchor
     * cannot change and reports [UNBOUNDED].
     *
     * ## Why simulating only a whole-file shift is enough — for insertions
     *
     * This models one edit: `s` lines inserted above every occurrence. The reasonable objection is
     * that a real edit lands *between* occurrences, shifting only those below it, and that the model
     * therefore flatters citations resolved to the **first** occurrence — of which this corpus has 30.
     *
     * It does not, and the reason turns an apparent gap into a guarantee. An insertion only ever moves
     * occurrences *down*, never up. For a citation resolved to the first occurrence every rival is
     * already below the advisory line, so any insertion pushes it **further** away; a rival can never
     * close the gap. A first-occurrence citation is insertion-robust for the same reason a unique one
     * is, and [UNBOUNDED] is the right answer rather than a modelling artefact. Argued and then
     * checked: re-measuring the corpus against a model that inserts at every point *between*
     * occurrences returns the same minimum (16 lines) and the same five finite-headroom citations.
     *
     * **What is genuinely not modelled: deletion.** Removing lines above a later occurrence can pull it
     * toward the advisory number. Deleting a cited region usually breaks the anchor outright, which
     * [Resolution.Broken] does catch, so the residual risk is narrow — but it is real, and it is why a
     * unique anchor is still worth preferring over a merely first-occurrence one.
     */
    private fun headroom(hits: List<Int>, chosen: Int, advisory: Int?): Int {
        if (hits.size == 1 || advisory == null) return UNBOUNDED
        val chosenIndex = hits.indexOf(chosen)
        for (s in 1..HEADROOM_PROBE_LIMIT) {
            val shifted = hits.map { it + s }
            if (shifted.indexOf(pick(shifted, advisory)) != chosenIndex) return s - 1
        }
        return UNBOUNDED
    }

    // ---------------------------------------------------------------------------------------------
    // Inventory
    // ---------------------------------------------------------------------------------------------

    /**
     * A countable summary of a corpus's health. Every field here is asserted against a floor or a
     * ceiling by [CitationContractTest]; "prints a proud number with no assertion under it" is the
     * failure mode this repo keeps rediscovering.
     */
    data class Inventory(
        val total: Int,
        val resolved: Int,
        val broken: List<Resolution.Broken>,
        val uniqueAnchors: Int,
        val advisoryCorrect: Int,
        val advisoryDrifted: List<Resolution.Resolved>,
        val lowHeadroom: List<Resolution.Resolved>,
        val unguarded: Int,
    ) {
        /**
         * The worst single advisory drift in the corpus, in lines — the number that actually measures
         * harm to a reader. See [MAX_ADVISORY_DRIFT] for why this, and not [advisoryDrifted]`.size`, is
         * the quantity worth asserting.
         */
        val worstAdvisoryDrift: Int
            get() = advisoryDrifted.maxOfOrNull { Math.abs(it.advisoryDrift ?: 0) } ?: 0

        /** The citation responsible for [worstAdvisoryDrift], for a failure message that names it. */
        val worstAdvisoryDriftAt: Resolution.Resolved?
            get() = advisoryDrifted.maxByOrNull { Math.abs(it.advisoryDrift ?: 0) }

        fun render(): String = buildString {
            appendLine("citations parsed:            $total")
            appendLine("  resolved by symbol:        $resolved")
            appendLine("  broken:                    ${broken.size}")
            appendLine("  unique anchors (unbounded headroom): $uniqueAnchors")
            appendLine("  advisory line exactly right: $advisoryCorrect")
            appendLine("  advisory line drifted:     ${advisoryDrifted.size}")
            appendLine("  worst advisory drift:      $worstAdvisoryDrift line(s) " +
                "(ceiling $MAX_ADVISORY_DRIFT)")
            appendLine("  headroom below $LOW_HEADROOM lines:      ${lowHeadroom.size}")
            append("unguarded bare citations:    $unguarded")
        }
    }

    /**
     * Headroom at or below this is reported as fragile. Chosen to be the old `ANCHOR_SLACK` of 2 so
     * that the warning the 2026-08-10 report asked for — "tell me which citations are surviving on
     * their last inch" — reports the same population it would have reported then, only now measured
     * per citation rather than assumed globally.
     */
    const val LOW_HEADROOM = 2

    /**
     * The largest advisory drift, in lines, that any one citation may carry before the build fails.
     *
     * ## Why the *magnitude* is asserted and the *count* is not
     *
     * Demoting line numbers to advisory removed a merge hazard and created a smaller one: a number
     * nothing enforces is a number that rots. Printing the drift count and calling it mitigation would
     * be this repo's single most-recorded failure — a proud number with no assertion under it — so it
     * is worth being precise about which quantity deserves the assertion.
     *
     * **Not the count.** How many citations drift is a function of how many citations happen to point
     * below the edit, which is a property of the *citing page's density*, not of harm. One insertion
     * into `Server.kt` drifts all seven citations that point into it; the same insertion into a
     * once-cited file drifts one. Those are equally harmful to a reader and the count calls them
     * seven-to-one. Worse, a ceiling on the count is exactly the merge hazard again: any sibling
     * touching a densely-cited file trips it.
     *
     * **The magnitude.** How *far* wrong a number is, is precisely how harmful it is. Land a reader
     * three lines off and they see the anchor without noticing; land them three hundred lines off and
     * the citation is a lie. So the contract is per-citation and absolute: *no number in a dev_doc may
     * be more than a screenful wrong.*
     *
     * ## Why this does not rebuild the hazard
     *
     * The 2026-08-07/08-10 collisions came from a ±2 window, where an ordinary edit anywhere above a
     * citation tripped the guard. At 40 lines the population that trips it is different in kind: a
     * branch displacing a cited site by more than a screenful is doing structural surgery on that file,
     * and refreshing the page that documents it is legitimately part of that work — a one-command fix
     * (`FIELD_CITATIONS_WRITE=1`), not the manual recomputation the old guard demanded. Tonight's
     * measured collision case, inserting 20 lines into `Server.kt`, sits comfortably under it and stays
     * a non-event.
     *
     * 40 is a judgement, not a measurement: roughly one screenful, the distance over which a reader who
     * lands slightly wrong still finds what they came for by looking around. It is stated as a
     * judgement so the next person can argue with it rather than assume it was derived.
     */
    const val MAX_ADVISORY_DRIFT = 40

    fun inventory(root: File, pages: List<File>): Inventory {
        val cites = pages.flatMap { p -> parse(p, p.relativeToOrSelf(root).path) }
        val resolutions = cites.map { resolve(root, it) }
        val ok = resolutions.filterIsInstance<Resolution.Resolved>()
        return Inventory(
            total = cites.size,
            resolved = ok.size,
            broken = resolutions.filterIsInstance<Resolution.Broken>(),
            uniqueAnchors = ok.count { it.isUnique },
            advisoryCorrect = ok.count { it.advisoryIsCorrect },
            advisoryDrifted = ok.filter { it.advisoryDrift != null && it.advisoryDrift != 0 },
            lowHeadroom = ok.filter { it.headroom <= LOW_HEADROOM },
            unguarded = pages.sumOf { unguarded(it).size },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Repair
    // ---------------------------------------------------------------------------------------------

    /**
     * Rewrite every advisory line number on [pageFile] to the line its anchor is actually on, and
     * return how many were changed.
     *
     * This is the other half of the bargain. Advisory numbers are not allowed to fail the build — that
     * is what makes a sibling's insertion harmless — so something has to stop them rotting quietly
     * instead, and this is it: a mechanical refresh a human runs when they are already editing the
     * page. It is deliberately *not* wired into `check`. A guard that rewrites tracked files during an
     * ordinary build is a worse problem than the one being solved.
     *
     * Only the number is touched. Anchors, prose and table structure are left exactly as they are, so
     * the diff of a refresh is legible and reviewable.
     */
    fun refreshAdvisoryLines(root: File, pageFile: File, pageName: String = pageFile.name): Int {
        val byLine = parse(pageFile, pageName)
            .mapNotNull { c ->
                val r = resolve(root, c) as? Resolution.Resolved ?: return@mapNotNull null
                if (r.advisoryDrift == null || r.advisoryDrift == 0) null else c to r.line
            }
            .groupBy({ it.first.pageLine }, { it })
        if (byLine.isEmpty()) return 0

        var changed = 0
        val out = pageFile.readLines().mapIndexed { idx, text ->
            var line = text
            byLine[idx + 1]?.forEach { (c, actual) ->
                // Replace only within this citation's own backticked span, so a line carrying two
                // citations cannot have one of them clobbered by the other's number.
                val old = "`${c.path}:${c.advisoryLine}"
                if (old in line) {
                    line = line.replace(old, "`${c.path}:$actual")
                    changed++
                }
            }
            line
        }
        if (changed > 0) pageFile.writeText(out.joinToString("\n", postfix = "\n"))
        return changed
    }

    /**
     * Write the whole corpus out as JSON, one record per citation, for anything that wants to ask a
     * question this test class did not anticipate — "which pages cite `Server.kt`?" being the obvious
     * one, since knowing that in advance is what would have let two of the three recorded merge
     * collisions be predicted rather than discovered.
     *
     * Hand-rolled rather than serialized: this is a test-source utility, and a schema this small is
     * not worth a dependency. Written under `build/`, so it is never committed.
     */
    fun writeInventoryJson(root: File, pages: List<File>, out: File) {
        fun esc(s: String) = buildString {
            s.forEach {
                when (it) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (it < ' ') append("\\u%04x".format(it.code)) else append(it)
                }
            }
        }
        val rows = pages.flatMap { p -> parse(p, p.relativeToOrSelf(root).path) }.map { c ->
            when (val r = resolve(root, c)) {
                is Resolution.Resolved -> """
                    |    {"page": "${esc(c.page)}", "pageLine": ${c.pageLine},
                    | "path": "${esc(c.path)}", "anchor": "${esc(c.anchor)}", "form": "${c.form}",
                    | "advisoryLine": ${c.advisoryLine ?: "null"}, "resolvedLine": ${r.line},
                    | "occurrences": ${r.hits.size}, "unique": ${r.isUnique},
                    | "headroom": ${if (r.headroom == UNBOUNDED) "null" else r.headroom},
                    | "advisoryDrift": ${r.advisoryDrift ?: "null"}, "broken": false}
                """.trimMargin().replace("\n", "")

                is Resolution.Broken -> """
                    |    {"page": "${esc(c.page)}", "pageLine": ${c.pageLine},
                    | "path": "${esc(c.path)}", "anchor": "${esc(c.anchor)}", "form": "${c.form}",
                    | "advisoryLine": ${c.advisoryLine ?: "null"}, "broken": true,
                    | "reason": "${esc(r.reason)}"}
                """.trimMargin().replace("\n", "")
            }
        }
        out.parentFile?.mkdirs()
        out.writeText("{\n  \"citations\": [\n" + rows.joinToString(",\n") + "\n  ]\n}\n")
    }

    /** Walk up to the directory holding `settings.gradle.kts`. */
    fun repoRoot(): File {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, "settings.gradle.kts").isFile) {
            d = d.parentFile ?: error("no repo root above ${System.getProperty("user.dir")}")
        }
        return d
    }
}
