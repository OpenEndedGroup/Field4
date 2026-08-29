package afield.datalog

import afield.Box
import afield.Rect
import afield.SessionInformation
import afield.thisSessionInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The half of `afield.datalog` that needs a live document: the box-graph bridge and the box
 * language's REPL.
 *
 * The engine itself is covered by the `datalog.core.smoke` FIELD_TEST, which is a `jvm-main`
 * entrypoint precisely because it touches none of this. Everything here does — `Box`,
 * `SessionInformation`, the property bag — so it lives in `jvmTest` instead.
 */
class DatalogBoxGraphTest {

    private lateinit var session: SessionInformation

    /**
     * A four-box document: root → a → b, root → c, with `c` nested in `a`.
     *
     * ```
     * root
     *  ├── a ── b
     *  └── c        (nested inside a)
     * ```
     */
    private fun document(): Map<String, Box> {
        val root = Box("root")
        session = SessionInformation(filename = "<datalog>", documentName = "datalog", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)

        val boxes = linkedMapOf("root" to root)
        listOf("a", "b", "c").forEach { id ->
            val box = Box(id)
            box.inside = session
            box.name = "box-$id"
            boxes[id] = box
        }
        // Mirrors the server: `SessionInformation.boxes` indexes everything *except*
        // the document root, which is what `documentBoxes` has to put back.
        boxes.values.filter { it != root }.forEach { session.boxes[it.id] = it }

        root.connect(boxes["a"]!!)
        root.connect(boxes["c"]!!)
        boxes["a"]!!.connect(boxes["b"]!!)
        boxes["b"]!!.frame = Rect(10.0, 20.0, 100.0, 50.0)
        boxes["b"]!!.setPropertyRaw("kind", "shader")
        boxes["c"]!!.setPropertyRaw("kind", "shader")
        boxes["a"]!!.setPropertyRaw("kind", "kotlin")
        return boxes
    }

    @AfterTest
    fun clearSessionBinding() {
        thisSessionInfo.remove()
    }

    @Test
    fun `the box graph loads as base relations`() {
        val boxes = document()
        val d = Datalog()
        d.addBoxGraph(boxes["root"]!!)

        assertEquals(4, d.query("box(X)").size, "one row per live box")
        assertEquals(listOf("box-a"), d.query("name(a, N)").column("N"))
        assertEquals(setOf(listOf("root", "a"), listOf("root", "c"), listOf("a", "b")), d.query("child(P, C)").rows.toSet())
        assertEquals(listOf(10L, 20L, 100L, 50L), d.query("frame(b, X, Y, W, H)").rows.single())
        assertTrue(d.ask("prop(b, \"kind\", shader)"), "scalar properties are exposed")
        assertFalse(d.ask("prop(b, \"children\", _)"), "topology is not duplicated into prop/3")
    }

    @Test
    fun `recursive reachability over the live graph`() {
        val boxes = document()
        val d = Datalog()
        d.addBoxGraph(boxes["root"]!!)
        d.add(
            """
            reaches(A, B) :- child(A, B).
            reaches(A, C) :- reaches(A, B), child(B, C).
            orphan(X) :- box(X), !child(_, X).
            """
        )
        assertEquals(listOf("a", "b", "c"), d.query("reaches(root, X)").column("X"))
        assertEquals(listOf("b"), d.query("reaches(a, X)").column("X"))
        // Only the document root has no parent inside the document.
        assertEquals(listOf("root"), d.query("orphan(X)").column("X"))
        // A join back onto the properties: which shader boxes are reachable from the root?
        assertEquals(
            listOf("b", "c"),
            d.query("reaches(root, X), prop(X, \"kind\", shader)").column("X"),
        )
    }

    @Test
    fun `nesting is its own relation`() {
        val boxes = document()
        boxes["a"]!!.setPropertyRaw("nested", mutableSetOf(boxes["c"]!!))
        val d = Datalog()
        d.addBoxGraph(boxes["root"]!!)
        assertEquals(listOf(listOf("a", "c")), d.query("nested(O, I)").rows)
    }

    @Test
    fun `a tombstoned box is not in the model`() {
        val boxes = document()
        boxes["c"]!!.setPropertyRaw("_tombstone", true)
        val d = Datalog()
        d.addBoxGraph(boxes["root"]!!)
        assertFalse(d.ask("box(c)"), "a deleted box must not answer queries")
        assertEquals(3, d.query("box(X)").size)
    }

