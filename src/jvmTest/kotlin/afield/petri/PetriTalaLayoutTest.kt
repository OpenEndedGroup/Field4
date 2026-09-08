package afield.petri

import afield.layout.D2Layout
import org.junit.After
import org.junit.Assume
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PetriLayout.TALA] and the optional helper behind it.
 *
 * The property that matters most here is the *absence* path, and it is the one test that always
 * runs: `native/field-d2-layout` is not built in a fresh checkout, is not built by Gradle, and
 * needs a Go toolchain Field does not otherwise want. A document that asks for TALA has to open
 * anyway, laid out by the spring model, on a machine that has never heard of Go.
 *
 * The presence path can only be checked where the binary exists, so it skips loudly rather than
 * passing quietly.
 */
class PetriTalaLayoutTest {

    /** Dining philosophers: dense, cyclic, and the net that motivated a better layout. */
    private fun philosophers(count: Int = 5): PetriNet = petri {
        for (index in 0 until count) place("fork.$index", tokens = 1)
        for (index in 0 until count) {
            val right = (index + 1) % count
            place("think.$index", tokens = 1)
            place("eat.$index")
            t("take.$index") {
                from("think.$index"); from("fork.$index"); from("fork.$right"); to("eat.$index")
            }
            t("drop.$index") {
                from("eat.$index"); to("think.$index"); to("fork.$index"); to("fork.$right")
            }
        }
    }

    private val helper = File("native/field-d2-layout/build/field-d2-layout")

    @After
    fun restoreHelperDiscovery() {
        D2Layout.binaryOverride = null
        D2Layout.reset()
        clearPetriLayoutCache()
    }

    @Test
    fun talaFallsBackToForceWhenTheHelperIsMissing() {
        D2Layout.binaryOverride = File("no", "such-field-d2-layout").path
        D2Layout.reset()
        clearPetriLayoutCache()
        assertTrue(!D2Layout.available, "a nonexistent path must not report as available")

        val net = philosophers()
        val tala = net.frame(PetriLayout.TALA).positions
        val force = net.frame(PetriLayout.FORCE).positions

        assertEquals(force.keys, tala.keys, "the fallback dropped or invented nodes")
        for ((name, at) in force) {
            assertEquals(at, tala[name], "$name moved; TALA did not fall through to FORCE")
        }
    }

    @Test
    fun talaLaysTheNetOutWhenTheHelperIsPresent() {
        Assume.assumeTrue(
            "native/field-d2-layout is not built (see its README); skipping the TALA path",
            helper.canExecute(),
        )
        D2Layout.binaryOverride = helper.path
        D2Layout.reset()
        clearPetriLayoutCache()
        assertTrue(D2Layout.available, "the built helper did not answer a version probe")

        val net = philosophers()
        val tala = net.frame(PetriLayout.TALA).positions
        val force = net.frame(PetriLayout.FORCE).positions

        assertEquals(force.keys, tala.keys)
        // Every node somewhere of its own. A partial answer is refused outright, so a collision
        // here would mean the engine genuinely stacked two nodes.
        val distinct = tala.values.map { "${it.x},${it.y}" }.toSet()
        assertEquals(tala.size, distinct.size, "two nodes share a position")
        // The whole point is that this is not the spring model's drawing.
        assertTrue(force.any { (name, at) -> tala[name] != at }, "TALA reproduced FORCE exactly")
    }

    @Test
    fun repeatedLayoutsOfTheSameNetAgree() {
        Assume.assumeTrue(
            "native/field-d2-layout is not built (see its README); skipping the TALA path",
            helper.canExecute(),
        )
        D2Layout.binaryOverride = helper.path
        D2Layout.reset()
        clearPetriLayoutCache()

        val first = philosophers().frame(PetriLayout.TALA).positions
        // A fresh net, and no memoised answer at either level to serve it from: this has to be
        // the engine agreeing with itself, not a cache agreeing with itself. `petriLayout` keeps
        // its own cache above D2Layout's, and clearing only the lower one made this test pass in
        // one millisecond without laying anything out.
        D2Layout.clearCache()
        clearPetriLayoutCache()
        val second = philosophers().frame(PetriLayout.TALA).positions

        assertEquals(first.keys, second.keys)
        for ((name, at) in first) assertEquals(at, second[name], "$name moved between runs")
    }

    @Test
    fun talaArcsFollowTheRoutesItReturns() {
        Assume.assumeTrue(
            "native/field-d2-layout is not built (see its README); skipping the TALA path",
            helper.canExecute(),
        )
        D2Layout.binaryOverride = helper.path
        D2Layout.reset()
        clearPetriLayoutCache()

        val net = philosophers()
        val tala = net.frame(PetriLayout.TALA)
        val force = net.frame(PetriLayout.FORCE)

        assertTrue(tala.routes.isNotEmpty(), "TALA returned no routed arcs")
        // A route with only its two endpoints is a straight line by another name; the point of
        // taking the routes is the ones that turn a corner.
        assertTrue(
            tala.routes.values.any { it.size > 2 },
            "no route bends, so nothing was gained over drawing straight arcs",
        )
        // Every route names an arc that exists.
        val arcs = buildSet<Pair<String, String>> {
            for (transition in net.transitions) {
                for (arc in transition.inputs) add(arc.place to transition.name)
                for (arc in transition.outputs) add(transition.name to arc.place)
            }
        }
        tala.routes.keys.forEach { assertTrue(it in arcs, "route for a non-existent arc: $it") }
        assertTrue(force.routes.isEmpty(), "the spring model should not be inventing routes")
    }

    @Test
    fun pinningANodeGivesUpTheRoutes() {
        Assume.assumeTrue(
            "native/field-d2-layout is not built (see its README); skipping the TALA path",
            helper.canExecute(),
        )
        D2Layout.binaryOverride = helper.path
        D2Layout.reset()
        clearPetriLayoutCache()

        val net = philosophers()
        val tala = net.frame(PetriLayout.TALA)
        assertTrue(tala.routes.isNotEmpty(), "nothing to give up")

        // A route was computed against the positions the engine chose, so moving a node by hand
        // leaves the arcs at either end of it describing a drawing that no longer exists.
        val moved = tala.at("fork.0", 0, 0)
        assertTrue(moved.routes.isEmpty(), "a pinned node must drop the routes, not keep stale ones")
    }
}
