package afield.server

import afield.installFieldApiCors
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The access-control tests that start an actual Ktor application.
 *
 * ## Why this file exists
 *
 * [DocsAnnotationSecurityTest] tests the guard *functions*. That is worth doing and it is
 * not enough, and the gap is exactly measurable: delete the guard call at the head of
 * `post("/api/docs-annotations")` and every one of those nineteen tests still passes. The
 * guards were covered; their *installation* was not, and neither was anything about the
 * request that reaches them — which for a cross-site attack is the entire subject.
 *
 * So this file stands the real routes up in a `testApplication`, installs Field's real
 * cross-origin configuration via [installFieldApiCors], and asks the questions as HTTP:
 * a request with these headers, from this initiator — what status comes back?
 *
 * ## Red-first, and what was actually observed
 *
 * A security test nobody has watched fail guards nothing. Seven reverts, each applied on
 * its own with everything else restored, run 2026-08-12 against
 * `DocsAnnotationRouteSecurityTest` + `DocsAnnotationSecurityTest` +
 * `FieldApiCorsExposureTest` — **33 tests**, of which this file is 13. Control, with
 * nothing reverted: **0 of 33**.
 *
 * The one that is the point of the file first:
 *
 *  - **the guard *call* deleted from `post("/api/docs-annotations")`**, leaving the guard
 *    function itself intact. The nineteen pre-existing security tests: **0 of 19 failed.**
 *    This file: **6 of 12 failed** (measured before the matrix test below existed). That is
 *    the gap, and it is why testing a predicate is not testing a route.
 *
 * Then each guard in turn:
 *
 *  - **the whole site guard short-circuited to "allowed"** — the posture `main` ships,
 *    where loopback is the only check: **7 of 33** — `aCrossSiteWriteIsRefused…`,
 *    `aSameSiteBrowserBadgeIsNotEnough…`, `aCrossSiteReadOfTheImprovementStatusIsRefused`,
 *    `anOriginThatDisagreesWithHost…`, `originNullIsNotAnAuthority…`, `theSecFetchSiteMatrix`,
 *    `theWholeApiSurfaceRefusesACrossSitePost`.
 *  - **the `Sec-Fetch-Site` arm removed**, leaving only `Origin`/`Host`: **2 of 33** —
 *    `theSecFetchSiteMatrix`, and `aCrossSiteWriteIsRefused…` via its `PUT`, which is the
 *    case that arrives with no `Origin` to compare.
 *  - **the `Origin`/`Host` arm removed**, leaving only `Sec-Fetch-Site`: **3 of 33** —
 *    `theSecFetchSiteMatrix`, `anOriginThatDisagreesWithHost…`, `originNullIsNotAnAuthority…`.
 *    The two arms failing *different* tests is the evidence that neither is decoration.
 *  - **the loopback check short-circuited to "allowed"**: **3 of 33** —
 *    `aRequestFromOffTheMachineIsRefusedBeforeAnythingElse`, `theMirrorIsStillMachineGuarded`
 *    and, from the older file, `annotationsAreLoopbackOnlyByDefaultBecauseTheyArePromptText`.
 *  - **`allowMethod(HttpMethod.Delete)` removed** from [installFieldApiCors]: **3 of 33**.
 *  - **`anyHost()` removed**: **10 of 33**. Worth knowing which way that one points — most
 *    of those are tests in *this* file that stop being able to reach the route, because
 *    Ktor's CORS plugin refuses the request before the handler runs. The tests here
 *    therefore depend on the exposure they were written about still existing. If
 *    `anyHost()` ever goes, this file needs rereading, not repairing.
 *
 * If you change a guard, do that again rather than trusting the green — and read what
 * [docsApp] says about the annotation store before you do.
 *
 * ## What the test engine hands us, which is not what a browser hands us
 *
 * Ktor's test engine reports `request.local.remoteAddress` as the string `"localhost"` —
 * a *name*, and [isLoopbackDocsClient] refuses names on purpose, so under the shipped
 * default every route here would 403 on the machine check and nothing else would ever be
 * exercised. It also sends no `Host` header unless one is asked for.
 *
 * Both are handled honestly rather than worked around: [remoteAllowed] opens the machine
 * gate through the policy seam so that the *site* gate is what the request meets, and
 * every request that is standing in for a browser sets `Host` explicitly, because a
 * browser always does.
 */
