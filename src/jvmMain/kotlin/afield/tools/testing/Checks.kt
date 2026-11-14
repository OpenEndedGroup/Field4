package afield.tools.testing

import afield.document.graph.Box
import org.json.JSONObject
import kotlin.math.abs

/**
 * One structured assertion a test states about itself: what was measured, what it was held to, whether
 * it passed, and who or what states the rule (`provenance`: an architect's sentence, a standard, a
 * saved model, "solver convergence target").
 *
 * A smoke emits each record as one stdout line, [LINE_PREFIX] followed by the record's JSON, and
 * `tools/run_field_tests.py` collects those lines per test: from a `jvm-main` entrypoint's stdout and
 * from a `backdoor-box` snippet's printed output. The runner tabulates them, writes them into
 * `build/field-tests/results.json` under each result's `checks`, and fails the test, naming the
 * check, when one did not pass. The line is a protocol, not log text: one JSON object per line, keys
 * as in [toJson], so no expectation has to scrape a sentence.
 *
 * `expected` is the target value, or for a predicate check a description of the rule. `tolerance` is
 * the absolute tolerance of a [Checks.near] comparison and null otherwise.
 */
data class CheckRecord(
    val name: String,
    val measured: Any?,
    val expected: Any?,
    val tolerance: Double? = null,
    val passed: Boolean,
    val note: String? = null,
    val provenance: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("measured", jsonValue(measured))
        .put("expected", jsonValue(expected))
        .put("tolerance", jsonValue(tolerance))
        .put("passed", passed)
        .put("note", note ?: JSONObject.NULL)
        .put("provenance", provenance ?: JSONObject.NULL)

    /** The stdout line the runner collects. Always a single line: JSON escapes newlines in strings. */
    fun line(): String = LINE_PREFIX + toJson().toString()

    companion object {
        const val LINE_PREFIX = "FIELD_CHECK "

        /** The record on [line], or null when the line is not a check line. Malformed JSON after the prefix throws. */
        fun parse(line: String): CheckRecord? {
            if (!line.startsWith(LINE_PREFIX)) return null
            val json = JSONObject(line.substring(LINE_PREFIX.length))
            return CheckRecord(
                name = json.getString("name"),
                measured = json.opt("measured").takeUnless { it == JSONObject.NULL },
                expected = json.opt("expected").takeUnless { it == JSONObject.NULL },
                tolerance = if (json.isNull("tolerance")) null else json.getDouble("tolerance"),
                passed = json.getBoolean("passed"),
                note = json.optString("note").takeUnless { json.isNull("note") },
                provenance = json.optString("provenance").takeUnless { json.isNull("provenance") },
            )
        }

        // JSON has no NaN or infinity, and a measurement is exactly where they turn up; keep them as text
        // rather than letting org.json throw from inside a failing check.
        private fun jsonValue(value: Any?): Any = when (value) {
            null -> JSONObject.NULL
            is Double -> if (value.isFinite()) value else value.toString()
            is Float -> if (value.isFinite()) value.toDouble() else value.toString()
            is Number, is Boolean, is String -> value
            else -> value.toString()
        }
    }
}

/**
 * The check records of one test, in order. Each record is printed through [emit] as it is made, so a
 * test that dies halfway still reports the checks it reached. By default that is the running box's
 * output when there is one (a `backdoor-box` smoke) and stdout otherwise (a `jvm-main` entrypoint).
 *
 * ```kotlin
 * val checks = Checks()
 * checks.near("solver cost", result.cost, 0.0, tolerance = 1e-10, provenance = "convergence target")
 * checks.that("area grew", shapedArea, "> base + 0.025") { it > baseArea + 0.025 }
 * checks.requireAll()   // throws, naming the failed checks, when any did not pass
 * ```
 */
class Checks(private val emit: (String) -> Unit = ::emitToBoxOrStdout) {
    private val records = mutableListOf<CheckRecord>()

    val all: List<CheckRecord> get() = records.toList()
    val failures: List<CheckRecord> get() = records.filterNot { it.passed }
    val ok: Boolean get() = records.all { it.passed }

    fun record(check: CheckRecord): CheckRecord {
        records += check
        emit(check.line())
        return check
    }

    /** `|measured - expected| <= tolerance`; a NaN measurement fails. */
    fun near(
        name: String, measured: Double, expected: Double, tolerance: Double,
        note: String? = null, provenance: String? = null,
    ): CheckRecord {
        require(tolerance >= 0.0) { "tolerance must be non-negative, got $tolerance" }
        return record(CheckRecord(name, measured, expected, tolerance, abs(measured - expected) <= tolerance, note, provenance))
    }

    fun equal(name: String, measured: Any?, expected: Any?, note: String? = null, provenance: String? = null): CheckRecord =
        record(CheckRecord(name, measured, expected, null, measured == expected, note, provenance))

    /** A check whose rule is a predicate; [expected] describes the rule for the report. */
    fun <T> that(
        name: String, measured: T, expected: String,
        note: String? = null, provenance: String? = null, pass: (T) -> Boolean,
    ): CheckRecord = record(CheckRecord(name, measured, expected, null, pass(measured), note, provenance))

    /** Throws when any check failed, naming each one, so a `jvm-main` entrypoint exits non-zero. */
    fun requireAll() {
        val failed = failures
        check(failed.isEmpty()) {
            "${failed.size} of ${records.size} checks failed: " + failed.joinToString("; ") {
                "${it.name} (measured ${it.measured}, expected ${it.expected}" +
                    (it.tolerance?.let { t -> " ± $t" } ?: "") + ")"
            }
        }
    }
}

private fun emitToBoxOrStdout(line: String) {
    val box = Box.context.get()
    if (box != null) box.println(line) else println(line)
}
