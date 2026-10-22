package afield.ui.output

import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.runtime.lifecycle.AnimationClock
import afield.runtime.lifecycle.Frames
import afield.runtime.lifecycle.Framer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [charGrid] and [charStream] run as a box runs them: the returned [Frames] pumped on a [Framer] the way
 * the Runloop pumps it, on a frame-indexed clock at the grid's own 24 fps, so one pump is one tick.
 */
class CharGridRunTest {
    private lateinit var session: SessionInformation
    private lateinit var box: Box
    private lateinit var token: AnimationClock.FrameToken

    @BeforeTest
    fun setUp() {
        val root = Box("root")
        session = SessionInformation(filename = "<chargrid>", documentName = "chargrid", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        box = Box("grid").also {
            it.inside = session
            session.boxes[it.id] = it
            session.root.children.add(it)
        }
        token = AnimationClock.beginFrameIndexed(24)
    }

    @AfterTest
    fun tearDown() = AnimationClock.endFrameIndexed(token)

    private fun pump(frames: Frames, pumps: Int) {
        val framer = Framer()
        val job = CoroutineScope(framer).launch { frames.body() }
        frames.job = job
        repeat(pumps) {
            framer.frame()
            AnimationClock.advance(token)
        }
        job.cancel()
        framer.frame()
    }

    @Test
    fun frameModeRunsTheBlockOnceATickWithPrevHoldingTheLastTick() {
        var grid: CharGridBuffer? = null
        val ticks = mutableListOf<Int>()
        val prevs = mutableListOf<Int>()
        val frames = box.charGrid(cols = 6, rows = 2, glyphs = " #") {
            grid = this.grid
            ticks += frame
            prevs += PREV(0, 0)
            SET(0, 0, frame % 2, "#50fa7b")
        }
        pump(frames, 5)
        assertEquals(listOf(0, 1, 2, 3, 4), ticks)
        assertEquals(listOf(0, 0, 1, 0, 1), prevs, "PREV(0, 0) is what the previous tick wrote")
        assertEquals(6, grid!!.cols)
        assertEquals("#50fa7b", grid.colorAt(0, 0))
    }

    @Test
    fun streamModeSetWaitsATickAndPrintDoesNot() {
        var grid: CharGridBuffer? = null
        val seen = mutableListOf<Int>()
        val frames = box.charStream(cols = 4, rows = 2, glyphs = CHARGRID_ASCII) {
            grid = this.grid
            print("hi")
            seen += frame
            SET(glyphIndex('!'))
            seen += frame
            seen += waitFrame()
            while (true) SET(glyphIndex('.'))
        }
        pump(frames, 6)
        assertEquals(listOf(0, 1, 2), seen, "print wrote at once, SET and waitFrame each took a tick")
        val g = grid!!
        assertEquals(listOf('h', 'i', '!', '.'), (0 until 4).map { CHARGRID_ASCII[g.get(it, 0)] })
        assertTrue(g.isShown(0, 1), "the cursor wrapped to the second row, one cell a tick")
    }
}
