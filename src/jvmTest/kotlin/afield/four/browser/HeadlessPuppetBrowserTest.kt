package afield.four.browser

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.AfterClass
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The first test of the puppet browser that is actually a browser.
 *
 * Everything before this asserted on message *shapes* — `toCdpJson`, `puppetBrowserState`, the
 * mailbox's conflation rules — which is worth having and proves nothing about whether Chromium
 * ever received, understood or acted on any of it. This one spawns the real producer, loads a
 * committed `file://` fixture, and asserts on values it read back out of the live DOM.
 *
 * No GPU and no network, which is the whole point: [HeadlessPuppetBrowser] runs the producer in its
 * no-frames mode, so this is a gate that can run on a build machine with no display, no Vulkan and
 * no route to the internet. The protocol under test is byte-for-byte the one the textured browser
 * speaks; only the paint handler differs.
 *
 * If Electron is not installed the class skips, loudly, and says which command installs it. Any
 * other failure — a producer that starts and then misbehaves — is a red test, not a skip: the
 * `@BeforeClass` constructs the browser, so a broken one fails the class rather than quietly
 * passing it.
 */
class HeadlessPuppetBrowserTest {

    @get:Rule
    val perTestTimeout: Timeout = Timeout.seconds(120)

    @Before
    fun requireBrowser() {
        Assume.assumeTrue(unavailable ?: "", unavailable == null)
        // The constructor's load is in flight when the first test starts; navigating across it is a
        // real race that `navigate` now absorbs, and `navigating immediately after construction`
        // below is the test that pins it. Every *other* test wants a settled page, not that race.
        browser().loaded.get(30, TimeUnit.SECONDS)
        // Every test starts from the committed fixture, so nothing here depends on JUnit's method
        // order or on what the test before it typed into the page.
        browser().navigate(fixtureUrl).get(30, TimeUnit.SECONDS)
    }

    @Test
    fun `the producer loads a local file and reports its title`() {
        val state = browser().loaded.get(30, TimeUnit.SECONDS)
        assertTrue(state.url.endsWith("fixture.html"), "loaded state url was ${state.url}")

        val title = value("document.title")
        assertEquals("Field puppet fixture", title.jsonPrimitive.content)
        assertEquals("Field puppet fixture", browser().state.title)
        assertEquals(false, browser().state.crashed)
    }

    @Test
    fun `evaluate returns a copied value rather than a handle`() {
        val result = browser()
            .evaluate("document.querySelectorAll('#readings tbody tr').length")
            .get(15, TimeUnit.SECONDS)

        assertEquals("number", result.type)
        assertEquals(3, result.value.jsonPrimitive.int)
        assertEquals(null, result.objectId, "VALUE mode must not retain a remote object")
    }

    @Test
    fun `evaluate surfaces a page exception instead of undefined`() {
        val error = failureOf(browser().evaluate("notDefinedAnywhere.reach()"))
        val message = error.message ?: ""
        assertContains(message, "ReferenceError")
        assertContains(message, "notDefinedAnywhere")
        assertTrue(error is PuppetBrowserCommandException, "got ${error::class.simpleName}: $message")
    }

    @Test
    fun `evaluate awaits a promise that has not settled yet`() {
        val started = System.nanoTime()
        val value = value("new Promise((resolve) => setTimeout(() => resolve('late-value'), 600))")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("late-value", value.jsonPrimitive.content)
        // The floor is the assertion that matters: without awaitPromise this returns a Promise
        // object immediately and the elapsed time collapses. Measured around 610-650ms here.
        assertTrue(elapsedMs >= 550, "awaiting the promise took only ${elapsedMs}ms")
    }

    @Test
    fun `the isolated world shares the DOM and not the page's globals`() {
        assertEquals(
            "main-world-only",
            value("window.fixtureSecret", BrowserJavascriptWorld.MAIN).jsonPrimitive.content,
        )
        assertEquals(
            "undefined",
            value("typeof window.fixtureSecret", BrowserJavascriptWorld.ISOLATED).jsonPrimitive.content,
        )
        assertEquals(
            "Pages as data",
            value(
                "document.getElementById('headline').textContent",
                BrowserJavascriptWorld.ISOLATED,
            ).jsonPrimitive.content,
        )
    }

