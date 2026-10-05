package afield.ai

import afield.ai.core.*
import kotlin.test.*

class RuntimeInspectionTextTest {
    @Test fun largePrimitiveStringsCannotBypassThePreviewBudgetOnTheWire() {
        // A single minified source line formerly escaped maxChars through primitiveValue.
        val source = "const x=\"minified\";".repeat(100_000) + "END_OF_LARGE_SOURCE"
        val limit = 7000
        val result = EvaluateKotlinExpressionResult(
            success = true, target = "test", boxUid = "test", boxName = "test",
            expression = "largeSource", valueKind = "primitive",
            primitiveValue = primitiveToolValue(source, limit),
            primitiveKind = "String", stringValue = clipToolText(source, limit)
        )
        val wire = aiToolMapper.writeValueAsString(result)
        val parsed = aiToolMapper.readTree(wire)
        assertEquals(parsed["stringValue"].asText(), parsed["primitiveValue"].asText())
        assertContains(parsed["primitiveValue"].asText(), "<truncated")
        assertTrue(wire.length < 20_000, "The serialized tool result must not contain the full source")
        assertFalse(wire.contains("END_OF_LARGE_SOURCE"))
    }

    @Test fun variableValuesUseTheirSmallerBudgetAndClippingIsExplicit() {
        val source = "x".repeat(200_000)
        val result = KotlinContextVariableInfo(
            "largeSource", "val largeSource: String", "String", "String", false,
            clipToolText(source, 300), primitiveToolValue(source, 300), "String"
        )
        val wire = aiToolMapper.writeValueAsString(result)
        assertEquals(result.valuePreview, result.primitiveValue)
        assertContains(result.valuePreview, "truncated 199700 chars")
        assertTrue(wire.length < 1000)
        assertTrue((primitiveToolValue(source, Int.MAX_VALUE) as String).length < 100_100)
        assertTrue((primitiveToolValue(source, -1) as String).length < 150)
    }

    @Test fun completePrimitiveValuesKeepTheirTypesAndContents() {
        for (value in listOf(null, true, 1.toByte(), 2.toShort(), 3, 4L, 1.5f, 2.5, "", "x".repeat(300))) {
            assertEquals(value, primitiveToolValue(value, 300))
        }
        assertEquals("q", primitiveToolValue('q', 300))
        assertNull(primitiveToolValue(listOf("not a primitive"), 300))
    }
}
