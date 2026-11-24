package afield.data.query.graph

import afield.geometry.path.FLine
import afield.math.linalg.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `::ClassName` type tests in graphPaths patterns. */
class GraphTypeTestTest {
    interface Shape
    open class Circle(var r: Float) : Shape
    class Ring(r: Float, var inner: Float) : Circle(r)
    class Scene(val a: Circle, val b: Ring, val label: String, val pos: Vector3f, val parts: List<Any>)
    class Holder(val scene: Scene, val other: Circle)

    private fun scene() = Scene(Circle(1f), Ring(2f, 1f), "s", Vector3f(1f, 2f, 3f), listOf(Circle(3f), "text", Vector3f()))

    @Test
    fun typeTestsMatchSubclassesInterfacesAndAliases() {
        val s = scene()
        assertEquals(listOf("a", "b", "parts/0"), s.graphPaths("**::Circle").paths())
        assertEquals(listOf("b"), s.graphPaths("**::Ring").paths())
        assertEquals(listOf("a", "b", "parts/0"), s.graphPaths("**::Shape").paths())
        assertEquals(listOf("pos", "parts/2"), s.graphPaths("**::Vec3").paths())
        assertEquals(listOf("pos"), s.graphPaths("*::Vec3").paths())
        assertEquals(listOf("b"), s.graphPaths("b::Circle").paths(), "a name and a type in one segment is the and")
        assertEquals(emptyList(), s.graphPaths("a::Ring").paths())
        assertEquals(listOf("label", "parts/1"), s.graphPaths("**::String").paths())
        assertEquals(listOf("a", "b", "parts/0"), s.graphPaths("**::${Circle::class.java.name}").paths(), "qualified names")
        assertEquals(listOf("b", "pos"), s.graphPaths("*::Ring | pos").paths(), "| still joins alternatives")
    }

    @Test
    fun typeTestOnAnEarlierSegmentReadsThatSlot() {
        val h = Holder(scene(), Ring(5f, 4f))
        assertEquals(listOf("other/r"), h.graphPaths("*::Ring/r").paths())
        assertEquals(listOf("scene/b/r", "scene/b/inner"), h.graphPaths("scene/*::Ring/*").paths())
    }

    @Test
    fun typeTestsFindFLines() {
        val line = FLine().moveTo(0, 0).lineTo(1, 1)
        val data = mapOf("strokes" to listOf(line, "not a line"), "outline" to line)
        val found = data.graphPaths("**::FLine").paths()
        assertTrue("strokes/0" in found && "outline" in found && found.size == 2, found.toString())
    }
}
