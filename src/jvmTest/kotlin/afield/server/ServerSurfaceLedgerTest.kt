package afield.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A bidirectional ledger between Field's server surface and the page that documents it.
 *
 * ## What this covers, and why it is not what you would guess
 *
 * [FieldApiCorsExposureTest] has pinned the **state-changing HTTP routes** since 2026-08-12
 * and the three WebSocket channels since 2026-08-19. This class deliberately does not
 * duplicate either. It covers the three surfaces nothing covered at all:
 *
 *  - **HTTP read routes.** 34 of them. The mutating census skips them outright — its loop
 *    body opens `if (match.groupValues[1] == "get") return@forEachIndexed`. A read route is
 *    also skipped by `installFieldApiWriteGuard`, whose interceptor only inspects mutating
 *    methods. So a `GET` is the one shape of route that no guard and no test looked at,
 *    while `GET /api/instance-info` returns the absolute path of the checkout and
 *    `GET /api/welcome-changelog` returns the human's recent commit messages. Both were read
 *    by a foreign origin in the 2026-08-11 measurement.
 *  - **`staticFiles` directory mounts.** Five, all in `Server.kt`, and **invisible to every
 *    route regex in the tree**, because a `staticFiles` call is not a verb declaration. One
 *    of them serves the workspace directory at `/`.
 *  - **The `/ws` command names.** 105 of them, in a single dispatch table declared at
 *    `Messages.kt:18`. This is the finding that reorders the whole threat model: the file
 *    proxy, the H2 explorer, box execution and the agent backdoor declare **no HTTP routes
 *    at all**. They are commands on this table. So the largest and most privileged surface
 *    Field has sits behind the door that got its first guard last — 2026-08-19, `3aa64af4d`
 *    — and that guard is off by default.
 *
 * ## Why the ledger lives in a document rather than in this file
 *
 * The expected values are parsed out of `dev_docs/server_surface_inventory.md`. A companion
 * page is a worse place to keep test data than a Kotlin `companion object`, in every respect
 * except the one that matters: a table in a document is *read*. The failure mode this whole
 * area has is confident prose over drifted code, and the only reliable cure is to make the
 * prose and the code fail the build together. So the document is the fixture, and a claim on
 * that page cannot outlive the code it describes.
 *
 * Both directions are asserted, over set equality, for every category — so **adding an
 * unguarded route fails the build**, and **deleting one fails the build** rather than
 * quietly shrinking a list.
 *
 * ## How this refuses to pass vacuously
 *
 * A regex over Kotlin source that silently stops matching after a refactor would present as
 * a green tick over an empty set, which is worse than no guard because it reads as evidence.
 * Three separate mechanisms stop that, and they are distinguishable in the output:
 *
 *  - [theDiscoveryMechanismFindsAPlausibleSurface] runs first in spirit: floors on every
 *    count, a floor on the number of *distinct files* the discovery reaches, and
 *    **landmarks** — specific entries that must be present. A shrunken census with its
 *    landmarks intact is a deletion, which is fine and wants a ledger edit; a shrunken
 *    census with its landmarks gone is a broken regex, which is not. The messages say which.
 *  - [aRouteWithABareWordPathWouldBeInvisibleAndIsRefused] asserts this test's own blind
 *    spot away. The discovery requires a route path to begin with `/` or `{`, because a
 *    bare-word path — `get("resolve")` — is textually indistinguishable from
 *    `JsonObjectBuilder.put("resolve")`, of which this tree has hundreds. Rather than leave
 *    that as a silent hole, the four files that declare Ktor routes are required to contain
 *    no verb call whose quoted path lacks a leading `/` or `{`.
 *  - every ledger table must itself be non-empty and must parse, so a mangled document
 *    fails loudly instead of comparing two empty sets.
 *
 * ## What it is not
 *
 * Not a Kotlin parser, and the same three limits [FieldApiCorsExposureTest] documents apply
 * here: literal paths only, a relative path joined to the nearest preceding `route(…)`
 * prefix in the same file (resolving a `const val` argument, which is how
 * `/field-docs/{...}` resolves), and guard attribution by textual containment, so a guard
 * named in a comment would count. A tripwire on growth and on regression, not a proof.
 */
class ServerSurfaceLedgerTest {

    // -------------------------------------------------------------------------------------
    // Discovery
    // -------------------------------------------------------------------------------------

