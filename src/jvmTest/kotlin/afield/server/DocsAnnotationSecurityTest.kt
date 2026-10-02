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
 * The access-control predicates behind `/api/docs-annotations` and the documentation
 * mirror, which the revision pass (`docsPass.kt`) reuses: annotation text is the prompt the
 * pass hands coding agents with write access to a checkout, and all of it is installed
 * alongside an SSL connector bound to `0.0.0.0`. The tests for the agent-spawning
 * `POST /api/docs-improvement` were removed with that route on 2026-10-02.
 *
 * Every test here was watched to fail against the pre-change behaviour. That is not a
 * figure of speech: each guard was locally reverted to what it replaced (for the
 * annotation routes, "return null" — permit everything; for the proxy,
 * `relative.contains("..")` against the raw path), the suite was run, and the failures
 * were recorded. A security test nobody has seen fail is a test that guards nothing.
 * If you change a guard, do the same again rather than trusting the green.
 *
 * The [DocsAccessPolicy] argument is what makes that cheap. The policy is a value, so
 * "the hatch shut" and "the hatch open" are both just arguments, and the tests do not
 * need a running Ktor application to say anything true.
 */
class DocsAnnotationSecurityTest {

    /** Exactly what a Field serves with no configuration at all. */
    private val default = DocsAccessPolicy()

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
     * loopback, neither `127.0.0.1` nor `::1`. Confirmed by the server's own log line from
     * the since-removed `POST /api/docs-improvement`,
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

