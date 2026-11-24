package afield.tools.automation

import kotlin.test.*

class TranscriptMountTest {
    private val scene = """
        name: scene
        operations:
        - create: { box: ctx, name: Context, frame: { x: 10.0, y: 20.0, w: 300.0, h: 100.0 }, properties: { code: 'println("ctx")' } }
        - create: { box: draw, name: Drawing, frame: { x: 10.0, y: 200.0, w: 300.0, h: 100.0 }, properties: { code: 'println("draw")' } }
        - create: { box: side, name: Aside, frame: { x: 400.0, y: 20.0, w: 300.0, h: 100.0 } }
        - connect: { parent: ctx, child: draw }
        - launch: { box: ctx }
        - await: { wait: pending }
        - launch: { box: draw }
        - save: { path: /tmp/elsewhere }
    """.trimIndent()

    private fun verb(op: Map<String, Any?>) = op.keys.first()
    private fun payload(op: Map<String, Any?>) = op.values.first() as Map<*, *>

    @Test
    fun everyCreatedBoxIsRenamedUnderThePrefixWhereverItIsNamed() {
        val (ops, uids, roots) = TranscriptMount.plan(TranscriptMount.operations(scene), "enc.a.", parent = null)
        assertEquals(mapOf("ctx" to "enc.a.ctx", "draw" to "enc.a.draw", "side" to "enc.a.side"), uids)
        assertEquals(listOf("enc.a.ctx", "enc.a.side"), roots, "draw is ctx's child inside the transcript, so it is not a root")
        val named = ops.flatMap { op -> payload(op).filterKeys { it in setOf("box", "parent", "child") }.values.map { it.toString() } }
        assertTrue(named.all { it.startsWith("enc.a.") }, "every box reference is renamed: $named")
        assertTrue(ops.none { verb(it) == "save" }, "a mounted scene does not save the document elsewhere")
    }

    @Test
    fun rootsAreConnectedBeneathTheParentBeforeAnythingRuns() {
        val (ops, _, _) = TranscriptMount.plan(TranscriptMount.operations(scene), "enc.a.", parent = "runner", dx = 100.0, dy = -5.0)
        val verbs = ops.map(::verb)
        val firstLaunch = verbs.indexOf("launch")
        val toRunner = ops.withIndex().filter { (_, op) -> verb(op) == "connect" && payload(op)["parent"] == "runner" }
        assertEquals(setOf("enc.a.ctx", "enc.a.side"), toRunner.map { payload(it.value)["child"] }.toSet())
        assertTrue(toRunner.all { it.index < firstLaunch }, "parent connections come before the first launch: $verbs")
        val frame = payload(ops.first { verb(it) == "create" })["frame"] as Map<*, *>
        assertEquals(110.0, frame["x"])
        assertEquals(15.0, frame["y"])
    }

    @Test
    fun teardownStopsNewestFirstThenDeletes() {
        val plan = TranscriptMount.teardownPlan(listOf("p.ctx", "p.draw", "p.side"))
        assertEquals(listOf("stop", "stop", "stop", "wait", "delete", "delete", "delete"), plan.map(::verb))
        assertEquals(listOf("p.side", "p.draw", "p.ctx"), plan.take(3).map { payload(it)["box"] })
        assertEquals(listOf("p.side", "p.draw", "p.ctx"), plan.takeLast(3).map { payload(it)["box"] })
    }

    @Test
    fun aPrefixMustBeSafeInUidsAndFileNamesAndAParentCannotBeMounted() {
        assertFailsWith<IllegalArgumentException> { TranscriptMount.plan(TranscriptMount.operations(scene), "enc/a", null) }
        assertFailsWith<IllegalArgumentException> { TranscriptMount.plan(TranscriptMount.operations(scene), "enc a.", null) }
        assertFailsWith<IllegalArgumentException> { TranscriptMount.plan(TranscriptMount.operations(scene), "p.", parent = "p.ctx") }
        assertFailsWith<IllegalArgumentException> { TranscriptMount.plan(TranscriptMount.operations("operations: []"), "p.", null) }
    }

    @Test
    fun aBareListOfOperationsIsATranscriptToo() {
        val ops = TranscriptMount.operations("- create: { box: a }\n- launch: { box: a }\n")
        assertEquals(listOf("create", "launch"), ops.map(::verb))
    }
}