class DocsAnnotationRouteSecurityTest {

    // ══════════════════════════════════════════════════════════════════════════
    // The hole: a cross-site write
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The finding, as three requests.
     *
     * On `main` all three of these are `200`. Annotation text is what
     * `POST /api/docs-improvement` turns into a prompt, so a page that can write a note can
     * write the instructions a human later presses a button to run — and the token that
     * guards the button does nothing about the text it is handed.
     */
    @Test
    fun aCrossSiteWriteIsRefusedOnEveryMutatingAnnotationRoute() = docsApp {
        assertRefusedAsCrossSite(post("/api/docs-annotations") { fromEvilPage(); jsonBody(UPSERT) })
        assertRefusedAsCrossSite(delete("/api/docs-annotations/x") { fromEvilPage() })
        // `PUT` with an `Origin` never reaches the route at all — the CORS plugin answers
        // it first with an empty 403, because `Put` is absent from `allowMethod`. That is
        // asserted on its own in [theCorsPreflightForACrossSiteWriteCurrentlySucceeds]. To
        // ask whether the *route* would refuse it, the question has to be put in a form
        // the plugin does not intercept: no `Origin`, and the browser's own account of who
        // started this. This is the world one `allowMethod(HttpMethod.Put)` away.
        assertRefusedAsCrossSite(
            put("/api/docs-annotations/x/resolve") {
                header(HttpHeaders.Host, FIELD_HOST)
                header(SEC_FETCH_SITE_HEADER, "cross-site")
                jsonBody(RESOLVE)
            },
        )
    }

    /**
     * `same-site` is not `same-origin`, and the difference is the whole measured attack.
     *
     * A page served from `http://localhost:9493` is a different origin from
     * `https://localhost:9092` — different scheme, different port — but browsers call the
     * pair *same-site*, because "site" is about the registrable domain. Last night's branch
     * drove a real Chromium page from `http://127.0.0.1:9493` and stopped the server with
     * it. A guard that allowed `same-site` would allow that page.
     */
    @Test
    fun aSameSiteBrowserBadgeIsNotEnoughToPassAsCrossSite() = docsApp {
        assertRefusedAsCrossSite(
            post("/api/docs-annotations") {
                header(HttpHeaders.Host, FIELD_HOST)
                header(HttpHeaders.Origin, "http://localhost:9493")
                header(SEC_FETCH_SITE_HEADER, "same-site")
                jsonBody(UPSERT)
            },
        )
    }

    /** `GET /api/docs-improvement` reports `workingDirectory` and up to 120 KB of the agent's log. */
    @Test
    fun aCrossSiteReadOfTheImprovementStatusIsRefused() = docsApp {
        val response = get("/api/docs-improvement") { fromEvilPage() }
        assertRefusedAsCrossSite(response)
        assertTrue("workingDirectory" !in response.bodyAsText(), "the refusal must not carry the payload")
    }

    /**
     * The fallback path, for a client old enough not to send `Sec-Fetch-Site`.
     *
     * `Origin` and `Host` are both set by the browser out of the same URL, so they agree
     * for a same-origin request and disagree for a cross-origin one. Nothing here consults
     * the connection point: on a server reached as `localhost`, as `127.0.0.1` and at a LAN
     * address, an origin and a socket are not comparable things.
     */
    @Test
    fun anOriginThatDisagreesWithHostIsRefusedWithoutSecFetchSite() = docsApp {
        assertRefusedAsCrossSite(
            post("/api/docs-annotations") {
                header(HttpHeaders.Host, FIELD_HOST)
                header(HttpHeaders.Origin, "https://somewhere-else.example")
                jsonBody(UPSERT)
            },
        )
        // The near miss that a substring check would wave through.
        assertRefusedAsCrossSite(
            post("/api/docs-annotations") {
                header(HttpHeaders.Host, FIELD_HOST)
                header(HttpHeaders.Origin, "https://localhost:9092.evil.example")
                jsonBody(UPSERT)
            },
        )
        // Same host, different port: a different origin, and the one that was measured.
        assertRefusedAsCrossSite(
            post("/api/docs-annotations") {
                header(HttpHeaders.Host, FIELD_HOST)
                header(HttpHeaders.Origin, "https://localhost:9493")
                jsonBody(UPSERT)
            },
        )
    }

