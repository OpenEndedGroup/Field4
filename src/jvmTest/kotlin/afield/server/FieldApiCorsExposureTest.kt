package afield.server

import afield.FIELD_API_REQUIRE_SAME_SITE_ENV
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A guard over the shape of Field's cross-origin exposure, and an inventory of what that
 * exposure applies to.
 *
 * ## Why this test reads source rather than starting a server
 *
 * It no longer *has* to — `ktor-server-test-host` is a declared `jvmTest` dependency and
 * [DocsAnnotationRouteSecurityTest] drives the real routes through the real CORS
 * configuration. This file kept the source-reading approach because it is asking a
 * different question. Behaviour tests ask *what does this request do*; these ask *what did
 * somebody decide, and is it still what they decided*. `anyHost()` cannot be tested away:
 * it is load-bearing for a feature, so what a test can usefully do is make changing it
 * deliberate. And the route inventory below is a census of source, because a route nobody
 * remembered to install in a test application is exactly the route it needs to catch.
 *
 * ## What was measured, on 2026-08-11, against a real Field on port 9492
 *
 * Not asserted here — a source-reading test cannot see it — but this is the evidence the
 * assertions below stand in for, from a real Chromium page served at `http://127.0.0.1:9493`,
 * a genuinely foreign origin:
 *
 *  - `GET /api/instance-info` and `GET /api/welcome-changelog` were **read** by that page.
 *    The first returns the absolute path of the checkout; the second returns the human's
 *    recent commit messages.
 *  - `POST /api/shutdown-instance` from that page **stopped the server**. Listener PID
 *    before: 4508. After: none.
 *  - With `anyHost()` removed and nothing else changed, every one of those probes failed
 *    with `TypeError: Failed to fetch`, the server replied `403 Forbidden` with the handler
 *    never running, and same-origin requests were unaffected.
 *
 * ## And the part that measurement did not reach
 *
 * That session concluded that `POST /api/docs-improvement` needed a credential and gave it
 * one. It did not follow the same sentence one screen up the same file: `POST` and `DELETE`
 * `/api/docs-annotations` were reachable from that page too, because their only guard was
 * a loopback check and the attacking page is in a browser that is on loopback. As of
 * 2026-08-12 those routes refuse a cross-site request themselves; see
 * [DocsAnnotationRouteSecurityTest], which watched each guard fail before trusting it.
 *
 * See `dev_docs/DOCS_IMPROVEMENT_AGENT.md` for the write-up and for what is still open.
 */
class FieldApiCorsExposureTest {

    private val serverSource: String by lazy {
        val file = File(repositoryRoot(), "src/jvmMain/kotlin/afield/Server.kt")
        assertTrue(file.isFile, "expected to find Server.kt at ${file.absolutePath}")
        file.readText()
    }

