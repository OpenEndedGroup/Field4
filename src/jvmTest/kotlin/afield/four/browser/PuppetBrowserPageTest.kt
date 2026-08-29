package afield.four.browser

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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The page-as-data layer, against the same committed `file://` fixture and the same GPU-free,
 * network-free browser as [HeadlessPuppetBrowserTest].
 *
 * Hermetic on purpose. Every one of these queries is the kind of thing you would otherwise verify
 * against a live site, and a test that reaches a live site measures that site's uptime.
 */
class PuppetBrowserPageTest {

    @get:Rule
    val perTestTimeout: Timeout = Timeout.seconds(90)

    @Before
    fun requireBrowser() {
        Assume.assumeTrue(unavailable ?: "", unavailable == null)
        browser().loaded.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun `text reads one element and null for one that is not there`() {
        assertEquals("Pages as data", await(browser().text("#headline")))
        assertEquals("Second page", await(browser().text("#link")))
        assertNull(await(browser().text("#definitelyNotHere")))
    }

    @Test
    fun `texts reads every match in document order`() {
        assertEquals(listOf("first note", "second note"), await(browser().texts("p.note")))
        assertEquals(
            listOf("alpha", "12", "301"),
            await(browser().texts("#readings tbody tr:first-child td")),
        )
        assertEquals(emptyList(), await(browser().texts(".nothing-matches-this")))
    }

    @Test
    fun `attribute reads the markup rather than the resolved property`() {
        // The property `href` would be the absolute file:// URL Chromium resolved; the attribute is
        // what the page actually wrote. Asserting the relative form is what distinguishes them.
        assertEquals("second.html", await(browser().attribute("#link", "href")))
        assertEquals("internal", await(browser().attribute("#link", "data-kind")))
        assertNull(await(browser().attribute("#link", "data-absent")))
        assertNull(await(browser().attribute("#definitelyNotHere", "href")))
    }

    @Test
    fun `count counts`() {
        assertEquals(3, await(browser().count("#readings tbody tr")))
        assertEquals(2, await(browser().count("p.note")))
        assertEquals(0, await(browser().count("blockquote")))
    }

    @Test
    fun `a table arrives as a header and rows a plot could use`() {
        val readings = await(browser().table("#readings"))

        assertEquals(listOf("station", "depth", "count"), readings.header)
        assertEquals(
            listOf(
                listOf("alpha", "12", "301"),
                listOf("beta", "48", "117"),
                listOf("gamma", "93", "4"),
            ),
            readings.rows,
        )
        assertEquals(listOf(12.0, 48.0, 93.0), readings.numbers("depth"))
        assertEquals(listOf("alpha", "beta", "gamma"), readings.column("station"))
        // The shape a Plotly or FLine box actually wants: two parallel numeric columns.
        assertEquals(
            listOf(12.0 to 301.0, 48.0 to 117.0, 93.0 to 4.0),
            readings.numbers("depth").zip(readings.numbers("count")).map { it.first!! to it.second!! },
        )
    }

    @Test
    fun `a table with no thead still finds its header, and one with no header has none`() {
        val plain = await(browser().table("#plain"))
        assertEquals(listOf("name", "value"), plain.header)
        assertEquals(listOf(listOf("one", "1,024"), listOf("two", "not a number")), plain.rows)
        // A thousands separator parses; a cell that is not a number becomes null and not zero.
        assertEquals(listOf(1024.0, null), plain.numbers("value"))

        val headerless = await(browser().table("#headerless"))
        assertEquals(emptyList(), headerless.header)
        assertEquals(listOf(listOf("x", "y"), listOf("p", "q")), headerless.rows)
    }

    @Test
    fun `waitFor returns once the late element arrives and reports the deadline otherwise`() {
        // The fixture appends #late 400ms after load, so this is a real wait and not a formality.
        browser().navigate(fixtureUrl).get(30, TimeUnit.SECONDS)
        assertNull(await(browser().text("#late")), "the late element should not be there yet")

        val started = System.nanoTime()
        browser().waitFor("document.getElementById('late') !== null").get(20, TimeUnit.SECONDS)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("arrived", await(browser().text("#late")))
        assertTrue(elapsedMs >= 100, "waitFor returned in ${elapsedMs}ms, before the element existed")

        val timedOut = failureOf(browser().waitFor("document.getElementById('neverAppears')", timeoutMillis = 500))
        assertContains(timedOut.message ?: "", "waitFor timed out after 500ms")
    }

    @Test
    fun `waitFor fails immediately on a condition that throws rather than waiting out the clock`() {
        val started = System.nanoTime()
        val error = failureOf(browser().waitFor("noSuchGlobal.field", timeoutMillis = 8_000))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertContains(error.message ?: "", "noSuchGlobal")
        assertTrue(elapsedMs < 4_000, "a broken condition took ${elapsedMs}ms, so it was retried")
    }

    @Test
    fun `a selector containing quotes and backslashes is not an injection`() {
        // The interpolation is the part that is easy to get wrong; this asserts the escaping works
        // both for a selector with a quote in it and for content that contains one.
        assertEquals("""he said "hello" \ goodbye""", await(browser().text("p[class=\"quote\"]")))
        assertEquals(1, await(browser().count("[data-kind='internal']")))
    }

    @Test
    fun `the queries read the DOM from an isolated world`() {
        // The page's own globals are invisible to the extraction world, so a page that shadowed
        // querySelectorAll could not change what these read. Proven by the fact that the fixture's
        // main-world global is unreachable from the same world the queries use.
        val secret = browser()
            .evaluate("typeof window.fixtureSecret", world = BrowserJavascriptWorld.ISOLATED)
            .get(15, TimeUnit.SECONDS)
        assertEquals("undefined", secret.value.toString().trim('"'))
        assertEquals("Pages as data", await(browser().text("#headline")))
    }

    private fun <T> await(future: CompletableFuture<T>): T = future.get(20, TimeUnit.SECONDS)

    private fun failureOf(future: CompletableFuture<*>): Throwable = try {
        future.get(30, TimeUnit.SECONDS)
        fail("expected the query to fail")
    } catch (execution: ExecutionException) {
        execution.cause ?: execution
    }

    private fun browser(): HeadlessPuppetBrowser = shared ?: fail("no headless browser")

    companion object {
        private val root = locateFieldProjectRoot()
        private val fixtureUrl =
            File(root, "src/jvmTest/resources/puppetBrowser/fixture.html").toURI().toString()

        private var shared: HeadlessPuppetBrowser? = null
        private var unavailable: String? = null

        @BeforeClass
        @JvmStatic
        fun startBrowser() {
            unavailable = HeadlessPuppetBrowser.unavailableReason(root)
            if (unavailable != null) {
                System.err.println(
                    """
                    |
                    |================================================================
                    | SKIPPING PuppetBrowserPageTest — the page-as-data layer is NOT
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
