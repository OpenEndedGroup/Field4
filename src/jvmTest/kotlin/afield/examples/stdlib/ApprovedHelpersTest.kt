package afield.examples.stdlib

import afield.animation.keyframes.KeyframeScope
import afield.document.graph.Box
import afield.geometry.mesh.*
import afield.geometry.spatial.AxisAlignedBoundingBox3D
import afield.graphics.render.bindPush
import afield.graphics.shader.Introspected
import afield.graphics.shader.PrimitiveType
import afield.graphics.vulkan.buffers.Structure
import afield.graphics.vulkan.buffers.bindPushConstant
import afield.math.linalg.Vec3
import afield.runtime.lifecycle.RunScope
import afield.ui.output.*
import arrow.core.left
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.test.*

class ApprovedHelpersTest {
    @Test fun numericPropertiesHaveFixedPrecisionAndStableLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("1.235", 1.23456.f3)
            assertEquals("-2.50", (-2.5).f2)
            assertEquals("4", 4.f0)
            assertEquals("4.000000000", 4.f9)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun uintBindingsAcceptLiveIntAndUIntValuesAndRejectOtherKinds() {
        val structure = Structure().setMembers(listOf(Introspected.Member("count", 0, PrimitiveType.uint.left())))
        var count = 7
        structure.bindPushConstant("count", { count }) { fail("missing member") }
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
        structure.writeToBuffer(bytes, null) { fail("unexpected reference") }
        assertEquals(7, bytes.getInt(0))
        count = 19
        structure.writeToBuffer(bytes, null) { fail("unexpected reference") }
        assertEquals(19, bytes.getInt(0))
        structure.bindPushConstant("count", { UInt.MAX_VALUE }) { fail("missing member") }
        structure.writeToBuffer(bytes, null) { fail("unexpected reference") }
        assertEquals(-1, bytes.getInt(0), "unsigned bits survive above Int.MAX_VALUE")
        structure.bindPushConstant("count", { 1.0f }) { fail("missing member") }
        assertFailsWith<IllegalArgumentException> { structure.writeToBuffer(bytes, null) { fail("reference") } }
    }

    @Test fun bindPushSharesLiveBindingsWithoutSamplingThem() {
        class Target { var count: () -> Int = { 0 } }
        val first = Target()
        val second = Target()
        var calls = 0
        var value = 3
        bindPush(first, second) { count = { calls++; value } }
        assertEquals(0, calls)
        assertEquals(3, first.count())
        value = 11
        assertEquals(11, second.count())
        assertEquals(2, calls)
    }

    @Test fun keyPairsKeepNullsAndLastDuplicateValues() {
        val scope = KeyframeScope()
        val key = scope.Key("amount" to 1.0, "name" to "test", "unset" to null, "amount" to 2.0)
        assertEquals(2.0, key["amount"])
        assertEquals("test", key["name"])
        assertTrue(key.values.containsKey("unset"))
        assertNull(key["unset"])
        assertTrue(scope.Key().values.isEmpty())
    }

    @Test fun meshIntegralMatchesAreaAndGaussBonnet() {
        val sphere = icosphereMesh(subdivisions = 2)
        // Positions and cotangent weights pass through float vectors; compare independent area
        // calculations at that precision, as in MeshQueryTest.
        assertEquals(sphere.surfaceArea(), sphere.integrate(DoubleArray(sphere.vertexCount) { 1.0 }), 1e-6)
        assertEquals(4 * PI, sphere.integrate(sphere.gaussianCurvature()), 1e-5)
        assertEquals(sphere.surfaceArea() * 2, sphere.integrate(FloatArray(sphere.vertexCount) { 2f }), 2e-6)
        assertFailsWith<IllegalArgumentException> { sphere.integrate(doubleArrayOf(1.0)) }
    }

    @Test fun seededPointsAreRepeatableBoundedAndDoNotShareMutableVectors() {
        val bounds = AxisAlignedBoundingBox3D(Vec3(-2f, 1f, 4f), Vec3(3f, 5f, 4f))
        val first = randomPoints(20, seed = 31, bounds = bounds)
        val second = randomPoints(20, seed = 31, bounds = bounds)
        assertEquals(first, second)
        assertTrue(first.all { it.x in -2f..3f && it.y in 1f..5f && it.z == 4f })
        first[0].x = 100f
        assertTrue(second[0].x <= 3f)
        assertTrue(randomPoints(0, bounds = bounds).isEmpty())
        assertFailsWith<IllegalArgumentException> { randomPoints(-1, bounds = bounds) }
    }

    @Test fun comparisonPrintsMeasuredAnalyticAndErrorsIncludingZeroReference() {
        val box = Box("compare")
        val printed = mutableListOf<String>()
        box.output.proxy = { value, _, _ -> printed += value.toString() }
        box.compare("area", 6.01, 6.0)
        assertTrue("measured 6.010000" in printed.last())
        assertTrue("analytic 6.000000" in printed.last())
        assertTrue("absolute error 0.010000" in printed.last())
        assertTrue("relative" in printed.last())
        box.compare("zero", 0.01, 0)
        assertFalse("relative" in printed.last())
    }

    @Test fun markdownAppendTrimsOldEntriesAndOversizedInput() {
        val box = Box("markdown-bounded")
        var html = ""
        box.output.displayProxy = { _, value -> html = value.toString() }
        box.markdownAppend("log", "first entry", maxEntries = 2, open = false)
        box.markdownAppend("log", "second entry", maxEntries = 2, open = false)
        box.markdownAppend("log", "third entry", maxEntries = 2, open = false)
        assertFalse("first entry" in html)
        assertTrue("second entry" in html && "third entry" in html)
        box.markdownAppend("log", "ABCDEFGHIJK", maxChars = 8, open = false)
        assertTrue("DEFGHIJK" in html)
        assertFalse("second entry" in html)
        assertFailsWith<IllegalArgumentException> { box.markdownAppend("log", "x", maxChars = 0) }
    }

    @Test fun olderCleanupCannotAppendAfterANewerRunClaimsTheChannel() {
        val box = Box("markdown-rerun")
        var html = ""
        box.output.displayProxy = { _, value -> html = value.toString() }
        val old = box.runScopes.begin("code", "test")
        RunScope.withCurrent(old) { box.markdownAppend("log", "old cleanup", open = false) }
        val next = box.runScopes.begin("code", "test")
        RunScope.withCurrent(next) { box.markdown("log", "new output", open = false) }
        box.runScopes.supersede(next)
        val before = html
        RunScope.withCurrent(old) { box.markdownAppend("log", "late old cleanup", open = false) }
        assertEquals(before, html)
        RunScope.withCurrent(next) { box.markdownAppend("log", "new cleanup", open = false) }
        assertTrue("new output" in html && "new cleanup" in html)
        assertFalse("late old cleanup" in html)
        box.runScopes.closeAll()
    }

    @Test fun stopAndNormalCompletionCleanupStayVisibleUntilTheNextWriter() {
        val box = Box("markdown-stop")
        var html = ""
        box.output.displayProxy = { _, value -> html = value.toString() }
        val scope = box.runScopes.begin("code", "test")
        box.runScopes.stopAll()
        RunScope.withCurrent(scope) { box.markdownAppend("log", "finally after stop", open = false) }
        assertTrue("finally after stop" in html)
        box.runScopes.closeAll()
    }
}