    /**
     * A verb declaration whose path is a string literal beginning with `/` or `{`.
     *
     * The leading-character requirement is the whole reason this is usable. Ktor's
     * `put("…")` and `kotlinx.serialization`'s `JsonObjectBuilder.put("…")` are the same
     * three tokens, and this tree contains well over a hundred of the latter — `put("abs")`,
     * `put("cell_type")`, `put("ease-in-out")`. Requiring a leading `/` or `{` separates them
     * exactly on the real inputs, and
     * [aRouteWithABareWordPathWouldBeInvisibleAndIsRefused] keeps it that way.
     */
    private val declaration = Regex(
        """^[ \t]*(get|post|put|delete|patch)\(\s*"((?:/|\{)[^"]*)""",
        RegexOption.MULTILINE,
    )

    /**
     * A `route(…)` prefix, capturing the argument expression rather than requiring a literal.
     *
     * Greedy up to the last `)` before the opening brace, so a call whose argument is itself
     * a call — `route(PROXY_ROOT.removeSuffix("/"))` — is captured whole rather than
     * truncated at the inner parenthesis. That one matters: it is the only non-literal prefix
     * in the tree, and truncating it is why `/field-docs/{...}` was missing from the first
     * draft of this census.
     */
    private val routePrefix = Regex("""^[ \t]*route\(\s*(.+)\)\s*\{\s*$""", RegexOption.MULTILINE)

    private val literalPrefix = Regex("""^"(/[^"]*)"$""")
    private val leadingIdentifier = Regex("""^([A-Za-z_][A-Za-z0-9_]*)""")
    private val constDefinition = Regex("const val ([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]*)\"")

    /** A `staticFiles(remotePath = …, dir = File(…))` mount. Not a verb, so nothing else sees it. */
    private val staticMount = Regex(
        """^[ \t]*staticFiles\(\s*remotePath\s*=\s*"([^"]*)"\s*,\s*dir\s*=\s*File\(([^)]*)\)""",
        RegexOption.MULTILINE,
    )

    /** An entry in the `/ws` command dispatch table declared at `Messages.kt:18`. */
    private val commandName = Regex("""handlers\[\s*"([A-Za-z0-9_.:-]+)"\s*]""")

    /**
     * The guard vocabulary of `docsAnnotations.kt`, decomposed into the question each asks.
     *
     * Identical to [FieldApiCorsExposureTest]'s map on purpose: "loopback" must mean the same
     * thing in the read ledger as in the write ledger, or comparing the two tables teaches a
     * reader something false.
     */
    private val guardAtoms = mapOf(
        "refuseUnlessSameSiteDocsClient" to listOf("loopback", "same-site"),
        "refuseUnlessLocalDocsClient" to listOf("loopback"),
        "refuseDocsImprovementLaunch" to listOf("loopback", "enabled+token"),
    )
    private val guardOrder = listOf("loopback", "same-site", "enabled+token")

    private data class Surface(
        /** `GET /api/source` -> (`Server.kt`, `none`). */
        val reads: Map<String, Pair<String, String>>,
        /**
         * `File(home)` -> (`/`, `Server.kt`).
         *
         * Keyed by the **directory expression**, not by the mount point: four of the five
         * mounts share `remotePath = ""`, so the mount point is not a key.
         */
        val mounts: Map<String, Pair<String, String>>,
        /** `set-string` -> `Server.kt`. */
        val commands: Map<String, String>,
        val routeDeclaringFiles: Set<String>,
    )

    private fun kotlinFiles(): List<File> =
        File(repositoryRoot(), "src/jvmMain/kotlin/afield").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    private val surface: Surface by lazy {
        val reads = sortedMapOf<String, Pair<String, String>>()
        val mounts = sortedMapOf<String, Pair<String, String>>()
        val commands = sortedMapOf<String, String>()
        val routeFiles = mutableSetOf<String>()

        kotlinFiles().forEach { file ->
            val text = file.readText()
            val declarations = declaration.findAll(text).toList()
            if (declarations.isNotEmpty()) routeFiles += file.name
            declarations.forEachIndexed { index, match ->
                if (match.groupValues[1] != "get") return@forEachIndexed
                val path = qualify(text, match.range.first, match.groupValues[2], file)
                val handler = text.substring(
                    match.range.last + 1,
                    declarations.getOrNull(index + 1)?.range?.first ?: text.length,
                )
                val atoms = guardAtoms.filterKeys { it in handler }.values.flatten().distinct()
                val guard = if (atoms.isEmpty()) "none" else guardOrder.filter { it in atoms }.joinToString(" + ")
                reads["GET $path"] = file.name to guard
            }
            staticMount.findAll(text).forEach { match ->
                val remote = match.groupValues[1].ifEmpty { "/" }
                mounts["File(${match.groupValues[2].trim()})"] = remote to file.name
            }
            commandName.findAll(text).forEach { commands[it.groupValues[1]] = file.name }
        }
        Surface(reads, mounts, commands, routeFiles)
    }

