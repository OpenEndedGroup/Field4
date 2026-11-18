package afield.io.drawingon

import afield.geometry.path.FLine
import afield.math.linalg.vec
import kotlinx.serialization.json.*
import kotlin.test.*

class DrawingOnNetworkTest {
    private fun stroke(id: String, vararg xy: Pair<Int, Int>, layer: String = "plan", tool: String = "freehand") =
        DrawingOnNetworkStroke(id, layer, tool, xy.map { (x, y) -> vec(x, y, 0) })

    @Test fun consolidatesRetracingButPreservesWallsGapsAndUnjoinedCrossings() {
        val graph = fitDrawingOnNetwork(listOf(
            stroke("wall", 0 to 0, 45 to 0, 100 to 0),
            stroke("retrace", 100 to 0, 50 to 1, 0 to 0),
            stroke("parallel", 0 to 3, 100 to 3),
            stroke("door-gap", 112 to 0, 140 to 0),
            stroke("crossing", 70 to -20, 70 to 20),
            stroke("other-layer", 0 to 0, 100 to 0, layer = "markup")
        ))
        assertEquals(5, graph.edges.size)
        assertEquals(10, graph.nodes.size)
        assertEquals(listOf("wall", "retrace"), graph.edges.first().strokeIds)
        assertEquals(6, graph.strokeStripScap().lineSequence().count { it == "{" })
        assertEquals(graph.edges.size + graph.nodes.size, graph.toFLines(showNodes = true).size)
    }

    @Test fun splitsAtTeeWithSharedNodeAndKeepsExactTools() {
        val wall = stroke("wall", 0 to 0, 50 to 10, 100 to 0, tool = "line")
        val stem = stroke("stem", 40 to 40, 40 to 2)
        val circle = stroke("circle", 50 to 50, 60 to 50, tool = "circle")
        val before = fitDrawingOnNetwork(listOf(wall))
        val graph = fitDrawingOnNetwork(listOf(wall, stem, circle))
        val split = graph.edges.filter { it.strokeIds == listOf("wall") }
        val branch = graph.edges.single { it.id == "stem" }
        assertEquals(2, split.size)
        assertEquals(before.edges.single().id, split.first().id)
        assertEquals(branch.to, split.first().to)
        assertEquals(branch.to, split.last().from)
        val junction = graph.nodes.single { it.id == branch.to }
        assertEquals(40f, junction.x)
        assertEquals(0f, junction.y)
        assertEquals(vec(40, 0, 0), split.first().line.nodes.last().to)
        val loop = graph.edges.single { it.id == "circle" }
        assertEquals(loop.from, loop.to)
        assertTrue(loop.line.nodes.last() is FLine.Close)
        assertEquals(vec(60, 50, 0), loop.line.nodes.first().to)
    }

    @Test fun replayUsesPenLiftCorrectionsAndUndo() {
        val events = mutableListOf<JsonObject>()
        fun add(t: Double, type: String, data: String) {
            events += buildJsonObject {
                put("seq", events.size + 1); put("t", t); put("type", type)
                put("data", drawingOnJson.parseToJsonElement(data))
            }
        }
        add(0.0, "stroke.begin", """{"stroke":"a","layer":"plan","tool":"line"}""")
        add(0.2, "stroke.samples", """{"stroke":"a","samples":[{"id":1,"x":0,"y":0,"t":0.1},{"id":2,"x":30,"y":0,"t":0.2}]}""")
        add(0.3, "stroke.end", """{"stroke":"a"}""")
        add(0.4, "stroke.correct", """{"stroke":"a","samples":[{"id":2,"x":40,"y":0,"t":0.2}]}""")
        add(0.5, "stroke.visibility", """{"stroke":"a","visible":false}""")
        val take = DrawingOnCapture { use -> events.forEach(use) }
        assertTrue(take.networkAt(0.25).edges.isEmpty())
        assertEquals(30f, take.networkAt(0.35).edges.single().line.nodes.last().to.x)
        assertEquals(40f, take.networkAt(0.45).edges.single().line.nodes.last().to.x)
        assertTrue(take.networkAt().edges.isEmpty())
        assertTrue(take.layerVisible("plan", false).networkAt(0.45).edges.isEmpty())
        assertEquals(1, take.resetLayers().networkAt(0.45).edges.size)
    }
}