    @Test
    fun `a page request round trips through Field and back into the page`() {
        val seen = CompletableFuture<JsonElement>()
        val remove = browser().onPageRequest("sum") { payload ->
            seen.complete(payload)
            val total = payload.jsonObject["a"]!!.jsonPrimitive.int + payload.jsonObject["b"]!!.jsonPrimitive.int
            CompletableFuture.completedFuture<JsonElement>(JsonPrimitive(total))
        }
        try {
            val answer = value("window.fieldPuppet.request('sum', { a: 20, b: 22 })")
            assertEquals(42, answer.jsonPrimitive.int)
            assertEquals(
                buildJsonObject { put("a", 20); put("b", 22) },
                seen.get(5, TimeUnit.SECONDS),
                "Field saw a different payload than the page sent",
            )
        } finally {
            remove()
        }

        // And the unhappy half: an unregistered name must reach the page as a rejection carrying
        // Field's own explanation, not as a silent null.
        val rejection = value(
            "window.fieldPuppet.request('nobodyHandlesThis', null).then(() => 'resolved', (e) => e.message)"
        )
        assertContains(rejection.jsonPrimitive.content, "No Field handler is registered for 'nobodyHandlesThis'")
    }

    @Test
    fun `inserted text and a dispatched key event both change the DOM`() {
        val browser = browser()
        browser.focus().get(10, TimeUnit.SECONDS)
        assertEquals(
            "entry",
            value("(document.getElementById('entry').focus(), document.activeElement.id)").jsonPrimitive.content,
        )

        browser.insertText("hello")
        browser.key(
            BrowserKeyEvent(
                type = BrowserKeyEventType.KEY_DOWN,
                key = "Backspace",
                code = "Backspace",
                windowsVirtualKeyCode = 8,
            )
        )
        browser.key(
            BrowserKeyEvent(
                type = BrowserKeyEventType.KEY_UP,
                key = "Backspace",
                code = "Backspace",
                windowsVirtualKeyCode = 8,
            )
        )
        // The barrier. `insertText` and `key` are fire-and-forget posts with no reply of their own,
        // so without this the read below races the renderer and the value is whatever arrived first.
        browser.flushInput().get(20, TimeUnit.SECONDS)

        assertEquals("hell", value("document.getElementById('entry').value").jsonPrimitive.content)
        // The page's own input listener ran, so this is the DOM reacting rather than CDP reporting.
        assertEquals("hell", value("document.getElementById('echo').textContent").jsonPrimitive.content)
        // insertText produces no key events; the dispatched Backspace produces exactly one keydown.
        assertEquals("Backspace", value("document.getElementById('keys').textContent").jsonPrimitive.content)
    }

    @Test
    fun `flushInput settles a coalesced pointer stream before the page is read`() {
        val browser = browser()
        value(
            """
            (() => {
                window.moves = []
                document.addEventListener('mousemove', (e) => window.moves.push([e.clientX, e.clientY]))
                return true
            })()
            """.trimIndent()
        )

        repeat(24) { step ->
            browser.pointer(
                BrowserPointerEvent(
                    type = BrowserPointerEventType.MOVE,
                    x = 10.0 + step * 5,
                    y = 20.0 + step,
                )
            )
        }
        browser.flushInput().get(30, TimeUnit.SECONDS)

        // Motion is deliberately coalesced (see queueInput in the producer), so the count is not
        // pinned — but the newest move must have been delivered, and it must be the last one.
        val last = value("window.moves.length ? window.moves[window.moves.length - 1] : null")
        assertEquals("[125,43]", last.toString())
        val count = value("window.moves.length").jsonPrimitive.int
        assertTrue(count in 1..24, "coalescing delivered $count of 24 moves")
    }

