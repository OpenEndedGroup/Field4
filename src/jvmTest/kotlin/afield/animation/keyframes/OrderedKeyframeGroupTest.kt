package afield.animation.keyframes

import afield.document.graph.Box
import afield.runtime.macros.macro_sourceBlock
import afield.study.source.SourceBackedFunction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OrderedKeyframeGroupTest {
    @Test fun nativeApplyValidatesAllMembersBeforeWritingAny() {
        var writes = 0
        val group = OrderedKeyframeGroup({ 5 }, sourceBoxId = "author") { writes++ }
            .lane("cooling", .1) { it in 0.0..1.0 }
            .lane("gamma", 1.0) { it > 0 }
        group.apply()
        val bad = group.capture().put("cooling", SourceBackedFunction<Int, Double>("author", "{ i: Int -> i.toDouble() }") { it.toDouble() })
        val failure = assertFailsWith<IllegalArgumentException> { bad() }
        assertTrue(failure.message!!.contains("'cooling', index 2"))
        assertEquals(1, writes)
        assertEquals(.1, group.capture()["cooling"])
        val crossLane = group.capture().put("gamma", -1.0)
        assertFailsWith<IllegalArgumentException> { crossLane() }
        assertEquals(1, writes)
    }

    @Test fun blendsFunctionsPointwiseAndKeepsTheirSourceInNativeKeys() {
        val group = OrderedKeyframeGroup({ 3 }, sourceBoxId = "author") {}
            .lane("cooling", 0.0)
        val a = group.capture()
        val source = "{ screenNo: Int -> 0.1 + 0.04 * screenNo }"
        val b = group.scope.Key("cooling" to SourceBackedFunction<Int, Double>("author", source) { .1 + .04 * it })
        val mid = group.blend(a, b) { .5 }
        assertEquals(listOf(.05, .07, .09), group.resolve(mid).values("cooling"))
        val nativeMid = group.scope.interpolate(a, b, .5)
        assertEquals(group.resolve(mid).values("cooling"), group.resolve(nativeMid).values("cooling"))
        val dir = Files.createTempDirectory("ordered-keyframes")
        b.save(dir.resolve("waves"))
        val restored = assertIs<SourceBackedFunction<*, *>>(group.scope.loadKey(dir.resolve("waves"))["cooling"])
        assertEquals("author", restored.boxId)
        assertEquals(source, restored.source)
        mid.save(dir.resolve("halfway"))
        val savedMid = assertIs<SourceBackedFunction<*, *>>(group.scope.loadKey(dir.resolve("halfway"))["cooling"])
        assertTrue(savedMid.source.contains(source))
        assertTrue(savedMid.source.contains("0.5"))
    }

    @Test fun staggeredCutsAndCrossfadesWorkForArbitraryGroupSizes() {
        val group = OrderedKeyframeGroup({ 5 }, sourceBoxId = "author") {}.lane("size", 0.0)
        val a = group.capture()
        val b = group.scope.Key("size" to 10.0)
        assertEquals(listOf(10.0, 5.0, 0.0, 0.0, 0.0),
            group.resolve(group.transition(a, b, 1.0, 1.0, .5)).values("size"))
        assertEquals(listOf(10.0, 10.0, 0.0, 0.0, 0.0),
            group.resolve(group.transition(a, b, .5, 0.0, .5)).values("size"))
        assertFailsWith<IllegalArgumentException> { group.blend(a, b) { Double.NaN } }
        assertFailsWith<IllegalArgumentException> { group["size"] = { _: Int -> 1.0 } }
    }

    @Test fun batchingAlsoPreparesIndependentGlobalsBeforeGroupCommit() {
        var writes = 0
        val group = OrderedKeyframeGroup({ 3 }, sourceBoxId = "author") { writes++ }.lane("size", 1.0)
        group.scope.target("speed", { 1.0 }) {}
        group.scope.applicationBatch("transport", setOf("speed")) { incoming ->
            require((incoming["speed"] as Double) > 0)
            val commit: () -> Unit = {}; commit
        }
        assertFailsWith<IllegalArgumentException> { group.scope.Key("size" to 2.0, "speed" to -1.0)() }
        assertEquals(0, writes)
    }

    @Test fun blendsRetainEndpointOwnersAndSampledRulesAreIndependentOfInputMutation() {
        val group = OrderedKeyframeGroup({ 3 }, sourceBoxId = "blend-author") {}.lane("value", 0.0)
        val left = group.scope.Key("value" to SourceBackedFunction<Int, Double>("left-author", "{ i: Int -> i * 2.0 }") { it * 2.0 })
        val right = group.scope.Key("value" to SourceBackedFunction<Int, Double>("right-author", "{ i: Int -> 10.0 - i }") { 10.0 - it })
        val mixed = group.blend(left, right) { .25 }
        assertEquals(listOf(2.5, 3.75, 5.0), group.resolve(mixed).values("value"))
        val source = assertIs<SourceBackedFunction<*, *>>(mixed["value"]).source
        assertTrue(source.contains("left-author") && source.contains("right-author"))
        val samples = mutableListOf(1.0, 2.0, 3.0)
        val rule = OrderedKeyframeGroup.sampledRule("author", samples)
        samples[0] = 100.0
        group["value"] = rule
        assertEquals(1.0, group.resolve()["value", 0])
        assertEquals(2.0, OrderedKeyframeGroup.sampledRule("", listOf(2.0, 2.0)))
        assertFailsWith<IllegalArgumentException> { OrderedKeyframeGroup.sampledRule("author", listOf(Double.NaN)) }
    }

    @Test fun macroRecognizesOnlyTheOuterTypedParameter() {
        val box = Box("source-function-macro")
        val function = box.macro_sourceBlock(listOf("{ screenNo: Int -> listOf(1, 2).map { n -> n * screenNo }.sum().toDouble() }"))
        assertTrue(function.here.contains("SourceBackedFunction"))
        val statements = box.macro_sourceBlock(listOf("{ screenNo: Int -> val offset = screenNo * .04; offset + .1 }"))
        assertTrue(statements.here.contains("SourceBackedFunction"))
        val localLambda = box.macro_sourceBlock(listOf("{ val inner = { n: Int -> n + 1 }; inner(2) }"))
        assertTrue(localLambda.here.contains("SourceBackedBlock("))
        val nested = box.macro_sourceBlock(listOf("{ listOf(1).map { n -> n + 1 } }"))
        assertTrue(nested.here.contains("SourceBackedBlock("))
        val strings = box.macro_sourceBlock(listOf("{ \\\"not an -> arrow\\\" }".replace("\\\"", "\"")))
        assertTrue(strings.here.contains("SourceBackedBlock("))
        assertFailsWith<IllegalArgumentException> { box.macro_sourceBlock(listOf("{ a: Int, b: Int -> a + b }")) }
        assertFailsWith<IllegalArgumentException> { box.macro_sourceBlock(listOf("{ a -> a }")) }
    }
}
