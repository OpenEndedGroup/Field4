package afield.server

import afield.FIELD_API_REQUIRE_SAME_SITE_ENV
import afield.FIELD_API_REQUIRE_SAME_SITE_PROPERTY
import afield.installFieldApiCors
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The opt-in refusal of cross-site writes across the whole API.
 *
 * `FieldApiCorsExposureTest.everyMutatingRouteAndItsGuardIsPinned` establishes the number:
 * 49 of 54 state-changing HTTP routes have no route-specific access control at all, and Field's CORS config
 * says `anyHost()`, so any website you have open can call every one of them. That number is
 * not something a nightly agent gets to fix by changing a default — turning a refusal on
 * across 49 routes is exactly how you find out which of them the human's own tooling was
 * quietly using, at 4am, with nobody awake.
 *
 * So the switch exists, the default is the old behaviour, and there is a loud line at
 * startup either way. What this file is for is making sure the switch is real: that it
 * refuses what it claims to, that it does not touch the one route that legitimately needs
 * to work cross-origin, and — the part worth writing a test for even though it is a
 * *limitation* — that it does nothing whatsoever about the network exposure, which is the
 * thing someone reading "same-site guard" in a release note would assume it fixed.
 *
 * ## Red-first
 *
 * Observed 2026-08-12, each revert applied alone, control 0 of 7:
 *
 *  - **the `intercept` block never installed**, keeping the startup log: 3 of 7 —
 *    `aCrossSiteWriteIsRefusedWhenTheGuardIsOn`, `everyMutatingMethodIsCovered` and
 *    `theRefusalNamesTheSwitchThatCausedIt`.
 *  - **the same-site check short-circuited to "permit"**: the same 3 of 7, which is the
 *    right answer — an interceptor that is absent and one that decides nothing are the same
 *    fact from a caller's point of view.
 *  - **the exemption for `/api/import-offline-doc` removed**: 1 of 7 —
 *    `theOfflineViewerImportIsExemptBecauseItIsTheReasonAnyHostIsThere`. That is a test
 *    catching a *feature* being eaten rather than a hole being opened, which is the failure
 *    a security switch is likeliest to have.
 *  - **`Options` no longer skipped**: 1 of 7 — `preflightsArePassedThrough`. (`Get`/`Head` used
 *    to be skipped here too; that was the read gap, and [FieldApiReadGuardTest] now covers them.)
 *
 * Nothing reverted here moves `theGuardDoesNothingAboutTheNetworkAndThisTestSaysSoOutLoud`,
 * and that is exactly its point: no arrangement of this switch closes the network exposure.
 */
class FieldApiWriteGuardTest {

    @Test
    fun theDefaultIsTheOldBehaviourBecauseNobodyIsAwakeToNoticeOtherwise() = guardedApp(on = false) {
        assertEquals(HttpStatusCode.OK, post("/api/anything") { fromEvilPage() }.status)
    }

    @Test
    fun aCrossSiteWriteIsRefusedWhenTheGuardIsOn() = guardedApp(on = true) {
        assertEquals(HttpStatusCode.Forbidden, post("/api/anything") { fromEvilPage() }.status)
        // …and the local UI, which is the whole point of it being *same-site* rather than off.
        assertEquals(HttpStatusCode.OK, post("/api/anything") { fromFieldsOwnPage() }.status)
    }

    /** A refusal nobody can act on is a support ticket. */
    @Test
    fun theRefusalNamesTheSwitchThatCausedIt() = guardedApp(on = true) {
        val body = post("/api/anything") { fromEvilPage() }.bodyAsText()
        assertTrue(FIELD_API_REQUIRE_SAME_SITE_ENV in body, "was: $body")
    }

    @Test
    fun everyMutatingMethodIsCovered() = guardedApp(on = true) {
        for (path in listOf("/api/anything", "/api/deep/nested/thing", "/field-audio/v1/jobs/one/cancel")) {
            assertEquals(HttpStatusCode.Forbidden, post(path) { fromEvilPage() }.status, "for $path")
        }
    }

    /**
     * The one exemption, and the reason `anyHost()` is in the CORS block in the first place.
     *
     * `offline-viewer.js` POSTs a document to `/api/import-offline-doc` from whatever origin
     * the offline bundle was served from — a genuinely foreign origin, on purpose. If this
     * test goes red the guard has eaten a feature.
     */
    @Test
    fun theOfflineViewerImportIsExemptBecauseItIsTheReasonAnyHostIsThere() = guardedApp(on = true) {
        assertEquals(HttpStatusCode.OK, post("/api/import-offline-doc") { fromEvilPage() }.status)
    }

    /**
     * The preflight passes — which matters more than it looks. An `OPTIONS` refused here would
     * never reach the CORS plugin, so a browser could not complete a preflight for
     * `/api/import-offline-doc` either, and the exemption above would be exempting a request the
     * browser had already given up on.
     *
     * **This test used to assert that cross-site *reads* passed too, and that assertion was the
     * read gap written down as a guarantee.** `nightly/2026-08-20/3-server-surface-threat-model`
     * measured a foreign origin reading the absolute path of the checkout out of
     * `GET /api/instance-info` with this switch fully on. Reads are now held to the same rule;
     * [FieldApiReadGuardTest] owns that half, including the three read shapes still deliberately
     * allowed. Only the preflight assertion belongs here.
     */
    @Test
    fun preflightsArePassedThrough() = guardedApp(on = true) {
        val preflight = options("/api/import-offline-doc") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "https://somewhere-else.example")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "content-type")
        }
        assertEquals(HttpStatusCode.OK, preflight.status)
        assertEquals("*", preflight.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    /**
     * The limitation, asserted so that it cannot be quietly forgotten.
     *
     * A `curl` from another machine on the LAN sends no `Origin` and no `Sec-Fetch-Site`.
     * [isSameSiteRequest] calls that same-site, deliberately — refusing it would break every
     * non-browser client Field has, including the `curl` recipes in its own dev_docs — and
     * Ktor's CORS plugin does not run for such a request either. So this guard, switched
     * fully on, still leaves all 36 unguarded mutating HTTP routes reachable from the network.
     *
     * Closing *that* means an authentication scheme or a narrower bind address, and the bind
     * address is `0.0.0.0` because the visionOS shell reaches Field over Wi-Fi.
     */
    @Test
    fun theGuardDoesNothingAboutTheNetworkAndThisTestSaysSoOutLoud() = guardedApp(on = true) {
        assertEquals(
            HttpStatusCode.OK, post("/api/shutdown-instance-lookalike") { /* no Origin: a curl */ }.status,
            "If this went red, the guard started refusing non-browser clients. That may even be what you " +
                "want — but it breaks curl, the dev_docs recipes and any native client, so it is a decision, " +
                "not a tightening. Update this test's KDoc rather than deleting it.",
        )
    }

    // ══════════════════════════════════════════════════════════════════════════

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
                        get("/api/{...}") { call.respondText("read") }
                        post("/api/{...}") { call.respondText("wrote") }
                    }
                }
                client.block()
            }
        } finally {
            if (previous == null) System.clearProperty(FIELD_API_REQUIRE_SAME_SITE_PROPERTY)
            else System.setProperty(FIELD_API_REQUIRE_SAME_SITE_PROPERTY, previous)
        }
    }

    private fun HttpRequestBuilder.fromEvilPage() {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Origin, "https://somewhere-else.example")
        header(SEC_FETCH_SITE_HEADER, "cross-site")
    }

    private fun HttpRequestBuilder.fromFieldsOwnPage() {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Origin, "http://localhost")
        header(SEC_FETCH_SITE_HEADER, "same-origin")
    }
}
