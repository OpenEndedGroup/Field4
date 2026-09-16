package afield.stdlib

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for `keyframeSweep.kt` — the overnight sweeper that turns a directory of saved keys into an
 * archive of renders.
 *
 * Every test here runs on the CPU with a callback that writes a file, because the sweep engine has
 * no graphics in it: the blend is [KeyframeScope.interpolate] folded, the rest is sampling and
 * bookkeeping. The GPU half lives in `keyframeSweepCapture.kt` and is covered by the
 * `keyframe.sweep.smoke` entrypoint instead, which needs a renderer.
 *
 * Two claims get the most attention, because they are the two an archive's value rests on.
 *
 * **The blend is the affine weighted mean.** Not approximately, and not only for two parents:
 * [weightedBlendIsExactlyTheAffineMeanOfItsParents] recomputes `Σ wᵢ·vᵢ` from the manifest the sweep
 * wrote and compares it against the `key.json` beside it, which is the whole provenance contract. If
 * the weights in the manifest do not reproduce the key, an offline pass cannot attribute anything to
 * anything.
 *
 * **A seed reproduces a night.** [theSameSeedDrawsTheSameRun] runs two archives and compares them
 * render for render; [resumeSkipsFinishedWorkWithoutChangingTheDraw] proves the harder half of that,
 * which is that resuming a half-finished archive draws the *same* renders it would have drawn
 * without the interruption.
 */
class KeyframeSweepTest {

    private fun tempDir(): Path = Files.createTempDirectory("keyframe-sweep-test")

    /** A scope with float lanes for [names], each reading and writing a slot of [live]. */
    private fun scopeOver(live: MutableMap<String, Float>, vararg names: String): KeyframeScope =
        KeyframeScope().apply {
            names.forEach { name ->
                floatTarget(name, { live[name] ?: 0f }) { live[name] = it }
            }
        }

    /** Write one key file into [dir], flat, the way a sweep archive does. */
    private fun writeKey(scope: KeyframeScope, dir: Path, name: String, values: Map<String, Any?>): Path {
        dir.createDirectories()
        val path = dir.resolve(name)
        path.writeText(scope.encodeKeyJson(scope.Key(values.toMutableMap())))
        return path
    }

    /** Three keys with one float lane each, at 0, 10 and 100 — spread wide so a mean is unmistakable. */
    private fun simplePool(scope: KeyframeScope, dir: Path) {
        writeKey(scope, dir, "a", mapOf("gain" to 0f, "size" to 1f))
        writeKey(scope, dir, "b", mapOf("gain" to 10f, "size" to 2f))
        writeKey(scope, dir, "c", mapOf("gain" to 100f, "size" to 3f))
    }

    /** A callback that just proves it ran, and counts. */
    private class Counting {
        var calls = 0
        val body: suspend (SweepRender) -> Unit = { render ->
            calls++
            render.file("ran.txt").writeText("render ${render.index}")
            render.artifact("marker", render.file("ran.txt"))
        }
    }

    // ==========================================================================================
    // The blend
    // ==========================================================================================

    @Test
    fun weightedBlendIsExactlyTheAffineMeanOfItsParents() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        val live = mutableMapOf("gain" to 0f, "size" to 0f)
        val scope = scopeOver(live, "gain", "size")
        simplePool(scope, pool)

        val counting = Counting()
        val report = runBlocking {
            KeyframeSweep(
                scope, pool, out,
                // Every render extrapolates, so the check covers negative weights too — the case a
                // pairwise fold is most likely to get wrong.
                SweepConfig(seed = 11, count = 12, minArity = 2, maxArity = 3, extrapolationProbability = 0.5)
            ).run(counting.body)
        }

        assertEquals(12, report.completed, "every render completed")
        assertEquals(12, counting.calls, "the callback ran once per render")

        val rows = readSweepArchive(out)
        assertEquals(12, rows.size, "one manifest per render")