    /**
     * A DNS-rebinding page keeps its own origin and `Host` equal while changing only the
     * address that name resolves to. Equality therefore proves same-origin, not that the
     * request named Field. `Sec-Fetch-Site` says `same-origin` in this attack too, so the
     * host allow-list has to run before either same-origin signal is trusted.
     */
    @Test
    fun aRebindingHostnameCannotNameFieldEvenWhenTheBrowserCallsItSameOrigin() = docsApp {
        assertRefusedAsCrossSite(
            post("/api/docs-annotations") {
                header(HttpHeaders.Host, "field.attacker.example")
                header(HttpHeaders.Origin, "http://field.attacker.example")
                header(SEC_FETCH_SITE_HEADER, "same-origin")
                jsonBody(UPSERT)
            },
        )
    }

    /** Literal LAN and loopback addresses remain valid Field authorities, including ports. */
    @Test
    fun literalIpAuthoritiesStillWorkForFieldsOwnPage() = docsApp {
        // IPv6 is covered directly in DocsAnnotationSecurityTest: Ktor's test engine parses
        // `[::1]:9092` as a broken decimal port before Field's route can see the request.
        for (authority in listOf("127.0.0.1:9092", "192.168.2.125:9092")) {
            val response = post("/api/docs-annotations") {
                header(HttpHeaders.Host, authority)
                header(HttpHeaders.Origin, "http://$authority")
                header(SEC_FETCH_SITE_HEADER, "same-origin")
                jsonBody(UPSERT)
            }
            assertEquals(HttpStatusCode.OK, response.status, "Field's own page at $authority: ${response.bodyAsText()}")
        }
    }

    /**
     * The whole `Sec-Fetch-Site` vocabulary, and the two ways of not saying anything.
     *
     * Written as a table because the interesting cases are the ones nobody thinks to try.
     * The `Origin` is held foreign throughout, so any row that passes is passing on the
     * header alone — which is what makes `same-site` and `Sec-Fetch-Site: SAME-ORIGIN`
     * worth their rows, and what makes the unrecognised values worth theirs. A value we do
     * not understand is not permission.
     */
    @Test
    fun theSecFetchSiteMatrix() = docsApp { secFetchSiteMatrix() }

    /** Extracted only so the whole matrix runs inside one temp store; the allowed rows really do write. */
    private suspend fun HttpClient.secFetchSiteMatrix() {
        val allowed = listOf("same-origin", "none", "  same-origin  ", "SAME-ORIGIN", "None")
        val refused = listOf("cross-site", "same-site", "CROSS-SITE", "Same-Site", "maybe", "same origin", "1")
        for (value in allowed) {
            assertEquals(
                HttpStatusCode.OK,
                post("/api/docs-annotations") {
                    header(HttpHeaders.Host, FIELD_HOST)
                    header(HttpHeaders.Origin, EVIL_ORIGIN)
                    header(SEC_FETCH_SITE_HEADER, value)
                    jsonBody(UPSERT)
                }.status,
                "Sec-Fetch-Site: '$value' should be trusted even against a foreign Origin — it is a forbidden " +
                    "header name, so a page cannot have written it",
            )
        }
        for (value in refused) {
            assertRefusedAsCrossSite(
                post("/api/docs-annotations") {
                    header(HttpHeaders.Host, FIELD_HOST)
                    header(HttpHeaders.Origin, EVIL_ORIGIN)
                    header(SEC_FETCH_SITE_HEADER, value)
                    jsonBody(UPSERT)
                },
                "for Sec-Fetch-Site: '$value' —",
            )
        }
        // Blank is "the header is not really there", and falls through to Origin/Host,
        // which here disagree. Absent entirely does the same.
        for (builder in listOf<HttpRequestBuilder.() -> Unit>(
            { header(SEC_FETCH_SITE_HEADER, "  ") },
            { },
        )) {
            assertRefusedAsCrossSite(
                post("/api/docs-annotations") {
                    header(HttpHeaders.Host, FIELD_HOST)
                    header(HttpHeaders.Origin, EVIL_ORIGIN)
                    builder()
                    jsonBody(UPSERT)
                },
            )
        }
    }

