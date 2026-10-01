package afield.server

import afield.FIELD_API_REQUIRE_SAME_SITE_ENV
import afield.FIELD_API_REQUIRE_SAME_SITE_PROPERTY
import afield.SEC_FETCH_MODE_HEADER
import afield.installFieldApiCors
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The read half of the same-site guard — the gap that `AFIELD_API_REQUIRE_SAME_SITE` did not
 * close until 2026-08-20.
 *
 * ## The finding this file exists for
 *
 * `nightly/2026-08-20/3-server-surface-threat-model` measured Field's live behaviour with the
 * switch fully on and found writes refused exactly as documented — and
 * `GET /api/instance-info` from `Origin: https://evil.example` answering **200 with a body**,
 * the body being the absolute path of the checkout. `installFieldApiSameSiteGuard` skipped
 * `GET`/`HEAD` by construction, and `installFieldApiCors`' `anyHost()` + `allowMethod(Get)`
 * makes the response *readable* cross-origin, so the page got the answer and not merely the
 * status. 31 of 34 read routes had no guard of any kind.
 *
 * A read is not a lesser exposure than a write. It is a different one, and the reason it went
 * missing is structural rather than careless: every census in this area counts *mutating*
 * routes, so "unguarded" was the default for reads and nothing anywhere recorded the decision
 * that a particular read did not need guarding.
 *
 * ## What is deliberately still allowed, and why each is a feature and not a hole
 *
 * Three shapes pass, and each has a test below whose job is to go red if the guard eats it —
 * which is the failure a security switch is likeliest to have:
 *
 *  - a **top-level navigation**, because its response lands in a Field-origin document that the
 *    initiating page cannot read, and refusing would break every link into Field;
 *  - a **WebSocket upgrade**, because `refuseCrossSiteFieldWebSocket` applies this same policy at
 *    the upgrade, where it can close with a readable reason instead of a bare 403;
 *  - the **self-guarding docs routes**, because `DOCS_ANNOTATIONS_ALLOW_CROSS_SITE` is a
 *    deliberate opt-in hatch that *permits* cross-site annotation reads, and a blanket refusal
 *    here would close it while its own switch still claimed to hold it open.
 *
 * ## Red-first
 *
 * Observed 2026-08-20, each revert applied alone, control 0 of 10 — and each revert named exactly
 * the tests predicted for it, which is the only reason the counts below are worth reading:
 *
 *  - **reads skipped again** (restoring `httpMethod in setOf(Get, Head, Options)`): 5 of 10 —
 *    `theExactLeakBranch3Measured`, `aCrossSiteReadIsRefusedWhenTheGuardIsOn`,
 *    `headIsCoveredAndNotJustGet`, `theRefusalSaysReadsRatherThanWrites` and
 *    `theGuardIsActuallyOnWhichIsThisFilesPremise`.
 *  - **the `navigate` exemption removed**: 1 of 10 — `aTopLevelNavigationIsNotTheExposure`.
 *  - **the `websocket` exemption removed**: 1 of 10 — `aWebSocketUpgradeIsLeftToItsOwnGuard`.
 *  - **`fieldApiSelfGuardedReadPrefixes` emptied**: 1 of 10 —
 *    `theSelfGuardingDocsRoutesStillDecideForThemselves`.
 *
 * Nothing reverted moves `aClientWithNoOriginIsStillUnaffectedAndThatIsTheLimitation`, which is
 * the point of it: no arrangement of this switch closes the network half.
 */
class FieldApiReadGuardTest {

    /**
     * The premise, asserted first so that the rest of this file cannot pass vacuously.
     *
     * Every other test here distinguishes 403 from 200. If the guard were not installed at all —
     * a bad merge, a renamed switch, a test harness that stopped setting the property — those
     * tests would still *run*, and the ones asserting 200 would still pass. This one fails
     * instead, and names why.
     */
    @Test
    fun theGuardIsActuallyOnWhichIsThisFilesPremise() = guardedApp(on = true) {
        assertEquals(
            HttpStatusCode.Forbidden, get("/api/anything") { fromEvilPage() }.status,
            "The guard is not refusing anything, so every 200 asserted in this file is vacuous. " +
                "Check that installFieldApiCors still calls installFieldApiSameSiteGuard and that " +
                "$FIELD_API_REQUIRE_SAME_SITE_ENV is still the switch.",
        )
    }

