package afield.ui.output

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reading a `web(...)` history file: titles join their visits, filters, and the link graph. */
class WebHistoryTest {

    private fun history(vararg lines: String): WebHistory {
        val file = File(Files.createTempDirectory("web-history-test-").toFile(), PROFILE_HISTORY_FILE)
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        return WebHistory(file)
    }

    private val sample = history(
        """{"type":"visit","id":"1","time":1000,"url":"https://en.wikipedia.org/wiki/Iframe","box":"b1","boxName":"Reader","channel":"web","status":200,"inPage":false}""",
        """{"type":"title","id":"1","title":"Iframe - Wikipedia"}""",
        """{"type":"visit","id":"2","time":2000,"url":"https://en.wikipedia.org/wiki/HTML","from":"https://en.wikipedia.org/wiki/Iframe","box":"b1","boxName":"Reader","channel":"web","status":200,"inPage":false}""",
        "not json at all",
        """{"type":"visit","id":"3","time":3000,"url":"https://github.com/","title":"GitHub","box":"b2","boxName":"Code","channel":"gh","status":200,"inPage":false}""",
        """{"type":"visit","id":"4","time":4000,"url":"https://en.wikipedia.org/wiki/Iframe","from":"https://en.wikipedia.org/wiki/HTML","box":"b1","boxName":"Reader","channel":"web","status":200,"inPage":false}""",
        """{"type":"visit","id":"5","time":5000,"url":"https://en.wikipedia.org/wiki/HTML","from":"https://en.wikipedia.org/wiki/Iframe","box":"b1","boxName":"Reader","channel":"web","status":200,"inPage":false}""",
    )

    @Test
    fun visitsComeBackOldestFirstWithTheirTitles() {
        val visits = sample.visits()
        assertEquals(listOf("1", "2", "3", "4", "5"), visits.map { it.id })
        assertEquals("Iframe - Wikipedia", visits[0].title)
        assertEquals("GitHub", visits[2].title)
        assertEquals(null, visits[0].from)
        assertEquals("en.wikipedia.org", visits[0].host)
        assertEquals(Instant.ofEpochMilli(2000), visits[1].time)
    }

    @Test
    fun filtersNarrowTheVisits() {
        assertEquals(listOf("1", "2", "4", "5"), sample.visits(host = "wikipedia.org").map { it.id })
        assertEquals(listOf("3"), sample.visits(box = "Code").map { it.id })
        assertEquals(listOf("3"), sample.visits(box = "b2").map { it.id })
        assertEquals(listOf("2", "3"), sample.visits(since = Instant.ofEpochMilli(2000), until = Instant.ofEpochMilli(4000)).map { it.id })
        assertEquals(listOf("1", "4"), sample.visits(contains = "iframe").map { it.id })
        assertEquals(listOf("4", "5"), sample.visits(limit = 2).map { it.id })
    }

    @Test
    fun linksCountEachStepFromOnePageToTheNext() {
        val links = sample.links()
        assertEquals(2, links["https://en.wikipedia.org/wiki/Iframe" to "https://en.wikipedia.org/wiki/HTML"])
        assertEquals(1, links["https://en.wikipedia.org/wiki/HTML" to "https://en.wikipedia.org/wiki/Iframe"])
        assertEquals(2, links.size)
    }

    @Test
    fun aMissingFileIsAnEmptyHistoryAndClearDeletesIt() {
        assertTrue(WebHistory(File("/nonexistent/field-history.jsonl")).visits().isEmpty())
        sample.clear()
        assertTrue(sample.visits().isEmpty())
    }
}