    /**
     * Resolve a declaration's path, joining a relative one to the nearest preceding prefix.
     *
     * A prefix argument that is not a string literal is resolved through a `const val` in the
     * same file, which covers `route(PROXY_ROOT.removeSuffix("/"))`. An unresolvable prefix
     * fails rather than producing a plausible-looking wrong path: a route silently filed
     * under `?/…` would sit in the ledger looking like a real entry.
     */
    private fun qualify(source: String, declarationStart: Int, rawPath: String, file: File): String {
        if (rawPath.startsWith('/')) return rawPath
        var prefix: String? = null
        routePrefix.findAll(source.substring(0, declarationStart)).forEach { match ->
            val argument = match.groupValues[1].trim()
            literalPrefix.find(argument)?.let { prefix = it.groupValues[1]; return@forEach }
            val identifier = leadingIdentifier.find(argument)?.groupValues?.get(1) ?: return@forEach
            constDefinition.findAll(source)
                .firstOrNull { it.groupValues[1] == identifier }
                ?.let { prefix = it.groupValues[2].trimEnd('/') }
        }
        val resolved = prefix
        assertTrue(
            resolved != null,
            "In ${file.name}, the relative route '$rawPath' has no resolvable preceding route(…) " +
                "prefix — neither a string literal nor a `const val` in the same file. Extend " +
                "ServerSurfaceLedgerTest.qualify rather than letting this route into the ledger " +
                "under a made-up path.",
        )
        return resolved.trimEnd('/') + "/" + rawPath.trimStart('/')
    }

    // -------------------------------------------------------------------------------------
    // The ledger, parsed out of the document
    // -------------------------------------------------------------------------------------

    private val ledgerPage = "dev_docs/server_surface_inventory.md"

    /**
     * Every pipe-delimited row on the page, as trimmed cells with the markdown backticks off.
     *
     * Header and separator rows are dropped. Nothing else is filtered here, because *which*
     * table a row belongs to is decided by [select] on the cells rather than by position —
     * a row's identity should not depend on how many prose paragraphs sit above it.
     */
    private val ledger: List<List<String>> by lazy {
        val page = File(repositoryRoot(), ledgerPage)
        assertTrue(page.isFile, "the ledger page $ledgerPage is missing; this test has no fixture without it")
        page.readLines()
            .map { it.trim() }
            .filter { it.startsWith("|") && it.endsWith("|") }
            .map { row -> row.trim('|').split("|").map { it.trim().trim('`') } }
            .filterNot { cells -> cells.all { cell -> cell.isEmpty() || cell.all { it == '-' || it == ':' } } }
    }

    /**
     * Rows belonging to one table, chosen by the shape of their cells.
     *
     * Selecting by cell shape rather than by a regex on the first cell is not fussiness. The
     * page carries **two** tables keyed by a `.kt` filename — the per-file command counts and
     * the prose description of what each file reaches — and a first-cell regex matches both,
     * so `associate` would silently keep whichever came last and compare command counts
     * against English. Shape separates them: the count table's second cell is digits.
     *
     * @param key the cell to key the ledger by.
     * @param value the remaining cells, joined, to compare against discovery.
     */
    private fun select(
        predicate: (List<String>) -> Boolean,
        key: (List<String>) -> String,
        value: (List<String>) -> String,
    ): Map<String, String> {
        val rows = ledger.filter(predicate)
        val keyed = rows.associate { key(it) to value(it) }
        assertEquals(
            rows.size, keyed.size,
            "two rows on $ledgerPage share a key, so one of them is invisible to this comparison: " +
                rows.groupingBy { key(it) }.eachCount().filterValues { it > 1 }.keys,
        )
        return keyed
    }

    private fun readRows() = select(
        { it.size == 3 && it[0].startsWith("GET /") },
        { it[0] },
        { "${it[1]} | ${it[2]}" },
    )

    private fun mountRows() = select(
        { it.size == 3 && it[1].startsWith("File(") },
        { it[1] },
        { "${it[0]} | ${it[2]}" },
    )

    private fun commandCountRows() = select(
        { it.size == 2 && it[0].endsWith(".kt") && it[1].isNotEmpty() && it[1].all(Char::isDigit) },
        { it[0] },
        { it[1] },
    )

