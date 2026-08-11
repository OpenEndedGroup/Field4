package afield.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A guard over the shape of Field's cross-origin exposure, and over the one property
 * that makes `/api/docs-improvement`'s token worth having.
 *
 * ## Why this test reads source instead of starting a server
 *
 * The CORS configuration is a seven-line literal inside `createServer` in `Server.kt`,
 * which cannot be reached without standing up a Netty engine, and `ktor-server-test-host`
 * is not a declared `jvmTest` dependency. Reading the source is the same move
 * `SmallLanguageAnatomyTest` makes for documentation citations, for the same reason: the
 * fact being guarded is a *decision recorded in the source*, and the failure mode is
 * somebody changing it without noticing what it implies.
 *
 * ## What was measured, on 2026-08-11, against a real Field on port 9492
 *
 * Not asserted here — a unit test cannot see it — but this is the evidence the
 * assertions below are standing in for, from a real Chromium page served from
 * `http://127.0.0.1:9493`, a genuinely foreign origin:
 *
 *  - `GET /api/instance-info` and `GET /api/welcome-changelog` were **read** by that
 *    page. The first returns the absolute path of the checkout; the second returns the
 *    human's recent commit messages.
 *  - `POST /api/shutdown-instance` from that page **stopped the server**. Listener PID
 *    before: 4508. After: none.
 *  - With `anyHost()` removed and nothing else changed, every one of those probes failed
 *    with `TypeError: Failed to fetch`, the server replied `403 Forbidden` with the
 *    handler never running, the server survived the shutdown attempt, and same-origin
 *    requests were entirely unaffected (`/health` 200, and 200 with a matching `Origin`).
 *
 * So `anyHost()` is load-bearing for the exposure in both directions, and Ktor's CORS
 * plugin is a server-side gate rather than mere header decoration. The single legitimate
 * consumer of it is `POST /api/import-offline-doc`, which the offline viewer calls from
 * whatever origin it happens to be hosted at (`dev_docs/OFFLINE_DEPLOYMENT.md`). That is
 * why this test pins the exposure rather than asserting it away: closing it means
 * deciding what happens to that feature, which is the human's call and not a test's.
 *
 * See `dev_docs/DOCS_IMPROVEMENT_AGENT.md` for the write-up and the recommended fix.
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
     * from the seven lines themselves.
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
     * Loopback-only does not stop a cross-site POST, because the human's own browser is
     * on loopback. What stops it is that `X-Field-Docs-Improvement-Token` is not a
     * CORS-safelisted header and is not in `allowHeader`, so the preflight fails — 403,
     * observed, and `TypeError: Failed to fetch` in the page.
     *
     * `anyHeader()`, or an `allowHeader` for this header, would silently convert the
     * agent-spawning endpoint back into something a web page can reach. This test is the
     * tripwire on that, and it is the reason this file exists at all.
     */
    @Test
    fun theImprovementTokenHeaderIsNotReachableFromAWebPage() {
        val block = Regex("""install\(CORS\)\s*\{([^}]*)\}""").find(serverSource)?.groupValues?.get(1).orEmpty()
        assertFalse(
            "anyHeader()" in block,
            "anyHeader() in the CORS config would let any website send $DOCS_IMPROVEMENT_TOKEN_HEADER, " +
                "which is the only thing standing between a web page and `codex exec --sandbox workspace-write`.",
        )
        assertFalse(
            DOCS_IMPROVEMENT_TOKEN_HEADER.lowercase() in block.lowercase(),
            "$DOCS_IMPROVEMENT_TOKEN_HEADER must never be CORS-allowed; the preflight failing is the CSRF defence.",
        )
        // PUT is likewise absent, which is why `PUT /api/docs-annotations/{id}/resolve`
        // is not cross-origin reachable. Recorded rather than relied upon — the loopback
        // check is that route's actual guard.
        assertFalse("allowMethod(HttpMethod.Put)" in block, "adding PUT widens the cross-origin surface")
    }

    /**
     * The size of the surface `anyHost()` applies to, pinned as a number and a list.
     *
     * A count in a worklog decays the first time somebody adds a route. This fails
     * instead, which turns "we added an endpoint" into "we added an endpoint that any
     * website can call while `anyHost()` stands".
     *
     * Counting from source rather than from a running server is deliberate and its
     * limitation is real: it sees literal-path declarations only, so parameterised
     * sub-routes under a `route("…") { }` prefix (the two `{...}` proxies) are not here.
     * It is a tripwire on growth, not a census.
     */
    @Test
    fun theUnauthenticatedMutatingApiSurfaceHasNotGrownUnnoticed() {
        val declaration = Regex("""^\s*(get|post|put|delete|patch)\(\s*"(/[^"]*)"""", RegexOption.MULTILINE)
        val sources = File(repositoryRoot(), "src/jvmMain/kotlin/afield")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }
        val mutating = sources.flatMap { file ->
            declaration.findAll(file.readText()).mapNotNull { match ->
                val method = match.groupValues[1]
                if (method == "get") null else "${method.uppercase()} ${match.groupValues[2]}"
            }
        }.toSortedSet()

        assertEquals(
            38, mutating.size,
            "The number of state-changing HTTP routes changed (was 38 on 2026-08-11). While the CORS config " +
                "says anyHost(), every one of these is reachable from any website the user has open — and only " +
                "POST /api/docs-improvement requires a credential. If you added one, decide whether it needs " +
                "protecting, then update this number.\n\nCurrent set:\n" + mutating.joinToString("\n") { "  $it" },
        )
        // Spot-check the ones whose names alone make the point, so a rename cannot quietly
        // drop them out of the count while keeping the total the same.
        listOf(
            "POST /api/shutdown-instance",
            "POST /api/agent-worktrees/delete",
            "POST /api/projects/delete",
            "POST /api/workspace-switch",
            "POST /api/maven-repositories",
        ).forEach { assertTrue(it in mutating, "expected $it to still be part of the pinned surface") }
    }

    private fun repositoryRoot(): File {
        var candidate: File? = File(".").absoluteFile
        while (candidate != null && !File(candidate, "settings.gradle.kts").isFile) candidate = candidate.parentFile
        assertTrue(candidate != null, "could not locate the repository root from ${File(".").absolutePath}")
        return candidate
    }
}