    @Test
    fun aFieldHostIsLocalhostOrAnIpLiteralAndNeverADnsName() {
        for (authority in listOf(
            "localhost", "LOCALHOST:9092", "127.0.0.1", "127.0.0.1:9092",
            "192.168.2.125:9092", "[::1]", "[::1]:9092", "[fe80::1]:9092",
        )) {
            assertTrue(isAllowedFieldHost(authority), "$authority must remain a valid Field authority")
        }
        for (authority in listOf(
            null, "", "field.attacker.example", "field.attacker.example:19092",
            "localhost.attacker.example", "127.0.0.1.nip.io", "::1:9092",
            "localhost:", "localhost:0", "localhost:65536", "localhost:not-a-port",
            "[::1]trailing", "[::1]:not-a-port",
        )) {
            assertFalse(isAllowedFieldHost(authority), "'$authority' must not become a trusted Host")
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
        // The empty string is what `AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE=` produces, and "off" is the
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
    // Where the revision pass is allowed to run
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

    @Test
    fun theAnnotationStoreCanBeIsolatedWithoutOrphaningTheDefaultNotebook() = withTempDir { dir ->
        val home = Files.createDirectory(dir.resolve("home"))
        val checkout = Files.createDirectory(dir.resolve("a checkout"))
        val explicit = dir.resolve("isolated notes.json")

        assertEquals(
            home.resolve(".afield/docs-annotations.json").toAbsolutePath().normalize(),
            resolveDocsAnnotationStorePath(null, null, home, checkout),
            "unset preserves the human's existing shared notebook",
        )
        assertEquals(
            checkout.resolve(".field-backdoor/docs-annotations.json").toAbsolutePath().normalize(),
            resolveDocsAnnotationStorePath(null, " .field-backdoor/docs-annotations.json ", home, checkout),
            "a relative environment override is checkout-local",
        )
        assertEquals(
            explicit.toAbsolutePath().normalize(),
            resolveDocsAnnotationStorePath(explicit.toString(), "ignored.json", home, checkout),
            "the JVM property is the more specific override",
        )
        assertEquals(
            checkout.resolve("from-env.json").toAbsolutePath().normalize(),
            resolveDocsAnnotationStorePath("   ", "from-env.json", home, checkout),
            "a blank property defers to the environment",
        )
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
    // The local build the mirror prefers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun theLocalMirrorRefusesEveryWayOutOfItsDirectory() {
        val root = createTempDirectory("docs-mirror").toRealPath()
        Files.writeString(root.resolve("index.html"), "<html><head></head>local</html>")
        val outside = Files.createDirectory(root.parent.resolve("${root.fileName}-outside"))
        Files.writeString(outside.resolve("secret.html"), "not documentation")
        // The same attacks the proxy is pinned against, now against a filesystem, where
        // they would read a file rather than fetch a page.
        for (attack in listOf(
            "/field-docs/../${outside.fileName}/secret.html",
            "/field-docs/%2e%2e/${outside.fileName}/secret.html",
            "/field-docs/..%2f${outside.fileName}%2fsecret.html",
            "/field-docs/a\\..\\b",
        )) {
            assertNull(docsMirrorFile(root, attack), "$attack must be refused")
        }
        // A symlink is the way out the textual checks cannot see, so it is checked on
        // the file that was actually found rather than on the path that was asked for.
        Files.createSymbolicLink(root.resolve("escape.html"), outside.resolve("secret.html"))
        assertNull(docsMirrorFile(root, "/field-docs/escape.html"), "a symlink out of the build is out of the build")
    }

    @Test
    fun theLocalMirrorServesTheBuildItIsFor() {
        val root = createTempDirectory("docs-mirror").toRealPath()
        Files.writeString(root.resolve("index.html"), "<html><head></head>home</html>")
        Files.writeString(root.resolve("rectsl.html"), "<html><head></head>rectsl</html>")
        val nested = Files.createDirectories(root.resolve("reference"))
        Files.writeString(nested.resolve("index.html"), "<html><head></head>reference</html>")
        // Both spellings of the root, and a directory, resolve to an index.html — the
        // published site's behaviour, which the pages' relative links assume.
        assertEquals(root.resolve("index.html"), docsMirrorFile(root, "/field-docs/"))
        assertEquals(root.resolve("index.html"), docsMirrorFile(root, "/field-docs"))
        assertEquals(nested.resolve("index.html"), docsMirrorFile(root, "/field-docs/reference/"))
        assertEquals(nested.resolve("index.html"), docsMirrorFile(root, "/field-docs/reference"))
        assertEquals(root.resolve("rectsl.html"), docsMirrorFile(root, "/field-docs/rectsl.html"))
        // A page the local build does not have is a miss, not an error: in AUTO that is
        // precisely the case that falls through to the published site.
        assertNull(docsMirrorFile(root, "/field-docs/not-built-yet.html"))
    }

    @Test
    fun theMirrorSourceIsAutoUnlessSomebodySaidOtherwise() {
        assertEquals(DocsMirrorSource.AUTO, resolveDocsMirrorSource(null, null))
        assertEquals(DocsMirrorSource.AUTO, resolveDocsMirrorSource(null, ""), "an exported-but-empty variable is not a choice")
        assertEquals(DocsMirrorSource.AUTO, resolveDocsMirrorSource(null, "localhost"), "an unrecognised value must not break the route")
        assertEquals(DocsMirrorSource.LOCAL, resolveDocsMirrorSource(null, "LOCAL"))
        assertEquals(DocsMirrorSource.UPSTREAM, resolveDocsMirrorSource(null, " upstream "))
        assertEquals(
            DocsMirrorSource.UPSTREAM,
            resolveDocsMirrorSource("upstream", "local"),
            "a -D flag on this JVM beats a variable from a login shell",
        )
    }

    @Test
    fun theMirrorDirectoryDefaultsToThisCheckoutsBuildAndNeedNotExistYet() {
        val checkout = createTempDirectory("checkout")
        assertEquals(
            checkout.resolve(DEFAULT_DOCS_MIRROR_DIRECTORY).toAbsolutePath().normalize(),
            resolveDocsMirrorDirectory(null, checkout),
        )
        assertEquals(
            checkout.resolve("elsewhere").toAbsolutePath().normalize(),
            resolveDocsMirrorDirectory(checkout.resolve("elsewhere").toString(), checkout),
        )
        // Deliberately not `toRealPath`: neither of the paths above exists, because a
        // docs build that finishes an hour after Field started must be picked up by the
        // next request rather than by the next restart.
        assertFalse(Files.exists(resolveDocsMirrorDirectory(null, checkout)), "resolution must not require a built site")
        // A relative override is resolved from Field's own working directory, the same
        // rule the annotation store follows, so `.field-backdoor/…` means what automation
        // expects it to mean.
        assertEquals(
            Path.of("some/other/build").toAbsolutePath().normalize(),
            resolveDocsMirrorDirectory("some/other/build", checkout),
        )
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
}