    private fun commandRows() = select(
        { it.size == 2 && it[1].endsWith(".kt") && !it[0].endsWith(".kt") },
        { it[0] },
        { it[1] },
    )

    // -------------------------------------------------------------------------------------
    // Premise
    // -------------------------------------------------------------------------------------

    /**
     * The floors and the landmarks: proof that discovery found a real surface.
     *
     * The floors are set below the measured counts rather than at them, so that deleting a
     * route is a one-line ledger edit rather than a two-line one. The **landmarks** are what
     * actually separate the two ways a census shrinks. `GET /health` is not going anywhere;
     * neither is `set-string`, nor the workspace mount. If those vanish, the regex broke.
     * A message that says "discovery is broken" instead of "the surface shrank" is the
     * difference between five minutes and an afternoon.
     */
    @Test
    fun theDiscoveryMechanismFindsAPlausibleSurface() {
        val reads = surface.reads
        val mounts = surface.mounts
        val commands = surface.commands

        println("--- server surface discovery ---")
        println("  HTTP read routes      ${reads.size}")
        println("  staticFiles mounts    ${mounts.size}")
        println("  /ws command names     ${commands.size} across ${commands.values.distinct().size} files")
        println("  route-declaring files ${surface.routeDeclaringFiles.sorted()}")

        // Measured 2026-08-20: 34 reads, 5 mounts, 105 commands in 12 files, 4 route files.
        assertTrue(reads.size >= 30, "only ${reads.size} HTTP read routes discovered (floor 30). $BROKEN")
        assertTrue(mounts.size >= 5, "only ${mounts.size} staticFiles mounts discovered (floor 5). $BROKEN")
        assertTrue(commands.size >= 100, "only ${commands.size} /ws commands discovered (floor 100). $BROKEN")
        assertTrue(
            commands.values.distinct().size >= 10,
            "the ${commands.size} discovered commands come from only " +
                "${commands.values.distinct().size} distinct files (floor 10). A regex that has " +
                "started matching in one file only is the shape of this failure. $BROKEN",
        )
        assertTrue(
            surface.routeDeclaringFiles.size >= 4,
            "route declarations found in only ${surface.routeDeclaringFiles.size} files " +
                "(floor 4): ${surface.routeDeclaringFiles.sorted()}. $BROKEN",
        )

        // Landmarks. Each is load-bearing for a real feature and is not plausibly deleted.
        listOf("GET /health", "GET /api/instance-info", "GET /project/{...}", "GET /field-docs/{...}")
            .forEach { assertTrue(it in reads, "landmark read route $it is missing. $BROKEN") }
        assertTrue(
            "File(home)" in mounts,
            "the workspace static mount File(home) is missing from discovery. $BROKEN",
        )
        listOf("set-string", "create-file-proxy-box", "launch-all", "initialize")
            .forEach { assertTrue(it in commands, "landmark /ws command '$it' is missing. $BROKEN") }
    }

    /**
     * This test's blind spot, asserted away rather than left implicit.
     *
     * [declaration] requires a route path to begin with `/` or `{`, and that requirement is
     * load-bearing: without it, every `JsonObjectBuilder.put("name")` in the tree enters the
     * ledger as a route. The cost is that a genuine Ktor route written with a bare relative
     * segment would be invisible.
     *
     * Measured 2026-08-20: in the four files that declare Ktor routes there is **no** verb
     * call whose quoted path lacks a leading `/` or `{`, so the hole is currently empty. This
     * keeps it empty, and the message tells the next person the cheap fix — write the path
     * with a leading slash — rather than leaving them to discover the census was lying.
     */
    @Test
    fun aRouteWithABareWordPathWouldBeInvisibleAndIsRefused() {
        val bareWord = Regex(
            "^[ \\t]*(get|post|put|delete|patch)\\(\\s*\"([^/{\"][^\"]*)\"",
            RegexOption.MULTILINE,
        )
        val offenders = kotlinFiles()
            .filter { it.name in surface.routeDeclaringFiles }
            .flatMap { file ->
                val text = file.readText()
                bareWord.findAll(text).map { match ->
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    "${file.name}:$line   ${match.groupValues[1]}(\"${match.groupValues[2]}\")"
                }.toList()
            }
        assertTrue(
            offenders.isEmpty(),
            "A route-declaring file contains a verb call whose quoted path does not begin with " +
                "'/' or '{', and ServerSurfaceLedgerTest's discovery cannot see it:\n" +
                offenders.joinToString("\n") { "  $it" } +
                "\n\nIf that is a Ktor route, write the path with a leading slash and it enters the " +
                "ledger. If it is a JsonObjectBuilder.put, move it out of a route-declaring file or " +
                "narrow this assertion deliberately — but know that the read ledger is incomplete " +
                "until you do.",
        )
        assertTrue(
            surface.routeDeclaringFiles.size >= 4,
            "this assertion is scoped to route-declaring files and there are now only " +
                "${surface.routeDeclaringFiles.size} of them; it may have become vacuous",
        )
    }