    @Test
    fun `a raw CDP command reaches the same tab`() {
        val tree = browser().cdp("Page.getFrameTree").get(15, TimeUnit.SECONDS)
        val url = tree.jsonObject["frameTree"]!!.jsonObject["frame"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(url.endsWith("/fixture.html"), "CDP reported frame url $url")

        val version = browser().cdp("Browser.getVersion").get(15, TimeUnit.SECONDS)
        assertContains(version.jsonObject["product"]!!.jsonPrimitive.content, "Chrome/")
    }

    @Test
    fun `navigation and history move between two local documents`() {
        val browser = browser()
        val second = File(fixtures, "second.html").toURI().toString()

        val forward = browser.navigate(second).get(30, TimeUnit.SECONDS)
        assertTrue(forward.url.endsWith("second.html"), "navigate reported ${forward.url}")
        assertEquals("Second page", value("document.getElementById('headline').textContent").jsonPrimitive.content)
        assertEquals(true, browser.state.canGoBack)

        browser.goBack().get(30, TimeUnit.SECONDS)
        waitUntil("document.getElementById('headline') && document.title === 'Field puppet fixture'")
        assertEquals("Pages as data", value("document.getElementById('headline').textContent").jsonPrimitive.content)
    }

    @Test
    fun `constructing and immediately navigating to the same page works`() {
        // The constructor starts a load and does not await it, so a navigation issued straight
        // afterwards races it: Chromium aborts the in-flight load and Electron reports that abort
        // (net error -3) against the *new* `loadURL`, which then rejects for a page that was about
        // to load perfectly well. Seen once, on this suite's own first assertion, 2026-08-29, and
        // fixed by making `navigate` wait for the un-awaited first load to settle.
        //
        // Read this as a smoke, not as a pin. Removing that fix again leaves this test green: the
        // window is narrow, it needs the abort event to land after the second `loadURL` installed
        // its listeners, and neither this shape nor a deliberately slow first page reproduces it on
        // demand. The worklog records both attempts. What it does still catch is the *loud* form of
        // the same failure — a navigation immediately after construction that does not arrive.
        val racer = HeadlessPuppetBrowser(url = fixtureUrl, width = 400, height = 300, projectRoot = root)
        try {
            val state = racer.navigate(fixtureUrl).get(30, TimeUnit.SECONDS)
            assertTrue(state.url.endsWith("fixture.html"), "navigate reported ${state.url}")
            assertEquals(
                "Pages as data",
                racer.evaluate("document.getElementById('headline').textContent")
                    .get(15, TimeUnit.SECONDS).value.jsonPrimitive.content,
            )
        } finally {
            racer.close()
        }
    }

    @Test
    fun `a closed browser refuses commands instead of hanging`() {
        val shortLived = HeadlessPuppetBrowser(url = fixtureUrl, width = 400, height = 300, projectRoot = root)
        shortLived.loaded.get(30, TimeUnit.SECONDS)
        shortLived.close()

        val error = failureOf(shortLived.evaluate("1 + 1"))
        assertTrue(error is PuppetBrowserCommandException, "got ${error::class.simpleName}: ${error.message}")
        assertContains(error.message ?: "", "closed")
    }

    /** Waits in the page rather than in Kotlin, so the wait costs exactly one round trip. */
    private fun waitUntil(condition: String, timeoutMillis: Long = 10_000) {
        value(
            """
            new Promise((resolve, reject) => {
                const deadline = Date.now() + $timeoutMillis
                const poll = () => {
                    try {
                        if ($condition) return resolve(true)
                    } catch (error) {
                        return reject(error)
                    }
                    if (Date.now() > deadline) return reject(new Error("timed out waiting for: $condition"))
                    setTimeout(poll, 25)
                }
                poll()
            })
            """.trimIndent(),
            timeoutMillis = timeoutMillis + 2_000,
        )
    }

    private fun value(
        javascript: String,
        world: BrowserJavascriptWorld = BrowserJavascriptWorld.MAIN,
        timeoutMillis: Long = 10_000,
    ): JsonElement = browser()
        .evaluate(javascript, world = world, timeoutMillis = timeoutMillis)
        .get(timeoutMillis + 10_000, TimeUnit.MILLISECONDS)
        .value

    private fun failureOf(future: CompletableFuture<*>): Throwable = try {
        future.get(15, TimeUnit.SECONDS)
        fail("expected the evaluation to fail")
    } catch (execution: ExecutionException) {
        execution.cause ?: execution
    }

    private fun browser(): HeadlessPuppetBrowser = shared ?: fail("no headless browser")

    companion object {
        private val root = locateFieldProjectRoot()
        private val fixtures = File(root, "src/jvmTest/resources/puppetBrowser")
        private val fixtureUrl = File(fixtures, "fixture.html").toURI().toString()

        private var shared: HeadlessPuppetBrowser? = null
        private var unavailable: String? = null

        @BeforeClass
        @JvmStatic
        fun startBrowser() {
            unavailable = HeadlessPuppetBrowser.unavailableReason(root)
            if (unavailable != null) {
                // Loud on purpose. A suite that goes green because it did not run is worse than one
                // that is red, and a one-word "skipped" in a thousand-test report is invisible.
                System.err.println(
                    """
                    |
                    |================================================================
                    | SKIPPING HeadlessPuppetBrowserTest — the browser stack is NOT
                    | being tested in this run.
                    |   $unavailable
                    |================================================================
                    |
                    """.trimMargin()
                )
                return
            }
            shared = HeadlessPuppetBrowser(url = fixtureUrl, width = 900, height = 600, projectRoot = root)
        }

        @AfterClass
        @JvmStatic
        fun stopBrowser() {
            shared?.close()
            shared = null
        }
    }
}
