import afield.ai.completion.*
import afield.ai.diagnostics.*
import afield.ai.models.*

val measured = aiTool("geometry.bounds3") {
    describe(
        "Measure axis-aligned bounds of finite 3D points. Units are those of the input. No native dependencies or document edits.",
    )
    val points by param<List<List<Double>>>("Non-empty list of xyz triples")
    action {
        val p = points()
        require(p.isNotEmpty() && p.all { it.size == 3 && it.all(Double::isFinite) }) {
            "Provide finite xyz points"
        }
        mapOf("min" to (0..2).map { a -> p.minOf { it[a] } }, "max" to (0..2).map { a -> p.maxOf { it[a] } })
    }
    test("negative and positive coordinates", mapOf("points" to listOf(listOf(-2, 3, 7), listOf(5, -1, 4)))) { value
        ->
        check(value == mapOf("min" to listOf(-2.0, -1.0, 4.0), "max" to listOf(5.0, 3.0, 7.0)))
    }
    testFailure(
        "empty input is refused",
        mapOf("points" to emptyList<List<Double>>()),
        messageContains = "Provide finite xyz points",
    )
    testFailure(
        "incomplete coordinates are refused",
        mapOf("points" to listOf(listOf(1, 2))),
        messageContains = "Provide finite xyz points",
    )
}
println("BOUNDS_TOOL_READY")