    /**
     * A mangled or emptied ledger page must fail loudly, not compare two empty sets.
     *
     * This is the other half of the vacuity problem. Flooring the *code* side stops a broken
     * regex passing; flooring the *page* side stops a gutted or reformatted document passing.
     * Without it, deleting the tables from the page would make every comparison below an
     * empty-set equality, and the suite would go green on a page that documents nothing.
     */
    @Test
    fun theLedgerPageParsesIntoNonEmptyTables() {
        println("--- $ledgerPage parsed ${ledger.size} table rows ---")
        println("  read rows ${readRows().size}, mount rows ${mountRows().size}, " +
            "command rows ${commandRows().size}, per-file count rows ${commandCountRows().size}")
        assertTrue(
            ledger.size >= 140,
            "only ${ledger.size} table rows parsed out of $ledgerPage (floor 140; measured 177 on " +
                "2026-08-20). Either the page was gutted or its tables are no longer pipe-delimited " +
                "markdown — either way every comparison in this class would be vacuous.",
        )
        assertTrue(readRows().size >= 30, "only ${readRows().size} read-route rows parsed out of $ledgerPage")
        assertTrue(mountRows().size >= 5, "only ${mountRows().size} static-mount rows parsed out of $ledgerPage")
        assertTrue(commandRows().size >= 100, "only ${commandRows().size} command rows parsed out of $ledgerPage")
        assertTrue(
            commandCountRows().size >= 10,
            "only ${commandCountRows().size} per-file count rows parsed out of $ledgerPage",
        )
    }

    // -------------------------------------------------------------------------------------
    // The three ledgers
    // -------------------------------------------------------------------------------------

    /**
     * Every HTTP read route, its file, and its guard — matched against the page both ways.
     *
     * A read route is the shape of route with the least covering it: not in the mutating
     * census, and not inspected by `installFieldApiWriteGuard`, whose interceptor runs on
     * mutating methods only. `none` in the guard column means precisely none, because there
     * is no `install(Authentication)` in the tree.
     */
    @Test
    fun everyHttpReadRouteIsInTheLedgerAndEveryLedgerRowIsAReadRoute() {
        val discovered = surface.reads.mapValues { (_, v) -> "${v.first} | ${v.second}" }
        assertLedger("HTTP read route", discovered, readRows(), "| `GET <path>` | `<file>` | <guard> |")

        val unguarded = surface.reads.count { it.value.second == "none" }
        assertTrue(
            unguarded >= 25,
            "only $unguarded of ${surface.reads.size} read routes are unguarded, down from 31 " +
                "measured 2026-08-20. That is good news and wants saying on the page — raise this " +
                "floor and say what closed them.",
        )
    }

    /**
     * Every `staticFiles` mount, matched both ways.
     *
     * Five rows that no other guard in the tree can see, one of which serves the workspace
     * directory. A sixth appearing without a ledger row is the event this exists for.
     */
    @Test
    fun everyStaticFileMountIsInTheLedgerAndEveryLedgerRowIsAMount() {
        val discovered = surface.mounts.mapValues { (_, v) -> "${v.first} | ${v.second}" }
        assertLedger("staticFiles mount", discovered, mountRows(), "| `<mount>` | `File(<dir>)` | `<file>` |")
        assertTrue(
            discovered.keys.any { it == "File(home)" },
            "the workspace mount File(home) is no longer discovered; it is the one mount on this list " +
                "that serves the human's own documents, so its disappearance is either a real change " +
                "worth a sentence on the page or a broken regex",
        )
    }

    /**
     * Every `/ws` command name and its declaring file, matched both ways.
     *
     * 105 rows. This is the surface a foreign web page reaches by opening a WebSocket, and it
     * is where box execution and the file proxy live. A new command name arriving without a
     * ledger row is exactly the event nothing in the tree noticed before.
     */
    @Test
    fun everyWebSocketCommandIsInTheLedgerAndEveryLedgerRowIsACommand() {
        assertLedger("/ws command", surface.commands, commandRows(), "| `<command>` | `<file>` |")
    }

