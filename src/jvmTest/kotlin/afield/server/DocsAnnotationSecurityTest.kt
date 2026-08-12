package afield.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The access-control tests for `/api/docs-annotations` and `/api/docs-improvement`.
 *
 * These exist because that second route spawns `codex exec --sandbox workspace-write`
 * — an autonomous coding agent with write access to a source checkout — and because it
 * is installed alongside an SSL connector bound to `0.0.0.0`. Before the branch that
 * added this file it took no credential of any kind.
 *
 * Every test here was watched to fail against the pre-change behaviour. That is not a
 * figure of speech: each guard was locally reverted to what it replaced (for the
 * spawn and the annotation routes, "return null" — permit everything; for the proxy,
 * `relative.contains("..")` against the raw path), the suite was run, and the failures
 * were recorded. A security test nobody has seen fail is a test that guards nothing.
 * If you change a guard, do the same again rather than trusting the green.
 *
 * The [DocsAccessPolicy] argument is what makes that cheap. The policy is a value, so
 * "the server as it shipped last week" and "the server with the flag on" are both just
 * arguments, and the tests do not need a running Ktor application to say anything true.
 */
class DocsAnnotationSecurityTest {

    /** Enabled, tokenised, and otherwise permissive — the *most* open posture that is still hardened. */
    private val enabled = DocsAccessPolicy(improvementEnabled = true, improvementToken = TOKEN)

    /** Exactly what a Field serves with no configuration at all. */
    private val default = DocsAccessPolicy()

    // ══════════════════════════════════════════════════════════════════════════
    // The spawn
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun aHostOnTheLanCannotLaunchTheAgentEvenWithEverythingElseCorrect() {
        // The original attack, in one line: the LAN could reach the port, and the port
        // would run a coding agent. Enabled and correctly tokenised here so that the
        // only thing refusing is the address.
        for (address in listOf("192.168.1.50", "10.0.0.7", "0.0.0.0", "169.254.1.1", "fe80::1")) {
            val refusal = refuseDocsImprovementLaunch(enabled, address, TOKEN)
            assertNotNull(refusal, "a launch from $address must be refused")
            assertEquals(HttpStatusCode.Forbidden, refusal.status, "for $address")
        }
    }

    @Test
    fun theAgentIsOffUntilItIsAskedForAndTheRefusalSaysHow() {
        val refusal = refuseDocsImprovementLaunch(default, "127.0.0.1", TOKEN)
        assertNotNull(refusal, "the agent must be disabled by default")
        assertEquals(HttpStatusCode.Forbidden, refusal.status)
        // The refusal is a help message, not a wall: by this point the caller is local
        // and is almost certainly the human wondering why the button did nothing.
        assertTrue(
            DOCS_IMPROVEMENT_ENABLE_PROPERTY in refusal.reason && DOCS_IMPROVEMENT_ENABLE_ENV in refusal.reason,
            "the refusal must name both ways to enable it; was: ${refusal.reason}",
        )
    }

    @Test
    fun anEnabledAgentStillNeedsTheToken() {
        for (presented in listOf(null, "", "  ", "wrong", TOKEN.dropLast(1), TOKEN + "x", TOKEN.uppercase())) {
            val refusal = refuseDocsImprovementLaunch(enabled, "127.0.0.1", presented)
            assertNotNull(refusal, "token '${presented}' must not be accepted")
            assertEquals(HttpStatusCode.Unauthorized, refusal.status, "for token '$presented'")
            assertTrue(DOCS_IMPROVEMENT_TOKEN_HEADER in refusal.reason, "the refusal must name the header")
        }
    }