    /**
     * The measured finding, reproduced as a test: same route shape, same headers, same switch.
     *
     * The body assertion is the part that matters. A 403 with the checkout path in it would be
     * just as much of a leak, and asserting only the status would not notice.
     */
    @Test
    fun theExactLeakBranch3Measured() = guardedApp(on = true) {
        val response = get("/api/instance-info") { fromEvilPage() }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(
            CHECKOUT_PATH !in response.bodyAsText(),
            "the refusal leaked the very thing it refused: ${response.bodyAsText()}",
        )
    }

    @Test
    fun aCrossSiteReadIsRefusedWhenTheGuardIsOn() = guardedApp(on = true) {
        for (path in listOf("/api/instance-info", "/api/welcome-changelog", "/api/source", "/api/workspace-search")) {
            assertEquals(HttpStatusCode.Forbidden, get(path) { fromEvilPage() }.status, "for $path")
        }
        // …and Field's own page still reads, which is the whole point of *same-site* rather than off.
        assertEquals(HttpStatusCode.OK, get("/api/instance-info") { fromFieldsOwnPage() }.status)
    }

    /** `HEAD` returns the headers of a `GET`, so a guard that covered only `GET` would be a sieve. */
    @Test
    fun headIsCoveredAndNotJustGet() = guardedApp(on = true) {
        assertEquals(HttpStatusCode.Forbidden, head("/api/instance-info") { fromEvilPage() }.status)
    }

    /** A refusal that misnames what it refused is a support ticket. */
    @Test
    fun theRefusalSaysReadsRatherThanWrites() = guardedApp(on = true) {
        val body = get("/api/instance-info") { fromEvilPage() }.bodyAsText()
        assertTrue("reads" in body, "was: $body")
        assertTrue(FIELD_API_REQUIRE_SAME_SITE_ENV in body, "was: $body")
    }

    /**
     * The default is still the old behaviour, for the reason the write guard states: turning a
     * refusal on across three dozen routes is how you discover which of them your own tooling
     * was quietly using. This test is what makes the read guard opt-in rather than a surprise.
     */
    @Test
    fun theDefaultIsStillTheOldBehaviourAndThereforeStillTheExposure() = guardedApp(on = false) {
        assertEquals(HttpStatusCode.OK, get("/api/instance-info") { fromEvilPage() }.status)
    }

    // ── the three exemptions, each asserted as a feature ──────────────────────

    /**
     * A cross-site *navigation* is not the exposure, and refusing it would break every link
     * into Field — including the `Open ↗` links the docs pages are built around.
     *
     * Note the header shape, which is why this needs saying: a GET navigation sends
     * `Sec-Fetch-Site: cross-site` and **no `Origin`**, so [isSameSiteRequest] calls it
     * cross-site and would refuse it. What makes it safe is not the origin but the
     * destination: the body lands in a Field-origin document, and the page that initiated the
     * navigation cannot read a byte of it.
     */
    @Test
    fun aTopLevelNavigationIsNotTheExposure() = guardedApp(on = true) {
        val response = get("/welcome") {
            header(HttpHeaders.Host, "localhost")
            header(SEC_FETCH_SITE_HEADER, "cross-site")
            header(SEC_FETCH_MODE_HEADER, "navigate")
        }
        assertEquals(
            HttpStatusCode.OK, response.status,
            "If this went red, clicking a link into Field from any other page now 403s.",
        )
    }

