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
 * duplicate either. It covers the five surfaces nothing covered at all:
 *
 *  - **HTTP read routes.** 36 of them. The mutating census skips them outright — its loop
 *    body opens `if (declared.verb == "get") return@forEachIndexed`. A read route is
 *    also skipped by `installFieldApiWriteGuard`, whose interceptor only inspects mutating
 *    methods. So a `GET` is the one shape of route that no guard and no test looked at,
 *    while `GET /api/instance-info` returns the absolute path of the checkout and
 *    `GET /api/welcome-changelog` returns the human's recent commit messages. Both were read
 *    by a foreign origin in the 2026-08-11 measurement.
 *  - **All-method routes.** 13 of them, added 2026-08-22, and the reason this class's own
 *    "how this refuses to pass vacuously" section below was not enough. A
 *    `route("/welcome") { handle { … } }` names no verb and answers every one of them, and it
 *    matched neither census's declaration regex, so thirteen routes that accept `POST`, `PUT`
 *    and `DELETE` from any origin were in no table anywhere. Demonstrated rather than argued:
 *    an unguarded verbless route planted in `Server.kt` left the whole suite green on
 *    `d7d8dccc2`.
 *  - **`staticFiles` directory mounts.** Six, all in `Server.kt`, and **invisible to every
 *    route regex in the tree**, because a `staticFiles` call is not a verb declaration. One
 *    of them serves the workspace directory at `/`.
 *  - **The `/ws` command names.** 105 of them, in a single dispatch table declared at
 *    `Messages.kt:18`. This is the finding that reorders the whole threat model: the file
 *    proxy, the H2 explorer, box execution and the agent backdoor declare **no HTTP routes
 *    at all**. They are commands on this table. So the largest and most privileged surface
 *    Field has sits behind the door that got its first guard last — 2026-08-19, `3aa64af4d`
 *    — and that guard is off by default.
 *  - **The `/ws` raw channels.** 9 of them, in a *second* dispatch table, `rawHandlers`, whose
 *    callbacks receive the whole frame text. Added 2026-08-22 and worth reading the near-miss
 *    on: the `handlers[…]` regex was unanchored and *looks* as though it would have swept
 *    these up by accident. It did not, because `rawHandlers` capitalises its H. The two
 *    tables were disjoint by accident of spelling, and nobody had counted the second.
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
 * And the honest addendum, because all three of those were in place on 2026-08-20 and none of
 * them caught the verbless routes: **a floor is not a completeness proof.** Floors and
 * landmarks detect a census that *shrank*. They cannot detect a category the census never had
 * a regex for, because that category was never in the count the floor was set below. What
 * found these was a second, independently written census that disagreed — which is the only
 * mechanism here that can find a *missing* category rather than a lost one.
 *
 * ## What it is not
 *
 * Not a Kotlin parser, and the limits [FieldApiCorsExposureTest] documents apply here too:
 * literal paths only, and guard attribution by textual containment, so a guard named in a
 * comment would count. A tripwire on growth and on regression, not a proof.
 *
 * One limit is however *gone* rather than restated. Path resolution used to join a relative
 * path to the *nearest preceding* `route(…)` in the file and return early on anything starting
 * with `/`. That was not a limitation, it was a wrong answer:
 * `route("/controllers") { get("/controls") }` is served at `/controllers/controls` and was
 * filed as `/controls` — by this discovery *and* by the page, so the assertion asserted their
 * agreement rather than the truth. [KotlinRouteScan] answers the real question, "which
 * `route(…)` blocks is this declaration lexically inside of", over sanitised source.
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
     * A `const val NAME = "value"`, which is how three of the nine `rawHandlers` channels and
     * one `route(…)` prefix are keyed.
     *
     * The `route(…)` prefix parsing that used to sit here has moved to [KotlinRouteScan],
     * because the question it was answering was the wrong one: "what `route(…)` came before me
     * in this file" is not "which `route(…)` blocks am I inside of", and the difference was
     * three wrong paths.
     */
    private val constDefinition = Regex("""const val ([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]*)"""")
    private val stringLiteral = Regex("""^"([^"]*)"$""")
    private val leadingIdentifier = Regex("""^([A-Za-z_][A-Za-z0-9_]*)""")

    /** A `staticFiles(remotePath = …, dir = File(…))` mount. Not a verb, so nothing else sees it. */
    private val staticMount = Regex(
        """^[ \t]*staticFiles\(\s*remotePath\s*=\s*"([^"]*)"\s*,\s*dir\s*=\s*File\(([^)]*)\)""",
        RegexOption.MULTILINE,
    )

    /**
     * An entry in the `/ws` command dispatch table declared at `Messages.kt:18`.
     *
     * Anchored on a non-word boundary since 2026-08-22. Unanchored it would also match the
     * tail of some other `…handlers[` identifier, and the one that exists in this tree —
     * `rawHandlers[` — is a *different* dispatch table whose entries belong in
     * [rawChannelName]'s ledger, not this one. Worth stating what the fix did and did not do:
     * the census is **105 either way**, because `rawHandlers` capitalises its H and
     * `handlers\[` therefore never matched it. The two tables were disjoint by accident of
     * spelling; this makes them disjoint on purpose.
     */
    private val commandName = Regex("""(?<![A-Za-z0-9_])handlers\[\s*"([A-Za-z0-9_.:-]+)"\s*]""")

    /**
     * An entry in the **other** `/ws` dispatch table: `rawHandlers`, in `Messages.kt`.
     *
     * `handlers` is keyed by a decoded message `address`; `rawHandlers` is keyed by a channel
     * name and receives the **whole frame text**, which `Server.kt:4483` fans out to every
     * registered channel. Nine of them, and until 2026-08-22 neither census nor either page
     * had a single row for any of them — the H2 explorer, the file browser, the log stream,
     * the spreadsheet, the three.js and Metal viewers and the RemoteJS bridge all arrive this
     * way. Three are keyed by a `const val` rather than a literal, which is why this captures
     * the argument expression and resolves it the same way a `route(…)` prefix is resolved.
     */
    private val rawChannelName = Regex(
        """(?<![A-Za-z0-9_])rawHandlers\s*(?:\[\s*([^]\n]+?)\s*]|\.getOrPut\(\s*([^),\n]+?)\s*\))""",
    )

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
         * `ANY /welcome` -> (`Server.kt`, `none`) — a `route(…) { handle { … } }`.
         *
         * Its own category rather than thirteen more read rows, because it is neither only a
         * read nor only a write: it answers **every** method, which
         * `FieldApiCorsExposureTest.aVerblessRouteReallyAnswersEveryMethodWhichIsWhyItIsInTheTable`
         * measures rather than assumes. The write half of these thirteen belongs to that
         * file's mutating table; the *existence* of the category belongs on the ledger page,
         * because a reader counting Field's doors needs them counted once, somewhere.
         */
        val allMethod: Map<String, Pair<String, String>>,
        /**
         * `File(home)` -> (`/`, `Server.kt`).
         *
         * Keyed by the **directory expression**, not by the mount point: four of the five
         * mounts share `remotePath = ""`, so the mount point is not a key.
         */
        val mounts: Map<String, Pair<String, String>>,
        /** `set-string` -> `Server.kt`. */
        val commands: Map<String, String>,
        /** `rawHandlers["__logs__"]` -> `logstream.kt` — the second `/ws` dispatch table. */
        val rawChannels: Map<String, String>,
        val routeDeclaringFiles: Set<String>,
    )

    private fun kotlinFiles(): List<File> = KotlinRouteScan.serverKotlinFiles(repositoryRoot())

    private val surface: Surface by lazy {
        val reads = sortedMapOf<String, Pair<String, String>>()
        val allMethod = sortedMapOf<String, Pair<String, String>>()
        val mounts = sortedMapOf<String, Pair<String, String>>()
        val commands = sortedMapOf<String, String>()
        val rawChannels = sortedMapOf<String, String>()
        val routeFiles = mutableSetOf<String>()

        kotlinFiles().forEach { file ->
            val text = file.readText()
            val scan = KotlinRouteScan(text)
            val constants = constDefinition.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

            // Three declaration shapes, merged in source order. The two that write no path —
            // `handle { }` and a bodyless `get { }` — were invisible to this census until
            // 2026-08-22, and their invisibility was not only a missing row: the handler
            // window runs to the *next* declaration, so a shape the census cannot see is a
            // boundary it cannot honour, and one route's guard leaks into the row above it.
            val declarations = buildList {
                declaration.findAll(text).forEach {
                    add(Declared(it.range, it.groupValues[1], it.groupValues[2]))
                }
                scan.allMethodHandlers().forEach { add(Declared(it.range, ALL_METHODS, null)) }
                scan.bodylessVerbs().forEach { add(Declared(it.range, it.groupValues[1], null)) }
            }.sortedBy { it.range.first }
            if (declarations.isNotEmpty()) routeFiles += file.name

            declarations.forEachIndexed { index, declared ->
                if (declared.verb != "get" && declared.verb != ALL_METHODS) return@forEachIndexed
                val path = qualify(scan, declared, file)
                val handler = text.substring(
                    declared.range.last + 1,
                    declarations.getOrNull(index + 1)?.range?.first ?: text.length,
                )
                val atoms = guardAtoms.filterKeys { it in handler }.values.flatten().distinct()
                val guard = if (atoms.isEmpty()) "none" else guardOrder.filter { it in atoms }.joinToString(" + ")
                val target = if (declared.verb == ALL_METHODS) allMethod else reads
                target["${declared.verb.uppercase()} $path"] = file.name to guard
            }

            staticMount.findAll(text).forEach { match ->
                val remote = match.groupValues[1].ifEmpty { "/" }
                mounts["File(${match.groupValues[2].trim()})"] = remote to file.name
            }
            commandName.findAll(text).forEach { commands[it.groupValues[1]] = file.name }
            rawChannelName.findAll(text).forEach { match ->
                val argument = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
                val channel = stringLiteral.find(argument)?.groupValues?.get(1)
                    ?: leadingIdentifier.find(argument)?.groupValues?.get(1)?.let { constants[it] }
                assertTrue(
                    channel != null,
                    "In ${file.name}:${scan.lineOf(match.range.first)}, the rawHandlers channel key " +
                        "'$argument' is neither a string literal nor a `const val` in the same file. Extend " +
                        "this resolution rather than letting a /ws channel into the ledger under a made-up " +
                        "name — an unnamed channel is exactly the door this table exists to count.",
                )
                rawChannels["""rawHandlers["$channel"]"""] = file.name
            }
        }
        Surface(reads, allMethod, mounts, commands, rawChannels, routeFiles)
    }

    /** One discovered declaration. [rawPath] is null for the two shapes that write no path. */
    private class Declared(val range: IntRange, val verb: String, val rawPath: String?)

    /**
     * Resolve a declaration's path from the `route(…)` blocks it is lexically **inside of**.
     *
     * This used to open with `if (rawPath.startsWith('/')) return rawPath`, and that one line
     * was the bug. `route("/controllers") { get("/controls") }` is served at
     * `/controllers/controls`; it was filed as `/controls` by this discovery *and* written as
     * `/controls` on the ledger page, so the assertion asserted the agreement of the two
     * rather than the truth of either. Three declarations in the tree were wrong that way.
     *
     * A prefix argument that is not a string literal is resolved through a `const val` in the
     * same file, which covers `route(PROXY_ROOT.removeSuffix("/"))`. An unresolvable prefix
     * fails rather than producing a plausible-looking wrong path: a route silently filed
     * under `?/…` would sit in the ledger looking like a real entry.
     */
    private fun qualify(scan: KotlinRouteScan, declared: Declared, file: File): String {
        val resolved = if (declared.rawPath == null) {
            scan.enclosingRoutePath(declared.range.first)
        } else {
            scan.qualify(declared.range.first, declared.rawPath)
        }
        assertTrue(
            resolved != null,
            "In ${file.name}:${scan.lineOf(declared.range.first)}, the ${declared.verb} declaration " +
                "'${declared.rawPath ?: "(no path — it takes its enclosing route's)"}' has no resolvable " +
                "path: either it is relative with no enclosing route(…), or an enclosing route(…) argument " +
                "is neither a string literal nor a `const val` in the same file. Extend KotlinRouteScan " +
                "rather than letting this route into the ledger under a made-up path.",
        )
        return resolved
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

    /** Same row shape as [readRows], separated by the verb column, which for these is `ANY`. */
    private fun allMethodRows() = select(
        { it.size == 3 && it[0].startsWith("ANY /") },
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

    /**
     * The `handlers` command rows — and the reason the exclusion below is explicit.
     *
     * The `rawHandlers` channel table has the same shape: a name and a `.kt` file. Rather than
     * separate them by guessing at the shape of a channel name (`__logs__` looks distinctive;
     * `remote` does not), the page writes a raw channel's key as `rawHandlers["…"]`, so each
     * row says which table it belongs to. Two tables telling this comparison apart by luck is
     * how the two censuses came to be disjoint by accident of spelling in the first place.
     */
    private fun commandRows() = select(
        { it.size == 2 && it[1].endsWith(".kt") && !it[0].endsWith(".kt") && !it[0].startsWith(RAW_PREFIX) },
        { it[0] },
        { it[1] },
    )

    private fun rawChannelRows() = select(
        { it.size == 2 && it[1].endsWith(".kt") && it[0].startsWith(RAW_PREFIX) },
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
        val allMethod = surface.allMethod
        val mounts = surface.mounts
        val commands = surface.commands
        val rawChannels = surface.rawChannels

        println("--- server surface discovery ---")
        println("  HTTP read routes      ${reads.size}")
        println("  all-method routes     ${allMethod.size}")
        println("  staticFiles mounts    ${mounts.size}")
        println("  /ws command names     ${commands.size} across ${commands.values.distinct().size} files")
        println("  /ws raw channels      ${rawChannels.size} across ${rawChannels.values.distinct().size} files")
        println("  route-declaring files ${surface.routeDeclaringFiles.sorted()}")

        // Measured 2026-08-22: 36 reads, 13 all-method routes, 5 mounts, 105 commands in 12
        // files, 9 raw channels in 9 files, 4 route files. Reads were 34 before the two
        // bodyless `get { }` declarations entered the census.
        assertTrue(reads.size >= 34, "only ${reads.size} HTTP read routes discovered (floor 34). $BROKEN")
        assertTrue(
            allMethod.size >= 12,
            "only ${allMethod.size} all-method `route(…) { handle { … } }` routes discovered (floor 12). " +
                "These are the routes that answer every HTTP method without naming one, and being " +
                "invisible is precisely what was wrong with them until 2026-08-22. $BROKEN",
        )
        assertTrue(mounts.size >= 5, "only ${mounts.size} staticFiles mounts discovered (floor 5). $BROKEN")
        assertTrue(commands.size >= 100, "only ${commands.size} /ws commands discovered (floor 100). $BROKEN")
        assertTrue(
            rawChannels.size >= 8,
            "only ${rawChannels.size} rawHandlers channels discovered (floor 8). This is the second /ws " +
                "dispatch table — the H2 explorer, the file browser, the log stream, the spreadsheet and " +
                "the viewers all arrive through it. $BROKEN",
        )
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
        listOf(
            "GET /health",
            "GET /api/instance-info",
            "GET /project/{...}",
            "GET /field-docs/{...}",
            // Nested, and filed as `GET /controls` until 2026-08-22. Its presence here is the
            // landmark for path resolution asking the right question.
            "GET /controllers/controls",
            // A verb with no path argument, invisible to the old declaration regex entirely.
            "GET /controllers",
        ).forEach { assertTrue(it in reads, "landmark read route $it is missing. $BROKEN") }
        listOf("ANY /welcome", "ANY /lazy-value-fragment", "ANY /h2-explorer")
            .forEach { assertTrue(it in allMethod, "landmark all-method route $it is missing. $BROKEN") }
        assertTrue(
            "File(home)" in mounts,
            "the workspace static mount File(home) is missing from discovery. $BROKEN",
        )
        listOf("set-string", "create-file-proxy-box", "launch-all", "initialize")
            .forEach { assertTrue(it in commands, "landmark /ws command '$it' is missing. $BROKEN") }
        listOf("""rawHandlers["__logs__"]""", """rawHandlers["__h2_explorer__"]""")
            .forEach { assertTrue(it in rawChannels, "landmark /ws raw channel $it is missing. $BROKEN") }
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
     *
     * The *other* blind spot this KDoc used to imply was the whole one is now closed rather
     * than asserted away: a route with **no path at all**, `route("/x") { get { … } }`, and a
     * route with no *verb* at all, `route("/x") { handle { … } }`, are both discovered as of
     * 2026-08-22. This assertion covers what is left: a path that is a bare word.
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
        println("  read rows ${readRows().size}, all-method rows ${allMethodRows().size}, " +
            "mount rows ${mountRows().size}, command rows ${commandRows().size}, " +
            "raw-channel rows ${rawChannelRows().size}, per-file count rows ${commandCountRows().size}")
        assertTrue(
            ledger.size >= 170,
            "only ${ledger.size} table rows parsed out of $ledgerPage (floor 170; measured 183 before " +
                "this branch and 216 after it). Either the page was gutted or its tables are no " +
                "longer pipe-delimited markdown — either way every comparison in this class would be " +
                "vacuous.",
        )
        assertTrue(readRows().size >= 34, "only ${readRows().size} read-route rows parsed out of $ledgerPage")
        assertTrue(
            allMethodRows().size >= 12,
            "only ${allMethodRows().size} all-method route rows parsed out of $ledgerPage",
        )
        assertTrue(mountRows().size >= 5, "only ${mountRows().size} static-mount rows parsed out of $ledgerPage")
        assertTrue(commandRows().size >= 100, "only ${commandRows().size} command rows parsed out of $ledgerPage")
        assertTrue(
            rawChannelRows().size >= 8,
            "only ${rawChannelRows().size} rawHandlers channel rows parsed out of $ledgerPage",
        )
        assertTrue(
            commandCountRows().size >= 10,
            "only ${commandCountRows().size} per-file count rows parsed out of $ledgerPage",
        )
    }

    // -------------------------------------------------------------------------------------
    // The five ledgers
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
            unguarded >= 30,
            "only $unguarded of ${surface.reads.size} read routes are unguarded, down from 33 " +
                "measured 2026-08-22 (31 of 34 on 2026-08-20, before the two bodyless `get { }` routes " +
                "entered the census). That is good news and wants saying on the page — raise this " +
                "floor and say what closed them.",
        )
    }

    /**
     * Every all-method route — a `route(…) { handle { … } }` — matched both ways.
     *
     * Thirteen rows that were on neither page and in neither census until 2026-08-22, and the
     * reason they are the higher-severity half of that finding: they are not merely
     * undocumented reads, they accept `POST`, `PUT` and `DELETE` from any origin, which is the
     * verb class the mutating census exists to count. `ANY /lazy-value-fragment` takes four
     * query parameters straight into `ValueVisualizer.renderLazyFragment`.
     *
     * The write half of these thirteen is pinned by
     * `FieldApiCorsExposureTest.everyMutatingRouteAndItsGuardIsPinned`, which is where the
     * unguarded-write headline lives. This table is here so that a reader counting Field's
     * doors finds them counted, with the file and the guard, in the inventory.
     */
    @Test
    fun everyAllMethodRouteIsInTheLedgerAndEveryLedgerRowIsAnAllMethodRoute() {
        val discovered = surface.allMethod.mapValues { (_, v) -> "${v.first} | ${v.second}" }
        assertLedger("all-method route", discovered, allMethodRows(), "| `ANY <path>` | `<file>` | <guard> |")
        assertTrue(
            "ANY /lazy-value-fragment" in discovered,
            "ANY /lazy-value-fragment is no longer discovered. It is the verbless route with the most " +
                "reach — four query parameters into ValueVisualizer.renderLazyFragment on any method — so " +
                "either it grew an explicit verb, which is an improvement worth a sentence on the page, or " +
                "KotlinRouteScan.allMethodHandlers stopped matching. $BROKEN",
        )
    }

    /**
     * Every `staticFiles` mount, matched both ways.
     *
     * Six rows that no other guard in the tree can see, one of which serves the workspace
     * directory. A seventh appearing without a ledger row is the event this exists for — and
     * on `2026-09-13` that happened for the first time: a `/sample-assets` mount for the
     * shared sample GLB was added to `Server.kt` and this test was the only thing that
     * noticed, which is the whole argument for counting a surface nothing else can see.
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
     * Every `rawHandlers` channel and its declaring file, matched both ways.
     *
     * The second `/ws` dispatch table, and until 2026-08-22 the only one of Field's five
     * server surfaces with **no** row anywhere. `handlers` is keyed by a decoded message
     * address; `rawHandlers` is keyed by a channel name and its callbacks receive the whole
     * frame text, fanned out by `Server.kt:4483`. Nine channels: the H2 explorer, the file
     * browser, the inspector, the log stream, the keyframe editor, the spreadsheet, the
     * three.js and Metal viewers, and the RemoteJS bridge.
     *
     * Worth being precise about how this was missed, because the near-miss is instructive: the
     * `handlers[…]` regex was unanchored, which *looks* like it would have swept these up by
     * accident. It did not, because `rawHandlers` capitalises its H. The two censuses were
     * disjoint by accident of spelling rather than by design, and nobody had counted the
     * second one.
     */
    @Test
    fun everyRawWebSocketChannelIsInTheLedgerAndEveryLedgerRowIsAChannel() {
        assertLedger("/ws raw channel", surface.rawChannels, rawChannelRows(), "| `rawHandlers[\"<name>\"]` | `<file>` |")
        assertEquals(
            emptySet(), surface.commands.keys intersect surface.rawChannels.keys,
            "a name appears in both /ws dispatch tables, so one of the two ledgers is describing the " +
                "other's entry. Keep the raw table keyed as rawHandlers[\"…\"] so the two stay distinguishable " +
                "on the page as well as in the code.",
        )
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
        /**
         * The verb column for a `route(…) { handle { … } }`, which names no verb and answers
         * all of them. Spelled identically in [FieldApiCorsExposureTest], because the two
         * tables are read side by side.
         */
        const val ALL_METHODS = "ANY"

        /** How a `rawHandlers` channel is written on the page, so its rows announce their table. */
        const val RAW_PREFIX = "rawHandlers["

        /** Appended to every floor and landmark failure, because they share one diagnosis. */
        const val BROKEN =
            "Either the surface really shrank — in which case update dev_docs/server_surface_inventory.md " +
                "and lower this floor deliberately — or the discovery regex in ServerSurfaceLedgerTest " +
                "stopped matching, which is the failure this floor exists to catch. Check a landmark " +
                "before assuming the first."
    }
}