    /**
     * The CORS block, pinned literally.
     *
     * Any edit to it fails this test, which is the entire point: every line of it decides
     * what an arbitrary website can do to a running Field, and none of that is visible
     * from the five lines themselves.
     */
    @Test
    fun theCorsConfigurationIsExactlyWhatWeThinkItIs() {
        val block = Regex("""install\(CORS\)\s*\{([^}]*)\}""").find(serverSource)?.groupValues?.get(1)
        assertTrue(block != null, "Server.kt no longer has an install(CORS) block — read this test's KDoc before proceeding")
        val directives = block.lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "allowHeader(HttpHeaders.ContentType)",
                "allowMethod(HttpMethod.Get)",
                "allowMethod(HttpMethod.Post)",
                "allowMethod(HttpMethod.Delete)",
                "anyHost()",
            ),
            directives,
            "The CORS configuration changed. That is allowed — but it decides what any website you visit " +
                "can do to a running Field, so update this list deliberately and re-read " +
                "dev_docs/DOCS_IMPROVEMENT_AGENT.md. Measured 2026-08-11: with anyHost() present a foreign " +
                "origin could read /api/instance-info and stop the server via POST /api/shutdown-instance; " +
                "with it absent the server returned 403 and survived.",
        )
    }

    /**
     * The invariant the documentation-improvement token actually rests on.
     *
     * Loopback-only does not stop a cross-site POST, because the human's own browser is on
     * loopback. What stops it is that `X-Field-Docs-Improvement-Token` is not a
     * CORS-safelisted header and is not in `allowHeader`, so the preflight fails — 403,
     * observed, and `TypeError: Failed to fetch` in the page.
     *
     * `anyHeader()`, or an `allowHeader` for this header, would silently convert the
     * agent-spawning endpoint back into something a web page can reach.
     */
    @Test
    fun theImprovementTokenHeaderIsNotReachableFromAWebPage() {
        val block = Regex("""install\(CORS\)\s*\{([^}]*)\}""").find(serverSource)?.groupValues?.get(1).orEmpty()
        assertFalse(
            "anyHeader()" in block,
            "anyHeader() in the CORS config would let any website send $DOCS_IMPROVEMENT_TOKEN_HEADER, " +
                "which is one of the two things standing between a web page and `codex exec --sandbox " +
                "workspace-write`. The other is the route's own same-site check, added 2026-08-12.",
        )
        assertFalse(
            DOCS_IMPROVEMENT_TOKEN_HEADER.lowercase() in block.lowercase(),
            "$DOCS_IMPROVEMENT_TOKEN_HEADER must never be CORS-allowed; the preflight failing is one layer " +
                "of the CSRF defence.",
        )
    }

    /**
     * `PUT` is absent from `allowMethod`, and this test says so — but not for the reason
     * the comment that used to sit here gave.
     *
     * It read: *"PUT is likewise absent, which is why `PUT /api/docs-annotations/{id}/resolve`
     * is not cross-origin reachable. Recorded rather than relied upon — the loopback check
     * is that route's actual guard."* Both halves of that were wrong, and in a way that
     * mattered, because the same file's other KDoc states the correct rule two paragraphs
     * earlier:
     *
     *  - the loopback check was **not** a guard against a cross-site request at all, for
     *    exactly the reason `theImprovementTokenHeaderIsNotReachableFromAWebPage` gives —
     *    the attacking page is in a browser, and the browser is on loopback. `POST` and
     *    `DELETE` on the same routes were cross-site writable the whole time this comment
     *    claimed otherwise;
     *  - so `PUT`'s absence from `allowMethod` was not a footnote to a real defence, it was
     *    the *only* thing making that one verb unreachable — a line of configuration in
     *    another file, one word away from being deleted by someone adding an unrelated
     *    `PUT` endpoint, with a comment here inviting them to.
     *
     * Since 2026-08-12 `PUT …/resolve` has a guard of its own and this assertion is what it
     * always should have been: a note that widening `allowMethod` widens the surface, and
     * nothing more. [DocsAnnotationRouteSecurityTest] covers the `PUT` in the form that
     * arrives when CORS is not there to pre-empt it.
     */
    @Test
    fun addingPutToTheCorsConfigWouldWidenTheSurfaceAndIsNotWhatGuardsResolve() {
        val block = Regex("""install\(CORS\)\s*\{([^}]*)\}""").find(serverSource)?.groupValues?.get(1).orEmpty()
        assertFalse(
            "allowMethod(HttpMethod.Put)" in block,
            "Adding PUT is allowed, but do it knowing what it changes: every mutating PUT in the inventory " +
                "below becomes reachable from any website you have open. Only the docs-annotation routes " +
                "refuse for themselves.",
        )
        val annotations = File(repositoryRoot(), "src/jvmMain/kotlin/afield/server/docsAnnotations.kt").readText()
        assertTrue(
            "refuseUnlessSameSiteDocsClient" in annotations,
            "the same-site guard is gone from docsAnnotations.kt, so PUT's absence from allowMethod is " +
                "load-bearing again and this test's KDoc is out of date",
        )
    }

    /**
     * Every state-changing route in the tree, and what guards it. The honest version of a
     * number in a worklog.
     *
     * A count decays the first time somebody adds a route; a *table* decays the first time
     * somebody adds an **unguarded** one, which is the event worth being told about. So
     * this fails on a new route, on a removed route, and — the case a count could never
     * see — on a route that quietly loses its guard.
     *
     * ## What it is measuring, and what it cannot
     *
     * Two literals per route, read out of the source: the declaration, and whether one of
     * the three known guard calls appears in the handler that follows it. The guard names
     * are the vocabulary of `docsAnnotations.kt`; there is no other access control in the
     * tree, because there is no `install(Authentication)` in it either. So `"none"` here
     * means precisely `"none"`.
     *
     * It reads **three** declaration shapes, and until 2026-08-22 it read only the first:
     *
     *  - `post("/api/…") { … }` — a verb call with a quoted path. 40 of these;
     *  - `route("/welcome") { handle { … } }` — a route that names **no verb**, and
     *    therefore answers every one of them. Thirteen of these, all in `Server.kt`, and
     *    every one of them was missing from this table while the table's headline number
     *    was published as the honest answer to "is Field's API authenticated". They are
     *    filed here as `ANY <path>`, and
     *    [aVerblessRouteReallyAnswersEveryMethodWhichIsWhyItIsInTheTable] measures that the
     *    verb really is every verb rather than taking Ktor's word for it;
     *  - `route("/history-screenshot") { get { … } }` — a verb with no path argument. Two
     *    of these, both reads, so they belong to `ServerSurfaceLedgerTest` rather than here;
     *    this census still has to *see* them, because the handler window is "up to the next
     *    declaration" and a declaration it cannot see is not a boundary.
     *
     * Its limits are real, and one of them was a wrong answer rather than a missing one.
     * Path resolution now asks [KotlinRouteScan] *which `route(…)` blocks a declaration is
     * lexically inside of*, over sanitised source, rather than "what is the nearest
     * preceding `route(…)`". The old form returned early on any path starting with `/`, so
     * `post("/stream")` inside `route("/vision")` was filed as `POST /stream` — a path Field
     * does not serve. It is still not a Kotlin parser: it attributes a guard by textual
     * containment between one declaration and the next, so a guard mentioned in a comment
     * would count. A tripwire on growth and on regression, not a proof.
     *
     * ## What the table says, and it is the honest answer to "is Field's API authenticated"
     *
     * **49 of 53 state-changing routes have no route-specific guard of any kind** — 36 of 40
     * before the verbless shape was counted — including `POST /api/shutdown-instance`, which
     * a foreign origin was measured stopping the server with, `POST /api/agent-worktrees/delete`,
     * and now `ANY /lazy-value-fragment`, which takes four query parameters straight into
     * `ValueVisualizer.renderLazyFragment`. The HTTPS connector binds `0.0.0.0` —
     * deliberately, so the visionOS shell and other devices can reach Field — and Ktor's CORS
     * plugin does not apply to a request with no `Origin` header at all. So every one of these
     * is reachable with `curl` from any host on the same network, today, and this branch did
     * not change that. Narrowing the bind address would break a real workflow overnight; it is
     * the human's call, not a nightly agent's. What is in scope for a test is refusing to let
     * the number grow quietly — and refusing to let thirteen routes stay outside the count.
     */
    @Test
    fun everyMutatingRouteAndItsGuardIsPinned() {
        val declaration = Regex(
            """^[ \t]*(get|post|put|delete|patch)\(\s*"((?:/|\{)[^"]*)""",
            RegexOption.MULTILINE,
        )
        // The guard vocabulary of docsAnnotations.kt, decomposed into the questions each
        // one actually asks, so that "loopback" means the same thing in every row.
        val guardAtoms = mapOf(
            "refuseUnlessSameSiteDocsClient" to listOf("loopback", "same-site"),
            "refuseUnlessLocalDocsClient" to listOf("loopback"),
            "refuseDocsImprovementLaunch" to listOf("loopback", "enabled+token"),
        )
        val order = listOf("loopback", "same-site", "enabled+token")

        val found = sortedMapOf<String, String>()
        var verbless = 0
        KotlinRouteScan.serverKotlinFiles(repositoryRoot()).forEach { file ->
            val text = file.readText()
            val scan = KotlinRouteScan(text)
            // The three shapes merged in source order. Order matters for more than tidiness:
            // the handler window runs to the *next* declaration, so a shape this census
            // cannot see is a boundary it cannot honour, and one route's guard leaks into
            // the row above it.
            val declarations = buildList {
                declaration.findAll(text).forEach {
                    add(Declaration(it.range, it.groupValues[1], it.groupValues[2]))
                }
                scan.allMethodHandlers().forEach { add(Declaration(it.range, ALL_METHODS, null)) }
                scan.bodylessVerbs().forEach { add(Declaration(it.range, it.groupValues[1], null)) }
            }.sortedBy { it.range.first }

            declarations.forEachIndexed { index, declared ->
                if (declared.verb == "get") return@forEachIndexed
                if (declared.verb == ALL_METHODS) verbless++
                val path = routePath(scan, file, declared)
                val handler = text.substring(
                    declared.range.last + 1,
                    declarations.getOrNull(index + 1)?.range?.first ?: text.length,
                )
                val atoms = guardAtoms.filterKeys { it in handler }.values.flatten().distinct()
                found["${declared.verb.uppercase()} $path"] =
                    if (atoms.isEmpty()) "none" else order.filter { it in atoms }.joinToString(" + ")
            }
        }

        assertEquals(
            EXPECTED_MUTATING_SURFACE, found,
            "The state-changing HTTP surface, or what guards it, changed.\n\n" +
                "If you added a route: decide whether it needs protecting before adding the row. Every " +
                "\"none\" is reachable by curl from any host on the LAN (the connector binds 0.0.0.0) and " +
                "from any website you have open (the CORS config says anyHost()).\n\n" +
                "An `ANY` row is a `route(\"…\") { handle { … } }`, which answers every HTTP method — see " +
                "aVerblessRouteReallyAnswersEveryMethodWhichIsWhyItIsInTheTable. If you meant it to serve " +
                "a page to a browser, write `get { … }` and it leaves this table for the read ledger.\n\n" +
                "If a row went from a guard to \"none\": that is a regression, and " +
                "DocsAnnotationRouteSecurityTest should be red too. If it is not, that test needs a case.\n\n" +
                "Current:\n" + found.entries.joinToString("\n") { "  ${it.key} -> ${it.value}" },
        )

        // Pinned as a number as well, so the shape of the problem survives a careless
        // wholesale update of the map above.
        val unguarded = found.count { it.value == "none" }
        assertEquals(
            49, unguarded,
            "The complete census has 49 state-changing routes with no route-specific access control, out " +
                "of ${found.size}. If this went down, say so in dev_docs/DOCS_IMPROVEMENT_AGENT.md — it is " +
                "the headline number in that page. If it went up, you added an unauthenticated write.",
        )
        // A floor on the verbless shape specifically. Without it, a regression in
        // KotlinRouteScan.allMethodHandlers would present as thirteen `stale` rows above,
        // which reads like thirteen deletions rather than like a broken census.
        assertTrue(
            verbless >= 13,
            "only $verbless verbless `route(…) { handle { … } }` declarations were discovered (floor 13, " +
                "measured 2026-08-22, all in Server.kt). Either they were rewritten with explicit verbs — " +
                "which is an improvement and wants this floor lowered deliberately — or " +
                "KotlinRouteScan.allMethodHandlers has stopped matching, which is the failure this floor " +
                "exists to catch.",
        )
        listOf(
            "POST /api/shutdown-instance",
            "POST /api/agent-worktrees/delete",
            "POST /api/projects/delete",
            // The verbless route with the largest reach: four query parameters into
            // ValueVisualizer.renderLazyFragment, on any method, from any origin.
            "ANY /lazy-value-fragment",
            // The row the old nearest-preceding-prefix resolution got wrong: this was
            // published as `POST /stream`, which Field does not serve.
            "POST /vision/stream",
        ).forEach { assertEquals("none", found[it], "$it is expected to still be unguarded, and to be named") }
    }

    /**
     * The premise the thirteen `ANY` rows above rest on, measured rather than assumed.
     *
     * `route("/welcome") { handle { … } }` installs a handler with no `HttpMethodRouteSelector`,
     * so in principle it answers every method. "In principle" is how a census becomes a
     * tautology: if a future Ktor made `handle` a `GET`-only convenience, those thirteen rows
     * would silently become wrong in the *dangerous* direction — a table claiming write
     * exposure that does not exist, next to real exposure it would then be trusted about.
     *
     * So this stands a `route { handle { } }` and a `route { get { } }` up in a real
     * application and asks both the same four questions. Measured against Ktor
     * **3.5.2** (`build.gradle.kts:61`) on 2026-08-22: the verbless route answered `GET`,
     * `POST`, `PUT` and `DELETE` with the method name in the body; the named one answered
     * `GET` and returned `405 Method Not Allowed` to the other three.
     */
    @Test
    fun aVerblessRouteReallyAnswersEveryMethodWhichIsWhyItIsInTheTable() = testApplication {
        application {
            routing {
                route("/verbless") { handle { call.respondText("verbless ${call.request.httpMethod.value}") } }
                route("/named") { get { call.respondText("named GET") } }
            }
        }

        assertEquals("verbless GET", client.get("/verbless").bodyAsText())
        assertEquals("verbless POST", client.post("/verbless").bodyAsText())
        assertEquals("verbless PUT", client.put("/verbless").bodyAsText())
        assertEquals("verbless DELETE", client.delete("/verbless").bodyAsText())

        // The counterfactual, and the reason the two bodyless `get { }` routes in the tree
        // stay out of the mutating table: a verb, even without a path, still restricts.
        assertEquals("named GET", client.get("/named").bodyAsText())
        listOf(
            "POST" to client.post("/named"),
            "PUT" to client.put("/named"),
            "DELETE" to client.delete("/named"),
        ).forEach { (method, response) ->
            assertEquals(
                HttpStatusCode.MethodNotAllowed, response.status,
                "a `get { }` answered $method. If Ktor's routing changed this, the read/write split in " +
                    "both server-surface censuses needs re-deriving, not patching.",
            )
        }
    }

    /**
     * WebSockets are their own exposure surface: CORS does not govern an upgrade, and the HTTP
     * interceptor deliberately stands aside for one. The two command handlers therefore apply the
     * shared origin policy inside their upgrades; the notification-only channel stays open.
     *
     * **Re-measured 2026-08-20.** The interceptor used to stand aside by skipping `GET`/`HEAD`
     * wholesale, and this test pinned that literal as its proof. That blanket skip *was* the read
     * gap — a foreign origin could read the checkout path out of `GET /api/instance-info` with
     * `AFIELD_API_REQUIRE_SAME_SITE` fully on — so reads are now guarded and the exemption is
     * narrower and explicit: `Sec-Fetch-Mode: websocket`. The invariant this test cares about is
     * unchanged (an upgrade must reach `refuseCrossSiteFieldWebSocket`, which can close with
     * `1008` and a readable reason, rather than being answered with a bare 403 by the
     * interceptor); only the mechanism that delivers it moved, so the assertion below names the
     * new mechanism. `FieldApiReadGuardTest.aWebSocketUpgradeIsLeftToItsOwnGuard` asserts the same
     * invariant behaviourally, at route level, which is the stronger half of the pair.
     */
    @Test
    fun everyWebSocketRouteAndItsWriteRoleIsPinned() {
        val declaration = Regex("""^[ \t]*webSocket\(\s*"([^"]*)"""", RegexOption.MULTILINE)
        val routeBoundary = Regex(
            """^[ \t]*(?:webSocket|route|get|post|put|delete|patch)\(""",
            RegexOption.MULTILINE,
        )
        val found = sortedMapOf<String, String>()

        File(repositoryRoot(), "src/jvmMain/kotlin/afield").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                declaration.findAll(text).forEach { match ->
                    val path = qualifyWebSocketPath(file, match.groupValues[1])
                    val end = routeBoundary.findAll(text)
                        .firstOrNull { it.range.first > match.range.first }
                        ?.range?.first ?: text.length
                    val handler = text.substring(match.range.last + 1, end)
                    val role = if (
                        "handleControllerInput(" in handler ||
                        "rawHandlers.values" in handler ||
                        "handle(message)" in handler
                    ) "commands" else "server notifications"
                    val guard = if ("refuseCrossSiteFieldWebSocket(" in handler) "same-site" else "none"
                    found[path] = "$role; $guard"
                }
            }

        assertEquals(
            EXPECTED_WEBSOCKET_SURFACE,
            found,
            "The WebSocket surface changed. Command channels must apply the same browser-origin policy " +
                "as $FIELD_API_REQUIRE_SAME_SITE_ENV inside their upgrade.",
        )
        assertTrue(
            Regex(""""navigate",\s*"websocket"\s*->\s*return false""").containsMatchIn(serverSource),
            "The HTTP same-site guard no longer visibly stands aside for a WebSocket upgrade, so an upgrade " +
                "may now be answered with a bare 403 before refuseCrossSiteFieldWebSocket can close it with " +
                "1008 and a reason. Re-measure the table instead of preserving this assertion mechanically — " +
                "and see FieldApiReadGuardTest.aWebSocketUpgradeIsLeftToItsOwnGuard, which should be red too. " +
                "If it is green while this is red, this assertion has drifted from the behaviour and should " +
                "be rewritten rather than patched.",
        )
    }

    /**
     * One discovered declaration: where it is, what verb it names, and the path it wrote.
     *
     * [rawPath] is `null` for the two shapes that write no path — `handle { }` and a bodyless
     * `get { }` — and for those the path *is* the enclosing `route(…)` prefix.
     */
    private class Declaration(val range: IntRange, val verb: String, val rawPath: String?)

    private fun routePath(scan: KotlinRouteScan, file: File, declared: Declaration): String {
        val resolved = if (declared.rawPath == null) {
            scan.enclosingRoutePath(declared.range.first)
        } else {
            scan.qualify(declared.range.first, declared.rawPath)
        }
        assertTrue(
            resolved != null,
            "In ${file.name}:${scan.lineOf(declared.range.first)}, the ${declared.verb} declaration " +
                "'${declared.rawPath ?: "(no path)"}' has no resolvable path: either it is relative with no " +
                "enclosing route(…), or an enclosing route(…) argument is neither a string literal nor a " +
                "`const val` in the same file. Extend KotlinRouteScan rather than letting a route into this " +
                "census under a made-up path — which is exactly how POST /stream got published.",
        )
        return resolved
    }

    private fun qualifyWebSocketPath(file: File, rawPath: String): String =
        if (file.name == "controllers.kt" && rawPath == "/ws") "/controllers/ws" else rawPath

    private fun repositoryRoot(): File {
        var candidate: File? = File(".").absoluteFile
        while (candidate != null && !File(candidate, "settings.gradle.kts").isFile) candidate = candidate.parentFile
        assertTrue(candidate != null, "could not locate the repository root from ${File(".").absolutePath}")
        return candidate
    }

    private companion object {
        /**
         * The verb column for a `route(…) { handle { … } }`, which names no verb and answers
         * all of them. Not an HTTP method name: it is a statement that every method applies.
         */
        const val ALL_METHODS = "ANY"

        /** Four route-specific guards; the application-wide switch is off by default. */
        val EXPECTED_MUTATING_SURFACE = sortedMapOf(
            // Thirteen verbless routes, every one of them a page-serving `handle { }` that
            // also accepts POST, PUT and DELETE. `/lazy-value-fragment` is the one with real
            // reach — four query parameters into ValueVisualizer.renderLazyFragment.
            "ANY /browse" to "none",
            "ANY /code-use" to "none",
            "ANY /dir" to "none",
            "ANY /h2-explorer" to "none",
            "ANY /history" to "none",
            "ANY /inspect" to "none",
            "ANY /keyframe" to "none",
            "ANY /lazy-value-fragment" to "none",
            "ANY /logs" to "none",
            "ANY /threejs" to "none",
            "ANY /tree" to "none",
            "ANY /vision" to "none",
            "ANY /welcome" to "none",
            "DELETE /api/docs-annotations/{id}" to "loopback + same-site",
            "DELETE /api/maven-dependencies" to "none",
            "DELETE /api/maven-repositories" to "none",
            "POST /api/agent-worktrees/delete" to "none",
            "POST /api/agent-worktrees/merge" to "none",
            "POST /api/agent-worktrees/restart" to "none",
            "POST /api/ai-settings" to "none",
            "POST /api/ai-tool-use-improvement" to "none",
            "POST /api/ai-tool-use-improvement/clear-reviewed" to "none",
            "POST /api/code-use/analyze" to "none",
            "POST /api/completion-doc-polish" to "none",
            "POST /api/docs-annotations" to "loopback + same-site",
            "POST /api/docs-improvement" to "loopback + same-site + enabled+token",
            "POST /api/duplicate-document" to "none",
            // The one route whose openness is a feature: the offline viewer POSTs here from
            // whatever origin its bundle was served from, which is why anyHost() stands.
            "POST /api/import-offline-doc" to "none",
            "POST /api/maven-dependencies" to "none",
            "POST /api/maven-repositories" to "none",
            "POST /api/maven-resolve" to "none",
            "POST /api/maven-upgrade" to "none",
            "POST /api/osc-editor-learn" to "none",
            "POST /api/preferences" to "none",
            "POST /api/projects" to "none",
            "POST /api/projects/add-document" to "none",
            "POST /api/projects/delete" to "none",
            "POST /api/python-install-jep" to "none",
            "POST /api/python-settings" to "none",
            "POST /api/reveal-document" to "none",
            "POST /api/semantic-search/cache/delete" to "none",
            "POST /api/semantic-search/download" to "none",
            "POST /api/semantic-search/query" to "none",
            "POST /api/semantic-search/rebuild" to "none",
            "POST /api/semantic-search/settings" to "none",
            "POST /api/shutdown-instance" to "none",
            "POST /api/starting-points/open" to "none",
            "POST /api/workspace-switch" to "none",
            "POST /field-audio/{...}" to "none",
            // Nested inside `route("/vision")`, and published as `POST /stream` until
            // 2026-08-22 because path resolution returned early on a leading slash.
            "POST /vision/stream" to "none",
            "PUT /api/docs-annotations/{id}/resolve" to "loopback + same-site",
            "PUT /api/document-tags" to "none",
            "PUT /field-audio/{...}" to "none",
        )

        val EXPECTED_WEBSOCKET_SURFACE = sortedMapOf(
            "/controllers/ws" to "commands; same-site",
            "/welcome-events" to "server notifications; none",
            "/ws" to "commands; same-site",
        )
    }
}