    /**
     * WebSocket upgrades are GETs, and they are refused — by
     * `refuseCrossSiteFieldWebSocket`, at the upgrade, where it can close with `1008` and a
     * reason a client can read. This test asserts only that *this* interceptor stands aside;
     * `FieldWebSocketWriteGuardTest` owns the refusal itself.
     */
    @Test
    fun aWebSocketUpgradeIsLeftToItsOwnGuard() = guardedApp(on = true) {
        val response = get("/ws") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "https://somewhere-else.example")
            header(SEC_FETCH_SITE_HEADER, "cross-site")
            header(SEC_FETCH_MODE_HEADER, "websocket")
        }
        assertEquals(
            HttpStatusCode.OK, response.status,
            "This interceptor has started pre-empting the WebSocket guard, which turns a documented " +
                "101-then-1008 handshake into a bare 403.",
        )
    }

    /**
     * `docsAnnotations.kt` guards its own reads and owns a deliberate hatch —
     * `DOCS_ANNOTATIONS_ALLOW_CROSS_SITE` — that *permits* cross-site annotation reads so a docs
     * page served from elsewhere can list them. If this goes red, this guard has closed another
     * switch's hatch behind its back, and `DocsAnnotationRouteSecurityTest` will not notice
     * because the refusal happens before its routes are reached.
     *
     * The revision pass (`docsPass.kt`) guards its own reads too, and is exempt by its exact
     * prefix, `/api/docs-improvement/pass`. The bare `/api/docs-improvement` was the prefix
     * until its own route was removed on 2026-10-02; the negative control below shows the
     * exemption did not survive under the shorter name.
     */
    @Test
    fun theSelfGuardingDocsRoutesStillDecideForThemselves() = guardedApp(on = true) {
        for (path in listOf(
            "/api/docs-annotations",
            "/api/docs-improvement/pass",
            "/api/docs-improvement/pass/diff",
            "/api/docs-improvement/pass/handoff",
        )) {
            assertEquals(HttpStatusCode.OK, get(path) { fromEvilPage() }.status, "for $path")
        }
        for (path in listOf("/api/docs-improvement", "/api/docs-improvement/other", "/api/docs-improvement/passage")) {
            assertEquals(HttpStatusCode.Forbidden, get(path) { fromEvilPage() }.status, "for $path")
        }
    }

    /**
     * The limitation, asserted so it cannot be quietly forgotten — the read-side twin of
     * `FieldApiWriteGuardTest.theGuardDoesNothingAboutTheNetworkAndThisTestSaysSoOutLoud`.
     *
     * A `curl` from the LAN sends no `Origin` and no `Sec-Fetch-Site`. [isSameSiteRequest] calls
     * that same-site on purpose, so every read this guard refuses to a website is still wide open
     * to any host on the network. Closing *that* needs authentication or a narrower bind address.
     */
    @Test
    fun aClientWithNoOriginIsStillUnaffectedAndThatIsTheLimitation() = guardedApp(on = true) {
        assertEquals(
            HttpStatusCode.OK, get("/api/instance-info") { /* no Origin: a curl */ }.status,
            "If this went red the guard started refusing non-browser clients — which breaks curl, the " +
                "dev_docs recipes, the visionOS shell and the docs pipeline. That is a decision, not a " +
                "tightening. Update this test's KDoc rather than deleting it.",
        )
    }

    // ══════════════════════════════════════════════════════════════════════════

    /** Stands in for the checkout path that `GET /api/instance-info` really returns. */
    private val CHECKOUT_PATH = "/Users/somebody/field4"

    private fun guardedApp(on: Boolean, block: suspend HttpClient.() -> Unit) {
        val previous = System.getProperty(FIELD_API_REQUIRE_SAME_SITE_PROPERTY)
        // The property beats the environment, so this decides the posture whatever the shell
        // that started Gradle happened to export.
        System.setProperty(FIELD_API_REQUIRE_SAME_SITE_PROPERTY, if (on) "true" else "false")
        try {
            testApplication {
                application {
                    installFieldApiCors()
                    routing {
                        get("/api/instance-info") { call.respondText(CHECKOUT_PATH) }
                        get("/api/{...}") { call.respondText("read") }
                        get("/ws") { call.respondText("would have upgraded") }
                        get("/welcome") { call.respondText("<html>the editor</html>") }
                    }
                }
                client.block()
            }
        } finally {
            if (previous == null) System.clearProperty(FIELD_API_REQUIRE_SAME_SITE_PROPERTY)
            else System.setProperty(FIELD_API_REQUIRE_SAME_SITE_PROPERTY, previous)
        }
    }

    /** A `fetch()` from a page on another origin — the shape that gets the body back. */
    private fun HttpRequestBuilder.fromEvilPage() {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Origin, "https://somewhere-else.example")
        header(SEC_FETCH_SITE_HEADER, "cross-site")
        header(SEC_FETCH_MODE_HEADER, "cors")
    }

    private fun HttpRequestBuilder.fromFieldsOwnPage() {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Origin, "http://localhost")
        header(SEC_FETCH_SITE_HEADER, "same-origin")
        header(SEC_FETCH_MODE_HEADER, "cors")
    }
}