        var extrapolated = 0
        rows.forEach { manifest ->
            val sum = manifest.parents.sumOf { it.weight }
            assertTrue(
                abs(sum - 1.0) < 1e-9,
                "render ${manifest.id}: weights must sum to 1, not $sum"
            )
            if (manifest.extrapolationGain != null) extrapolated++

            val blended = scope.loadKey(out.resolve("renders/${manifest.id}/key.json"))
            listOf("gain", "size").forEach { lane ->
                val expected = manifest.parents.sumOf { parent ->
                    val parentKey = scope.loadKey(pool.resolve(parent.key))
                    parent.weight * (parentKey[lane] as Float).toDouble()
                }
                val actual = (blended[lane] as Float).toDouble()
                assertTrue(
                    abs(actual - expected) < 1e-3,
                    "render ${manifest.id} lane $lane: manifest weights give $expected, key.json has $actual"
                )
            }
        }
        assertTrue(extrapolated > 0, "at p=0.5 over 12 renders, some blend should have left the hull")
    }

    @Test
    fun extrapolationPushesPastTheParentsAndSaysByHowMuch() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val live = mutableMapOf("gain" to 0f)
        val scope = scopeOver(live, "gain")
        writeKey(scope, pool, "low", mapOf("gain" to 0f))
        writeKey(scope, pool, "high", mapOf("gain" to 10f))

        val out = root.resolve("archive")
        runBlocking {
            KeyframeSweep(
                scope, pool, out,
                SweepConfig(
                    seed = 5, count = 24, minArity = 2, maxArity = 2,
                    extrapolationProbability = 1.0,
                    minExtrapolationGain = 2.0, maxExtrapolationGain = 2.0
                )
            ).run(Counting().body)
        }

        val rows = readSweepArchive(out)
        assertEquals(24, rows.size)
        val (pushed, convex) = rows.partition { it.extrapolationGain != null }
        assertTrue(pushed.isNotEmpty(), "at p=1.0 most renders should have been pushed")
        assertTrue(pushed.all { it.extrapolationGain == 2.0 }, "…each at the pinned gain")

        // Gain is measured in hull-crossings, so above 1.0 it is *always* outside — the anchor's own
        // weight is exactly `1 - gain`, and both parents are 0 and 10, so the value leaves 0..10.
        pushed.forEach { manifest ->
            val pole = assertNotNull(manifest.extrapolationPole, "an extrapolated render names its pole")
            val poleWeight = manifest.parents.first { it.key == pole }.weight
            assertTrue(
                abs(poleWeight - (1.0 - 2.0)) < 1e-9,
                "render ${manifest.id}: at gain 2 the pole's weight is -1, not $poleWeight"
            )
            val value = scope.loadKey(out.resolve("renders/${manifest.id}/key.json"))["gain"] as Float
            assertTrue(
                value < 0f || value > 10f,
                "render ${manifest.id} extrapolated but landed at $value, inside the parents' 0..10"
            )
        }

        // And the guard: a blend already dominated by one key is left convex rather than blasted,
        // because the push needed to escape a hull you are already standing at the edge of is huge.
        // The pole is not the anchor: the key a blend flees should not also be the key whose code
        // and strings it wears. Over 24 renders they must disagree at least once, and under gain 2
        // the pole is the one weight below zero while the anchor is drawn by magnitude — so the
        // anchor is usually the *dominant* key instead.
        assertTrue(
            pushed.any { it.extrapolationPole != it.anchor },
            "pole and anchor are separate draws and must sometimes differ"
        )

        convex.forEach { manifest ->
            assertEquals(null, manifest.extrapolationPole, "a convex blend has no pole")
            val dominant = manifest.parents.maxOf { it.weight }
            assertTrue(
                dominant > 0.9,
                "render ${manifest.id} declined to extrapolate, so one key must have dominated it; " +
                    "largest weight was $dominant"
            )
            val value = scope.loadKey(out.resolve("renders/${manifest.id}/key.json"))["gain"] as Float
            assertTrue(value in 0f..10f, "a convex blend stays between its parents; got $value")
        }
    }

    @Test
    fun arityOneReplaysOneKeyExactly() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val live = mutableMapOf("gain" to 0f, "size" to 0f)
        val scope = scopeOver(live, "gain", "size")
        simplePool(scope, pool)

        val out = root.resolve("archive")
        runBlocking {
            KeyframeSweep(
                scope, pool, out,
                SweepConfig(seed = 3, count = 6, minArity = 1, maxArity = 1, extrapolationProbability = 1.0)
            ).run(Counting().body)
        }

        readSweepArchive(out).forEach { manifest ->
            assertEquals(1, manifest.parents.size, "arity 1 means one parent")
            assertEquals(1.0, manifest.parents[0].weight, "…at full weight")
            assertEquals(null, manifest.extrapolationGain, "there is nothing to extrapolate away from")
            val parent = scope.loadKey(pool.resolve(manifest.parents[0].key))
            val blended = scope.loadKey(out.resolve("renders/${manifest.id}/key.json"))
            assertEquals(parent["gain"], blended["gain"], "a blend of one key is that key")
            assertEquals(parent["size"], blended["size"])
        }
    }

    @Test
    fun discreteLanesComeWholeFromOneParentAndTheManifestSaysWhich() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val live = mutableMapOf("gain" to 0f)
        val scope = scopeOver(live, "gain").apply {
            stringTarget("look", { "" }) { }
        }
        writeKey(scope, pool, "a", mapOf("gain" to 0f, "look" to "warm"))
        writeKey(scope, pool, "b", mapOf("gain" to 10f, "look" to "cool"))
        writeKey(scope, pool, "c", mapOf("gain" to 50f, "look" to "acid"))

        val out = root.resolve("archive")
        runBlocking {
            KeyframeSweep(
                scope, pool, out,
                SweepConfig(seed = 19, count = 20, minArity = 2, maxArity = 3, extrapolationProbability = 0.4)
            ).run(Counting().body)
        }

        val byLook = mutableMapOf<String, Int>()
        readSweepArchive(out).forEach { manifest ->
            val owner = manifest.discreteLanes["look"]
            assertNotNull(owner, "render ${manifest.id} must record where its discrete lane came from")
            assertEquals(manifest.anchor, owner, "under DiscreteBlend.ANCHOR that owner is the anchor")
            assertTrue(
                manifest.parents.any { it.key == owner },
                "the owner must be one of the render's own parents"
            )

            val blended = scope.loadKey(out.resolve("renders/${manifest.id}/key.json"))
            val expected = scope.loadKey(pool.resolve(owner))["look"] as String
            assertEquals(expected, blended["look"] as String, "the lane is that parent's value, whole")
            byLook[expected] = (byLook[expected] ?: 0) + 1
        }
        assertTrue(
            byLook.size > 1,
            "over 20 renders the roulette should have picked more than one look; got $byLook"
        )
    }

    @Test
    fun aMissingLaneIsBackFilledFromTheLiveScopeAndRecordedAsSuch() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val live = mutableMapOf("gain" to 0f, "size" to 7f)
        val scope = scopeOver(live, "gain", "size")
        // The old key predates the `size` target; the new one has it.
        writeKey(scope, pool, "old", mapOf("gain" to 0f))
        writeKey(scope, pool, "new", mapOf("gain" to 10f, "size" to 3f))

        val out = root.resolve("archive")
        runBlocking {
            KeyframeSweep(
                scope, pool, out,
                SweepConfig(seed = 2, count = 4, minArity = 2, maxArity = 2, extrapolationProbability = 0.0)
            ).run(Counting().body)
        }

        readSweepArchive(out).forEach { manifest ->
            val old = manifest.parents.first { it.key == "old" }
            assertEquals(listOf("size"), old.filled, "the old key's missing lane is named in the manifest")
            assertTrue(manifest.parents.first { it.key == "new" }.filled.isEmpty())
            assertTrue(manifest.droppedLanes.isEmpty(), "a filled lane is not a dropped lane")

            val blended = scope.loadKey(out.resolve("renders/${manifest.id}/key.json"))
            assertEquals(2, blended.values.size, "both lanes survived the blend")
            // `old` contributed the live 7 and `new` its own 3, so the blend is between them.
            val size = blended["size"] as Float
            assertTrue(size in 3f..7f, "the filled lane blended against the live value; got $size")
        }
    }

    @Test
    fun theIntersectPolicyBlendsOnlyTheSharedLanes() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val live = mutableMapOf("gain" to 0f, "size" to 7f)
        val scope = scopeOver(live, "gain", "size")
        writeKey(scope, pool, "old", mapOf("gain" to 0f))
        writeKey(scope, pool, "new", mapOf("gain" to 10f, "size" to 3f))

        val out = root.resolve("archive")
        runBlocking {
            KeyframeSweep(
                scope, pool, out,
                SweepConfig(
                    seed = 2, count = 3, minArity = 2, maxArity = 2,
                    extrapolationProbability = 0.0, missingSlots = MissingSlots.INTERSECT
                )
            ).run(Counting().body)
        }

        readSweepArchive(out).forEach { manifest ->
            assertTrue(manifest.parents.all { it.filled.isEmpty() }, "INTERSECT fills nothing")
            val blended = scope.loadKey(out.resolve("renders/${manifest.id}/key.json"))
            assertEquals(setOf("gain"), blended.values.keys, "only the lane both keys had is blended")
        }
    }

    @Test
    fun requireAllExcludesThePartialKeysAndCountsThem() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val live = mutableMapOf("gain" to 0f, "size" to 7f)
        val scope = scopeOver(live, "gain", "size")
        writeKey(scope, pool, "old", mapOf("gain" to 0f))
        writeKey(scope, pool, "full-a", mapOf("gain" to 10f, "size" to 3f))
        writeKey(scope, pool, "full-b", mapOf("gain" to 20f, "size" to 4f))

        val out = root.resolve("archive")
        val report = runBlocking {
            KeyframeSweep(
                scope, pool, out,
                SweepConfig(
                    seed = 2, count = 5, minArity = 2, maxArity = 2,
                    extrapolationProbability = 0.0, missingSlots = MissingSlots.REQUIRE_ALL
                )
            ).run(Counting().body)
        }

        assertEquals(2, report.poolSize, "only the two complete keys are eligible")
        assertEquals(1, report.ineligible, "…and the partial one is counted, not silently dropped")
        assertTrue(
            readSweepArchive(out).none { m -> m.parents.any { it.key == "old" } },
            "the partial key never appears as a parent"
        )
    }

    @Test
    fun aBlendedIntLaneComesBackAsAnInt() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val scope = KeyframeScope()
        writeKey(scope, pool, "a", mapOf("layers" to 3, "gain" to 0f))
        writeKey(scope, pool, "b", mapOf("layers" to 8, "gain" to 1f))

        val out = root.resolve("archive")
        runBlocking {
            KeyframeSweep(
                scope, pool, out,
                SweepConfig(seed = 4, count = 5, minArity = 2, maxArity = 2, extrapolationProbability = 0.0)
            ).run(Counting().body)
        }

        val values = readSweepArchive(out).map {
            scope.loadKey(out.resolve("renders/${it.id}/key.json"))["layers"]
        }
        assertTrue(values.all { it is Int }, "preserveNumericKind rounds back to the anchor's Int; got $values")
        assertTrue(values.all { (it as Int) in 3..8 }, "…and stays between the parents; got $values")

        // Turned off, the same lane widens to a Double, which is what an Int uniform would refuse.
        val wide = root.resolve("wide")
        runBlocking {
            KeyframeSweep(
                scope, pool, wide,
                SweepConfig(
                    seed = 4, count = 3, minArity = 2, maxArity = 2,
                    extrapolationProbability = 0.0, preserveNumericKind = false
                )
            ).run(Counting().body)
        }
        assertTrue(
            readSweepArchive(wide).any {
                scope.loadKey(wide.resolve("renders/${it.id}/key.json"))["layers"] is Double
            },
            "without preserveNumericKind the blend widens to Double"
        )
    }

    // ==========================================================================================
    // Reproducibility
    // ==========================================================================================

    @Test
    fun theSameSeedDrawsTheSameRun() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val live = mutableMapOf("gain" to 0f, "size" to 0f)
        val scope = scopeOver(live, "gain", "size")
        simplePool(scope, pool)
        val config = SweepConfig(seed = 4242, count = 8, minArity = 1, maxArity = 3)

        fun drive(out: Path) = runBlocking { KeyframeSweep(scope, pool, out, config).run(Counting().body) }
        drive(root.resolve("first"))
        drive(root.resolve("second"))

        val first = readSweepArchive(root.resolve("first"))
        val second = readSweepArchive(root.resolve("second"))
        assertEquals(8, first.size)
        first.zip(second).forEach { (a, b) ->
            assertEquals(a.id, b.id, "the directory name is a function of the seed and the index")
            assertEquals(a.parents, b.parents, "render ${a.index}: same parents at the same weights")
            assertEquals(a.anchor, b.anchor)
            assertEquals(a.extrapolationGain, b.extrapolationGain)
        }
        assertEquals(
            root.resolve("first/renders/${first[0].id}/key.json").readText(),
            root.resolve("second/renders/${second[0].id}/key.json").readText(),
            "…right down to the bytes of the blended key"
        )
    }

    @Test
    fun aDifferentSeedDrawsADifferentRun() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)

        fun drive(out: Path, seed: Long) = runBlocking {
            KeyframeSweep(scope, pool, out, SweepConfig(seed = seed, count = 8, minArity = 2, maxArity = 3))
                .run(Counting().body)
        }
        drive(root.resolve("a"), 1)
        drive(root.resolve("b"), 2)

        val a = readSweepArchive(root.resolve("a")).map { it.parents }
        val b = readSweepArchive(root.resolve("b")).map { it.parents }
        assertTrue(a != b, "two seeds must not explore the same eight points")
    }

    @Test
    fun resumeSkipsFinishedWorkWithoutChangingTheDraw() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)
        val config = SweepConfig(seed = 77, count = 9, minArity = 2, maxArity = 3)

        // A night that was cut short, then resumed.
        val firstPass = runBlocking {
            KeyframeSweep(scope, pool, out, config.copy(count = 4)).run(Counting().body)
        }
        assertEquals(4, firstPass.completed)

        val resumed = runBlocking { KeyframeSweep(scope, pool, out, config).run(Counting().body) }
        assertEquals(4, resumed.skipped, "the first four were already done")
        assertEquals(5, resumed.completed, "…and the remaining five ran")

        // The uninterrupted run, for comparison: resuming must not have changed what was drawn.
        val straight = root.resolve("straight")
        runBlocking { KeyframeSweep(scope, pool, straight, config).run(Counting().body) }
        val interrupted = readSweepArchive(out)
        val uninterrupted = readSweepArchive(straight)
        assertEquals(9, interrupted.size)
        interrupted.zip(uninterrupted).forEach { (a, b) ->
            assertEquals(a.parents, b.parents, "render ${a.index} was drawn the same either way")
        }
    }

    @Test
    fun aRenderLeftRunningByADeadProcessIsRedoneRatherThanSkipped() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)
        val config = SweepConfig(seed = 8, count = 3, minArity = 2, maxArity = 2)

        runBlocking { KeyframeSweep(scope, pool, out, config).run(Counting().body) }
        val victim = readSweepArchive(out)[1]
        val manifestFile = out.resolve("renders/${victim.id}/manifest.json")
        manifestFile.writeText(manifestFile.readText().replace("\"status\": \"ok\"", "\"status\": \"running\""))
        out.resolve("renders/${victim.id}/ran.txt").let { Files.delete(it) }

        val second = runBlocking { KeyframeSweep(scope, pool, out, config).run(Counting().body) }
        assertEquals(2, second.skipped, "the two terminal renders were skipped")
        assertEquals(1, second.completed, "the one left mid-flight was redone")
        assertTrue(
            out.resolve("renders/${victim.id}/ran.txt").exists(),
            "…and its callback ran again, rewriting what the dead process never finished"
        )
    }

    // ==========================================================================================
    // Surviving the night
    // ==========================================================================================

    @Test
    fun aThrowingCallbackFailsOneRenderAndTheRunCarriesOn() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)

        var calls = 0
        val report = runBlocking {
            KeyframeSweep(scope, pool, out, SweepConfig(seed = 1, count = 6, minArity = 2, maxArity = 2))
                .run { render ->
                    calls++
                    if (render.index % 2 == 0) error("deliberate failure on ${render.index}")
                    render.file("ran.txt").writeText("ok")
                }
        }

        assertEquals(6, calls, "every render was attempted")
        assertEquals(3, report.completed)
        assertEquals(3, report.failed)
        assertEquals("count", report.stoppedBecause, "alternating failures are not a broken night")

        val rows = readSweepArchive(out)
        val failedRow = rows.first { it.status == "failed" }
        assertEquals("deliberate failure on ${failedRow.index}", failedRow.error, "the failure is recorded")
        assertTrue(
            out.resolve("renders/${failedRow.id}").exists(),
            "a failed render keeps its directory for forensics"
        )
        assertTrue(rows.filter { it.status == "ok" }.all { it.error == null })
    }

    @Test
    fun enoughConsecutiveFailuresEndsTheRun() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)

        var calls = 0
        val report = runBlocking {
            KeyframeSweep(
                scope, pool, out,
                SweepConfig(seed = 1, count = 500, minArity = 2, maxArity = 2, maxConsecutiveFailures = 3)
            ).run { _ ->
                calls++
                error("everything is broken")
            }
        }

        assertEquals(3, calls, "it gave up after three in a row rather than writing 500 empty renders")
        assertEquals(0, report.completed)
        assertEquals(3, report.failed)
        assertEquals("failures", report.stoppedBecause)
    }

    @Test
    fun theDiskFloorStopsTheRunBeforeTheDiskDoes() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)

        val counting = Counting()
        val report = runBlocking {
            KeyframeSweep(
                scope, pool, out,
                // No filesystem has this much free, so the floor trips on the first check.
                SweepConfig(seed = 1, count = 50, minFreeBytes = Long.MAX_VALUE)
            ).run(counting.body)
        }

        assertEquals(0, counting.calls, "not one render was started")
        assertEquals("disk", report.stoppedBecause)
        assertTrue(out.resolve("sweep.json").exists(), "…and the run still described itself")
        assertTrue(
            out.resolve("sweep.json").readText().contains("\"stoppedBecause\": \"disk\""),
            "the reason is in sweep.json, where the morning will look for it"
        )
    }

    @Test
    fun theDurationBudgetEndsTheRun() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)

        val report = runBlocking {
            KeyframeSweep(
                scope, pool, out,
                SweepConfig(seed = 1, count = 10_000, minArity = 2, maxArity = 2, maxDurationMs = 150)
            ).run { render -> render.file("ran.txt").writeText("ok") }
        }

        assertEquals("duration", report.stoppedBecause)
        assertTrue(report.completed > 0, "it did some work before the budget ran out")
        assertTrue(report.completed < 10_000, "…and did not do all of it")
    }

    @Test
    fun theScopeIsPutBackWhenTheRunEnds() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val live = mutableMapOf("gain" to 42f, "size" to 5f)
        val scope = scopeOver(live, "gain", "size")
        simplePool(scope, pool)

        var sawApplied = false
        runBlocking {
            KeyframeSweep(scope, pool, root.resolve("archive"), SweepConfig(seed = 1, count = 4))
                .run { render ->
                    // The blend really is live while the callback runs — that is the point of it.
                    if (live["gain"] != 42f) sawApplied = true
                    assertEquals(render.key["gain"] as Float, live["gain"], "the live lane is the blend's")
                    render.file("ran.txt").writeText("ok")
                }
        }

        assertTrue(sawApplied, "applyToScope wrote the blend to the live targets")
        assertEquals(42f, live["gain"], "…and the scope was restored when the run ended")
        assertEquals(5f, live["size"])
    }

    @Test
    fun anEmptyPoolIsAnErrorRatherThanAnEmptyArchive() {
        val root = tempDir()
        val pool = root.resolve("pool").apply { createDirectories() }
        val scope = scopeOver(mutableMapOf(), "gain")

        val failure = assertFailsWith<IllegalArgumentException> {
            runBlocking {
                KeyframeSweep(scope, pool, root.resolve("archive"), SweepConfig(count = 3)).run(Counting().body)
            }
        }
        assertTrue(failure.message!!.contains("no usable keys"), "it says what is wrong: ${failure.message}")
    }

    @Test
    fun aDamagedKeyInThePoolIsRecordedAndTheRestStillSweep() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)
        // Two kinds of non-key, which the collection treats differently on purpose: a key whose
        // JSON no longer decodes is damage worth naming, and a stray text file is just a notice.
        pool.resolve("broken.json").writeText("""{"values": {"gain": {"type": "num"}}}""")
        pool.resolve("notes.txt").writeText("remember to try the cold palette")

        val report = runBlocking {
            KeyframeSweep(scope, pool, out, SweepConfig(seed = 1, count = 4, minArity = 2, maxArity = 2))
                .run(Counting().body)
        }

        assertEquals(4, report.completed, "three good keys are still a space")
        assertEquals(3, report.poolSize, "neither non-key became a pool key")
        val runManifest = out.resolve("sweep.json").readText()
        assertTrue(runManifest.contains("broken.json"), "sweep.json names the key it could not read")
        assertTrue(
            runManifest.contains("1 file ignored"),
            "…and counts what was never a key at all: $runManifest"
        )
    }

    // ==========================================================================================
    // The archive as an interface
    // ==========================================================================================

    @Test
    fun theIndexHasOneLinePerRenderAndReindexRebuildsItFromTheManifests() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)
        val sweep = KeyframeSweep(scope, pool, out, SweepConfig(seed = 6, count = 5, minArity = 2, maxArity = 2))
        runBlocking { sweep.run(Counting().body) }

        val index = out.resolve("index.jsonl")
        assertEquals(5, index.readText().trim().lines().size, "one compact line per render")

        // A crash can leave the index short; the manifests are the record, so reindex repairs it.
        index.writeText(index.readText().trim().lines().first() + "\n")
        assertEquals(1, readSweepArchive(out).size, "…and a short index is believed until repaired")
        assertEquals(5, sweep.reindex(), "reindex rebuilt every line")
        assertEquals(5, readSweepArchive(out).size)
    }

    @Test
    fun readingAnArchiveFallsBackToTheManifestsWhenTheIndexIsGone() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)
        runBlocking {
            KeyframeSweep(scope, pool, out, SweepConfig(seed = 6, count = 4, minArity = 2, maxArity = 2))
                .run(Counting().body)
        }

        Files.delete(out.resolve("index.jsonl"))
        val rows = readSweepArchive(out)
        assertEquals(4, rows.size, "the tree still describes itself")
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.index }, "…in order")
        assertTrue(sweepArchiveSummary(out).contains("4 renders"), "and the morning summary reads it")
    }

    @Test
    fun anArchiveOfRendersIsItselfAValidPool() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val first = root.resolve("night-one")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)
        runBlocking {
            KeyframeSweep(scope, pool, first, SweepConfig(seed = 6, count = 5, minArity = 2, maxArity = 2))
                .run(Counting().body)
        }

        // The recursion that makes a sweep worth running twice: last night's output as tonight's input.
        val second = root.resolve("night-two")
        val report = runBlocking {
            KeyframeSweep(
                scope, first.resolve("renders"), second,
                // Only the key.json files are keys; manifest.json and ran.txt must not be.
                SweepConfig(seed = 7, count = 4, minArity = 2, maxArity = 2, pattern = "*/key.json")
            ).run(Counting().body)
        }
        assertEquals(5, report.poolSize, "every render of night one is a key for night two")
        assertEquals(4, report.completed)
        assertTrue(
            readSweepArchive(second).all { m -> m.parents.all { it.key.endsWith("key.json") } },
            "…and the parents are those keys"
        )
    }

    @Test
    fun theRunManifestRecordsTheConfigurationItRanWith() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)
        val config = SweepConfig(seed = 31, count = 3, minArity = 2, maxArity = 2, concentration = 0.4)
        runBlocking { KeyframeSweep(scope, pool, out, config).run(Counting().body) }

        val text = out.resolve("sweep.json").readText()
        assertTrue(text.contains("\"seed\": 31"), "the seed a night is reproducible from")
        assertTrue(text.contains("\"concentration\": 0.4"), "…and the knob that shaped it")
        assertTrue(text.contains("\"gain\""), "scopeSlots records what the scope was watching")
        assertTrue(text.contains("\"completed\": 3"))
    }

    @Test
    fun theOnRenderHookSeesEveryTerminalRenderOnce() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)

        val seen = mutableListOf<String>()
        runBlocking {
            KeyframeSweep(
                scope, pool, root.resolve("archive"),
                SweepConfig(seed = 1, count = 4, minArity = 2, maxArity = 2),
                onRender = { seen += "${it.index}:${it.status}" }
            ).run { render ->
                if (render.index == 2) error("one bad render")
                render.file("ran.txt").writeText("ok")
            }
        }

        assertEquals(listOf("0:ok", "1:ok", "2:failed", "3:ok"), seen, "progress reporting sees failures too")
    }

    @Test
    fun artifactsAreRecordedIncludingTheOnesThatCouldNotBeMade() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        val scope = scopeOver(mutableMapOf(), "gain", "size")
        simplePool(scope, pool)

        runBlocking {
            KeyframeSweep(scope, pool, out, SweepConfig(seed = 1, count = 2, minArity = 2, maxArity = 2))
                .run { render ->
                    val still = render.file("still.txt")
                    still.writeText("pretend pixels")
                    render.artifact("still", still)
                    render.artifact("clip", note = "no ffmpeg on this machine")
                }
        }

        readSweepArchive(out).forEach { manifest ->
            assertEquals(2, manifest.artifacts.size)
            val still = manifest.artifacts.first { it.kind == "still" }
            assertEquals("still.txt", still.file, "recorded relative to the render directory")
            assertEquals(14L, still.bytes, "…with its size, so an empty file is visible as one")
            val clip = manifest.artifacts.first { it.kind == "clip" }
            assertEquals(null, clip.file, "a missing artifact has no file")
            assertEquals("no ffmpeg on this machine", clip.note, "…and a stated reason instead")
        }
    }

    // ==========================================================================================
    // Sampling
    // ==========================================================================================

    @Test
    fun dirichletWeightsSumToOneAndAreUniformAtConcentrationOne() {
        val rng = Random(1234)
        val n = 4
        val means = DoubleArray(n)
        val trials = 4000
        repeat(trials) {
            val w = dirichlet(rng, n, 1.0)
            assertTrue(abs(w.sum() - 1.0) < 1e-9, "weights must sum to 1, not ${w.sum()}")
            assertTrue(w.all { it >= 0.0 }, "a Dirichlet draw is non-negative before extrapolation")
            for (i in 0 until n) means[i] += w[i] / trials
        }
        // Symmetric Dirichlet: every component has mean 1/n. Standard error here is ~0.003.
        means.forEachIndexed { i, mean ->
            assertTrue(abs(mean - 1.0 / n) < 0.02, "component $i mean $mean should be near ${1.0 / n}")
        }
    }

    @Test
    fun lowConcentrationBunchesIntoTheCornersAndHighConcentrationEvensOut() {
        val n = 5
        fun meanLargestWeight(concentration: Double): Double {
            val rng = Random(99)
            return (0 until 2000).sumOf { dirichlet(rng, n, concentration).max() } / 2000.0
        }

        val corner = meanLargestWeight(0.1)
        val uniform = meanLargestWeight(1.0)
        val mush = meanLargestWeight(50.0)

        // The claim the docs page makes about what the knob does, in one line of arithmetic. The
        // uniform band is pinned tightly because that one has a closed form: for a symmetric
        // Dirichlet(1) over n components the largest weight averages (1/n)·Σᵢ 1/i, which is 0.4567
        // at n = 5.
        assertTrue(corner > 0.75, "at 0.1 a blend is mostly one key; largest weight averaged $corner")
        assertTrue(uniform in 0.43..0.49, "at 1.0 it is uniform over the simplex; averaged $uniform")
        assertTrue(mush < 0.28, "at 50 every blend is the average; largest weight averaged $mush")
        assertTrue(corner > uniform && uniform > mush, "the knob is monotone")
    }

    @Test
    fun theAnchorRouletteFollowsTheWeights() {
        val rng = Random(7)
        val weights = doubleArrayOf(0.7, 0.2, 0.1)
        val counts = IntArray(3)
        repeat(10_000) { counts[roulette(rng, weights)]++ }
        assertTrue(abs(counts[0] / 10_000.0 - 0.7) < 0.02, "got ${counts.toList()}")
        assertTrue(abs(counts[1] / 10_000.0 - 0.2) < 0.02, "got ${counts.toList()}")
        assertTrue(abs(counts[2] / 10_000.0 - 0.1) < 0.02, "got ${counts.toList()}")

        // Under extrapolation a weight goes negative, and a key pulling hard away from the blend
        // still has a large say in it — so the roulette is over magnitudes.
        val negative = doubleArrayOf(1.6, -0.6)
        val negativeCounts = IntArray(2)
        repeat(10_000) { negativeCounts[roulette(rng, negative)]++ }
        assertTrue(negativeCounts[1] > 1000, "the negative weight must be reachable; got ${negativeCounts.toList()}")
    }

    @Test
    fun configurationRefusesAnImpossibleSweep() {
        assertFailsWith<IllegalArgumentException> { SweepConfig(minArity = 0) }
        assertFailsWith<IllegalArgumentException> { SweepConfig(minArity = 4, maxArity = 2) }
        assertFailsWith<IllegalArgumentException> { SweepConfig(concentration = 0.0) }
        assertFailsWith<IllegalArgumentException> { SweepConfig(extrapolationProbability = 1.5) }
        assertFailsWith<IllegalArgumentException> { SweepConfig(minExtrapolationGain = 0.5) }
        assertFailsWith<IllegalArgumentException> { SweepConfig(maxConsecutiveFailures = 0) }
        assertEquals(2..3, SweepConfig().arity, "the defaults blend two or three keys")
    }

    @Test
    fun arityIsClampedToThePoolRatherThanFailing() {
        val root = tempDir()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        val scope = scopeOver(mutableMapOf(), "gain")
        writeKey(scope, pool, "only", mapOf("gain" to 1f))

        val report = runBlocking {
            KeyframeSweep(scope, pool, out, SweepConfig(seed = 1, count = 3, minArity = 3, maxArity = 5))
                .run(Counting().body)
        }

        assertEquals(3, report.completed, "a pool of one still sweeps")
        assertTrue(
            readSweepArchive(out).all { it.parents.size == 1 },
            "…with the arity clamped to what there is"
        )
        assertFalse(
            readSweepArchive(out).any { it.parents.size > 1 },
            "and never asks for a key twice in one blend"
        )
    }
}
