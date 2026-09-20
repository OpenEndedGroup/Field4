package afield.four.synth

import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Vector-valued stages: the space laws, the mutation hazard, and a reader that emits a `Vector3f`.
 *
 * The interesting test here is not that three numbers interpolate. It is that **the bank survives
 * being read**. In `afield.stdlib.linalg`, `add(other)`, `sub(other)`, `mul(scalar)` and
 * `normalize()` are in-place members returning the receiver, and `/` resolves to the mutating
 * member operator rather than the allocating extension. A stage target lives in a bank and is read
 * once per stage for as long as the reader runs, so one in-place write does not produce a wrong
 * frame — it rewrites the material. Nothing in the type system says so, and a single-pass test
 * cannot see it, so the checks below read twice and compare.
 */
class MarfVectorSpaceTest {

    private fun v3(x: Double, y: Double, z: Double) = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())

    private fun components(v: Vector3f) = listOf(v.x, v.y, v.z)

    // ── the space laws ────────────────────────────────────────────────────────────────────

    @Test
    fun `every space lerps exactly to its endpoints`() {
        assertEquals(2.0, MarfDoubles.lerp(2.0, 7.0, 0.0))
        assertEquals(7.0, MarfDoubles.lerp(2.0, 7.0, 1.0))
        assertEquals(4.5, MarfDoubles.lerp(2.0, 7.0, 0.5))

        val a = v3(1.0, 2.0, 3.0)
        val b = v3(5.0, 6.0, 7.0)
        assertEquals(components(a), components(MarfVector3f.lerp(a, b, 0.0)), "phase 0 must be exactly `from`")
        assertEquals(components(b), components(MarfVector3f.lerp(a, b, 1.0)), "phase 1 must be exactly `to`")
        assertEquals(listOf(3f, 4f, 5f), components(MarfVector3f.lerp(a, b, 0.5)))
    }

    @Test
    fun `zero is the additive identity and scale by one is identity`() {
        for (k in listOf(0.0, 0.5, 1.0, 2.0)) {
            assertEquals(0.0, MarfDoubles.scale(MarfDoubles.zero, k))
        }
        val a = v3(1.5, -2.0, 0.25)
        assertEquals(components(a), components(MarfVector3f.add(a, MarfVector3f.zero)))
        assertEquals(components(a), components(MarfVector3f.scale(a, 1.0)))
        assertEquals(listOf(0f, 0f, 0f), components(MarfVector3f.sub(a, a)))
    }

    /** The hazard, tested directly: no space operation may write into its arguments. */
    @Test
    fun `no vector space operation mutates its arguments`() {
        fun check2(name: String, op: (Vector2f, Vector2f) -> Any) {
            val a = Vector2f(1f, 2f)
            val b = Vector2f(3f, 4f)
            op(a, b)
            assertEquals(listOf(1f, 2f), listOf(a.x, a.y), "$name mutated its left argument")
            assertEquals(listOf(3f, 4f), listOf(b.x, b.y), "$name mutated its right argument")
        }
        check2("Vector2f.add") { x, y -> MarfVector2f.add(x, y) }
        check2("Vector2f.sub") { x, y -> MarfVector2f.sub(x, y) }
        check2("Vector2f.lerp") { x, y -> MarfVector2f.lerp(x, y, 0.5) }
        check2("Vector2f.scale") { x, _ -> MarfVector2f.scale(x, 3.0) }

        fun check3(name: String, op: (Vector3f, Vector3f) -> Any) {
            val a = v3(1.0, 2.0, 3.0)
            val b = v3(4.0, 5.0, 6.0)
            op(a, b)
            assertEquals(listOf(1f, 2f, 3f), components(a), "$name mutated its left argument")
            assertEquals(listOf(4f, 5f, 6f), components(b), "$name mutated its right argument")
        }
        check3("Vector3f.add") { x, y -> MarfVector3f.add(x, y) }
        check3("Vector3f.sub") { x, y -> MarfVector3f.sub(x, y) }
        check3("Vector3f.lerp") { x, y -> MarfVector3f.lerp(x, y, 0.5) }
        check3("Vector3f.scale") { x, _ -> MarfVector3f.scale(x, 3.0) }

        fun check4(name: String, op: (Vector4f, Vector4f) -> Any) {
            val a = Vector4f(1f, 2f, 3f, 4f)
            val b = Vector4f(5f, 6f, 7f, 8f)
            op(a, b)
            assertEquals(listOf(1f, 2f, 3f, 4f), listOf(a.x, a.y, a.z, a.w), "$name mutated its left argument")
            assertEquals(listOf(5f, 6f, 7f, 8f), listOf(b.x, b.y, b.z, b.w), "$name mutated its right argument")
        }
        check4("Vector4f.add") { x, y -> MarfVector4f.add(x, y) }
        check4("Vector4f.sub") { x, y -> MarfVector4f.sub(x, y) }
        check4("Vector4f.lerp") { x, y -> MarfVector4f.lerp(x, y, 0.5) }
        check4("Vector4f.scale") { x, _ -> MarfVector4f.scale(x, 3.0) }
    }

    /** The same hazard at the level that matters: a bank read twice must read the same. */
    @Test
    fun `reading a bank does not rewrite the bank`() {
        val a = v3(0.0, 0.0, 0.0)
        val b = v3(1.0, 0.5, 0.0)
        val c = v3(0.0, 1.0, 1.0)
        val bank = marfBankIn(
            MarfVector3f,
            stageTo(a, 1.0, label = "a"),
            stageTo(b, 0.5, MarfCurve.Smooth, label = "b"),
            stageTo(c, 2.0, MarfCurve.Exp(3.0), label = "c")
        )

        val first = bank.read(pat("<0 1 2>").nums(), fields = MarfFieldClock.STAGES, name = "pass1")
        val firstValues = (0..40).map { components(first.at(it * 0.25).value) }

        // A fresh reader over the same bank objects. If the first pass wrote through a stage
        // target, these disagree — and so would every later frame of the first reader.
        val second = bank.read(pat("<0 1 2>").nums(), fields = MarfFieldClock.STAGES, name = "pass2")
        val secondValues = (0..40).map { components(second.at(it * 0.25).value) }
        assertEquals(firstValues, secondValues, "reading the bank changed what the bank holds")

        // And the stage targets are still the objects that were put in.
        assertEquals(listOf(0f, 0f, 0f), components(bank.stages[0].target))
        assertEquals(listOf(1f, 0.5f, 0f), components(bank.stages[1].target))
        assertEquals(listOf(0f, 1f, 1f), components(bank.stages[2].target))
        assertEquals(listOf(0f, 0f, 0f), components(a), "the caller's own vector was written into")
    }

    // ── a vector reader behaves like the scalar one ───────────────────────────────────────

    @Test
    fun `a Vector3f reader interpolates componentwise and stays continuous`() {
        val bank = marfBankIn(
            MarfVector3f,
            stageTo(v3(0.0, 0.0, 0.0), 1.0),
            stageTo(v3(1.0, 0.0, 0.0), 1.0),
            stageTo(v3(1.0, 1.0, 0.0), 1.0),
            stageTo(v3(0.0, 0.0, 1.0), 1.0)
        )
        val reader = bank.read(pat("<0 1 2 3>").nums(), fields = MarfFieldClock.STAGES, name = "path")

        // Stage 1 ramps (0,0,0) -> (1,0,0); halfway is (0.5, 0, 0).
        assertEquals(listOf(0.5f, 0f, 0f), components(reader.at(1.5).value))
        // Stage 2 ramps (1,0,0) -> (1,1,0).
        assertEquals(listOf(1f, 0.5f, 0f), components(reader.at(2.5).value))

        // Continuous at every boundary, exactly — the same claim the gallery makes for scalars.
        val stages = reader.stagesIn(PatArc(PatQ.ZERO, PatQ.of(4)))
        assertEquals(4, stages.size)
        for (i in 1 until stages.size) {
            assertEquals(
                components(stages[i - 1].valueAt(1.0)),
                components(stages[i].valueAt(0.0)),
                "output jumps entering stage ${stages[i].index}"
            )
        }
    }

    @Test
    fun `a fractional address blends vector stages`() {
        val bank = marfBankIn(
            MarfVector3f,
            stageTo(v3(0.0, 0.0, 0.0), 1.0, MarfCurve.Hold),
            stageTo(v3(4.0, 8.0, 0.0), 3.0, MarfCurve.Hold)
        )
        val mid = bank.read(constant(0.5), name = "mid")
        assertEquals(listOf(2f, 4f, 0f), components(mid.at(0.5).value), "the midpoint of the two targets")
        assertEquals(PatQ.of(2), mid.stageAt(PatQ.ZERO).duration, "and the midpoint of the two durations")

        val quarter = bank.read(constant(0.25), name = "quarter")
        assertEquals(listOf(1f, 2f, 0f), components(quarter.at(0.5).value))
    }

    @Test
    fun `a component of a vector reader is an ordinary signal`() {
        val bank = marfBankIn(
            MarfVector3f,
            stageTo(v3(0.0, 1.0, 0.0), 1.0),
            stageTo(v3(1.0, 0.0, 0.0), 1.0)
        )
        val reader = bank.read(pat("<0 1>").nums(), fields = MarfFieldClock.STAGES, name = "xy")

        val x = reader.signal { it.x.toDouble() }
        val y = reader.signal { it.y.toDouble() }

        // Each is a PatPattern<Double>, so the whole scalar language applies.
        val scaled = x.range(100.0, 200.0)
        assertEquals(4, scaled.segment(4).events(PatArc(PatQ.ZERO, PatQ.ONE)).size)

        val voiced = pat("bd*4") with gain(y.range(0.2, 1.0))
        val events = voiced.events(PatArc(PatQ.of(1), PatQ.of(2)))
        assertEquals(4, events.size)
        assertTrue(events.all { it.annotations.containsKey("gain") })

        // And the two components really are different signals.
        val probe = PatArc(PatQ.of(3, 2), PatQ.of(3, 2) + PatQ.of(1, 1000))
        assertNotEquals(x.events(probe).first().value, y.events(probe).first().value)
    }

    @Test
    fun `the fieldless builder works for vectors too`() {
        val reader = marfIn(
            MarfVector2f,
            value = pat("<0 1 2>").nums().map { Vector2f(it.toFloat(), (it * 2).toFloat()) },
            duration = pat("<1 .5 2>").nums(),
            fields = MarfFieldClock.STAGES,
            name = "vec2"
        )
        val stages = reader.stagesIn(PatArc(PatQ.ZERO, PatQ.of(4)))
        assertEquals(listOf(PatQ.ONE, PatQ.of(1, 2), PatQ.of(2)), stages.take(3).map { it.duration })
        assertEquals(listOf(0f, 0f), listOf(stages[0].target.x, stages[0].target.y))
        assertEquals(listOf(1f, 2f), listOf(stages[1].target.x, stages[1].target.y))
        assertEquals(listOf(2f, 4f), listOf(stages[2].target.x, stages[2].target.y))
    }

    @Test
    fun `a vector value labels itself readably`() {
        assertEquals("1,0.5,0", MarfVector3f.label(v3(1.0, 0.5, 0.0)))
        assertEquals("0.25,0.5", MarfVector2f.label(Vector2f(0.25f, 0.5f)))
        assertEquals("0.5", MarfDoubles.label(0.5))
    }

    /**
     * Which label a pulse carries, and why the two builders answer differently.
     *
     * A bank read names the **slot** — its explicit label, or the index — because that is what a
     * trajectory is made of and what you want to see in a pulse stream. The fieldless builder has
     * no slots, so it names the **value** through [MarfSpace.label]. Neither is the space's job to
     * override.
     */
    @Test
    fun `a pulse names the slot for a bank read and the value for a fieldless reader`() {
        val unlabelled = marfBankIn(MarfVector3f, stageTo(v3(1.0, 0.5, 0.0), 1.0))
        assertEquals(
            "0",
            unlabelled.read(constant(0.0), name = "byIndex")
                .triggers().events(PatArc(PatQ.ZERO, PatQ.ONE)).first().value,
            "an unlabelled bank stage is named by its slot"
        )

        val labelled = marfBankIn(MarfVector3f, stageTo(v3(1.0, 0.5, 0.0), 1.0, label = "warm"))
        assertEquals(
            "warm",
            labelled.read(constant(0.0), name = "byLabel")
                .triggers().events(PatArc(PatQ.ZERO, PatQ.ONE)).first().value
        )

        val fieldless = marfIn(
            MarfVector3f,
            value = marfCurvePattern(MarfCurve.Linear).map { v3(1.0, 0.5, 0.0) },
            name = "byValue"
        )
        assertEquals(
            "1,0.5,0",
            fieldless.triggers().events(PatArc(PatQ.ZERO, PatQ.ONE)).first().value,
            "a fieldless reader has no slot to name, so it names the value"
        )
    }
}