    @Test
    fun `the box language accumulates across fragments`() {
        val boxes = document()
        val execution = DatalogExecution(boxes["a"]!!)

        val first = execution.runProgram("edge(x, y). edge(y, z).")
        assertTrue(first.contains("added 2 fact(s), 0 rule(s)"), first)

        val second = execution.runProgram(
            """
            path(A, B) :- edge(A, B).
            path(A, C) :- path(A, B), edge(B, C).
            """
        )
        assertTrue(second.contains("0 fact(s), 2 rule(s)"), second)

        // A bare non-ground atom is a question, not a malformed fact.
        val asked = execution.runProgram("path(x, Z).")
        assertTrue(asked.contains("?- path(x, Z)"), asked)
        assertTrue(asked.contains("y") && asked.contains("z"), asked)

        // …and so is an explicit ?- goal.
        assertTrue(execution.runProgram("?- path(x, z).").contains("yes"))

        // Re-running the whole fragment must not double the program.
        execution.runProgram("path(A, B) :- edge(A, B).")
        assertEquals(2, execution.datalog.rules.size, "rules must be de-duplicated across runs")
    }

    @Test
    fun `directives drive the box language`() {
        val boxes = document()
        val execution = DatalogExecution(boxes["a"]!!)

        val loaded = execution.runProgram("#boxes")
        assertTrue(loaded.contains("box/1 4"), loaded)

        val counted = execution.runProgram("#relations")
        assertTrue(counted.contains("child/2"), counted)

        execution.runProgram("reaches(A, B) :- child(A, B).\nreaches(A, C) :- reaches(A, B), child(B, C).")
        val why = execution.runProgram("#why reaches(root, b)")
        assertTrue(why.contains("reaches(root, b)"), why)
        assertTrue(why.contains("child(a, b)"), "the proof should bottom out at real edges:\n$why")

        assertTrue(execution.runProgram("#facts child").contains("child(root, a)"))
        assertTrue(execution.runProgram("#help").contains("#boxes"))
        assertTrue(execution.runProgram("#nonsense").contains("unknown directive"))

        execution.runProgram("#reset")
        assertEquals(0, execution.datalog.rules.size)
        assertTrue(execution.runProgram("#relations").contains("nothing yet"))
    }

    @Test
    fun `the embedded-region header is accepted, not reported as a directive`() {
        val boxes = document()
        val execution = DatalogExecution(boxes["a"]!!)
        val out = execution.runProgram("#datalog\nedge(x, y).")
        assertFalse(out.contains("unknown directive"), out)
        assertTrue(out.contains("added 1 fact(s)"), out)
        assertTrue(execution.datalog.ask("edge(x, y)"))
    }

    @Test
    fun `a directive sees the rules stated in its own fragment`() {
        val boxes = document()
        val execution = DatalogExecution(boxes["a"]!!)
        val out = execution.runProgram(
            """
            cell(a1, 3). cell(a3, 10).
            over(C) :- cell(C, V), V > 5.

            #why over(a3)
            """.trimIndent()
        )
        assertFalse(out.contains("not in the model"), out)
        assertTrue(out.contains("cell(a3, 10)"), "the proof should name the fact it used:\n" + out)

        // …and `#reset` still clears before the fragment is merged, not after.
        val cleared = DatalogExecution(boxes["a"]!!)
        cleared.runProgram("stale(1).")
        cleared.runProgram("#reset\nfresh(2).")
        assertFalse(cleared.datalog.ask("stale(1)"), "reset should have dropped the old fact")
        assertTrue(cleared.datalog.ask("fresh(2)"), "reset must not eat the fragment it precedes")
    }

    @Test
    fun `a syntax error is reported with a line number`() {
        val boxes = document()
        val execution = DatalogExecution(boxes["a"]!!)
        val thrown = try {
            execution.runProgram("ok(1).\nbroken(1) :- .")
            null
        } catch (e: DlException) {
            e
        }
        assertTrue(thrown != null, "a bad program must throw")
        assertEquals(2, thrown.line, "the error should point at the second line")
    }

    @Test
    fun `Box datalog is the one-liner form`() {
        val boxes = document()
        val d = boxes["root"]!!.datalog(
            """
            reaches(A, B) :- child(A, B).
            reaches(A, C) :- reaches(A, B), child(B, C).
            """
        )
        assertEquals(listOf("box-a", "box-b", "box-c"), d.query("reaches(root, X), name(X, N)").column("N"))
    }
}