    /**
     * The per-file command counts on the page, which is what the prose reach column hangs on.
     *
     * The page describes what each file's commands reach in prose a human checked. Prose
     * cannot be verified by a test, but the *number* it is attached to can — so if
     * `executing.kt` goes from 9 commands to 10, this fails and the sentence about box
     * execution gets re-read rather than silently covering one more command than it was
     * written for.
     */
    @Test
    fun thePerFileCommandCountsOnThePageMatchTheCode() {
        val discovered = surface.commands.values.groupingBy { it }.eachCount()
            .mapValues { it.value.toString() }.toSortedMap()
        val documented = commandCountRows()
        assertLedger("per-file command count", discovered, documented, "| `<file>` | <count> |")
        assertEquals(
            surface.commands.size,
            documented.values.sumOf { it.toInt() },
            "the per-file counts on $ledgerPage sum to something other than the ${surface.commands.size} " +
                "commands discovered",
        )
    }

    // -------------------------------------------------------------------------------------
    // Machinery
    // -------------------------------------------------------------------------------------

    /**
     * Set equality in both directions, with a message that says what to do.
     *
     * Three populations, reported separately because they call for three different actions:
     * **missing** wants a decision about a guard and then a new row, **stale** wants a row
     * deleted, and **changed** wants one cell edited. Lumping them into one `assertEquals`
     * diff is what makes a ledger failure feel like a chore rather than a question.
     */
    private fun assertLedger(
        what: String,
        discovered: Map<String, String>,
        documented: Map<String, String>,
        rowShape: String,
    ) {
        val missing = discovered.keys - documented.keys
        val stale = documented.keys - discovered.keys
        val changed = (discovered.keys intersect documented.keys)
            .filter { discovered[it] != documented[it] }
            .sorted()

        if (missing.isEmpty() && stale.isEmpty() && changed.isEmpty()) return

        val message = buildString {
            appendLine("The $what surface and $ledgerPage disagree.")
            appendLine()
            appendLine("Row shape on the page:  $rowShape")
            if (missing.isNotEmpty()) {
                appendLine()
                appendLine("${missing.size} in the CODE but not on the PAGE — add a row, and decide first")
                appendLine("whether the new surface needed a guard. Every unguarded entry is reachable by")
                appendLine("curl from any host on the LAN (the connector binds 0.0.0.0) and, for HTTP, from")
                appendLine("any website you have open (the CORS config says anyHost()):")
                missing.sorted().forEach { appendLine("  + | `$it` | ${discovered[it]} |") }
            }
            if (stale.isNotEmpty()) {
                appendLine()
                appendLine("${stale.size} on the PAGE but not in the CODE — delete the row. If you did not")
                appendLine("delete this route or command, the discovery regex has stopped matching and")
                appendLine("theDiscoveryMechanismFindsAPlausibleSurface should be red too; if it is green,")
                appendLine("that test needs a landmark for this entry:")
                stale.sorted().forEach { appendLine("  - | `$it` | ${documented[it]} |") }
            }
            if (changed.isNotEmpty()) {
                appendLine()
                appendLine("${changed.size} present on both but describing different things. Read which cell")
                appendLine("moved before editing: a guard going to `none` is a regression rather than a")
                appendLine("documentation update, and code that moved file may have changed who can reach it:")
                changed.forEach { appendLine("  ~ `$it`\n      page: ${documented[it]}\n      code: ${discovered[it]}") }
            }
            appendLine()
            appendLine("The page is the fixture on purpose: a claim about Field's exposure should not be")
            appendLine("able to outlive the code it describes. Read what changed before editing the table.")
        }
        throw AssertionError(message)
    }

    private fun repositoryRoot(): File {
        var candidate: File? = File(".").absoluteFile
        while (candidate != null && !File(candidate, "settings.gradle.kts").isFile) candidate = candidate.parentFile
        assertTrue(candidate != null, "could not locate the repository root from ${File(".").absolutePath}")
        return candidate
    }

    private companion object {
        /** Appended to every floor and landmark failure, because they share one diagnosis. */
        const val BROKEN =
            "Either the surface really shrank — in which case update dev_docs/server_surface_inventory.md " +
                "and lower this floor deliberately — or the discovery regex in ServerSurfaceLedgerTest " +
                "stopped matching, which is the failure this floor exists to catch. Check a landmark " +
                "before assuming the first."
    }
}