    /** A sandboxed iframe sends the literal string `null`, which is not an authority and cannot match. */
    @Test
    fun originNullIsNotAnAuthorityThatCanMatch() = docsApp {
        assertRefusedAsCrossSite(
            post("/api/docs-annotations") {
                header(HttpHeaders.Host, FIELD_HOST)
                header(HttpHeaders.Origin, "null")
                jsonBody(UPSERT)
            },
        )
    }

    /** Every `/api/docs-…` route, so a route added later without the guard is caught by name. */
    @Test
    fun theWholeApiSurfaceRefusesACrossSitePost() = docsApp {
        for (path in listOf("/api/docs-annotations", "/api/docs-improvement")) {
            assertRefusedAsCrossSite(post(path) { fromEvilPage(); jsonBody("{}") }, "for $path")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The client that has to keep working
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The panel `docsAnnotations.js` injects into the mirrored docs, driven for real.
     *
     * It calls `fetch(new URL(url, window.location.origin), …)` with
     * `Content-Type: application/json`, from a page Field itself served at
     * `/field-docs/…`. So its requests are same-origin: the browser sets
     * `Sec-Fetch-Site: same-origin` and an `Origin` matching `Host`. This test sends
     * exactly that, over the same four verbs the panel uses, and checks the full loop —
     * create, list, resolve, delete — lands in the store.
     *
     * That is how I know the hardening did not break the local client, and it is checked
     * here rather than asserted in a worklog because a worklog cannot go red.
     */
    @Test
    fun theLocalPanelStillCompletesItsWholeLoop() = docsApp {
        val created = post("/api/docs-annotations") { fromThePanel(); jsonBody(UPSERT) }
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(created.bodyAsText())?.groupValues?.get(1)
        assertTrue(id != null, "the created note must come back with an id: ${created.bodyAsText()}")

        val listed = get("/api/docs-annotations") { fromThePanel() }
        assertEquals(HttpStatusCode.OK, listed.status)
        assertTrue(id in listed.bodyAsText(), "the note must be listed back to the panel")

        val resolved = put("/api/docs-annotations/$id/resolve") { fromThePanel(); jsonBody(RESOLVE) }
        assertEquals(HttpStatusCode.OK, resolved.status, resolved.bodyAsText())
        assertTrue("Resolved by user" in resolved.bodyAsText())

        assertEquals(HttpStatusCode.OK, delete("/api/docs-annotations/$id") { fromThePanel() }.status)
        assertEquals(HttpStatusCode.OK, get("/api/docs-improvement") { fromThePanel() }.status)

        // And it went to the temp store rather than the human's real notebook.
        assertTrue(Files.exists(storeInUse), "the panel's writes must have landed in $storeInUse")
    }

    /**
     * `Sec-Fetch-Site: none` — a human typing the URL, or a bookmark — is allowed.
     *
     * There is no initiating page, so there is no ambient authority being borrowed, so it
     * is not the thing this guard is for. Refusing it would break `curl` and the address
     * bar for no gain.
     */
    @Test
    fun aRequestWithNoInitiatorIsNotACrossSiteRequest() = docsApp {
        assertEquals(
            HttpStatusCode.OK,
            get("/api/docs-annotations") {
                header(HttpHeaders.Host, FIELD_HOST)
                header(SEC_FETCH_SITE_HEADER, "none")
            }.status,
        )
        // No Origin and no Sec-Fetch-Site at all: curl, a script, the visionOS shell. The
        // loopback check is the boundary for those, not this one.
        assertEquals(HttpStatusCode.OK, get("/api/docs-annotations") { header(HttpHeaders.Host, FIELD_HOST) }.status)
    }

    /** The hatch, since a guard with an escape hatch nobody has tried is a guard with a story about a hatch. */
    @Test
    fun theCrossSiteHatchOpensExactlyTheAnnotationApi() {
        docsApp(remoteAllowed.copy(allowCrossSiteAnnotations = true)) {
            assertEquals(
                HttpStatusCode.OK,
                post("/api/docs-annotations") { fromEvilPage(); jsonBody(UPSERT) }.status,
            )
        }
        docsApp(remoteAllowed.copy(allowCrossSiteAnnotations = true, improvementEnabled = true)) {
            // …and never the spawn. `refuseDocsImprovementLaunch` does not consult either
            // hatch, so the machine check answers first and the reply is the one a caller
            // off the machine gets — enabled or not, hatch open or not.
            val response = post("/api/docs-improvement") { fromThePanel(); jsonBody("{}") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue("can only be launched from this machine" in response.bodyAsText(), response.bodyAsText())
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The machine gate, now proved to be installed rather than merely to exist
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * With the shipped default policy the test engine's peer — the name `"localhost"` —
     * is not a literal loopback address, so this stands in for a host on the LAN.
     */
    @Test
    fun aRequestFromOffTheMachineIsRefusedBeforeAnythingElse() = docsApp(DocsAccessPolicy()) {
        for (response in listOf(
            get("/api/docs-annotations") { fromThePanel() },
            post("/api/docs-annotations") { fromThePanel(); jsonBody(UPSERT) },
            put("/api/docs-annotations/x/resolve") { fromThePanel(); jsonBody(RESOLVE) },
            delete("/api/docs-annotations/x") { fromThePanel() },
            get("/api/docs-improvement") { fromThePanel() },
            post("/api/docs-improvement") { fromThePanel(); jsonBody("{}") },
        )) {
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(
                DOCS_ANNOTATIONS_ALLOW_REMOTE_ENV in response.bodyAsText() ||
                    "can only be launched from this machine" in response.bodyAsText(),
                "the machine refusal must be the one that answered: ${response.bodyAsText()}",
            )
        }
    }

    /** The mirror is deliberately machine-guarded and *not* site-guarded; it is reached by navigation. */
    @Test
    fun theMirrorIsStillMachineGuarded() = docsApp(DocsAccessPolicy()) {
        val response = get("/field-docs/index.html") { fromThePanel() }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(DOCS_ANNOTATIONS_ALLOW_REMOTE_ENV in response.bodyAsText())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The configuration that made the hole reachable, asserted as behaviour
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The preflight, which is the step everyone assumes stops this and which does not.
     *
     * A cross-site `fetch` with `Content-Type: application/json` is not a simple request,
     * so the browser sends `OPTIONS` first. Field's CORS config allows the `Content-Type`
     * header, allows `POST` and `DELETE`, and allows any origin — so the preflight is
     * *approved*, and the real request follows. This is asserted as an ongoing fact, not
     * fixed: `POST /api/import-offline-doc` is a real cross-origin consumer and narrowing
     * the plugin would break it. The route refuses for itself instead, which is the
     * previous section.
     *
     * `PUT` is the exception and it is an accident: it is simply absent from `allowMethod`,
     * so its preflight fails and `PUT …/resolve` was never cross-site reachable. That is
     * configuration, not a defence, and it is one line away from not being true — which is
     * why the route now has its own guard and this test says so out loud.
     */
    @Test
    fun theCorsPreflightForACrossSiteWriteCurrentlySucceeds() = docsApp {
        for (method in listOf("POST", "DELETE")) {
            val preflight = options("/api/docs-annotations") {
                header(HttpHeaders.Host, FIELD_HOST)
                header(HttpHeaders.Origin, EVIL_ORIGIN)
                header(HttpHeaders.AccessControlRequestMethod, method)
                header(HttpHeaders.AccessControlRequestHeaders, "content-type")
            }
            assertEquals(
                HttpStatusCode.OK, preflight.status,
                "The CORS preflight for a cross-site $method is expected to SUCCEED — that is the " +
                    "exposure, and it is why the annotation routes need a guard of their own rather " +
                    "than relying on the browser. If this went red, the CORS config changed; read " +
                    "installFieldApiCors and dev_docs/DOCS_IMPROVEMENT_AGENT.md before updating it.",
            )
            assertEquals("*", preflight.headers[HttpHeaders.AccessControlAllowOrigin], "for $method")
        }
        val put = options("/api/docs-annotations/x/resolve") {
            header(HttpHeaders.Host, FIELD_HOST)
            header(HttpHeaders.Origin, EVIL_ORIGIN)
            header(HttpHeaders.AccessControlRequestMethod, "PUT")
        }
        assertEquals(
            HttpStatusCode.Forbidden, put.status,
            "PUT is absent from allowMethod, so its preflight fails. Recorded, not relied upon.",
        )
    }

    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Stands the real routes and the real CORS configuration up, and hands [block] the
     * client to drive them with.
     *
     * The default policy is [remoteAllowed] rather than the shipped one for the reason in
     * this class's KDoc: the test engine's peer is the *name* `"localhost"`, which the
     * machine guard correctly refuses, so leaving it shut would mean every test here proved
     * only that the machine guard works. Opening it is what puts the *site* guard on the
     * path. The tests that want the machine guard pass `DocsAccessPolicy()` explicitly.
     *
     * **Every app runs against a temporary store, unconditionally**, and that is not
     * belt-and-braces — it is a bug fix. Most of the tests here assert a *refusal*, so on
     * the face of it they never reach the store at all. But the whole method of this file is
     * to revert a guard and watch them fail, and a reverted guard is precisely the state in
     * which those POSTs succeed. Running the six experiments in this file's KDoc wrote
     * **twelve** notes into the human's real `~/.afield/docs-annotations.json` before this
     * was noticed. (Noticing was easy, which is the one good part: `recordedInCheckout`,
     * added the night before for exactly this, named the worktree on every one of them.)
     * A test that is safe *while it passes* is not a safe test.
     */
    private fun docsApp(
        policy: DocsAccessPolicy = remoteAllowed,
        block: suspend HttpClient.() -> Unit,
    ) = withTempStore { store ->
        storeInUse = store
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installFieldApiCors()
                routing { installDocsAnnotationRoutes(policy) }
            }
            client.block()
        }
    }

    /** Loopback opened, everything else as shipped: the posture that isolates the site guard. */
    private val remoteAllowed = DocsAccessPolicy(allowRemoteAnnotations = true)

    /** The temporary file the currently-running [docsApp] is writing to. */
    private lateinit var storeInUse: Path

    /** Headers a browser attaches to a `fetch` from a page on another website. */
    private fun HttpRequestBuilder.fromEvilPage() {
        header(HttpHeaders.Host, FIELD_HOST)
        header(HttpHeaders.Origin, EVIL_ORIGIN)
        header(SEC_FETCH_SITE_HEADER, "cross-site")
    }

    /** Headers a browser attaches to `docsAnnotations.js` calling its own server. */
    private fun HttpRequestBuilder.fromThePanel() {
        header(HttpHeaders.Host, FIELD_HOST)
        header(HttpHeaders.Origin, "http://$FIELD_HOST")
        header(SEC_FETCH_SITE_HEADER, "same-origin")
    }

    private fun HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun assertRefusedAsCrossSite(response: HttpResponse, note: String = "") {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Forbidden, response.status, "$note $body")
        assertTrue(
            "same-site only" in body,
            "$note the *site* guard must be the one that refused, not some other 403 — an empty body here " +
                "means Ktor's CORS plugin answered first and the route never ran: [$body]",
        )
    }

    /** Keeps a route-level POST out of the human's real `~/.afield/docs-annotations.json`. */
    private fun withTempStore(block: (Path) -> Unit) {
        val dir = createTempDirectory("docs-annotations-route-test-")
        val store = dir.resolve("docs-annotations.json")
        try {
            withDocsAnnotationStoreAt(store) { block(store) }
        } finally {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private companion object {
        /**
         * What the browser would have in its address bar, and therefore in `Host`.
         *
         * `localhost` with no port, because that is what Ktor's test engine *is*: its
         * connection point is `http://localhost:80`, and Ktor's CORS plugin decides
         * same-origin against the connection point rather than against `Host`. Give the
         * panel a `Host` the engine does not agree with and the plugin refuses its `PUT`
         * before the route ever runs — a test artefact, not a fact about Field, and one
         * that would otherwise be mistaken for the guard working.
         */
        const val FIELD_HOST = "localhost"
        const val EVIL_ORIGIN = "https://somewhere-else.example"
        const val UPSERT =
            """{"annotation":{"pageUrl":"https://openendedgroup.com/field4/index.html","note":"a note @docs"}}"""
        const val RESOLVE = """{"resolved":true,"resolution":"Resolved by user"}"""
    }
}
