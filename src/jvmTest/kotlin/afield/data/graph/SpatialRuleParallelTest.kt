package afield.data.graph

import afield.data.collections.atomic
import afield.data.collections.invoke
import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import java.util.Collections
import java.util.concurrent.ForkJoinPool
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpatialRuleParallelTest {
    /**
     * Forty points circle and some come and go; nearby pairs publish a bond and a midpoint, move
     * the midpoint, retract some bonds early and fade after their match ends, and a second layer
     * beads every bond. Everything the run produces is written down frame by frame.
     */
    private fun run(parallel: Boolean, frames: Int = 120): List<String> {
        val record = mutableListOf<String>()
        SpatialRuleWorld().use { world ->
            world.source("seeds") {
                val seeds = 40 { i -> point("seed", orbit(i, 0.0)) }.toMutableList()
                while (true) {
                    for ((i, seed) in seeds.withIndex()) move(seed, orbit(i, now))
                    if (frame % 17 == 0L) {
                        val i = (frame / 17 % seeds.size).toInt()
                        retract(seeds[i])
                        seeds[i] = point("seed", orbit(i, now))
                    }
                    nextFrame()
                }
            }
            world.rules("bonds") {
                onEnter("bond", points("seed").pairsWithin(1.2, 1.5), parallel = parallel) { (a, b) ->
                    val bond = edge("bond", a, b)
                    val middle = point("middle", mid(a.pos, b.pos), lifetime = SpatialLifetime.MATCH)
                    val early = (a.id + b.id) % 3 == 0L
                    var frames = 0
                    var bonded = true
                    while (match.live && a.alive && b.alive) {
                        move(middle, mid(a.pos, b.pos))
                        if (bonded) set(bond, "frames", frames)
                        frames++
                        if (early && frames == 5) {
                            retract(bond)
                            bonded = false
                        }
                        draw(line(a.pos, b.pos, frames))
                        nextFrame()
                    }
                    during(0.1) { t -> draw(line(a.pos, b.pos, (100 * t).toInt())) }
                }
            }
            world.rules("beads") {
                onEnter("bead", edges("bond"), parallel = parallel) { bond ->
                    val bead = point("bead", bond.at(0.5), lifetime = SpatialLifetime.MATCH)
                    edge("tie", bead, bond.from, lifetime = SpatialLifetime.MATCH)
                    whileLive {
                        move(bead, bond.at(0.5))
                        draw(line(bond.from.pos, bead.pos, 0))
                    }
                }
            }
            repeat(frames) { f ->
                val drawn = world.step(f / 60.0).joinToString(";") { it.describe() }
                val points = listOf("seed", "middle", "bead").flatMap(world::points).sortedBy { it.id }
                    .joinToString(";") { "${it.id}:${it.kind}:${it.pos}:${it.data}" }
                val edges = listOf("bond", "tie").flatMap(world::edges).sortedBy { it.id }
                    .joinToString(";") { "${it.id}:${it.from.id}-${it.to.id}:${it.data}" }
                record += "$f|$drawn|$points|$edges"
            }
        }
        return record
    }

    @Test
    fun aParallelRuleProducesExactlyWhatASerialOneDoes() {
        val serial = run(parallel = false)
        assertTrue(serial.last().contains("bead"), "the run should reach the bead layer")
        assertEquals(serial, run(parallel = true))
        assertEquals(serial, run(parallel = true))
    }

    @Test
    fun parallelProcessesRunOnSeveralThreads() {
        if (ForkJoinPool.getCommonPoolParallelism() < 2) return
        val threads = Collections.synchronizedSet(mutableSetOf<String>())
        SpatialRuleWorld().use { world ->
            world.source("seeds") {
                64 { i -> point("seed", Vec3(10f * i, 0f, 0f)) }
                while (true) nextFrame()
            }
            world.rules("busy") {
                onEnter("work", points("seed").each(), parallel = true) { seed ->
                    whileLive {
                        threads += Thread.currentThread().name
                        var x = seed.pos.x.toDouble()
                        repeat(20_000) { x = sin(x) + 1.0 }
                        if (x.isNaN()) draw(FLine())
                    }
                }
            }
            repeat(10) { world.step(it.toDouble()) }
        }
        assertTrue(threads.size > 1, "expected several threads, saw $threads")
    }

    @Test
    fun atomicCountersLoseNoUpdatesFromParallelProcesses() {
        val added = 0.atomic
        var stepped = 0.atomic
        val total = 0.0.atomic
        SpatialRuleWorld().use { world ->
            world.source("seeds") {
                64 { i -> point("seed", Vec3(10f * i, 0f, 0f)) }
                while (true) nextFrame()
            }
            world.rules("count") {
                onEnter("count", points("seed").each(), parallel = true) {
                    whileLive {
                        added += 1
                        stepped++
                        total += 0.5
                    }
                }
            }
            repeat(51) { world.step(it.toDouble()) }
        }
        // The seeds commit on the first step and their processes start on the second.
        assertEquals(64 * 50, added.value)
        assertEquals(64 * 50, stepped.value)
        assertEquals(64 * 50 * 0.5, total.value)
        assertEquals("3200", "$added")
    }

    @Test
    fun aParallelProcessNumbersItsFactsWhenTheStepEnds() {
        SpatialRuleWorld().use { world ->
            val seen = mutableListOf<Long>()
            world.source("seeds") {
                point("seed", Vec3())
                point("seed", Vec3(100f, 0f, 0f))
                while (true) nextFrame()
            }
            world.rules("marks") {
                onEnter("mark", points("seed").each(), parallel = true) { seed ->
                    val mark = point("mark", seed.pos)
                    synchronized(seen) { seen += mark.id }
                    edge("to seed", mark, seed)
                    whileLive()
                }
            }
            world.step(0.0)
            world.step(1.0)
            assertEquals(listOf(0L, 0L), seen)
            world.step(2.0)
            val marks = world.points("mark")
            assertEquals(listOf(3L, 5L), marks.map { it.id })
            assertEquals(marks, marks.map { world.fact(it.id) })
            assertEquals(listOf(4L, 6L), world.edges("to seed").map { it.id })
            assertEquals(marks, world.edges("to seed").map { it.from })
        }
    }

    @Test
    fun aFailingParallelProcessFailsTheStep() {
        SpatialRuleWorld().use { world ->
            world.source("seeds") {
                8 { i -> point("seed", Vec3(10f * i, 0f, 0f)) }
                while (true) nextFrame()
            }
            world.rules("fail") {
                onEnter("fail", points("seed").each(), parallel = true) { seed ->
                    check(seed.pos.x < 50f) { "too far" }
                    whileLive()
                }
            }
            world.step(0.0)
            val failure = assertFailsWith<IllegalStateException> { world.step(1.0) }
            assertEquals("too far", failure.cause?.message)
            assertEquals(2, failure.suppressed.size)
        }
    }

    @Test
    fun aSkinPinsTheShapesBoundsToItsPoints() {
        // A 2 by 1 box from (1, 1) to (3, 2), as a closed outline.
        val box = FLine().apply {
            moveTo(Vec3(1f, 1f, 0f))
            lineTo(Vec3(3f, 1f, 0f))
            lineTo(Vec3(3f, 2f, 0f))
            lineTo(Vec3(1f, 2f, 0f))
            color = Vec4(1f, 1f, 1f, 1f)
        }
        fun corners(line: FLine) = line.nodes.map { it.storage.last() }.map { "${it.x.round()},${it.y.round()}" }

        // Upright between (0, 0) and (0, 10): along the line is +y, so across it is -x.
        val upright = spatialSkin(box, Vec3(0f, 0f, 0f), Vec3(0f, 10f, 0f), width = 4.0)
        assertEquals(listOf("2,0", "2,10", "-2,10", "-2,0"), corners(upright))
        // Without a width the shape keeps its proportions: 10 long, so 5 across.
        val even = spatialSkin(box, Vec3(0f, 0f, 0f), Vec3(10f, 0f, 0f), opacity = 0.5)
        assertEquals(listOf("0,-2.5", "10,-2.5", "10,2.5", "0,2.5"), corners(even))
        assertEquals(0.5f, even.color.w)
        assertEquals(1f, box.color.w, "the shape itself is left alone")

        val quad = [Vec3(0f, 0f, 0f), Vec3(10f, 0f, 0f), Vec3(8f, 6f, 0f), Vec3(2f, 4f, 0f)]
        assertEquals(listOf("0,0", "10,0", "8,6", "2,4"), corners(spatialSkin(box, quad)))
    }

    private fun Float.round(): String =
        String.format(java.util.Locale.ROOT, "%.1f", this).removeSuffix(".0").let { if (it == "-0") "0" else it }

    private fun orbit(i: Int, t: Double): Vec3 {
        val a = 0.7 * i + (0.3 + 0.02 * i) * t * 6
        val r = 2.0 + i % 5
        return Vec3((r * cos(a)).toFloat(), (r * sin(a)).toFloat(), 0f)
    }

    private fun mid(a: Vec3, b: Vec3) = Vec3((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2)

    private fun line(a: Vec3, b: Vec3, shade: Int) = FLine().apply {
        moveTo(a)
        lineTo(b)
        color = Vec4(shade / 100f, 0f, 0f, 1f)
    }

    private fun FLine.describe(): String = nodes.joinToString(",") { node -> node.storage.joinToString(" ") } + "/$color"
}
