package afield.document.properties

import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.ReactiveRuns
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with

import afield.app.server.SessionInformation
import afield.app.server.sessions
import afield.data.table.get
import afield.document.context.ContextResolution
import afield.document.graph.Box
import afield.document.graph.box
import afield.document.graph.needsResync
import afield.runtime.lifecycle.map
import afield.ui.output.templates.filter
import afield.document.properties.Inheritance
import afield.document.properties.inheritedPropertyRaw
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private var Box.dependencyTestSpeed: Double? by Inheritance<Double?>()

/**
 * What [PropertyDependencies] records from the one context walk and the inherited setters, what it
 * derives from that ([PropertyDependencies.flows], misses), and that `dependencies.edn` round-trips.
 * No Kotlin engine: the walks are driven directly, with `Box.context` set as a running box would.
 */
class PropertyDependenciesTest {

    private val sessions = mutableListOf<SessionInformation>()
    private val boxes = mutableListOf<Box>()

    private fun session(label: String, path: String = "<dependencies-$label>"): SessionInformation {
        val root = Box("$label-root")
        val s = SessionInformation(filename = path, documentName = label, root = root, ephemeral = true)
        root.inside = s
        s.loading = false
        sessions += s
        boxes += root
        PropertyDependencies.forget(path)
        return s
    }

    private fun boxIn(s: SessionInformation, id: String): Box =
        Box(id).also {
            it.inside = s
            s.boxes[id] = it
            boxes += it
        }

    private fun <T> Box.running(block: () -> T): T {
        val previous = Box.context.get()
        Box.context.set(this)
        try {
            return block()
        } finally {
            if (previous == null) Box.context.remove() else Box.context.set(previous)
        }
    }

    @AfterTest
    fun cleanUp() {
        val mine = boxes.toSet()
        needsResync.removeAll { (box, _) -> box in mine }
        sessions.forEach { PropertyDependencies.forget(it.filename) }
        sessions.clear()
        boxes.clear()
        Box.context.remove()
    }

    @Test
    fun anInheritedReadIsAFlowFromTheSupplierAndAnOwnReadIsNothing() {
        val s = session("read")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        parent.setPropertyRaw("speed", 3.0)
        child.setPropertyRaw("own", 1)

        assertEquals(3.0, child.running { inheritedPropertyRaw(child, "speed") })
        child.running { inheritedPropertyRaw(child, "speed") }
        child.running { inheritedPropertyRaw(child, "own") }

        val store = PropertyDependencies.storeFor(s.filename)
        val reads = store.edges.values.filter { it.key.access == PropertyDependencies.Access.READ }
        assertEquals(1, reads.size, "own reads are not dependencies: ${store.edges.keys}")
        assertEquals(2, reads.single().count.get())
        val flow = PropertyDependencies.flows(store, s.root.id).single()
        assertEquals("parent" to "child", flow.from to flow.to)
        assertEquals(listOf("speed"), flow.names)
        assertEquals(listOf("parent"), PropertyDependencies.upstreamOf(child))
    }

    @Test
    fun aMissIsShownUntilALaterReadAnswersIt() {
        val s = session("miss")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)

        child.running { inheritedPropertyRaw(child, "speed") }
        assertEquals(listOf("speed"), PropertyDependencies.graph(s).misses.map { it.name })

