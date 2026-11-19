package afield.io.drawingon

import afield.math.linalg.vec
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class DrawingOnCaptureTest {
    private val events = mutableListOf<JsonObject>()
    private fun event(t: Double, type: String, data: String) {
        events += buildJsonObject {
            put("v", 1); put("session", "capture"); put("seq", events.size + 1)
            put("t", t); put("type", type); put("data", drawingOnJson.parseToJsonElement(data))
        }
    }
    private fun sample(id: Int, t: Double, x: Double, y: Double, force: Double = 0.4) =
        """{"id":$id,"t":$t,"x":$x,"y":$y,"drawingX":$x,"drawingY":$y,"force":$force,"altitude":0.8,"roll":0.2}"""
    private fun begin(t: Double, id: String = "a", layer: String = "one", tool: String = "freehand") =
        event(t, "stroke.begin", """{"stroke":"$id","layer":"$layer","tool":"$tool"}""")
    private fun points(t: Double, id: String = "a", vararg samples: String) =
        event(t, "stroke.samples", """{"stroke":"$id","samples":[${samples.joinToString()}]}""")
    private fun layers(t: Double, visible: Boolean = true, solo: String? = null) = event(t, "layers.state",
        """{"layers":[{"id":"one","name":"Plan","visible":$visible},{"id":"two","name":"Notes","visible":true}],"active":"one","solo":${solo?.let { "\"$it\"" } ?: "null"}}""")
    private fun capture() = DrawingOnCapture { consume -> events.toList().forEach(consume) }
    private fun hover(t: Double, x: Double, phase: Int = 2) = event(t, "pencil.hover",
        """{"x":$x,"y":4.0,"drawingX":$x,"drawingY":4.0,"z":0.6,"phase":$phase,"altitude":0.9}""")

    @Test fun scrubDoesNotLeakCorrectionsUndoOrFutureSamplesAndCanRewind() {
        layers(0.0)
        begin(0.1)
        points(1.0, "a", sample(1,0.5,10.0,20.0), sample(2,0.9,30.0,40.0))
        event(1.1, "stroke.prediction", """{"stroke":"a","samples":[${sample(-1,1.3,999.0,999.0)}]}""")
        event(2.0, "stroke.correct", """{"stroke":"a","samples":[${sample(1,0.5,15.0,25.0,0.7)}]}""")
        event(3.0, "stroke.visibility", """{"stroke":"a","visible":false}""")
        val c = capture()
        assertTrue(c.drawingAt(0.2).isEmpty())
        assertEquals(10f, c.drawingAt(1.5).single().nodes.first().to.x)
        assertEquals(15f, c.drawingAt(2.5).single().nodes.first().to.x)
        assertTrue(c.drawingAt(3.0).isEmpty())
        assertEquals(10f, c.drawingAt(1.5).single().nodes.first().to.x)
        val raw = c.drawingSince(0.5).single()
        assertEquals(1, raw.nodes.size)
        assertEquals(0.9, raw.here.drawingOnSample!!.t)
        assertEquals(0.8, raw.here.drawingOnSample!!.altitude)
        assertTrue(c.drawingSince(0.0, visibleOnly = true).isEmpty())
        assertTrue(c.drawingSince(0.9).isEmpty(), "Exclusive lower bound avoids drawing a sample twice")
        assertFailsWith<IllegalArgumentException> { c.at(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { c.drawingSince(4.0,3.0) }
    }

    @Test fun layersAreIndependentOfScrubbingAndCanOverrideRecordedSolo() {
        layers(0.0)
        begin(0.1); points(0.2, "a", sample(1,0.2,1.0,2.0))
        begin(0.3,"b","two"); points(0.4,"b", sample(2,0.4,3.0,4.0))
        layers(1.0, false, "two")
        val c = capture()
        assertEquals(2, c.drawingAt(0.5).size)
        assertEquals(1, c.drawingAt(1.0).size)
        c.solo(null).layerVisible("one",true)
        assertEquals(2, c.drawingAt(1.0).size)
        c.layerVisible("two",false)
        assertEquals(listOf("a"), c.drawingAt(0.5).map { it.properties["drawingOn.stroke"] })
        assertEquals(1, c.drawingSince(0.0).size, "Local layer controls also filter analysis paths")
        c.resetLayers()
        assertEquals(1, c.drawingAt(1.0).size)
        assertEquals(listOf("Plan","Notes"), c.layers.map { it.name })
        c.solo("one").layerVisible("one",true)
        assertEquals(1, c.drawingAt(1.0).size)
    }

    @Test fun shapeReplayUsesIntentButIncrementalGraphsUseMeasuredSamples() {
        begin(0.0,tool="line")
        points(1.0,"a",sample(1,0.1,1.0,2.0),sample(2,0.5,50.0,75.0),sample(3,0.9,10.0,20.0))
        begin(1.1,"circle","one","circle")
        points(1.2,"circle",sample(4,1.1,100.0,100.0),sample(5,1.2,110.0,100.0))
        event(1.3,"stroke.end","""{"stroke":"circle","cancelled":true}""")
        val c = capture()
        assertEquals(2, c.drawingAt(1.0).single().nodes.size)
        assertEquals(3, c.drawingSince(0.0,1.0).single().nodes.size)
        val circle = c.drawingAt(1.2).last()
        assertEquals(110f,circle.nodes.first().to.x)
        assertTrue(circle.nodes.last() is afield.geometry.path.FLine.Close)
        assertEquals(1,c.drawingAt(1.3).size)
        val graph = c.drawingSince(0.0,1.0) { vec(it.t * 100, (it.force ?: 0.0)*200,0) }.single()
        assertEquals(10f,graph.nodes.first().to.x)
        assertEquals(80f,graph.nodes.first().to.y)
        assertEquals(1,graph.nodes.first().drawingOnSample!!.id)
        graph.nodes.first().to.x = 999f
        assertEquals(1f,c.drawingAt(1.0).single().nodes.first().to.x, "Returned geometry is independent")
    }

    @Test fun hoverIsSeparateAndBreaksAtRangeContactNavigationGapsAndWindowEdges() {
        layers(0.0)
        hover(0.1,1.0,1); hover(0.2,2.0); hover(0.21,3.0,3)
        hover(0.3,4.0,1); hover(0.4,5.0)
        begin(0.45)
        hover(0.5,6.0); hover(1.0,7.0)
        event(1.05,"canvas.transform","""{"transform":{"a":1,"d":1,"tx":10}}""")
        hover(1.1,8.0)
        val c = capture()
        val paths = c.hoverSince(0.0)
        assertEquals(listOf(2,2,1,1,1),paths.map { it.nodes.size })
        assertEquals(0.6,paths.first().here.drawingOnSample!!.z)
        assertNull(paths.first().here.drawingOnSample!!.force)
        assertTrue(c.drawingSince(0.0).isEmpty())
        val recent = c.hoverSince(0.1,0.2).single()
        assertEquals(1,recent.nodes.size)
        assertEquals(2f,recent.here.to.x)
        c.layerVisible("one",false)
        assertTrue(c.hoverSince(0.0).isEmpty())
    }

    @Test fun refreshAddsLiveInputWithoutDuplicationAndFileReaderKeepsViewport() {
        val c = capture()
        assertEquals(0.0,c.duration)
        layers(0.0)
        begin(0.1); points(0.2,"a",sample(1,0.2,20.0,30.0))
        event(0.3,"canvas.viewport","""{"drawingCorners":[[10,20],[110,20],[110,220],[10,220]],"transform":{"a":2,"d":2}}""")
        event(0.4,"canvas.appearance","""{"light":true}""")
        event(5.0,"audio.buffer","""{"base64":"unused"}""")
        c.refresh().refresh()
        assertEquals(5.0,c.duration)
        assertEquals(1,c.drawingSince(0.0).single().nodes.size)
        val file = Files.createTempFile("drawingon-capture",".ndjson").toFile()
        try {
            file.writeText(events.joinToString("\n",postfix="\n"))
            val fromFile = readDrawingOn(file)
            val frame = fromFile.at(1.0)
            assertTrue(frame.light)
            assertEquals(5,frame.viewport!!.nodes.size)
            assertEquals(10f,frame.viewport.nodes.first().to.x)
            assertEquals(2.0,frame.transform!!["a"]!!.jsonPrimitive.double)
            points(6.0,"a",sample(2,6.0,40.0,50.0))
            val next = events.last().toString()
            file.appendText(next.take(next.length / 2))
            fromFile.refresh()
            assertEquals(5.0,fromFile.duration, "A partial live record is not yet visible")
            file.appendText(next.drop(next.length / 2) + "\n")
            fromFile.refresh().refresh()
            assertEquals(1,fromFile.drawingSince(5.0).single().nodes.size)
            assertEquals(6.0,fromFile.duration)
        } finally { file.delete() }
    }
}