    @Test
    fun misconfiguredIsNotTheSameAsUnauthenticated() {
        // `resolveDocsAccessPolicy` mints a token whenever the feature is on, so this
        // state should be unreachable. It is checked anyway, because "enabled but with
        // an empty expected token" is precisely the shape of an accident that would
        // otherwise re-open the endpoint to every empty header on loopback.
        val brokenlyConfigured = DocsAccessPolicy(improvementEnabled = true, improvementToken = "")
        assertNotNull(refuseDocsImprovementLaunch(brokenlyConfigured, "127.0.0.1", ""))
        assertNotNull(refuseDocsImprovementLaunch(brokenlyConfigured, "127.0.0.1", null))
        assertNotNull(refuseDocsImprovementLaunch(brokenlyConfigured, "127.0.0.1", "anything"))
    }

    @Test
    fun theRemoteEscapeHatchNeverOpensTheSpawn() {
        // AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE exists so a tablet can read the docs and
        // leave notes. If it also let the LAN start a coding agent it would be a hole
        // with a friendly name, so the spawn guard does not consult it — and this test
        // is the reason it will stay that way.
        val hatchOpen = enabled.copy(allowRemoteAnnotations = true)
        assertNull(refuseDocsAnnotationRequest(hatchOpen, "192.168.1.50"), "annotations are what the hatch opens")
        val refusal = refuseDocsImprovementLaunch(hatchOpen, "192.168.1.50", TOKEN)
        assertNotNull(refusal, "the hatch must not reach the agent")
        assertEquals(HttpStatusCode.Forbidden, refusal.status)
    }