        Thread.sleep(2)
        parent.setPropertyRaw("speed", 3.0)
        child.running { inheritedPropertyRaw(child, "speed") }
        val graph = PropertyDependencies.graph(s)
        assertTrue(graph.misses.isEmpty(), "answered miss still shown: ${graph.misses}")
        assertEquals(1, graph.flows.size)
    }

    @Test
    fun aRememberedSupplierIsOfferedForAMiss() {
        val s = session("remembered")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        parent.setPropertyRaw("speed", 3.0)
        child.running { inheritedPropertyRaw(child, "speed") }

        // As on a fresh start: the supplier has not run yet.
        parent.removePropertyRaw("speed")
        Thread.sleep(2)
        child.running { inheritedPropertyRaw(child, "speed") }

        val miss = PropertyDependencies.graph(s).misses.single()
        assertEquals(listOf("parent"), miss.rememberedSuppliers)
    }

    @Test
    fun aWriteIntoAnotherBoxMakesTheWriterTheProducer() {
        val s = session("rootprop")
        val writer = boxIn(s, "writer")
        val reader = boxIn(s, "reader")

        // `rootProp<Double>(speed)` in both: `root.speed = 4.0` in one, `root.speed` read in the other.
        writer.running { s.root.dependencyTestSpeed = 4.0 }
        assertEquals(4.0, reader.running { s.root.dependencyTestSpeed })

        val flows = PropertyDependencies.flows(PropertyDependencies.storeFor(s.filename), s.root.id)
        assertEquals(listOf("writer" to "reader"), flows.map { it.from to it.to }, "the root is never a producer")
    }

    @Test
    fun describingAResolutionDoesNotRecordIt() {
        val s = session("describe")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        parent.setPropertyRaw("speed", 3.0)

        val query = ContextResolution.Query.propertyNamed("speed")
        child.running {
            ContextResolution.walk(child, query, ContextResolution.Policy.INHERITANCE, ContextResolution.Mechanism.INHERITANCE, explain = true)
            ContextResolution.peek(child, query, ContextResolution.Policy.INHERITANCE, ContextResolution.Mechanism.INHERITANCE)
        }
        assertTrue(PropertyDependencies.storeFor(s.filename).edges.isEmpty())
    }

    @Test
    fun theRecordRoundTripsThroughDependenciesEdn() {
        val dir = Files.createTempDirectory("dependencies-edn").toFile()
        try {
            val s = session("persist", dir.absolutePath)
            val parent = boxIn(s, "parent")
            val child = boxIn(s, "child")
            parent.connect(child)
            parent.setPropertyRaw("speed", 3.0)
            child.running { inheritedPropertyRaw(child, "speed") }
            child.running { inheritedPropertyRaw(child, "missing") }

            PropertyDependencies.write(PropertyDependencies.storeFor(dir.absolutePath), dir)
            PropertyDependencies.forget(dir.absolutePath)

            val reloaded = PropertyDependencies.storeFor(dir.absolutePath)
            assertEquals(2, reloaded.edges.size)
            val graph = PropertyDependencies.graph(s)
            assertEquals(listOf("parent" to "child"), graph.flows.map { it.from to it.to })
            assertEquals(0, graph.flows.single().sessionCount, "a reloaded edge is remembered, not seen")
            assertEquals(listOf("missing"), graph.misses.map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aCompletedFullRunRetiresTheReadsItDidNotRepeat() {
        val dir = Files.createTempDirectory("dependencies-retire").toFile()
        try {
            val s = session("retire", dir.absolutePath)
            val parent = boxIn(s, "parent")
            val child = boxIn(s, "child")
            parent.connect(child)
            parent.setPropertyRaw("speed", 3.0)
            parent.setPropertyRaw("size", 2.0)
            child.running { inheritedPropertyRaw(child, "speed") }
            child.running { inheritedPropertyRaw(child, "size") }
            val store = PropertyDependencies.storeFor(s.filename)
            fun historical(name: String) = store.edges.values.single { it.key.name == name }.historical

            // A fragment, and a failed run, say too little to forget anything.
            ReactiveRuns.runStarted(child, "fragment")
            ReactiveRuns.runFinished(child, true, "fragment")
            ReactiveRuns.runStarted(child, "run")
            ReactiveRuns.runFinished(child, false, "run")
            assertEquals(false to false, historical("speed") to historical("size"))

            // A full run that reads only `size` retires `speed`.
            ReactiveRuns.runStarted(child, "run")
            child.running { inheritedPropertyRaw(child, "size") }
            ReactiveRuns.runFinished(child, true, "run")
            assertEquals(true to false, historical("speed") to historical("size"))
            val flow = PropertyDependencies.graph(s).flows.single()
            assertEquals(setOf("speed", "size"), flow.names.toSet(), "still drawn, to explain")
            assertEquals(false, flow.historical, "the arrow still carries a live read")

            // It stays retired across a save and reload, and a fresh read revives it.
            PropertyDependencies.write(store, dir)
            PropertyDependencies.forget(dir.absolutePath)
            val reloaded = PropertyDependencies.storeFor(dir.absolutePath)
            assertTrue(reloaded.edges.values.single { it.key.name == "speed" }.historical)
            assertEquals(false, reloaded.edges.values.single { it.key.name == "size" }.historical)
            child.running { inheritedPropertyRaw(child, "speed") }
            assertEquals(false, reloaded.edges.values.single { it.key.name == "speed" }.historical)
        } finally {
            dir.deleteRecursively()
        }
    }
}
