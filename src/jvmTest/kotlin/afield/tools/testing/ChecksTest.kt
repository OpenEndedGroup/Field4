package afield.tools.testing

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChecksTest {
    @Test
    fun nearPassesInsideToleranceAndFailsOutsideAndOnNaN() {
        val checks = Checks(emit = {})
        assertTrue(checks.near("inside", 1.05, 1.0, tolerance = 0.1).passed)
        assertTrue(checks.near("edge", 1.5, 1.0, tolerance = 0.5).passed)
        assertFalse(checks.near("outside", 1.2, 1.0, tolerance = 0.1).passed)
        assertFalse(checks.near("nan", Double.NaN, 1.0, tolerance = 1e9).passed)
        assertFailsWith<IllegalArgumentException> { checks.near("negative", 1.0, 1.0, tolerance = -1.0) }
        assertEquals(listOf("outside", "nan"), checks.failures.map { it.name })
        assertFalse(checks.ok)
    }

    @Test
    fun equalAndPredicateChecksRecordTheirRule() {
        val checks = Checks(emit = {})
        val equal = checks.equal("count", 27, 27, provenance = "skeleton definition")
        val rule = checks.that("area grew", 0.31, "> 0.3") { it > 0.3 }
        assertTrue(equal.passed && rule.passed && checks.ok)
        assertEquals("> 0.3", rule.expected)
        assertNull(rule.tolerance)
        assertEquals("skeleton definition", equal.provenance)
    }

    @Test
    fun eachRecordIsEmittedAsOneParseableLineAsItIsMade() {
        val lines = mutableListOf<String>()
        val checks = Checks(emit = lines::add)
        checks.near("cost", 2e-12, 0.0, tolerance = 1e-10, note = "two\nlines", provenance = "convergence target")
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line.startsWith(CheckRecord.LINE_PREFIX))
        assertFalse('\n' in line, "a record must stay on one stdout line")
        val json = JSONObject(line.removePrefix(CheckRecord.LINE_PREFIX))
        assertEquals(setOf("name", "measured", "expected", "tolerance", "passed", "note", "provenance"), json.keySet())
        val parsed = CheckRecord.parse(line)!!
        assertEquals("cost", parsed.name)
        assertEquals(1e-10, parsed.tolerance)
        assertTrue(parsed.passed)
        assertEquals("two\nlines", parsed.note)
        assertEquals("convergence target", parsed.provenance)
        assertNull(CheckRecord.parse("cost 2e-12"))
    }

    @Test
    fun nonFiniteMeasurementsStillSerialize() {
        val lines = mutableListOf<String>()
        Checks(emit = lines::add).near("diverged", Double.POSITIVE_INFINITY, 0.0, tolerance = 1.0)
        val json = JSONObject(lines.single().removePrefix(CheckRecord.LINE_PREFIX))
        assertEquals("Infinity", json.getString("measured"))
        assertFalse(json.getBoolean("passed"))
        assertTrue(json.isNull("note"))
    }

    @Test
    fun requireAllNamesTheFailedChecks() {
        val checks = Checks(emit = {})
        checks.near("ok", 1.0, 1.0, tolerance = 0.0)
        checks.requireAll()
        checks.near("drift", 3.0, 1.0, tolerance = 0.5)
        val error = assertFailsWith<IllegalStateException> { checks.requireAll() }
        assertTrue("drift (measured 3.0, expected 1.0 ± 0.5)" in error.message!!, error.message)
        assertTrue("1 of 2 checks failed" in error.message!!, error.message)
    }
}