    @Test
    fun theHumanCanStillRunItAndTheOrderOfChecksIsWhatIsClaimed() {
        // The workflow this whole branch is trying not to break.
        assertNull(refuseDocsImprovementLaunch(enabled, "127.0.0.1", TOKEN))
        assertNull(refuseDocsImprovementLaunch(enabled, "::1", TOKEN))
        assertNull(refuseDocsImprovementLaunch(enabled, "127.0.0.5", TOKEN))

        // Loopback is checked first, so an off-machine caller learns nothing about
        // whether the feature is configured — same refusal either way.
        assertEquals(
            refuseDocsImprovementLaunch(default, "192.168.1.50", null)?.reason,
            refuseDocsImprovementLaunch(enabled, "192.168.1.50", TOKEN)?.reason,
            "a remote caller must not be able to tell an enabled server from a disabled one",
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // What counts as "this machine"
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The forms Netty was *observed* handing us, as opposed to the ones I assumed.
     *
     * A loopback guard that refuses the human's own browser is a worse outcome than the
     * hole it closes, and it would fail silently — the panel would simply say
     * "loopback-only" on the machine you are sitting at. Driving a real Field on port
     * 9492 on 2026-08-11, a request from `curl https://localhost:9492/…` arrived with
     * `request.local.remoteAddress` = `0:0:0:0:0:0:0:1`: the fully expanded IPv6
     * loopback, neither `127.0.0.1` nor `::1`. Confirmed by the server's own log line,
     * `Refused a documentation improvement launch from 0:0:0:0:0:0:0:1: 403 Forbidden` —
     * a refusal from the *disabled* check, which a request only reaches by passing the
     * loopback check first.
     *
     * The LAN forms in the second half are from the same session, where the machine's
     * own address was 192.168.2.125 and every probe from it was correctly refused.
     */
    @Test
    fun theAddressFormsNettyActuallyHandsUsAreLoopback() {
        for (observed in listOf("0:0:0:0:0:0:0:1", "127.0.0.1", "::1", "::ffff:127.0.0.1")) {
            assertTrue(isLoopbackDocsClient(observed), "$observed must be treated as this machine")
        }
        assertFalse(isLoopbackDocsClient("192.168.2.125"), "the machine's own LAN address is not loopback")
        assertFalse(isLoopbackDocsClient("::ffff:192.168.2.125"), "nor is it, wearing an IPv6 hat")
    }

    @Test
    fun loopbackIsDecidedOnALiteralAddressAndNeverOnAName() {
        for (address in listOf("127.0.0.1", "127.0.0.5", "127.255.255.254", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1", "[::1]", " 127.0.0.1 ")) {
            assertTrue(isLoopbackDocsClient(address), "$address is loopback")
        }
        // The classic shortened IPv4 forms, which `InetAddress.ofLiteral` still accepts:
        // "127.1" is 127.0.0.1 and "127.0.0" is 127.0.0.0. Both are inside 127.0.0.0/8,
        // so calling them loopback is correct rather than lax — and no engine hands us
        // a peer address in that shape anyway. Recorded because it surprised this test
        // first time round.
        for (address in listOf("127.1", "127.0.0", "2130706433")) {
            assertTrue(isLoopbackDocsClient(address), "$address is a 127/8 literal")
        }
        for (address in listOf("192.168.1.50", "10.0.0.7", "0.0.0.0", "::", "128.0.0.1", "126.255.255.255", "fe80::1")) {
            assertFalse(isLoopbackDocsClient(address), "$address is not loopback")
        }
        // A *name* is refused even when it would resolve to loopback. Resolving it would
        // make the verdict depend on a DNS answer, and DNS is not a trust boundary.
        for (name in listOf("localhost", "localhost.localdomain", "ip6-localhost", "127.0.0.1.nip.io")) {
            assertFalse(isLoopbackDocsClient(name), "'$name' is a name, not an address")
        }
        // Fails closed on anything it cannot parse, including nothing at all.
        for (junk in listOf(null, "", "   ", "not-an-address", "127.0.0.1:9492", "999.0.0.1", "127.0.0.1/8")) {
            assertFalse(isLoopbackDocsClient(junk), "'$junk' must fail closed")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The annotation routes
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun annotationsAreLoopbackOnlyByDefaultBecauseTheyArePromptText() {
        assertNull(refuseDocsAnnotationRequest(default, "127.0.0.1"))
        val refusal = refuseDocsAnnotationRequest(default, "192.168.1.50")
        assertNotNull(refusal, "annotation writes are the input to the agent's prompt")
        assertEquals(HttpStatusCode.Forbidden, refusal.status)
        // The panel renders this string straight into its list, so it has to be usable.
        assertTrue(DOCS_ANNOTATIONS_ALLOW_REMOTE_ENV in refusal.reason, "the refusal must say how to allow it")
        assertTrue("localhost" in refusal.reason, "the refusal must say what to do instead")
    }

    @Test
    fun theHatchDoesWhatItSays() {
        val hatchOpen = DocsAccessPolicy(allowRemoteAnnotations = true)
        assertNull(refuseDocsAnnotationRequest(hatchOpen, "192.168.1.50"))
        assertNull(refuseDocsAnnotationRequest(hatchOpen, "127.0.0.1"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Reading the flags
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun aFlagIsOnOnlyWhenItSaysSo() {
        for (on in listOf("1", "true", "TRUE", "yes", "on", " true ")) {
            assertTrue(docsFlagEnabled(null, on), "'$on' should enable")
            assertTrue(docsFlagEnabled(on, null), "'$on' should enable as a property")
        }
        // The empty string is what `AFIELD_DOCS_IMPROVEMENT=` produces, and "off" is the
        // only safe reading of it.
        for (off in listOf(null, "", " ", "0", "false", "no", "off", "enabled", "sure")) {
            assertFalse(docsFlagEnabled(null, off), "'$off' should not enable")
        }
        // A -D flag on this JVM's command line is a more specific statement of intent
        // than a variable inherited from a login shell, so it wins — in both directions.
        assertTrue(docsFlagEnabled("true", "false"))
        assertFalse(docsFlagEnabled("false", "true"))
        // ...but a blank property is not a statement at all, and defers.
        assertTrue(docsFlagEnabled("", "true"))
        assertTrue(docsFlagEnabled("  ", "true"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Where the agent is allowed to run
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun theWorkingDirectoryIsResolvedRatherThanInherited() = withTempDir { dir ->
        val server = Files.createDirectory(dir.resolve("server-cwd"))
        val elsewhere = Files.createDirectory(dir.resolve("a checkout"))

        // Unset: the server's own directory, but canonicalised — the old code passed a
        // bare `Paths.get(".")` to ProcessBuilder, which is not something you can put in
        // a log line and expect to mean anything later.
        val implicit = resolveDocsImprovementDirectory(null, server)
        assertEquals("", implicit.problem)
        assertEquals(server.toRealPath(), implicit.path)
        assertTrue(implicit.path.toString().startsWith("/"), "an absolute path, so a run is attributable")

        // Configured, including via a relative-looking path with a dot segment in it.
        assertEquals(elsewhere.toRealPath(), resolveDocsImprovementDirectory(elsewhere.toString(), server).path)
        assertEquals(
            elsewhere.toRealPath(),
            resolveDocsImprovementDirectory("$server/../a checkout", server).path,
        )
        // Blank is "unset", not "the empty path".
        assertEquals(server.toRealPath(), resolveDocsImprovementDirectory("   ", server).path)

        // A directory that is not there, or is not a directory, refuses with a sentence
        // rather than throwing route installation over.
        val missing = resolveDocsImprovementDirectory(dir.resolve("nope").toString(), server)
        assertNull(missing.path)
        assertTrue(missing.problem.isNotBlank(), "a refusal must explain itself")
        assertEquals("<unresolved>", missing.display)

        val file = Files.writeString(dir.resolve("a-file.txt"), "not a checkout")
        val notADirectory = resolveDocsImprovementDirectory(file.toString(), server)
        assertNull(notADirectory.path)
        assertTrue("not a directory" in notADirectory.problem, "was: ${notADirectory.problem}")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The documentation proxy
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun theProxyRefusesTraversalThroughItsEncodedForm() {
        // The defect: the old guard tested the *raw* path for "..", so this walked past
        // it and `HttpClient` normalised it on the way out.
        for (attack in listOf(
            "/field-docs/../secret",
            "/field-docs/%2e%2e/secret",
            "/field-docs/%2E%2E/%2e%2e/secret",
            "/field-docs/a/b/%2e%2e/%2e%2e/%2e%2e/secret",
            "/field-docs/%2e%2e%2fsecret",
            "/field-docs/..%2fsecret",
        )) {
            assertNull(docsProxyTarget(attack, null), "$attack must be refused")
        }
        // Other ways to leave /field4/ on the way out.
        assertNull(docsProxyTarget("/field-docs//evil.example.com/x", null), "a rooted path picks its own origin")
        assertNull(docsProxyTarget("/field-docs/a\\..\\b", null), "backslashes are separators to somebody")
        // Written as an escape rather than as the byte itself. It used to be a literal
        // NUL in the source, which made `file` report this .kt as binary data and made
        // plain `grep` skip the whole file — so the one test file whose subject is
        // "what does an attacker put in a path" was invisible to the two tools you
        // would reach for while looking into exactly that.
        assertNull(docsProxyTarget("/field-docs/a\u0000b", null), "control characters are not filenames")
    }

    @Test
    fun theProxyStillServesTheDocumentationItIsFor() {
        assertEquals("https://openendedgroup.com/field4/index.html", docsProxyTarget("/field-docs/", null).toString())
        // Ktor routes the no-trailing-slash form too. The old prefix-strip did not match
        // it, so `/field-docs` was assembled into `/field4//field-docs` and 404'd.
        assertEquals("https://openendedgroup.com/field4/index.html", docsProxyTarget("/field-docs", null).toString())
        assertEquals("https://openendedgroup.com/field4/rectsl.html", docsProxyTarget("/field-docs/rectsl.html", null).toString())
        assertEquals(
            "https://openendedgroup.com/field4/assets/sonic_pi/sonicpi_region.png",
            docsProxyTarget("/field-docs/assets/sonic_pi/sonicpi_region.png", null).toString(),
        )
        assertEquals("https://openendedgroup.com/field4/search.html?q=fbm", docsProxyTarget("/field-docs/search.html", "q=fbm").toString())
        // A file whose name merely contains dots is not a traversal.
        assertEquals("https://openendedgroup.com/field4/a..b.html", docsProxyTarget("/field-docs/a..b.html", null).toString())
        // Double-encoded: the upstream decodes once too, so this reaches it as the
        // literal name `%2e%2e` and never becomes a traversal. Refusing it would be a
        // false positive, and this test is what stops someone "fixing" that.
        assertNotNull(docsProxyTarget("/field-docs/%252e%252e", null))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The store
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun tagExtractionDecidesWhatAnAgentRunIsHandedSoItIsPinnedHere() {
        assertEquals(listOf("ai", "docs"), docsAnnotationTags("fix this @docs and @ai", emptyList()))
        assertEquals(listOf("ai"), docsAnnotationTags("@ai @AI @Ai", emptyList()), "case-folded and de-duplicated")
        assertEquals(listOf("a-b", "c_d"), docsAnnotationTags("@a-b @c_d", emptyList()), "hyphens and underscores")
        assertEquals(listOf("docs", "urgent"), docsAnnotationTags("@docs", listOf("urgent")), "supplied tags merge in")
        assertEquals(listOf("docs"), docsAnnotationTags("@docs", listOf("@docs")), "a supplied @docs is the same tag")
        assertEquals(listOf("a", "b", "c"), docsAnnotationTags("@c @a @b", emptyList()), "sorted, so filters are stable")

        // The lookbehind, which is the part that would quietly change meaning if someone
        // simplified the regex.
        assertEquals(emptyList(), docsAnnotationTags("write to marc@uchicago.edu", emptyList()))
        assertEquals(emptyList(), docsAnnotationTags("@@shout", emptyList()))
        assertEquals(emptyList(), docsAnnotationTags("@2fast @-dash @_score", emptyList()), "a tag starts with a letter")
        assertEquals(emptyList(), docsAnnotationTags("no tags here", emptyList()))
        assertEquals(listOf("tag"), docsAnnotationTags("(@tag)", emptyList()), "punctuation is not a word character")
        assertEquals(listOf("a".repeat(64)), docsAnnotationTags("@" + "a".repeat(80), emptyList()), "64 characters, then it stops")
    }

    @Test
    fun theStoreIsReplacedInOneStepAndLeavesNothingBehind() = withTempDir { dir ->
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
        val store = dir.resolve("nested").resolve("docs-annotations.json")

        writeDocsAnnotationsAtomically(store, json, listOf(annotation("first")))
        assertTrue(Files.exists(store), "the parent directory is created on demand")
        assertTrue("first" in Files.readString(store))

        writeDocsAnnotationsAtomically(store, json, listOf(annotation("second"), annotation("third")))
        val text = Files.readString(store)
        assertTrue("second" in text && "third" in text && "first" !in text, "a replacement, not an append")

        // The point of the exercise: the temp file lives in the destination's own
        // directory, so ATOMIC_MOVE is a rename within one filesystem rather than a
        // copy with a torn-file window — and nothing is left over when it succeeds.
        assertEquals(
            listOf(store.fileName.toString()),
            Files.list(store.parent).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() },
            "no stray docs-annotations-*.json temporaries",
        )
    }

    // ══════════════════════════════════════════════════════════════════════════

    private fun annotation(note: String) = DocsAnnotation(
        id = note, pageUrl = "https://openendedgroup.com/field4/index.html", note = note,
    )

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = createTempDirectory("docs-annotations-test-")
        try {
            block(dir)
        } finally {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private companion object {
        /** Shaped like a real minted token — 24 random bytes, hex — so length games look realistic. */
        const val TOKEN = "8f2c1a90b47e35d6c8a10f7be2049d3c5a6b7e8f90123456"
    }
}
